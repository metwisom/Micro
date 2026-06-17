// Micro — Tauri client backend.
// Rust handles UDP networking, discovery and native audio decode (Opus + RNNoise) with
// packet-loss concealment; the webview plays the decoded PCM and renders the UI.

use std::io::{Read, Write};
use std::net::{Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use base64::Engine;
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{ChaCha20Poly1305, Key, KeyInit, Nonce};
use hkdf::Hkdf;
use serde::Serialize;
use sha2::Sha256;
use tauri::ipc::{Channel, Response};
use tauri::{AppHandle, Emitter, Manager, State};
use x25519_dalek::{PublicKey, StaticSecret};

const PORT: u16 = 50005;
const FRAME: usize = 480; // 10 ms @ 48 kHz, also RNNoise frame size
const HANGOVER: i32 = 10;
const MAX_PLC: u32 = 5; // cap concealed frames per gap

// ---------------------------------------------------------------------------
struct Shared {
    denoise: AtomicBool,
    vad_bits: AtomicU32,
    muted: AtomicBool, // synced with the server (stops transmission when muted)
    code: Mutex<String>, // pairing code typed by the user (empty for known devices)
}
impl Shared {
    fn vad(&self) -> f32 {
        f32::from_bits(self.vad_bits.load(Ordering::Relaxed))
    }
}

struct Worker {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}
impl Worker {
    fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

pub struct AppState {
    client_id: String,
    client_name: String,
    shared: Arc<Shared>,
    net: Mutex<Option<Worker>>,
    discovery: Mutex<Option<Worker>>,
    pulse_modules: Mutex<Vec<String>>, // Linux virtual-mic module indices (to unload on exit)
}

#[derive(Clone, Serialize)]
struct DeviceFound {
    ip: String,
    port: u16,
    name: String,
}

#[derive(Clone, Serialize)]
struct Stats {
    kbps: u32,
    loss: f32,
    ping: u32, // round-trip ms (network)
}

// ---------------------------------------------------------------------------
fn config_dir() -> std::path::PathBuf {
    let mut d = dirs::config_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    d.push("Micro");
    d
}

fn load_or_create_client_id() -> String {
    let dir = config_dir();
    let file = dir.join("client-id");
    if let Ok(mut f) = std::fs::File::open(&file) {
        let mut s = String::new();
        if f.read_to_string(&mut s).is_ok() {
            let s = s.trim().to_string();
            if !s.is_empty() {
                return s;
            }
        }
    }
    let id = uuid::Uuid::new_v4().to_string();
    let _ = std::fs::create_dir_all(&dir);
    if let Ok(mut f) = std::fs::File::create(&file) {
        let _ = f.write_all(id.as_bytes());
    }
    id
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "PC".to_string())
}

// --- Long-term identity + pinned server keys (TOFU after SAS pairing) --------
fn load_identity() -> StaticSecret {
    let file = config_dir().join("identity");
    if let Ok(b) = std::fs::read(&file) {
        if b.len() >= 32 {
            let mut a = [0u8; 32];
            a.copy_from_slice(&b[..32]);
            return StaticSecret::from(a);
        }
    }
    let mut sk = [0u8; 32];
    let _ = getrandom::getrandom(&mut sk);
    let _ = std::fs::create_dir_all(config_dir());
    let _ = std::fs::write(&file, sk);
    StaticSecret::from(sk)
}

fn host_key(host: &str) -> String {
    host.chars().map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' }).collect()
}
fn pin_file(host: &str) -> std::path::PathBuf {
    config_dir().join("pins").join(host_key(host))
}
fn load_pin(host: &str) -> Option<[u8; 32]> {
    let txt = std::fs::read_to_string(pin_file(host)).ok()?;
    let raw = base64::engine::general_purpose::STANDARD.decode(txt.trim()).ok()?;
    if raw.len() == 32 {
        let mut a = [0u8; 32];
        a.copy_from_slice(&raw);
        Some(a)
    } else {
        None
    }
}
fn save_pin(host: &str, pubkey: &[u8; 32]) {
    let _ = std::fs::create_dir_all(config_dir().join("pins"));
    let _ = std::fs::write(
        pin_file(host),
        base64::engine::general_purpose::STANDARD.encode(pubkey),
    );
}

fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}
fn unb64_32(s: &str) -> Option<[u8; 32]> {
    let raw = base64::engine::general_purpose::STANDARD.decode(s.trim()).ok()?;
    if raw.len() == 32 {
        let mut a = [0u8; 32];
        a.copy_from_slice(&raw);
        Some(a)
    } else {
        None
    }
}
fn sha256(data: &[u8]) -> [u8; 32] {
    use sha2::Digest;
    let mut h = sha2::Sha256::new();
    h.update(data);
    h.finalize().into()
}
/// 4-digit Short Authentication String over both long-term public keys.
fn sas(client_pub: &[u8; 32], server_pub: &[u8; 32]) -> String {
    use sha2::Digest;
    let mut h = sha2::Sha256::new();
    h.update(b"MICRO-SAS-v1");
    h.update(client_pub);
    h.update(server_pub);
    let d = h.finalize();
    let v = ((d[0] as u32) << 8 | d[1] as u32) % 10000;
    format!("{v:04}")
}
/// Session key: ephemeral-ephemeral DH (forward secrecy) mixed with static-static DH of the
/// pinned long-term keys (authentication). A MITM lacking the long-term privates can't derive it.
fn session_cipher(
    eph_secret: &StaticSecret,
    static_secret: &StaticSecret,
    their_eph: &[u8; 32],
    their_static: &[u8; 32],
    salt: &[u8],
) -> ChaCha20Poly1305 {
    let de = eph_secret.diffie_hellman(&PublicKey::from(*their_eph));
    let ds = static_secret.diffie_hellman(&PublicKey::from(*their_static));
    let mut ikm = Vec::with_capacity(64);
    ikm.extend_from_slice(de.as_bytes());
    ikm.extend_from_slice(ds.as_bytes());
    let hk = Hkdf::<Sha256>::new(Some(salt), &ikm);
    let mut key = [0u8; 32];
    hk.expand(b"micro-session", &mut key).unwrap();
    ChaCha20Poly1305::new(Key::from_slice(&key))
}

