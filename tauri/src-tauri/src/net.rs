//! The connection to the phone, start to finish: SAS pairing, the authenticated HELO/KEYS exchange,
//! the sealed control channel, and the audio receive path that feeds the native output.
//!
//! One thread owns all of it. Everything it needs to be correct about on its own — nonce layouts,
//! counter freshness, mute semantics, jitter recovery, key derivation — lives in `proto`, `crypto`
//! and `jitter`, where it is unit-tested; what stays here is the sequencing between them.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use chacha20poly1305::aead::AeadInPlace;
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use tauri::{AppHandle, Emitter};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::audio::Sink;
use crate::crypto::{
    auth_tag, auth_tag_ok, b64, fingerprint, helo_auth_cipher, sas, session_cipher, sha256, unb64, unb64_32,
    unb64_n,
};
use crate::events::{Stats, Vu};
use crate::jitter::JitterBuffer;
use crate::link::{send_ctl, Link};
use crate::proto::{
    ctl_nonce, ctr_fresh, next_helo_ctr, should_arm_repair, wall_ms, AUDIO_HDR, AUTH_APPROVED, AUTH_BUSY,
    AUTH_DENIED, AUTH_PENDING, CTL_S2C, CT_BYE, CT_LOSS, CT_MUTE, CT_PING, CT_PONG, DENIED_BACKOFF, FRAME,
    HANGOVER, HELO_RESERVE, MSG_BUSY, MSG_PAIR_APPROVED, MSG_REPAIR, NONCE_AUDIO, NONCE_HELO, NONCE_KEYS,
    PKT_AUDIO, PK_TIMEOUT, PR_RECOMMIT_EVERY, REPAIR_GRACE, REPAIR_HINT_TTL, SESSION_TIMEOUT,
};
use crate::rnnoise;
use crate::state::{LockExt, Shared};
use crate::store::{is_pinned, load_helo_ctr, save_helo_ctr, save_pin_id};

/// Decode/denoise one frame into f32 [-1,1] and push it to the native cpal output sink.
/// The audio path is fully native (no webview/JSON) — the webview only renders the UI and the
/// VU meter, fed by the separate ~30 Hz `vu` event. While the desktop is muted nothing reaches the
/// output, whatever the phone sends: "Muted" here is authoritative, not a request to the phone.
#[allow(clippy::too_many_arguments)]
fn emit_pcm(
    sink: &Sink,
    denoiser: &mut rnnoise::Denoiser,
    pcm: &[i16],
    denoise: bool,
    gate: f32,
    muted: bool,
    hold: &mut i32,
    din: &mut [f32; FRAME],
    dout: &mut [f32; FRAME],
    vu_peak: &mut f32,
) {
    if muted {
        return;
    }
    let mut buf = [0.0f32; FRAME];
    if denoise {
        for i in 0..FRAME {
            din[i] = pcm[i] as f32;
        }
        let vad = denoiser.process(dout, din);
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
            buf[i] = if mute { 0.0 } else { dout[i] / 32768.0 };
        }
    } else {
        for i in 0..FRAME {
            buf[i] = pcm[i] as f32 / 32768.0;
        }
    }
    let mut p = 0.0f32;
    for &x in &buf {
        let a = x.abs();
        if a > p {
            p = a;
        }
    }
    if p > *vu_peak {
        *vu_peak = p;
    }
    sink.push(&buf);
}

