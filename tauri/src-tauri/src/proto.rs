//! The wire protocol itself: constants shared with the Android server, the nonce layouts that keep
//! every AEAD stream in its own space, and the counter rules that make replays detectable.
//!
//! Nothing here touches I/O, app state or the OS, so the invariants the two implementations must
//! agree on are unit-testable on their own. `protocol_constants_locked` pins the plain values; its
//! mirror on the phone is `ProtocolConstantsTest.kt`, so a one-sided edit fails a build instead of
//! silently breaking interop.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub(crate) const PORT: u16 = 50005;
pub(crate) const FRAME: usize = 480; // 10 ms @ 48 kHz, also RNNoise frame size
pub(crate) const HANGOVER: i32 = 10;
pub(crate) const MAX_PLC: u32 = 5; // cap concealed frames per gap
/// Out-of-order frames tolerated before a missing one is concealed (≈20 ms).
pub(crate) const REORDER: u32 = 2;
/// First byte of an audio datagram; every control/bootstrap message starts with an ASCII letter.
pub(crate) const PKT_AUDIO: u8 = 0;
pub(crate) const AUDIO_HDR: usize = 5; // type byte + 4-byte seq
/// After `AUTH:DENIED` (plaintext, spoofable) back off this long before knocking again.
pub(crate) const DENIED_BACKOFF: Duration = Duration::from_secs(5);
/// `PK` unanswered for this long → the phone lost the pairing slot; start a fresh commit round.
pub(crate) const PK_TIMEOUT: Duration = Duration::from_secs(20);
/// In the reveal phase, every Nth keepalive is a `PC` again: the phone answers `PS` to every `PC`
/// but hands the pairing slot to one uuid at a time, so a desktop whose `PR` is being ignored
/// (someone else is pairing) picks the slot up as soon as it frees instead of knocking forever.
pub(crate) const PR_RECOMMIT_EVERY: u32 = 5;
/// Consecutive send() failures after which the UDP socket is re-created (its bound source address
/// may be gone after a desktop interface/IP change; a connect()ed UDP socket never recovers alone).
pub(crate) const SEND_FAILS_REBIND: u32 = 5;
/// No decryptable audio/CT from the server for this long → the session is dead. The server answers
/// our 1 Hz PING with a sealed PONG even while muted or silent, so a healthy session never trips it.
pub(crate) const SESSION_TIMEOUT: Duration = Duration::from_secs(5);
/// IP ToS for DSCP EF (46): Wi-Fi WMM maps it to the voice access category.
pub(crate) const DSCP_EF_TOS: u32 = 0xB8;

// AEAD nonce byte 0 — one value per nonce space, so streams never collide under a shared key.
pub(crate) const NONCE_AUDIO: u8 = 0; // session key: audio frames, last 4 bytes = seq
pub(crate) const NONCE_CTL: u8 = 1; //   session key: control (CT), byte 1 = direction, last 4 = counter
pub(crate) const NONCE_HELO: u8 = 2; //  HELO-auth key: our HELO tag, last 8 bytes = HELO counter
pub(crate) const NONCE_KEYS: u8 = 3; //  HELO-auth key: the server's KEYS tag, last 8 bytes = HELO counter

// Control-plane direction (nonce byte 1) — stops a sealed CT being reflected back at its sender.
pub(crate) const CTL_S2C: u8 = 1;
pub(crate) const CTL_C2S: u8 = 2;

// CT message types — the first plaintext byte of the sealed control body.
pub(crate) const CT_MUTE: u8 = 1; // payload: 1 byte (0/1)             both directions
pub(crate) const CT_BYE: u8 = 2; //  payload: none                     client → server
pub(crate) const CT_LOSS: u8 = 3; // payload: 1 byte percent (0..100)  client → server
pub(crate) const CT_PING: u8 = 4; // payload: 8-byte token (BE)        client → server
pub(crate) const CT_PONG: u8 = 5; // payload: 8-byte token echo (BE)   server → client

// Plaintext bootstrap replies from the server.
pub(crate) const MSG_REPAIR: &[u8] = b"REPAIR";
pub(crate) const MSG_BUSY: &[u8] = b"BUSY";
pub(crate) const MSG_PAIR_APPROVED: &[u8] = b"PA";
pub(crate) const AUTH_PENDING: &str = "AUTH:PENDING";
pub(crate) const AUTH_DENIED: &str = "AUTH:DENIED";
pub(crate) const AUTH_BUSY: &str = "AUTH:BUSY"; // synthesized for the UI from a BUSY reply
pub(crate) const AUTH_APPROVED: &str = "AUTH:APPROVED"; // synthesized for the UI from a PA reply

/// Control-plane nonce: byte0 = NONCE_CTL, byte1 = direction, last 4 bytes = the monotonic counter.
pub(crate) fn ctl_nonce(dir: u8, ctr: u32) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[0] = NONCE_CTL;
    n[1] = dir;
    n[8..].copy_from_slice(&ctr.to_be_bytes());
    n
}