#[cfg(windows)]
fn read_accent() -> Option<String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;
    let dwm = RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey("Software\\Microsoft\\Windows\\DWM")
        .ok()?;
    let c: u32 = dwm.get_value("ColorizationColor").ok()?;
    Some(format!("#{:06x}", c & 0x00FF_FFFF))
}
#[cfg(not(windows))]
fn read_accent() -> Option<String> {
    None
}

// --- Linux: auto-create a virtual microphone (null-sink + remapped source) ----
// On Windows/macOS this is a no-op (use VB-Cable / BlackHole instead).
#[cfg(target_os = "linux")]
fn setup_virtual_mic() -> Vec<String> {
    use std::process::Command;
    // Reuse if a "Micro" sink already exists (e.g. left over from a crash).
    let exists = Command::new("pactl")
        .args(["list", "short", "sinks"])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).contains("Micro"))
        .unwrap_or(false);
    if exists {
        return Vec::new();
    }
    let load = |args: &[&str]| -> Option<String> {
        let o = Command::new("pactl").args(args).output().ok()?;
        if o.status.success() {
            Some(String::from_utf8_lossy(&o.stdout).trim().to_string())
        } else {
            None
        }
    };
    let mut ids = Vec::new();
    match load(&[
        "load-module",
        "module-null-sink",
        "sink_name=Micro",
        "sink_properties=device.description=Micro",
    ]) {
        Some(id) => ids.push(id),
        None => return ids,
    }
    if let Some(id) = load(&[
        "load-module",
        "module-remap-source",
        "master=Micro.monitor",
        "source_name=MicroMic",
        "source_properties=device.description=Micro",
    ]) {
        ids.push(id);
    }
    ids
}

#[cfg(target_os = "linux")]
fn teardown_virtual_mic(ids: &[String]) {
    use std::process::Command;
    for id in ids.iter().rev() {
        let _ = Command::new("pactl").args(["unload-module", id]).output();
    }
}

#[cfg(not(target_os = "linux"))]
fn setup_virtual_mic() -> Vec<String> {
    Vec::new()
}
#[cfg(not(target_os = "linux"))]
fn teardown_virtual_mic(_ids: &[String]) {}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------
#[tauri::command]
fn get_client_id(state: State<'_, AppState>) -> String {
    state.client_id.clone()
}

#[tauri::command]
fn get_accent() -> Option<String> {
    read_accent()
}