/// Handle one inbound authenticated control datagram from the server ("CT|" + counter + sealed body).
/// Carries the mute sync (also a keepalive while muted) and the PONG echo of our RTT probe. Rejects
/// replays/out-of-order by the monotonic counter, then decrypts under the live session key in the
/// server→client nonce direction; a bad counter, failed tag, or empty body is silently dropped.
#[allow(clippy::too_many_arguments)]
fn handle_server_ctl(
    app: &AppHandle,
    shared: &Arc<Shared>,
    cipher: &Option<ChaCha20Poly1305>,
    buf: &[u8],
    n: usize,
    loop_start: Instant,
    ctl_recv: &mut u32,
    seen_mute_req: u32,
    mute_cmd: &mut Option<bool>,
    mute_reported: &mut bool,
    last_rtt: &mut u32,
    last_rx: &mut Instant,
) {
    let Some(c) = cipher else { return };
    let ctr = u32::from_be_bytes([buf[3], buf[4], buf[5], buf[6]]);
    if !ctr_fresh(*ctl_recv, ctr) {
        return; // replay / out-of-order
    }
    let mut data = buf[7..n].to_vec();
    if c.decrypt_in_place(Nonce::from_slice(&ctl_nonce(CTL_S2C, ctr)), b"", &mut data).is_err()
        || data.is_empty()
    {
        return;
    }
    *ctl_recv = ctr;
    *last_rx = Instant::now();
    match data[0] {
        CT_MUTE if data.len() >= 2 => {
            let on = data[1] != 0;
            if let Some(show) = shared.on_mute_report(seen_mute_req, mute_cmd, !*mute_reported, on) {
                let _ = app.emit("remote-mute", show);
            }
            *mute_reported = true;
        }
        CT_PONG if data.len() >= 9 => {
            let token = u64::from_be_bytes(data[1..9].try_into().unwrap());
            let now = loop_start.elapsed().as_millis() as u64;
            *last_rtt = now.saturating_sub(token) as u32;
        }
        _ => {}
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn net_loop(
    app: &AppHandle,
    host: &str,
    port: u16,
    client_id: &str,
    client_name: &str,
    identity: &StaticSecret,
    shared: &Arc<Shared>,
    stop: &Arc<AtomicBool>,
    sink: &Sink,
) -> Result<(), String> {
    let mut link = Link::new(host, port)?;

    let mut decoder = audiopus::coder::Decoder::new(audiopus::SampleRate::Hz48000, audiopus::Channels::Mono)
        .map_err(|e| format!("opus init: {e}"))?;

    let mut denoiser = rnnoise::Denoiser::new();

    let my_pub: [u8; 32] = PublicKey::from(identity).to_bytes();
    let my_pub_b64 = b64(&my_pub);
    // Fresh per-pairing nonce mixed into the SAS so the code differs on every attempt. The commitment
    // (`PC`) binds it, so a MITM still can't grind it. Rotated on a wrong code.
    let mut pair_nonce = [0u8; 16];
    getrandom::fill(&mut pair_nonce).map_err(|e| e.to_string())?;
    let commit_of = |nonce: &[u8; 16]| b64(&sha256(&[my_pub.as_slice(), nonce.as_slice()].concat()));
    let mut commit_b64 = commit_of(&pair_nonce);

    // Fresh ephemeral keypair per session → forward secrecy.
    let mut esk = [0u8; 32];
    getrandom::fill(&mut esk).map_err(|e| e.to_string())?;
    let eph = StaticSecret::from(esk);
    esk.zeroize();
    let eph_pub_b64 = b64(&PublicKey::from(&eph).to_bytes());

    // --- Pairing / session state machine ---------------------------------------------------------
    // pairing      : running PC/PR (first contact or re-pair) until the user's code matched.
    // awaiting_pa  : code matched → PK is (re)sent every second until the phone answers PA
    //                (user tapped Allow), AUTH:PENDING (still waiting) or AUTH:DENIED.
    // !pairing     : HELO keepalive; KEYS → session.
    let mut server_pin: Option<[u8; 32]> = None;
    let mut pairing = true;
    let mut awaiting_pa = false;
    let mut awaiting_since = Instant::now();
    // When the phone last said AUTH:DENIED. Plaintext and spoofable, so it only backs us off for a
    // while instead of silencing us for good; any authenticated progress clears it.
    let mut denied_at: Option<Instant> = None;
    let mut pair_server_pub: Option<[u8; 32]> = None; // candidate during pairing
    let mut pair_sas: Option<String> = None;
    // True while re-pairing a server we've *already* pinned: makes the PS handler show the SAS prompt
    // even though the identity is pinned.
    let mut force_pair = false;
    // Grace timer for a *known* (pinned) server that doesn't bring a session up. If it expires with
    // no session key, the server likely forgot us → escalate to a re-pair SAS prompt. This is the ONLY
    // path that arms the prompt for a pinned identity; an unauthenticated REPAIR can merely shorten it.
    let mut repair_deadline: Option<Instant> = None;
    // When the phone last answered REPAIR, i.e. was reachable and did not know us. It expires:
    // a hint from a minute ago says nothing about now, and leaving it set meant one spoofed packet
    // stayed armed for the whole time the phone was merely asleep. Cleared outright by any
    // authenticated progress — a KEYS, an approval, or the phone saying it is still thinking.
    let mut saw_repair_at: Option<Instant> = None;
    // HELO-auth cipher, derived from the static×static DH once the server identity is pinned/known.
    let mut helo_auth: Option<ChaCha20Poly1305> = None;
    let mut cipher: Option<ChaCha20Poly1305> = None;
    let mut last_keys = String::new(); // "KEYS|salt|eph" of the live session (resends are ignored)

    // HELO counter echoed by the KEYS we adopted; a different, older KEYS is stale.
    let mut adopted_ctr: u64 = 0;
    let mut last_rx = Instant::now(); // last packet that decrypted under the session key

    // The last few HELO counters we sent: a KEYS must echo one of them, which binds the reply to a
    // request we actually made.
    let mut sent_ctrs = [0u64; 4];
    let mut sent_idx = 0usize;

    let mut last_keep = Instant::now() - Duration::from_secs(2);
    let mut last_stats = Instant::now();

    // Strictly-monotonic HELO counter, persisted so it survives restarts and never regresses even if
    // the system clock steps backwards. Seeded above the wall clock so a fresh install starts near "now".
    //
    // Counters are handed out from a block reserved on disk *before* any of them is used. Writing
    // the counter we just sent would be too late: this is the nonce for a Poly1305 tag under the
    // long-term HELO-auth key, so a crash between the send and the write used to let the counter
    // come back and repeat a nonce under a key that outlives the process.
    // Reserved lazily: a connection that never reaches the HELO stage (pairing refused, wrong
    // phone, user cancels) must not burn a block and push the stored mark further from the clock
    // on every attempt. `helo_reserved == helo_ctr` means "nothing reserved yet".
    let mut helo_ctr: u64 = load_helo_ctr().max(wall_ms());
    let mut helo_reserved = helo_ctr;

    let mut buf = [0u8; 2048];
    let mut pcm = [0i16; 1920];
    let mut din = [0.0f32; FRAME];
    let mut dout = [0.0f32; FRAME];
    let mut hold = 0i32;

    let loop_start = Instant::now();
    let mut last_rtt: u32 = 0;
    let mut vu_peak = 0.0f32;
    let mut vu_active = false;
    let mut last_vu = Instant::now();
    let mut scratch: Vec<u8> = Vec::with_capacity(512); // reused decrypt buffer (no per-frame alloc)
    let mut jb = JitterBuffer::new();
    // Mute (see `mute_report_action`): a local toggle becomes a command we repeat until the phone
    // reports that state back; with nothing outstanding the phone's report is simply adopted. We
    // never re-assert our state on a timer — that is what made the two sides overwrite each other.
    let mut mute_cmd: Option<bool> = None;
    let mut mute_reported = false; // has the phone reported at all in this session?
    let mut seen_mute_req = shared.mute_req.load(Ordering::Relaxed);
    let mut pr_ticks: u32 = 0;
    let mut ctl_send: u32 = 0; // our outgoing control counter (client → server)
    let mut ctl_recv: u32 = 0; // highest accepted incoming control counter (server → client)
    let mut bytes: u64 = 0;
    let mut recv_frames: u32 = 0;
    let mut lost_frames: u32 = 0;

    while !stop.load(Ordering::Relaxed) {
        if last_keep.elapsed() >= Duration::from_secs(1) {
            // A pinned server that produces no session must eventually lead somewhere. Deciding
            // this from our own state (rather than from the phone's plaintext REPAIR hint) is what
            // lets that hint stay advisory: it may only *shorten* this deadline, never create one.
            let saw_repair = saw_repair_at.is_some_and(|t| t.elapsed() < REPAIR_HINT_TTL);
            if should_arm_repair(
                saw_repair,
                server_pin.is_some(),
                cipher.is_some(),
                pairing,
                awaiting_pa,
                repair_deadline.is_some(),
            ) {
                repair_deadline = Some(Instant::now() + REPAIR_GRACE);
            }
            if awaiting_pa && awaiting_since.elapsed() > PK_TIMEOUT {
                // No AUTH:PENDING / PA for a long time: the phone lost the pairing slot (service
                // restarted, slot aged out). Start a fresh commit round so its prompt comes back.
                awaiting_pa = false;
                pairing = true;
                force_pair = true;
                let _ = getrandom::fill(&mut pair_nonce);
                commit_b64 = commit_of(&pair_nonce);
                pair_server_pub = None;
                pair_sas = None;
            }
            if denied_at.is_some_and(|t| t.elapsed() < DENIED_BACKOFF) {
                // The phone rejected this device: back off (the UI shows it); a genuine denial keeps
                // answering AUTH:DENIED harmlessly, a spoofed one is outlived by the real phone's answer.
            } else if awaiting_pa {
                // Code matched → keep asking until the phone's user taps Allow (PA) or denies. A code
                // typed meanwhile has nothing to be compared against, so it is dropped here.
                shared.code.lock_safe().clear();
                link.send(format!("PK|{client_id}").as_bytes());
            } else if pairing {
                pr_ticks = pr_ticks.wrapping_add(1);
                match pair_server_pub {
                    Some(_) if pr_ticks % PR_RECOMMIT_EVERY != 0 => {
                        // Reveal phase: our actual pub + the committed nonce (server checks the commit,
                        // then computes the SAS over pub‖serverPub‖nonce and shows it).
                        link.send(format!("PR|{client_id}|{my_pub_b64}|{}", b64(&pair_nonce)).as_bytes());
                    }
                    _ => {
                        // Commit phase: send H(our pub ‖ nonce) before the server reveals its key. Also
                        // repeated every PR_RECOMMIT_EVERY ticks during the reveal phase (same commit, so
                        // the phone keeps the same code and shows no duplicate prompt).
                        link.send(format!("PC|{client_id}|{client_name}|{commit_b64}").as_bytes());
                    }
                }
            } else {
                // Authenticated HELO: "HELO|uuid|name|ephPub|<ctr(8B BE)>|<tag(16B)>".
                if helo_auth.is_none() {
                    if let Some(sp) = server_pin {
                        helo_auth = Some(helo_auth_cipher(identity, &sp));
                    }
                }
                if let Some(ka) = &helo_auth {
                    helo_ctr = next_helo_ctr(wall_ms(), helo_ctr);
                    // Reserve the block containing this counter before the counter leaves the
                    // process. Also covers the very first HELO, where nothing is reserved yet.
                    sent_ctrs[sent_idx % sent_ctrs.len()] = helo_ctr;
                    sent_idx += 1;
                    let prefix = format!(
                        "HELO|{client_id}|{client_name}|{eph_pub_b64}|{}",
                        b64(&helo_ctr.to_be_bytes())
                    );
                    // Reserve the next block before using a counter from it. In steady state the
                    // counter tracks the wall clock, so this is one small write per ~65 s.
                    if helo_ctr >= helo_reserved {
                        helo_reserved = helo_ctr.saturating_add(HELO_RESERVE);
                        save_helo_ctr(helo_reserved);
                    }
                    let tag = auth_tag(ka, NONCE_HELO, helo_ctr, prefix.as_bytes());
                    link.send(format!("{prefix}|{}", b64(&tag)).as_bytes());
                }
                // Retry an unconfirmed mute command. Nothing is sent when there is none, so the
                // phone stays the authority over its own state. The 1 Hz PING below is the liveness
                // heartbeat proper.
                if let (Some(c), Some(want)) = (&cipher, mute_cmd) {
                    ctl_send = ctl_send.wrapping_add(1);
                    send_ctl(&mut link, c, ctl_send, &[CT_MUTE, want as u8]);
                }
            }
            last_keep = Instant::now();
        }

        // VU meter + "is audio flowing" signal (~30 Hz).
        if last_vu.elapsed() >= Duration::from_millis(33) {
            let _ = app.emit("vu", Vu { peak: vu_peak, active: vu_active });
            vu_peak = 0.0;
            vu_active = false;
            last_vu = Instant::now();
        }

        // The user toggled mute here → command the phone, and keep commanding until it reports that
        // state back. `mute_req` only moves for a local toggle, so adopting the phone's own report
        // never lands here and cannot bounce back at it. The wanted state comes out of the request
        // itself, never out of `muted`, which a report may have written since the click.
        if let Some(want) = shared.take_mute_request(&mut seen_mute_req) {
            mute_cmd = Some(want);
            if let Some(c) = &cipher {
                ctl_send = ctl_send.wrapping_add(1);
                send_ctl(&mut link, c, ctl_send, &[CT_MUTE, want as u8]);
            }
        }

        // User-entered SAS: verify it locally against our computed value. Only consumed once we have
        // a value to compare against, so a code typed during a nonce rotation is not silently lost.
        if pair_sas.is_some() {
            let typed = {
                let mut g = shared.code.lock_safe();
                let c = g.clone();
                g.clear();
                c
            };
            if !typed.is_empty() {
                if let (Some(expected), Some(spub)) = (pair_sas.as_ref(), pair_server_pub) {
                    if &typed == expected {
                        save_pin_id(&spub); // pin the now-authenticated server identity (overwrites any old)
                        server_pin = Some(spub);
                        helo_auth = None; // new pinned identity → re-derive the HELO-auth key
                        repair_deadline = None;
                        force_pair = false;
                        pair_sas = None;
                        awaiting_pa = true; // PK until the phone's user allows us
                        awaiting_since = Instant::now();
                        denied_at = None;
                        link.send(format!("PK|{client_id}").as_bytes());
                        let _ = app.emit("auth", AUTH_PENDING);
                    } else {
                        // Wrong code → rotate the nonce and restart the commit→reveal round so the
                        // phone shows a *fresh* code to compare.
                        let _ = getrandom::fill(&mut pair_nonce);
                        commit_b64 = commit_of(&pair_nonce);
                        pair_server_pub = None; // force a new PC (new commit) → new PS → new SAS
                        pair_sas = None;
                        let _ = app.emit("sas-bad", ());
                    }
                }
            }
        }

        // Re-pair escalation (the only path that prompts for a pinned identity). If a known server
        // hasn't produced a session key before the grace deadline, treat it as "server forgot us"
        // and run a fresh commit→reveal round so the user can compare a new SAS.
        if let Some(deadline) = repair_deadline {
            if cipher.is_some() {
                repair_deadline = None;
            } else if !pairing && !awaiting_pa && Instant::now() >= deadline {
                pairing = true;
                force_pair = true; // re-pairing a *pinned* server → show the SAS despite the pin
                pair_server_pub = None; // start with PC so the server opens a pairing for us
                pair_sas = None;
                repair_deadline = None;
            }
        }

        if last_stats.elapsed() >= Duration::from_secs(1) {
            let total = recv_frames + lost_frames;
            let loss = if total > 0 { lost_frames as f32 / total as f32 } else { 0.0 };
            let _ = app.emit("stats", Stats { kbps: (bytes * 8 / 1000) as u32, loss, ping: last_rtt });
            // Loss report + RTT probe ride the authenticated CT channel. The PONG the server sends
            // back is also our liveness signal, so this runs whether or not audio flows.
            if let Some(c) = &cipher {
                ctl_send = ctl_send.wrapping_add(1);
                send_ctl(
                    &mut link,
                    c,
                    ctl_send,
                    &[CT_LOSS, ((loss * 100.0).round() as i64).clamp(0, 100) as u8],
                );
                let token = loop_start.elapsed().as_millis() as u64;
                let mut ping = [0u8; 9];
                ping[0] = CT_PING;
                ping[1..].copy_from_slice(&token.to_be_bytes());
                ctl_send = ctl_send.wrapping_add(1);
                send_ctl(&mut link, c, ctl_send, &ping);
            }
            bytes = 0;
            recv_frames = 0;
            lost_frames = 0;
            last_stats = Instant::now();
        }

        // Session liveness: nothing decrypted for SESSION_TIMEOUT → the server is gone, restarted, or
        // forgot/revoked us. Drop the key and fall back to bootstrap: HELO keeps going, and a server
        // that still knows us answers KEYS while one that doesn't answers REPAIR (honored again now).
        // `last_keys` and the control counters are kept: if the server still holds the same session
        // (asymmetric loss), the next KEYS re-adopts it with the counters continuing, so its replay
        // filter keeps accepting our CTs. A different KEYS starts a fresh nonce space (below).
        if cipher.is_some() && last_rx.elapsed() > SESSION_TIMEOUT {
            cipher = None;
            // Keep the sequence cursor: the key is unchanged, and the server may still hold this
            // very session (the asymmetric-loss case below re-adopts it). Rewinding here would let
            // a recorded frame from earlier in the same key be played again.
            jb.drop_buffered();
            // The phone will report its mute state again on the next session; until it does, ours is
            // only a local display. An unconfirmed command stays pending and is retried under the
            // new key.
            mute_reported = false;
            // Our own socket may be the dead half (desktop changed interface/IP): start it afresh.
            link.rebind();
            let _ = app.emit("session", "lost");
        }

        let n = match link.sock.recv(&mut buf) {
            Ok(n) => n,
            Err(_) => continue, // timeout
        };
        let pkt = &buf[..n];

        // Authenticated control from the server: "CT|" + counter(4 BE) + sealed [type, payload…].
        if n >= 7 && &pkt[..3] == b"CT|" {
            handle_server_ctl(
                app,
                shared,
                &cipher,
                &buf,
                n,
                loop_start,
                &mut ctl_recv,
                seen_mute_req,
                &mut mute_cmd,
                &mut mute_reported,
                &mut last_rtt,
                &mut last_rx,
            );
            continue;
        }

        // "KEYS|<salt>|<serverEphPub>|<ctr>|<tag>" — the server's answer to our authenticated HELO.
        // The tag (HELO-auth key, nonce NONCE_KEYS ‖ ctr) is checked BEFORE any DH, and ctr must be a
        // HELO counter we actually sent. A genuine new KEYS is adopted immediately: the server has
        // already switched to it, and only the genuine server can produce one.
        if pkt.starts_with(b"KEYS|") {
            let (Some(ka), Some(sp)) = (&helo_auth, server_pin) else { continue };
            let Ok(text) = std::str::from_utf8(pkt) else { continue };
            let Some((aad, tag_b64)) = text.rsplit_once('|') else { continue };
            let Some(tag) = unb64_n::<16>(tag_b64) else { continue };
            let mut f = aad.split('|').skip(1);
            let salt = f.next().and_then(unb64);
            let es = f.next().and_then(unb64_32);
            let ctr = f.next().and_then(unb64_n::<8>).map(u64::from_be_bytes);
            let (Some(salt), Some(es), Some(ctr)) = (salt, es, ctr) else { continue };
            if !sent_ctrs.contains(&ctr) || !auth_tag_ok(ka, NONCE_KEYS, ctr, aad.as_bytes(), &tag) {
                continue; // stale, forged, or not a reply to us
            }
            let Some(body) = aad.rsplit_once('|').map(|(b, _)| b) else { continue };
            let same_session = body == last_keys;
            if same_session && cipher.is_some() {
                continue; // the server re-sends KEYS on every HELO of the same session
            }
            if !same_session && ctr <= adopted_ctr {
                continue; // a genuine but older KEYS replayed inside the counter window → not newer
            }
            let Some(newc) = session_cipher(&eph, identity, &es, &sp, &salt) else { continue };
            cipher = Some(newc);
            last_keys = body.to_string();
            adopted_ctr = ctr;
            last_rx = Instant::now();
            // A genuinely new key starts a new sequence space, so the cursor resets with it. A
            // re-adopted *same* session keeps its cursor — see `drop_buffered`.
            if same_session {
                jb.drop_buffered();
            } else {
                jb.reset();
            }
            if !same_session {
                ctl_send = 0; // fresh key → fresh control nonce space on both sides
                ctl_recv = 0;
            }
            pairing = false;
            awaiting_pa = false;
            denied_at = None;
            repair_deadline = None;
            saw_repair_at = None; // a KEYS proves the phone knows us after all
                                  // A fresh session: wait for the phone to report its own state rather than asserting
                                  // ours. Any unconfirmed command is still pending and goes out on the next heartbeat.
            mute_reported = false;
            let _ = app.emit("session", "up");
            continue;
        }

        // Pairing / bootstrap control messages (only meaningful before a session exists).
        if cipher.is_none() {
            // Server reveals its long-term pub. If we've pinned this identity before, go straight to a
            // session (no code); otherwise show the SAS for first-time pairing.
            if pkt.starts_with(b"PS|") {
                if awaiting_pa {
                    continue; // we're past the reveal; a late PS changes nothing
                }
                if let Some(spub) = std::str::from_utf8(&pkt[3..]).ok().and_then(unb64_32) {
                    pair_server_pub = Some(spub);
                    if is_pinned(&spub) && !force_pair {
                        // Known phone → HELO flow, no SAS prompt. Arm the grace timer in case the
                        // server actually forgot us and never sends KEYS.
                        server_pin = Some(spub);
                        helo_auth = None; // (re)derive the HELO-auth key for this identity
                        pairing = false;
                        if repair_deadline.is_none() {
                            repair_deadline = Some(Instant::now() + REPAIR_GRACE);
                        }
                    } else if pairing {
                        pair_sas = Some(sas(&my_pub, &spub, &pair_nonce));
                        // Emitted once per commit→reveal round (the phone answers PS to PC only). A
                        // wrong code starts a new round via sas-bad, which re-shows the prompt.
                        let _ = app.emit("sas-needed", fingerprint(&spub));
                    }
                }
                continue;
            }
            if pkt == MSG_PAIR_APPROVED {
                if awaiting_pa {
                    awaiting_pa = false;
                    pairing = false; // switch to the session HELO flow (with ephemerals)
                    denied_at = None;
                    saw_repair_at = None; // the phone just approved us; it plainly knows us
                    let _ = app.emit("auth", AUTH_APPROVED);
                }
                continue;
            }
            if pkt == MSG_BUSY {
                // Another confirmed client holds the phone: not a forgotten pairing, so don't escalate.
                repair_deadline = None;
                let _ = app.emit("auth", AUTH_BUSY);
                continue;
            }
            if pkt.starts_with(b"AUTH:") {
                if let Ok(s) = std::str::from_utf8(pkt) {
                    if s == AUTH_DENIED {
                        denied_at = Some(Instant::now()); // back off; keep the current phase
                    } else if s == AUTH_PENDING && awaiting_pa {
                        awaiting_since = Instant::now(); // the phone still holds our pairing
                        denied_at = None;
                        saw_repair_at = None; // talking to us about *this* pairing, not amnesia
                    }
                    let _ = app.emit("auth", s.to_string());
                }
                continue;
            }
            // Server says it forgot us (e.g. reinstalled). REPAIR is unauthenticated plaintext, so it
            // never acts destructively on its own: it only *shortens* the pinned-server grace timer;
            // the genuine server's KEYS still races to clear it, and the pin changes only after the
            // user confirms a new code.
            if pkt == MSG_REPAIR {
                // Only ever *shorten* a grace timer that the pinned-server path already armed.
                // `map`, not `map_or`: with `map_or` a `None` deadline became `Some(soon)`, so a
                // single spoofed plaintext packet could raise a re-pair prompt out of nothing —
                // exactly what PROTOCOL.md says REPAIR must never be able to do on its own.
                if !pairing && !awaiting_pa {
                    saw_repair_at = Some(Instant::now()); // the phone is here and has forgotten us
                    let soon = Instant::now() + Duration::from_secs(2);
                    repair_deadline = repair_deadline.map(|d| d.min(soon));
                }
                continue;
            }
            continue; // nothing else expected before the session key exists
        }

        // Audio: [PKT_AUDIO][seq BE u32][sealed frame]. Anything else here is a plaintext bootstrap
        // message that is meaningless while a session is live.
        if n <= AUDIO_HDR || buf[0] != PKT_AUDIO {
            continue;
        }
        bytes += n as u64;

        let seq = u32::from_be_bytes([buf[1], buf[2], buf[3], buf[4]]);
        let Some(c) = &cipher else { continue };

        // Cheap pre-decrypt drop of replays/dupes — a flood of stale/duplicate seqs costs no decrypt.
        // (The first frame of a session sets the cursor; an earlier one overtaken on the wire is a
        // single 10 ms late frame and is dropped like any other.)
        if jb.contains(seq) || jb.behind_cursor(seq) {
            continue;
        }

        // Decrypt with the live key (nonce derived from the plaintext seq).
        let mut nonce = [0u8; 12];
        nonce[0] = NONCE_AUDIO;
        nonce[8..].copy_from_slice(&seq.to_be_bytes());
        scratch.clear();
        scratch.extend_from_slice(&buf[AUDIO_HDR..n]);
        if c.decrypt_in_place(Nonce::from_slice(&nonce), b"", &mut scratch).is_err() {
            continue; // not ours (stale key after a re-key, or garbage) → drop
        }
        last_rx = Instant::now();

        vu_active = true; // a packet decrypted → audio is flowing (covers PLC/FEC too)
                          // Move the plaintext into the buffer with a recycled Vec (no per-frame heap alloc).
        let mut slot = jb.take_free();
        std::mem::swap(&mut scratch, &mut slot);
        jb.insert(seq, slot);

        let denoise = shared.denoise.load(Ordering::Relaxed);
        let gate = shared.vad();
        let muted = shared.muted.load(Ordering::Relaxed);

        // Drain in strict sequence order, emitting every frame we can (real / FEC / PLC).
        jb.drain(&mut decoder, &mut pcm, &mut recv_frames, &mut lost_frames, |frame| {
            emit_pcm(
                sink,
                &mut denoiser,
                frame,
                denoise,
                gate,
                muted,
                &mut hold,
                &mut din,
                &mut dout,
                &mut vu_peak,
            );
        });
    }

    // Authenticated clean-shutdown notice. If a session never came up there's no key to send under;
    // the server simply times the endpoint out.
    if let Some(c) = &cipher {
        ctl_send = ctl_send.wrapping_add(1);
        send_ctl(&mut link, c, ctl_send, &[CT_BYE]);
    }
    // No counter flush here on purpose. The reservation is written *before* the counter that needs
    // it leaves the process (see the HELO branch), so the mark on disk is always at or above every
    // counter this run used, and a flush on the way out would be a no-op that only looked reassuring
    // — it would do nothing for the case that matters, which is not reaching this line at all.
    Ok(())
}