/// Nonce for the HELO-auth key: byte0 = kind (NONCE_HELO / NONCE_KEYS), last 8 bytes = the HELO
/// counter. Two kinds under one key with the same counter are distinct nonces, never a reuse.
pub(crate) fn auth_nonce(kind: u8, ctr: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[0] = kind;
    n[4..].copy_from_slice(&ctr.to_be_bytes());
    n
}

/// Inbound control anti-replay: accept only a counter strictly above the last accepted one.
pub(crate) fn ctr_fresh(last: u32, ctr: u32) -> bool {
    ctr > last
}

/// Strictly-monotonic HELO counter: tracks the wall clock but never repeats or regresses
/// (`max(now, last + 1)`), so a clock step can't lock us out and back-to-back sends stay distinct.
pub(crate) fn next_helo_ctr(wall_ms: u64, last: u64) -> u64 {
    wall_ms.max(last.saturating_add(1))
}

/// How long a pinned server may go without producing a session before the desktop offers a
/// re-pair. A phone that still knows us answers `KEYS` within about a second.
pub(crate) const REPAIR_GRACE: Duration = Duration::from_secs(6);

/// Should the re-pair grace timer be armed right now?
///
/// Two failure modes have to be avoided at once, and each is the other's fix taken too far.
///
/// Arming purely on the phone's plaintext `REPAIR` would let one spoofed datagram put a pairing
/// prompt on the user's screen. Arming purely on local state — "a pin, and no session" — raises
/// that prompt every time the phone is merely *absent* for a few seconds: a phone that still knows
/// us answers `KEYS`, not `REPAIR`, so a restart or a Wi-Fi blip would be treated as amnesia.
/// Arming nowhere at all leaves a desktop whose phone genuinely forgot it sending HELOs forever.
///
/// So: `REPAIR` supplies the evidence that the phone is present and does not know us, and the local
/// state supplies the sanity check — we hold a pin, no session is up, we are not already pairing or
/// waiting for Allow, and no deadline is running (re-arming would push it away forever). A spoofed
/// `REPAIR` can therefore only act in the window where the desktop is genuinely stuck, and even
/// then the genuine phone's `KEYS` clears the deadline before it expires.
pub(crate) fn should_arm_repair(
    saw_repair: bool,
    pinned: bool,
    have_session: bool,
    pairing: bool,
    awaiting_pa: bool,
    already_armed: bool,
) -> bool {
    saw_repair && pinned && !have_session && !pairing && !awaiting_pa && !already_armed
}

/// How long the phone's plaintext `REPAIR` hint counts as current. A hint older than this says
/// nothing about the phone's state now, and keeping it forever let one spoofed packet stay armed
/// for as long as the phone happened to be away.
pub(crate) const REPAIR_HINT_TTL: Duration = Duration::from_secs(20);

/// How many HELO counters are reserved on disk in one go. The stored value is always a counter we
/// have *not* used yet, so a crash can only ever skip counters, never repeat one.
pub(crate) const HELO_RESERVE: u64 = 65_536;

pub(crate) fn wall_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// What to do with a mute report from the phone, given any command of ours it has not confirmed yet.
///
/// Mute is deliberately asymmetric, and the two directions already travel in separate nonce spaces:
/// client→server `CT_MUTE` is a **command** ("set mute to X"), server→client is a **report** ("mine
/// is X now"). Only a local user toggle issues a command, and it is repeated until the phone reports
/// that state back. Anything else the phone reports is adopted as-is.
///
/// Re-asserting our state on a timer instead would make mute last-writer-wins on a lossy link: the
/// phone's fresh toggle and our periodic re-assert overwrite each other and the state flaps.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum MuteReport {
    /// The phone confirmed the command we were retrying; stop retrying.
    Confirms,
    /// The phone has not applied our command yet — this report predates it, so ignore it.
    Stale,
    /// No command outstanding: the phone is the authority, take its state.
    Adopt(bool),
}

