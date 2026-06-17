# Micro — desktop client (Tauri)

Native Tauri v2 rewrite of the client. **Rust** does UDP networking, phone discovery and
native audio decode (Opus via libopus, RNNoise via `nnnoiseless`); the **webview** plays the
decoded PCM and renders the UI. Much smaller binary than Electron, no bundled Chromium.

```
[Android: Opus/UDP]  ──Wi-Fi──▶  [Rust: UDP + Opus + RNNoise]  ──events──▶  [webview: WebAudio → device]
```

- `src-tauri/` — Rust backend (commands, UDP, decode, tray).
- `src/`, `index.html`, `public/` — frontend (UI, playback, device selection).

## Prerequisites (Windows)
- Rust (`rustup`, msvc target) + **VS Build Tools** (MSVC linker).
- **cmake** + **nasm** — needed to build libopus (the `audiopus` crate).
- Node + WebView2 runtime (preinstalled on Win10/11).

## Run / build
```powershell
cd tauri
npm install
npm run tauri dev      # dev with hot reload
npm run tauri build    # produces an NSIS installer in src-tauri/target/release/bundle
```

`npm run tauri icon ..\icon.png` regenerates the icon set in `src-tauri/icons/`.

## How it works
- Frontend `invoke()`s Rust commands: `start_discovery`, `connect{host,port}`, `disconnect`,
  `set_denoise{on}`, `set_vad{gate}`, `reset`.
- Rust emits events to the webview: `device-found`, `pcm` (Float32 480-sample frames), `auth`
  (`AUTH:PENDING`/`AUTH:DENIED`), `net-error`.
- The webview pushes `pcm` into an AudioWorklet jitter buffer → GainNode → output device via
  `AudioContext.setSinkId` (point it at VB-Cable's `CABLE Input`).
- Stable client UUID is stored in `%APPDATA%\Micro\client-id` (used in the `HELO|<uuid>|<name>`
  auth handshake). Language/device/volume settings are in the webview's localStorage.
- Window closes to the **tray** (Show / Quit). Fixed width 500; height follows content.

## Protocol
Same wire format as the other clients — see the root `PROTOCOL.md`.

## Notes / TODO
- Audio output device **labels** need media permission; WebView2 may prompt once (we call
  `getUserMedia` to unlock labels). If labels are blank, VB-Cable auto-select can't match —
  pick the device manually.
- Tray menu labels are currently English-only.
- System accent color isn't applied (light/dark theme follows the OS via CSS).