#[tauri::command]
fn set_denoise(state: State<'_, AppState>, on: bool) {
    state.shared.denoise.store(on, Ordering::Relaxed);
}

#[tauri::command]
fn set_vad(state: State<'_, AppState>, gate: f32) {
    state.shared.vad_bits.store(gate.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
}

#[tauri::command]
fn set_mute(state: State<'_, AppState>, on: bool) {
    state.shared.muted.store(on, Ordering::Relaxed);
}

#[tauri::command]
fn set_code(state: State<'_, AppState>, code: String) {
    *state.shared.code.lock().unwrap() = code.trim().to_string();
}

#[tauri::command]
fn start_discovery(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let mut guard = state.discovery.lock().unwrap();
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
fn connect(
    app: AppHandle,
    state: State<'_, AppState>,
    host: String,
    port: Option<u16>,
    audio: Channel<Response>,
) -> Result<(), String> {
    {
        let mut g = state.net.lock().unwrap();
        if let Some(w) = g.as_mut() {
            w.stop();
        }
        *g = None;
    }
    let port = port.unwrap_or(PORT);
    let shared = state.shared.clone();
    let cid = state.client_id.clone();
    let cname = state.client_name.clone();
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    let handle = std::thread::spawn(move || {
        if let Err(e) = net_loop(&app, &host, port, &cid, &cname, &shared, &stop2, &audio) {
            log::warn!("net_loop ended: {e}");
            let _ = app.emit("net-error", e);
        }
    });
    *state.net.lock().unwrap() = Some(Worker { stop, handle: Some(handle) });
    Ok(())
}

#[tauri::command]
fn disconnect(state: State<'_, AppState>) {
    let mut g = state.net.lock().unwrap();
    if let Some(w) = g.as_mut() {
        w.stop();
    }
    *g = None;
}

#[tauri::command]
fn reset(app: AppHandle, state: State<'_, AppState>) {
    if let Some(w) = state.net.lock().unwrap().as_mut() {
        w.stop();
    }
    let _ = std::fs::remove_dir_all(config_dir());
    app.restart();
}

/// The app icon decoded to RGBA8 once (embedded at compile time).
fn app_icon_rgba() -> (Vec<u8>, u32, u32) {
    let bytes: &[u8] = include_bytes!("../icons/64x64.png");
    let mut reader = png::Decoder::new(bytes)
        .read_info()
        .expect("tray icon png");
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).expect("tray icon frame");
    let (w, h) = (info.width, info.height);
    let n = info.buffer_size();
    let rgba = match info.color_type {
        png::ColorType::Rgba => buf[..n].to_vec(),
        png::ColorType::Rgb => {
            let mut o = Vec::with_capacity((w * h * 4) as usize);
            for px in buf[..n].chunks_exact(3) {
                o.extend_from_slice(&[px[0], px[1], px[2], 255]);
            }
            o
        }
        _ => buf[..n].to_vec(),
    };
    (rgba, w, h)
}

/// Tray icon = the app icon with a colored status dot (active / muted / idle) in the corner.
fn tray_image(rgb: [u8; 3]) -> tauri::image::Image<'static> {
    let (mut rgba, w, h) = app_icon_rgba();
    let radius = w as f32 * 0.17;
    let cx = w as f32 - radius - 2.0;
    let cy = h as f32 - radius - 2.0;
    for y in 0..h {
        for x in 0..w {
            let dx = x as f32 - cx;
            let dy = y as f32 - cy;
            let dist = (dx * dx + dy * dy).sqrt();
            let i = ((y * w + x) * 4) as usize;
            if dist <= radius {
                rgba[i] = rgb[0];
                rgba[i + 1] = rgb[1];
                rgba[i + 2] = rgb[2];
                rgba[i + 3] = 255;
            } else if dist <= radius + 1.3 {
                // white ring for contrast against the icon
                rgba[i] = 255;
                rgba[i + 1] = 255;
                rgba[i + 2] = 255;
                rgba[i + 3] = 255;
            }
        }
    }
    tauri::image::Image::new_owned(rgba, w, h)
}

#[tauri::command]
fn set_tray_icon(app: AppHandle, state: String) {
    let rgb = match state.as_str() {
        "active" => [0x3e, 0xcf, 0x8e],
        "muted" => [0xc4, 0x2b, 0x1c],
        _ => [0x9a, 0xa0, 0xa6],
    };
    if let Some(tray) = app.tray_by_id("main") {
        let _ = tray.set_icon(Some(tray_image(rgb)));
    }
}

