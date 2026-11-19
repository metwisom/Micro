//! The `invoke` surface the webview calls, plus the orderly shutdown both Quit and Reset go through.
//!
//! Anything that joins a thread or enumerates devices runs inside `spawn_blocking`. Marking such a
//! command `async` is not enough on its own: an `async fn` with no `.await` runs to completion in a
//! single poll, so the join happens *on* one of Tauri's shared async workers and stalls every other
//! command queued behind it. `spawn_blocking` moves it to the blocking pool, which also means the
//! lifecycle `Mutex` below is never held across an await point.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use tauri::{AppHandle, Emitter, Manager, State};

use crate::audio;
use crate::discovery::discovery_loop;
use crate::net::net_loop;
use crate::platform::{read_accent, teardown_virtual_mic};
use crate::proto::PORT;
use crate::state::{AppState, LockExt, Worker};
use crate::store::{clear_identity, config_dir};

#[tauri::command]
pub fn get_accent() -> Option<String> {
    read_accent()
}

#[tauri::command]
pub fn set_denoise(state: State<'_, AppState>, on: bool) {
    state.shared.denoise.store(on, Ordering::Relaxed);
}

#[tauri::command]
pub fn set_vad(state: State<'_, AppState>, gate: f32) {
    state.shared.vad_bits.store(gate.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
}

#[tauri::command]
pub fn set_mute(state: State<'_, AppState>, on: bool) {
    // The user's own intent: net_loop sends it to the phone as a command and holds it until the
    // phone confirms. Reports adopted from the phone go straight to `muted` instead.
    state.shared.request_mute(on);
}

#[tauri::command]
pub fn set_code(state: State<'_, AppState>, code: String) {
    *state.shared.code.lock_safe() = code.trim().to_string();
}

#[tauri::command]
pub async fn list_outputs() -> Result<Vec<String>, String> {
    // Device enumeration talks to the OS audio stack and can take hundreds of milliseconds.
    tauri::async_runtime::spawn_blocking(audio::list_outputs).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub fn set_gain(state: State<'_, AppState>, gain: f32) {
    if let Some(o) = state.output.lock_safe().as_ref() {
        o.set_gain(gain.max(0.0));
    }
}

#[tauri::command]
pub fn set_output_device(state: State<'_, AppState>, device: Option<String>) {
    if let Some(o) = state.output.lock_safe().as_ref() {
        o.set_device(device);
    }
}

#[tauri::command]
pub fn set_monitor(state: State<'_, AppState>, on: bool, device: Option<String>) {
    if let Some(o) = state.output.lock_safe().as_ref() {
        o.set_monitor(on, device);
    }
}

/// Start the LAN discovery broadcast loop. It stays up for the life of the app (also while
/// connected: that is how the UI notices the phone moving to another IP). Idempotent.
#[tauri::command]
pub fn start_discovery(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let mut guard = state.discovery.lock_safe();
    if guard.is_some() {
        return Ok(());
    }
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    let handle = std::thread::spawn(move || discovery_loop(app, stop2));
    *guard = Some(Worker { stop, handle: Some(handle) });
    Ok(())
}

#[tauri::command]
pub async fn connect(
    app: AppHandle,
    host: String,
    port: Option<u16>,
    device: Option<String>,
    buffer: Option<usize>,
    gain: Option<f32>,
) -> Result<(), String> {
    let handle = app.clone();
    tauri::async_runtime::spawn_blocking(move || connect_blocking(handle, host, port, device, buffer, gain))
        .await
        .map_err(|e| e.to_string())?
}

/// The blocking half of [`connect`]: joins the previous worker and opens the output device.
fn connect_blocking(
    app: AppHandle,
    host: String,
    port: Option<u16>,
    device: Option<String>,
    buffer: Option<usize>,
    gain: Option<f32>,
) -> Result<(), String> {
    let state = app.state::<AppState>();
    let _lifecycle = state.lifecycle.lock_safe();
    // Take the old worker out, then join it *after* dropping the net lock.
    let old = state.net.lock_safe().take();
    if let Some(mut w) = old {
        w.stop();
    }
    // A connection starts unmuted on our side (the UI does the same). net_loop never pushes an unmute
    // to the phone before the states are synced, so a phone muted on the phone stays muted and
    // re-syncs us via its MUTE keepalive.
    state.shared.muted.store(false, Ordering::Relaxed);

    // Native output (cpal) — alive for the duration of this connection. Drop the old one first
    // (joins the audio thread, outside the lock) so the device is never opened twice at once.
    let old_out = state.output.lock_safe().take();
    drop(old_out);
    // Mirror the ceiling the UI declares (index.html `max="40"`): the webview can send anything,
    // and an absurd prefill target is indistinguishable from "no audio ever arrives".
    let output =
        audio::Output::start(app.clone(), buffer.unwrap_or(3).clamp(1, 40), device, gain.unwrap_or(1.5));
    let sink = output.sink();
    *state.output.lock_safe() = Some(output);

    let port = port.unwrap_or(PORT);
    let shared = state.shared.clone();
    let identity = state.identity.clone();
    let cid = state.client_id.clone();
    let cname = state.client_name.clone();
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    // `state` borrows `app`, so the net thread gets its own handle rather than moving this one.
    let net_app = app.clone();
    let handle = std::thread::spawn(move || {
        if let Err(e) = net_loop(&net_app, &host, port, &cid, &cname, &identity, &shared, &stop2, &sink) {
            log::warn!("net_loop ended: {e}");
            let _ = net_app.emit("net-error", e);
        }
    });
    *state.net.lock_safe() = Some(Worker { stop, handle: Some(handle) });
    Ok(())
}

#[tauri::command]
pub async fn disconnect(app: AppHandle) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<AppState>();
        let _lifecycle = state.lifecycle.lock_safe();
        let old = state.net.lock_safe().take();
        if let Some(mut w) = old {
            w.stop(); // join outside the lock
        }
        let out = state.output.lock_safe().take();
        drop(out); // stops the cpal streams (joins the audio thread) outside the output lock
    })
    .await
    .map_err(|e| e.to_string())
}

/// Stop everything that owns OS resources: the net worker (sends the sealed BYE), the discovery
/// worker, the cpal output, and the Linux virtual mic. Used by Reset and by Quit. The HELO counter
/// needs nothing here: its block is reserved on disk before any counter in it is sent.
pub(crate) fn shutdown(app: &AppHandle) {
    let state = app.state::<AppState>();
    let _lifecycle = state.lifecycle.lock_safe();
    let old = state.net.lock_safe().take();
    if let Some(mut w) = old {
        w.stop();
    }
    // Discovery outlives every connection, so it is only ever stopped here. Taking it out of the
    // lock first keeps the join outside the lock, as with the net worker above.
    let disc = state.discovery.lock_safe().take();
    if let Some(mut w) = disc {
        w.stop();
    }
    let out = state.output.lock_safe().take();
    drop(out);
    let ids = std::mem::take(&mut *state.pulse_modules.lock_safe());
    teardown_virtual_mic(&ids);
}

#[tauri::command]
pub async fn reset(app: AppHandle) -> Result<(), String> {
    // app.restart() does NOT fire RunEvent::Exit, so tear down explicitly first. The teardown joins
    // threads and the keyring call can block on the OS secret store, hence the blocking pool.
    let handle = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        shutdown(&handle);
        clear_identity(); // the identity lives in the OS secret store, not just config_dir()
        let _ = std::fs::remove_dir_all(config_dir());
    })
    .await
    .map_err(|e| e.to_string())?;
    app.restart()
}
