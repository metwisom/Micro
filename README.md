<div align="center">

<img src="icon.png" width="120" alt="Micro" />

# Micro

**Turn your Android phone into a wireless, end-to-end encrypted microphone for your PC.**

![Platforms](https://img.shields.io/badge/client-Windows%20%7C%20macOS%20%7C%20Linux-blue)
![Server](https://img.shields.io/badge/server-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

</div>

---

The phone captures the mic, encodes it with **native Opus** and streams it over Wi-Fi. A small
desktop app decodes it (with **RNNoise** noise suppression), and feeds it into a **virtual
microphone** so any app — Discord, Zoom, OBS, Teams — sees the phone as a real mic.

```
┌──────────────────────────┐          Wi-Fi (UDP)         ┌────────────────────────────────┐
│  Android (server)        │     encrypted Opus frames    │  Desktop client (Tauri)        │
│  mic → Opus → ChaCha20   │ ───────────────────────────> ⁠⁠│  decrypt → Opus → RNNoise →    │
│  (X25519 + FEC)          │ <───── pairing/keepalive ─── │  WebAudio → virtual mic        │
└──────────────────────────┘                              └────────────────────────────────┘
                                                                          │
                                                          Discord / Zoom / OBS see a microphone
```

## Features

- 🔊 **Native audio** — libopus encode (NDK) on the phone, libopus decode + RNNoise on the PC. 10 ms frames, low latency.
- 🔐 **End-to-end encryption** — X25519 ECDH + ChaCha20-Poly1305, **forward secrecy** (ephemeral keys), authenticated by **pinned identities**.
- 🤝 **SAS pairing** — a 4-digit code shown on the phone, with a commitment step → real MITM protection (not just a shared secret).
- 🛡️ **Resilience** — Opus in-band **FEC** + packet-loss-concealment, **adaptive bitrate**, adaptive jitter buffer.
- 📡 **Auto-discovery** — the client finds the phone on the LAN (no typing IPs); manual IP also supported.
- 🎚️ **Controls** — volume, RNNoise toggle, VAD gate, buffer/latency, monitor-to-headphones test, global mute hotkey.
- 🖥️ **Native desktop app** — Tauri (Rust + webview), tray, system theme + accent, i18n (EN/RU), tiny binary.
- 🐧 **Virtual mic, per OS** — auto-created on Linux; VB-Cable on Windows; BlackHole on macOS.

## Repository layout

| Path | What |
|------|------|
| [`android/`](android) | **Server** — Android app (Kotlin + NDK libopus). Captures the mic, encrypts, streams. |
| [`tauri/`](tauri) | **Client** — desktop app (Tauri/Rust). Decodes, denoises, feeds the virtual mic. |
| [`PROTOCOL.md`](PROTOCOL.md) | UDP wire format: discovery, SAS pairing, encryption, audio. |
| [`BUILD.md`](BUILD.md) | Build commands for every platform. |

## Quick start

1. **Phone:** install the APK ([`android/`](android)), open Micro (the server auto-starts).
2. **PC:** run the client (`cd tauri && npm install && npm run tauri dev`).
3. The phone appears as a button — click it, enter the **4-digit code** shown on the phone.
4. Pick the **virtual mic** as the output device; select it as the microphone in your app.

Virtual microphone:

| OS | Virtual mic |
|----|-------------|
| **Linux** | Created automatically at launch (PulseAudio/PipeWire sink "Micro"). |
| **Windows** | Install [VB-CABLE](https://vb-audio.com/Cable/); the client auto-selects `CABLE Input`. |
| **macOS** | Install [BlackHole](https://github.com/ExistentialAudio/BlackHole); select it as output. |

Full build instructions → [BUILD.md](BUILD.md).

## Security

Confidentiality + authentication on the LAN: ECDH gives confidentiality, the pinned long-term
keys authenticate every session, and the ephemeral keys give forward secrecy. The 4-digit SAS
with a commitment step defeats an active man-in-the-middle (≈ 1 in 10000 per attempt). Details
in [PROTOCOL.md](PROTOCOL.md). Note: this is a hobby project, not an audited security product.

## License

[MIT](LICENSE) © metwisom