#[tauri::command]
fn update_tray(
    app: AppHandle,
    show: String,
    quit: String,
    disconnect: Option<String>,
    status: Option<String>,
) -> tauri::Result<()> {
    rebuild_tray_menu(&app, &show, &quit, disconnect.as_deref(), status.as_deref())
}

// ---------------------------------------------------------------------------
// Discovery
// ---------------------------------------------------------------------------
fn broadcast_addrs() -> Vec<Ipv4Addr> {
    let mut v = vec![Ipv4Addr::new(255, 255, 255, 255)];
    if let Ok(ifaces) = if_addrs::get_if_addrs() {
        for iface in ifaces {
            if let if_addrs::IfAddr::V4(a) = iface.addr {
                if a.ip.is_loopback() {
                    continue;
                }
                let b = a
                    .broadcast
                    .unwrap_or_else(|| Ipv4Addr::from(u32::from(a.ip) | !u32::from(a.netmask)));
                if !v.contains(&b) {
                    v.push(b);
                }
            }
        }
    }
    v
}

fn discovery_loop(app: AppHandle, stop: Arc<AtomicBool>) {
    let sock = match UdpSocket::bind(("0.0.0.0", 0)) {
        Ok(s) => s,
        Err(_) => return,
    };
    let _ = sock.set_broadcast(true);
    let _ = sock.set_read_timeout(Some(Duration::from_millis(500)));
    let mut last = Instant::now() - Duration::from_secs(10);
    let mut buf = [0u8; 256];

    while !stop.load(Ordering::Relaxed) {
        if last.elapsed() >= Duration::from_secs(5) {
            for b in broadcast_addrs() {
                let _ = sock.send_to(b"MICRO?", (b, PORT));
            }
            last = Instant::now();
        }
        if let Ok((n, src)) = sock.recv_from(&mut buf) {
            if let Ok(text) = std::str::from_utf8(&buf[..n]) {
                if let Some(rest) = text.strip_prefix("MICRO!") {
                    let mut it = rest.splitn(2, '|');
                    let port = it.next().and_then(|p| p.trim().parse().ok()).unwrap_or(PORT);
                    let name = it.next().unwrap_or("").trim();
                    let ip = src.ip().to_string();
                    let _ = app.emit(
                        "device-found",
                        DeviceFound {
                            ip: ip.clone(),
                            port,
                            name: if name.is_empty() { ip } else { name.to_string() },
                        },
                    );
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------
/// Decode/denoise one frame into f32 [-1,1] little-endian bytes and stream it to the webview
/// over a binary Channel (no JSON — ~1 alloc/frame, raw ArrayBuffer on the JS side).
fn emit_pcm(
    audio: &Channel<Response>,
    denoiser: &mut nnnoiseless::DenoiseState,
    pcm: &[i16],
    denoise: bool,
    gate: f32,
    hold: &mut i32,
    din: &mut [f32; FRAME],
    dout: &mut [f32; FRAME],
) {
    let mut bytes = Vec::with_capacity(FRAME * 4);
    if denoise {
        for i in 0..FRAME {
            din[i] = pcm[i] as f32;
        }
        let vad = denoiser.process_frame(dout, din);
        let mut mute = false;
        if gate > 0.0 {
            if vad >= gate {
                *hold = HANGOVER;
            } else if *hold > 0 {
                *hold -= 1;
            } else {
                mute = true;
            }
        }
        for i in 0..FRAME {
            let v = if mute { 0.0 } else { dout[i] / 32768.0 };
            bytes.extend_from_slice(&v.to_le_bytes());
        }
    } else {
        for i in 0..FRAME {
            bytes.extend_from_slice(&(pcm[i] as f32 / 32768.0).to_le_bytes());
        }
    }
    let _ = audio.send(Response::new(bytes));
}

fn net_loop(
    app: &AppHandle,
    host: &str,
    port: u16,
    client_id: &str,
    client_name: &str,
    shared: &Arc<Shared>,
    stop: &Arc<AtomicBool>,
    audio: &Channel<Response>,
) -> Result<(), String> {
    let sock = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
    sock.connect((host, port)).map_err(|e| e.to_string())?;
    sock.set_read_timeout(Some(Duration::from_millis(250))).ok();

    let mut decoder =
        audiopus::coder::Decoder::new(audiopus::SampleRate::Hz48000, audiopus::Channels::Mono)
            .map_err(|e| format!("opus init: {e}"))?;

    let mut denoiser = nnnoiseless::DenoiseState::new();

    // Long-term identity (persisted). Sessions authenticate via pinned keys set at SAS pairing.
    let identity = load_identity();
    let my_pub: [u8; 32] = PublicKey::from(&identity).to_bytes();
    let my_pub_b64 = b64(&my_pub);
    let commit_b64 = b64(&sha256(&my_pub));

    // Fresh ephemeral keypair per session → forward secrecy.
    let mut esk = [0u8; 32];
    getrandom::getrandom(&mut esk).map_err(|e| e.to_string())?;
    let eph = StaticSecret::from(esk);
    let eph_pub_b64 = b64(&PublicKey::from(&eph).to_bytes());

    let mut server_pin = load_pin(host); // Some => already paired with this host
    let mut pairing = server_pin.is_none();
    let mut pair_server_pub: Option<[u8; 32]> = None; // candidate during pairing
    let mut pair_sas: Option<String> = None;
    let mut sas_emitted = false;
    let mut cipher: Option<ChaCha20Poly1305> = None;

    let mut last_keep = Instant::now() - Duration::from_secs(2);
    let mut last_stats = Instant::now();

    let mut buf = [0u8; 2048];
    let mut pcm = [0i16; 1920];
    let mut din = [0.0f32; FRAME];
    let mut dout = [0.0f32; FRAME];
    let mut hold = 0i32;

    let loop_start = Instant::now();
    let mut last_rtt: u32 = 0;
    let mut last_seq: Option<u32> = None;
    let mut last_keys = String::new();
    let mut last_mute = false;
    let mut bytes: u64 = 0;
    let mut recv_frames: u32 = 0;
    let mut lost_frames: u32 = 0;

    while !stop.load(Ordering::Relaxed) {
        if last_keep.elapsed() >= Duration::from_secs(1) {
            if pairing {
                match pair_server_pub {
                    None => {
                        // Commit phase: send H(our pub) before the server reveals its key.
                        let _ = sock.send(format!("PC|{client_id}|{client_name}|{commit_b64}").as_bytes());
                    }
                    Some(_) => {
                        // Reveal phase: send our actual pub (server checks it against the commit).
                        let _ = sock.send(format!("PR|{client_id}|{my_pub_b64}").as_bytes());
                    }
                }
            } else {
                let _ = sock.send(format!("HELO|{client_id}|{client_name}|{eph_pub_b64}").as_bytes());
            }
            last_keep = Instant::now();
        }

        // Sync mute to the server (stops transmission while muted).
        let want_mute = shared.muted.load(Ordering::Relaxed);
        if want_mute != last_mute {
            last_mute = want_mute;
            let _ = sock.send(if want_mute { b"MUTE|1" } else { b"MUTE|0" });
        }

        // User-entered SAS (one-shot): verify it locally against our computed value.
        let typed = {
            let mut g = shared.code.lock().unwrap();
            let c = g.clone();
            g.clear();
            c
        };
        if !typed.is_empty() {
            if let (Some(expected), Some(spub)) = (pair_sas.as_ref(), pair_server_pub) {
                if &typed == expected {
                    save_pin(host, &spub); // pin the now-authenticated server key
                    server_pin = Some(spub);
                    let _ = sock.send(format!("PK|{client_id}").as_bytes());
                    pairing = false; // switch to the session HELO flow (with ephemerals)
                } else {
                    let _ = app.emit("sas-bad", ());
                }
            }
        }
        if last_stats.elapsed() >= Duration::from_secs(1) {
            let total = recv_frames + lost_frames;
            let loss = if total > 0 { lost_frames as f32 / total as f32 } else { 0.0 };
            let _ = app.emit("stats", Stats { kbps: (bytes * 8 / 1000) as u32, loss, ping: last_rtt });
            // Tell the server our loss so it can tune FEC / bitrate.
            let _ = sock.send(format!("LOSS|{}", (loss * 100.0).round() as u32).as_bytes());
            // Round-trip ping (token = our monotonic ms; the server echoes it back).
            let token = loop_start.elapsed().as_millis() as u64;
            let _ = sock.send(format!("PING|{token}").as_bytes());
            bytes = 0;
            recv_frames = 0;
            lost_frames = 0;
            last_stats = Instant::now();
        }

        let n = match sock.recv(&mut buf) {
            Ok(n) => n,
            Err(_) => continue, // timeout
        };

        // Ping echo: server returned our token -> compute round-trip ms.
        if n >= 5 && &buf[..5] == b"PONG|" {
            if let Some(token) = std::str::from_utf8(&buf[5..n]).ok().and_then(|s| s.trim().parse::<u64>().ok()) {
                let now = loop_start.elapsed().as_millis() as u64;
                last_rtt = now.saturating_sub(token) as u32;
            }
            continue;
        }

        // Mute sync from the server (also serves as a keepalive while muted).
        if n >= 5 && &buf[..5] == b"MUTE|" {
            let on = buf.get(5) == Some(&b'1');
            let prev = shared.muted.swap(on, Ordering::Relaxed);
            last_mute = on; // don't echo it straight back
            if prev != on {
                let _ = app.emit("remote-mute", on);
            }
            continue;
        }

        // KEYS is handled even mid-session so the client always follows the server's current
        // session key. "KEYS|<salt>|<server ephemeral pub>".
        if n >= 5 && &buf[..5] == b"KEYS|" {
            if let Ok(rest) = std::str::from_utf8(&buf[5..n]) {
                // Only act on a *new* KEYS (server (re)started / re-keyed); ignore resends.
                if rest != last_keys {
                    let mut it = rest.split('|');
                    let salt = it.next().and_then(|s| {
                        base64::engine::general_purpose::STANDARD.decode(s.trim()).ok()
                    });
                    let eph_server = it.next().and_then(unb64_32);
                    if let (Some(salt), Some(es), Some(sp)) = (salt, eph_server, server_pin) {
                        cipher = Some(session_cipher(&eph, &identity, &es, &sp, &salt));
                        pairing = false;
                        last_seq = None; // server reset its sequence → resync (avoid dropping)
                        last_keys = rest.to_string();
                    }
                }
            }
            continue;
        }

        // Pairing control messages (only before a session exists).
        if cipher.is_none() {
            // Server reveals its long-term pub -> compute SAS, ask the user.
            if n >= 3 && &buf[..3] == b"PS|" {
                if let Some(spub) = std::str::from_utf8(&buf[3..n]).ok().and_then(unb64_32) {
                    if pairing {
                        pair_server_pub = Some(spub);
                        pair_sas = Some(sas(&my_pub, &spub));
                        if !sas_emitted {
                            sas_emitted = true;
                            let _ = app.emit("sas-needed", ());
                        }
                    }
                }
                continue;
            }
            // Server forgot us (e.g. reinstalled) -> drop the pin and re-pair.
            if n == 6 && &buf[..6] == b"REPAIR" {
                let _ = std::fs::remove_file(pin_file(host));
                server_pin = None;
                pairing = true;
                pair_server_pub = None;
                pair_sas = None;
                sas_emitted = false;
                continue;
            }
            if n >= 5 && &buf[..5] == b"AUTH:" {
                if let Ok(s) = std::str::from_utf8(&buf[..n]) {
                    let _ = app.emit("auth", s.to_string());
                }
                continue;
            }
            continue; // nothing else expected before the session key exists
        }

        if n < 5 {
            continue;
        }
        bytes += n as u64;

        let seq = u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]);
        let mut missing = 0u32;
        if let Some(last) = last_seq {
            let gap = seq.wrapping_sub(last);
            if gap == 0 || gap > 0x8000_0000 {
                continue; // duplicate / out-of-order
            }
            missing = (gap - 1).min(MAX_PLC);
        }
        last_seq = Some(seq);

        // Decrypt the Opus payload (nonce derived from the plaintext seq).
        let c = match &cipher {
            Some(c) => c,
            None => continue, // handshake not done yet
        };
        let mut nonce = [0u8; 12];
        nonce[8..].copy_from_slice(&seq.to_be_bytes());
        let payload = match c.decrypt(Nonce::from_slice(&nonce), &buf[4..n]) {
            Ok(p) => p,
            Err(_) => continue,
        };

        let denoise = shared.denoise.load(Ordering::Relaxed);
        let gate = shared.vad();

        // Recover lost frames: older ones via PLC, the one right before this packet via
        // Opus in-band FEC carried in *this* packet (decode_fec = true).
        if missing >= 1 {
            for _ in 0..(missing - 1) {
                if decoder.decode(Option::<&[u8]>::None, &mut pcm[..FRAME], false).is_ok() {
                    lost_frames += 1;
                    emit_pcm(audio, &mut denoiser, &pcm[..FRAME], denoise, gate, &mut hold, &mut din, &mut dout);
                }
            }
            if let Ok(s) = decoder.decode(Some(payload.as_slice()), &mut pcm[..FRAME], true) {
                if s >= FRAME {
                    lost_frames += 1;
                    emit_pcm(audio, &mut denoiser, &pcm[..FRAME], denoise, gate, &mut hold, &mut din, &mut dout);
                }
            }
        }

        // Current frame.
        let samples = match decoder.decode(Some(payload.as_slice()), &mut pcm[..], false) {
            Ok(s) => s,
            Err(_) => continue,
        };
        if samples < FRAME {
            continue;
        }
        recv_frames += 1;
        emit_pcm(audio, &mut denoiser, &pcm[..FRAME], denoise, gate, &mut hold, &mut din, &mut dout);
    }

    let _ = sock.send(b"BYE!");
    Ok(())
}

// ---------------------------------------------------------------------------
// Tray
// ---------------------------------------------------------------------------
fn rebuild_tray_menu(
    app: &AppHandle,
    show: &str,
    quit: &str,
    disconnect: Option<&str>,
    status: Option<&str>,
) -> tauri::Result<()> {
    use tauri::menu::{MenuBuilder, MenuItemBuilder};

    let mut b = MenuBuilder::new(app);
    if let Some(s) = status {
        let item = MenuItemBuilder::with_id("status", s).enabled(false).build(app)?;
        b = b.item(&item).separator();
    }
    let show_i = MenuItemBuilder::with_id("show", show).build(app)?;
    b = b.item(&show_i);
    if let Some(d) = disconnect {
        let di = MenuItemBuilder::with_id("disconnect", d).build(app)?;
        b = b.item(&di);
    }
    let quit_i = MenuItemBuilder::with_id("quit", quit).build(app)?;
    b = b.separator().item(&quit_i);

    let menu = b.build()?;
    if let Some(tray) = app.tray_by_id("main") {
        tray.set_menu(Some(menu))?;
    }
    Ok(())
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    use tauri::menu::{MenuBuilder, MenuItemBuilder};
    use tauri::tray::TrayIconBuilder;

    let show = MenuItemBuilder::with_id("show", "Show").build(app)?;
    let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;
    let menu = MenuBuilder::new(app).items(&[&show, &quit]).build()?;

    let mut builder = TrayIconBuilder::with_id("main")
        .menu(&menu)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            "disconnect" => {
                let _ = app.emit("tray-action", "disconnect");
            }
            "quit" => app.exit(0),
            _ => {}
        });
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder.build(app)?;
    Ok(())
}

// ---------------------------------------------------------------------------
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let state = AppState {
        client_id: load_or_create_client_id(),
        client_name: hostname(),
        shared: Arc::new(Shared {
            denoise: AtomicBool::new(true),
            vad_bits: AtomicU32::new(0.0f32.to_bits()),
            muted: AtomicBool::new(false),
            code: Mutex::new(String::new()),
        }),
        net: Mutex::new(None),
        discovery: Mutex::new(None),
        pulse_modules: Mutex::new(Vec::new()),
    };

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_log::Builder::new().build())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .manage(state)
        .setup(|app| {
            build_tray(app.handle())?;
            // Linux: spin up a virtual mic (null-sink + remap source). No-op elsewhere.
            let ids = setup_virtual_mic();
            *app.state::<AppState>().pulse_modules.lock().unwrap() = ids;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_client_id,
            get_accent,
            set_denoise,
            set_vad,
            set_mute,
            set_code,
            start_discovery,
            connect,
            disconnect,
            reset,
            update_tray,
            set_tray_icon,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            if let tauri::RunEvent::Exit = event {
                let ids = app_handle.state::<AppState>().pulse_modules.lock().unwrap().clone();
                teardown_virtual_mic(&ids);
            }
        });
}
