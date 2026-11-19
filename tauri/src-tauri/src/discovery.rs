//! LAN discovery: broadcast `MICRO?`, collect `MICRO!` replies, emit one `device-found` per answer.
//!
//! It keeps running while connected — that is how the UI notices the phone moving to another IP.

use std::net::{Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter};

use crate::events::DeviceFound;
use crate::proto::PORT;

/// Every broadcast address we can reach, plus the global one. A single 255.255.255.255 is not
/// enough: some stacks only deliver it on the default route, so per-interface addresses are added.
fn broadcast_addrs() -> Vec<Ipv4Addr> {
    let mut v = vec![Ipv4Addr::new(255, 255, 255, 255)];
    if let Ok(ifaces) = if_addrs::get_if_addrs() {
        for iface in ifaces {
            if let if_addrs::IfAddr::V4(a) = iface.addr {
                if a.ip.is_loopback() {
                    continue;
                }
                let b =
                    a.broadcast.unwrap_or_else(|| Ipv4Addr::from(u32::from(a.ip) | !u32::from(a.netmask)));
                if !v.contains(&b) {
                    v.push(b);
                }
            }
        }
    }
    v
}

pub(crate) fn discovery_loop(app: AppHandle, stop: Arc<AtomicBool>) {
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
                    let mut it = rest.splitn(3, '|');
                    let port = it.next().and_then(|p| p.trim().parse().ok()).unwrap_or(PORT);
                    let name = it.next().unwrap_or("").trim();
                    let id = it.next().unwrap_or("").trim().to_string(); // opaque phone id
                    let ip = src.ip().to_string();
                    let _ = app.emit(
                        "device-found",
                        DeviceFound {
                            ip: ip.clone(),
                            port,
                            name: if name.is_empty() { ip } else { name.to_string() },
                            id,
                        },
                    );
                }
            }
        }
    }
}
