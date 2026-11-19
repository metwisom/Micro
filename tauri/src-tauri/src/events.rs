//! The payloads the backend emits to the webview. This is a contract with the frontend: every
//! struct here has a reader in `src/main.js`, so renaming a field breaks the UI silently unless
//! both sides move together.

use serde::Serialize;

/// `device-found` — one reply to a discovery broadcast.
#[derive(Clone, Serialize)]
pub(crate) struct DeviceFound {
    pub(crate) ip: String,
    pub(crate) port: u16,
    pub(crate) name: String,
    /// Opaque per-install id (stable per phone) — dedups multiple IPs of one device.
    pub(crate) id: String,
}

/// `stats` — once a second while connected.
#[derive(Clone, Serialize)]
pub(crate) struct Stats {
    pub(crate) kbps: u32,
    pub(crate) loss: f32,
    pub(crate) ping: u32, // round-trip ms (network)
}

/// `vu` — ~30 Hz meter feed.
#[derive(Clone, Serialize)]
pub(crate) struct Vu {
    pub(crate) peak: f32,
    /// A real audio frame arrived this window (drives the "streaming" status).
    pub(crate) active: bool,
}
