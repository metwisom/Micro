//! The client's UDP socket and the one thing every caller sends through it.
//!
//! The socket is `connect()`ed, which is what makes a desktop interface/IP change fatal to it: the
//! bound source address is gone and every later send fails silently forever. `Link` counts those
//! failures and re-creates the socket, so a laptop moving between networks recovers on its own.

use std::net::UdpSocket;
use std::time::Duration;

use chacha20poly1305::aead::AeadInPlace;
use chacha20poly1305::{ChaCha20Poly1305, Nonce};

use crate::proto::{ctl_nonce, CTL_C2S, DSCP_EF_TOS, SEND_FAILS_REBIND};

pub(crate) struct Link {
    pub(crate) sock: UdpSocket,
    host: String,
    port: u16,
    send_fails: u32,
}

impl Link {
    fn open(host: &str, port: u16) -> Result<UdpSocket, String> {
        let sock = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        sock.connect((host, port)).map_err(|e| e.to_string())?;
        sock.set_read_timeout(Some(Duration::from_millis(250))).ok();
        set_voice_tos(&sock);
        Ok(sock)
    }

    pub(crate) fn new(host: &str, port: u16) -> Result<Link, String> {
        Ok(Link { sock: Link::open(host, port)?, host: host.to_string(), port, send_fails: 0 })
    }

    pub(crate) fn send(&mut self, bytes: &[u8]) {
        match self.sock.send(bytes) {
            Ok(_) => self.send_fails = 0,
            Err(e) => {
                self.send_fails += 1;
                if self.send_fails >= SEND_FAILS_REBIND {
                    log::warn!("udp send failing ({e}); re-creating the socket");
                    self.rebind();
                }
            }
        }
    }

    /// Fresh socket (new local port is fine: the server rebinds a stale endpoint on the next
    /// authenticated HELO/CT). Keeps the old one if the OS refuses right now.
    pub(crate) fn rebind(&mut self) {
        if let Ok(s) = Link::open(&self.host, self.port) {
            self.sock = s;
        }
        self.send_fails = 0;
    }
}

/// DSCP EF on the client socket so Wi-Fi WMM queues our control packets as voice (best effort:
/// Windows ignores IP_TOS without the QoS API, which is fine for a 4 pps control channel).
fn set_voice_tos(sock: &UdpSocket) {
    if let Err(e) = socket2::SockRef::from(sock).set_tos_v4(DSCP_EF_TOS) {
        log::debug!("set_tos_v4: {e}");
    }
}

/// Seal one control message under the live session key and send it (client → server direction).
/// `ctr` must be fresh and strictly increasing — the receiver rejects anything it has already seen.
pub(crate) fn send_ctl(link: &mut Link, cipher: &ChaCha20Poly1305, ctr: u32, body: &[u8]) {
    let mut pt = body.to_vec();
    if cipher.encrypt_in_place(Nonce::from_slice(&ctl_nonce(CTL_C2S, ctr)), b"", &mut pt).is_ok() {
        let mut pkt = Vec::with_capacity(7 + pt.len());
        pkt.extend_from_slice(b"CT|");
        pkt.extend_from_slice(&ctr.to_be_bytes());
        pkt.extend_from_slice(&pt);
        link.send(&pkt);
    }
}
