// Micro — Tauri client backend.
// Rust handles UDP networking, discovery and native audio decode (Opus + RNNoise) with
// packet-loss concealment, and plays the result through cpal; the webview only renders the UI.
//
// Module map, roughly outermost-in:
//
//   commands  the `invoke` surface the webview calls, plus the orderly shutdown
//   tray      tray icon/menu and the "window must stay reachable" rule
//   net       the connection: pairing, key exchange, control channel, audio receive
//   discovery LAN broadcast/reply loop
//   audio     native cpal output, monitoring and device switching
//   jitter    reorder buffer with Opus FEC/PLC
//   link      the connected UDP socket and the sealed control send
//   crypto    key derivation, auth tags, the small wire encodings
//   proto     wire constants, nonce layouts, counter and mute rules  (mirrored on the phone)
//   store     identity, pinned keys and counters that survive restarts
//   state     what the threads share; Tauri's managed state
//   events    the payload structs emitted to the webview
//   platform  the per-OS bits (hostname, accent colour, Linux virtual mic)
//   rnnoise   safe wrapper over the vendored C denoiser
//
// `proto`, `crypto` and `jitter` are deliberately free of I/O and app state: the invariants the two
// implementations must agree on are unit-tested there, next to the code they constrain.

use std::sync::atomic::{AtomicBool, AtomicU32};
use std::sync::{Arc, Mutex};

use tauri::Manager;

mod audio;
mod commands;
mod crypto;
mod discovery;
mod events;
mod jitter;
mod link;
mod memory_profile;
mod net;
mod platform;
mod proto;
mod rnnoise;
mod state;
mod store;
mod tray;

use state::{AppState, LockExt, Shared};

// Fast small-allocation allocator (the audio path allocates a couple of tiny buffers per frame).
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Before anything reads a pin: a desktop upgraded from 1.0.0 keeps its pairing. Too early to
    // log — the log plugin installs its logger while the builder below runs, and `log` drops records
    // written before that — so the count is carried into `setup`.
    let migrated_pins = store::migrate_legacy_pins();

    let state = AppState {
        client_id: store::load_or_create_client_id(),
        client_name: platform::hostname(),
        identity: Arc::new(store::load_identity()),
        shared: Arc::new(Shared {
            denoise: AtomicBool::new(true),
            vad_bits: AtomicU32::new(0.0f32.to_bits()),
            muted: AtomicBool::new(false),
            mute_req: AtomicU32::new(0),
            code: Mutex::new(String::new()),
        }),
        lifecycle: Mutex::new(()),
        net: Mutex::new(None),
        discovery: Mutex::new(None),
        output: Mutex::new(None),
        pulse_modules: Mutex::new(Vec::new()),
    };

    tauri::Builder::default()
        // Launching a second copy raises the running one instead (and rescues it if it drifted
        // off-screen), which is also the user's way out of an unreachable window.
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(w) = app.get_webview_window("main") {
                tray::show_window(&w);
            }
        }))
        .plugin(tauri_plugin_log::Builder::new().build())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, None))
        .manage(state)
        .setup(move |app| {
            if migrated_pins > 0 {
                log::info!("migrated {migrated_pins} pin(s) from the 1.0.0 layout");
            }
            tray::build_tray(app.handle())?;
            // Linux: spin up a virtual mic (null-sink + remap source). No-op elsewhere.
            let ids = platform::setup_virtual_mic();
            *app.state::<AppState>().pulse_modules.lock_safe() = ids;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_accent,
            commands::set_denoise,
            commands::set_vad,
            commands::set_mute,
            commands::set_code,
            commands::list_outputs,
            commands::set_gain,
            commands::set_output_device,
            commands::set_monitor,
            commands::start_discovery,
            commands::connect,
            commands::disconnect,
            memory_profile::memory_profile,
            commands::reset,
            tray::update_tray,
            tray::set_tray_icon,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            if let tauri::RunEvent::Exit = event {
                commands::shutdown(app_handle);
            }
        });
}
