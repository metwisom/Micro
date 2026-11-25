# Micro — desktop client (Tauri)

Native Tauri v2 client. **Rust** does everything on the audio path — UDP networking, phone
discovery, decryption, Opus decode, RNNoise denoise, jitter buffering and native playback (cpal).
The **webview** only renders the UI and a VU meter. Much smaller binary than Electron, no bundled
Chromium, and no audio ever crosses the JS boundary.

```
[Android: Opus/UDP]  ──Wi-Fi──▶  [Rust: UDP + decrypt + Opus + RNNoise + cpal]  ──▶  output device
                                            │
                                            └──events (vu/stats/…)──▶  [webview: UI only]
```

- `src-tauri/` — Rust backend, one concern per module; `src/lib.rs` is just the module map and
  the Tauri builder. Vendored C RNNoise lives in `rnnoise/` (built by `build.rs`).
- `src/`, `index.html` — frontend (UI, device selection, VU meter). `src/state.js` holds the pure
  status/auth/IP-follow logic; `npm test` runs its unit tests (`tests/`, node:test, no DOM needed).

## Prerequisites (Windows)
- Rust (`rustup`, msvc target) + **VS Build Tools** (MSVC linker).
- A C compiler toolchain for the vendored RNNoise (`cc`, comes with the MSVC tools).
- Node 18+ to build, **22+ to run `npm test`** (the test runner only expands the glob in
  `node --test "tests/**/*.test.mjs"` from 22 on), and the WebView2 runtime (preinstalled on Win10/11).

No cmake or nasm: `audiopus_sys` links a prebuilt libopus for the MSVC target. On Linux/macOS it
instead finds libopus through pkg-config — see [`../scripts/README.md`](../scripts/README.md).

## Run / build
```powershell
cd tauri
npm install
npm run tauri dev      # dev with hot reload
npm run tauri build    # produces an NSIS installer in src-tauri/target/release/bundle
```

`npm run tauri icon ..\icon.png` regenerates the icon set in `src-tauri/icons/`. It also emits
iOS and Android sets; those are gitignored — Micro is desktop-only here, and the phone app is a
separate native project.

## How it works
- Frontend `invoke()`s Rust commands: `start_discovery` (runs for the life of the app, also while
  connected — that is how a phone IP change is noticed), `connect{host,port,device,buffer,gain}`,
  `disconnect`, `set_denoise{on}`, `set_vad{gate}`, `set_gain`, `set_mute`, `set_monitor`,
  `set_output_device{device}`, `set_code`, `list_outputs`, `update_tray`, `set_tray_icon`,
  `get_accent`, `reset`. Commands that
  join threads or enumerate devices are `async` so they never block the main thread.
- Rust emits UI events to the webview: `device-found`, `vu`, `stats`, `audio-output`, `session`
  (`"up"` / `"lost"`), `sas-needed`, `sas-bad`, `auth` (`AUTH:PENDING` / `AUTH:APPROVED` /
  `AUTH:BUSY` / `AUTH:DENIED`), `remote-mute`, `net-error`, `tray-action`. **No PCM is sent to JS** — playback
  is entirely native (`audio.rs`: lock-free SPSC ring → cpal callback → adaptive jitter buffer →
  Catmull-Rom resampler → gain → soft-knee limiter). A second (monitor) stream can mirror the audio
  to headphones.
- Output device is selected by name (`list_outputs` / `connect{device}`); the UI auto-picks
  VB-Cable's `CABLE Input` (Windows) or the `Micro` sink (Linux) when present.
- Long-term identity is stored in the **OS secret store** (Windows Credential Manager / macOS
  Keychain / Linux Secret Service), with an owner-only file fallback. Stable client UUID is in
  `%APPDATA%\Micro\client-id`; server pins under `%APPDATA%\Micro\pins\`. UI settings live in the
  webview's localStorage.
- Window closes to the **tray** (localized Show / Disconnect / Quit, with a status line and a
  colored status dot). Fixed width 500; height follows content and can be resized up to the current
  monitor's work area, with scrolling when content does not fit.

## Linux memory diagnostics

Quit Micro completely via the tray, then start it with `MICRO_MEMORY_PROFILE=1 micro-client`.
Every 30 seconds the app records its and its WebKit child processes' RSS, PSS, anonymous memory
and swap, plus UI event/layout counters and DOM node count. Logs are private to the user, in
`~/.cache/Micro/memory-<pid>-<start-time>.log` (or `$XDG_CACHE_HOME/Micro`), and capped at 2 MiB
per run. Stop and restart to begin another log; delete old logs when no longer needed. No audio,
IP addresses, device names, or credentials are recorded. This tracks memory growth and activity,
not allocation call stacks or JavaScript heap contents.

## Protocol
Same wire format as the server — see the root [`PROTOCOL.md`](../PROTOCOL.md).