pub(crate) fn mute_report_action(pending_cmd: Option<bool>, reported: bool) -> MuteReport {
    match pending_cmd {
        Some(want) if want == reported => MuteReport::Confirms,
        Some(_) => MuteReport::Stale,
        None => MuteReport::Adopt(reported),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_constants_locked() {
        // Mirror of the Android ProtocolConstantsTest — pins the plain wire values shared with the
        // server so a one-sided edit fails the build instead of silently breaking interop.
        assert_eq!(PORT, 50005);
        assert_eq!(FRAME, 480);
        assert_eq!([CT_MUTE, CT_BYE, CT_LOSS, CT_PING, CT_PONG], [1, 2, 3, 4, 5]);
        assert_eq!([CTL_S2C, CTL_C2S], [1, 2]);
        assert_eq!([NONCE_AUDIO, NONCE_CTL, NONCE_HELO, NONCE_KEYS], [0, 1, 2, 3]);
        assert_eq!(PKT_AUDIO, 0);
        assert_eq!(DSCP_EF_TOS, 0xB8);
        assert_eq!(MSG_REPAIR, b"REPAIR");
        assert_eq!(MSG_BUSY, b"BUSY");
        assert_eq!(MSG_PAIR_APPROVED, b"PA");
        assert_eq!(AUTH_PENDING, "AUTH:PENDING");
        assert_eq!(AUTH_DENIED, "AUTH:DENIED");
    }

    #[test]
    fn nonce_spaces_are_disjoint() {
        let ctl = ctl_nonce(CTL_S2C, 0x0102_0304);
        assert_eq!(ctl[0], NONCE_CTL);
        assert_eq!(ctl[1], CTL_S2C);
        assert_eq!(&ctl[8..], &[1, 2, 3, 4]);
        let mut audio = [0u8; 12];
        audio[0] = NONCE_AUDIO;
        audio[8..].copy_from_slice(&0x0102_0304u32.to_be_bytes());
        assert_ne!(ctl, audio);
        let helo = auth_nonce(NONCE_HELO, 0x0102_0304);
        let keys = auth_nonce(NONCE_KEYS, 0x0102_0304);
        assert_ne!(helo, keys);
        assert_eq!(&helo[4..], &0x0102_0304u64.to_be_bytes());
    }

    #[test]
    fn counters_are_monotonic_and_replay_proof() {
        assert!(ctr_fresh(0, 1));
        assert!(!ctr_fresh(5, 5));
        assert!(!ctr_fresh(5, 3));
        assert!(ctr_fresh(5, 6));
        assert_eq!(next_helo_ctr(1000, 0), 1000); // tracks the clock
        assert_eq!(next_helo_ctr(1000, 1000), 1001); // same millisecond → still distinct
        assert_eq!(next_helo_ctr(500, 1000), 1001); // clock stepped back → never regresses
        assert_eq!(next_helo_ctr(0, u64::MAX), u64::MAX); // saturates instead of wrapping
    }

    #[test]
    fn a_pinned_server_that_never_answers_always_gets_a_repair_deadline() {
        // The case that regressed: the phone answered REPAIR, so it is present and has forgotten
        // us. We hold a pin, no session, not pairing, nothing armed -> arm it, or the client HELOs
        // forever and the user can never re-pair without restarting the app.
        assert!(should_arm_repair(true, true, false, false, false, false));

        // The false-prompt case: the phone is simply gone (restart, Wi-Fi blip). It sends nothing,
        // so no REPAIR, so no prompt -- it answers KEYS again the moment it is back.
        assert!(!should_arm_repair(false, true, false, false, false, false));
        // Never while a session is up, however loudly something claims otherwise.
        assert!(!should_arm_repair(true, true, true, false, false, false));
        // Never before we know which phone we reached: first-time pairing arms nothing.
        assert!(!should_arm_repair(true, false, false, false, false, false));
        // Never while a pairing round is already running, or while waiting for Allow.
        assert!(!should_arm_repair(true, true, false, true, false, false));
        assert!(!should_arm_repair(true, true, false, false, true, false));
        // Never re-arm an armed one: doing that once a second would push the deadline out forever.
        assert!(!should_arm_repair(true, true, false, false, false, true));
    }

    #[test]
    fn mute_reports_never_fight_an_outstanding_command() {
        // Nothing outstanding: the phone owns its state, so whatever it reports is taken. This is
        // the path a mute/unmute tapped on the phone travels.
        assert_eq!(mute_report_action(None, true), MuteReport::Adopt(true));
        assert_eq!(mute_report_action(None, false), MuteReport::Adopt(false));

        // We commanded "mute"; the phone still reports the old state. Adopting it would undo the
        // user's click, so it is ignored and the command keeps being retried.
        assert_eq!(mute_report_action(Some(true), false), MuteReport::Stale);
        // Once the phone reports what we asked for, the command is done.
        assert_eq!(mute_report_action(Some(true), true), MuteReport::Confirms);

        // Same in the unmute direction.
        assert_eq!(mute_report_action(Some(false), true), MuteReport::Stale);
        assert_eq!(mute_report_action(Some(false), false), MuteReport::Confirms);
    }

    #[test]
    fn a_phone_side_toggle_converges_in_one_round() {
        // Regression: while muted, the client used to re-assert MUTE=1 every second and drop any
        // report that disagreed with it, so an unmute tapped on the phone was discarded and the
        // next heartbeat muted the phone again. With no command outstanding there is nothing to
        // re-assert, and the report is adopted immediately.
        let mut pending: Option<bool> = None; // steady state: our last command was confirmed
                                              // Desktop shows muted; the phone reports "unmuted" once.
        let local = match mute_report_action(pending, false) {
            MuteReport::Adopt(v) => v,
            other => panic!("expected the report to be adopted, got {other:?}"),
        };
        assert!(!local, "the desktop follows the phone on the first try");
        assert_eq!(pending, None, "and sends nothing that could bounce it back");

        // A local toggle still wins over a stale report that crosses it on the wire.
        pending = Some(true);
        assert_eq!(mute_report_action(pending, false), MuteReport::Stale);
        assert_eq!(mute_report_action(pending, true), MuteReport::Confirms);
    }
}
