//! Process-wide state: what the UI thread, the net thread and the audio thread share, and the
//! handles Tauri's managed state owns.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use x25519_dalek::StaticSecret;

use crate::audio;
use crate::proto::{mute_report_action, MuteReport};

/// Lock a `Mutex` without ever panicking on poison. None of our locked data has a "broken
/// half-updated" invariant — every guard either does a single assignment or a clone — so if some
/// other thread panicked while holding the lock, recovering the inner value is strictly better than
/// cascading that panic into every future lock holder.
pub(crate) trait LockExt<T> {
    fn lock_safe(&self) -> std::sync::MutexGuard<'_, T>;
}

impl<T> LockExt<T> for Mutex<T> {
    fn lock_safe(&self) -> std::sync::MutexGuard<'_, T> {
        self.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Settings the net thread reads every frame, written by the UI commands.
pub(crate) struct Shared {
    pub(crate) denoise: AtomicBool,
    pub(crate) vad_bits: AtomicU32,
    /// Effective local state: gates the output and drives the UI.
    pub(crate) muted: AtomicBool,
    /// The user's latest mute toggle here (button, hotkey, tray), as one word: a sequence number in
    /// the high bits and the wanted state in bit 0. Written only by [`Shared::request_mute`].
    /// Adopting the phone's state never moves it, which is how net_loop tells a fresh local intent
    /// apart from an echo of the phone's own state.
    pub(crate) mute_req: AtomicU32,
    /// Pairing code typed by the user (empty for known devices).
    pub(crate) code: Mutex<String>,
}

impl Shared {
    pub(crate) fn vad(&self) -> f32 {
        f32::from_bits(self.vad_bits.load(Ordering::Relaxed))
    }

    /// Record the user's own toggle. The wanted state travels in the same word as the sequence bump,
    /// so net_loop never reads it back from `muted` — a flag the phone's reports also write, and
    /// which a report landing between the click and net_loop's next pass used to overwrite. While
    /// muted the phone reports every 500 ms and the socket wakes on little else, so that pass lost
    /// roughly every second unmute click.
    pub(crate) fn request_mute(&self, on: bool) {
        let _ = self.mute_req.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| {
            Some(((v >> 1).wrapping_add(1) << 1) | on as u32)
        });
        self.muted.store(on, Ordering::Relaxed);
    }

    /// The toggle net_loop has not picked up yet, if there is one. Does not consume it.
    pub(crate) fn pending_mute(&self, seen: u32) -> Option<bool> {
        let v = self.mute_req.load(Ordering::Relaxed);
        (v != seen).then_some(v & 1 == 1)
    }

    /// Pick up a new local toggle: `Some(wanted)` once per change of [`Shared::mute_req`].
    pub(crate) fn take_mute_request(&self, seen: &mut u32) -> Option<bool> {
        let v = self.mute_req.load(Ordering::Relaxed);
        if v == *seen {
            return None;
        }
        *seen = v;
        let want = v & 1 == 1;
        // A report adopted in the instant before the toggle landed must not outlive it.
        self.muted.store(want, Ordering::Relaxed);
        Some(want)
    }

    /// Apply a mute report from the phone (see [`mute_report_action`]). `cmd` is the command being
    /// retried; a toggle net_loop has not picked up yet counts as one — and as the newer of the two —
    /// or the report would undo the click it crossed. Returns the state the UI has to be told about, if any.
    pub(crate) fn on_mute_report(
        &self,
        seen_req: u32,
        cmd: &mut Option<bool>,
        first_report: bool,
        reported: bool,
    ) -> Option<bool> {
        match mute_report_action(self.pending_mute(seen_req).or(*cmd), reported) {
            // Applied — stop retrying. The UI is told as well: it is already in this state unless
            // an adopted report raced the click, and then this is what puts it right.
            MuteReport::Confirms => {
                *cmd = None;
                Some(reported)
            }
            MuteReport::Stale => None, // predates our command; it will be applied
            MuteReport::Adopt(on) => {
                let prev = self.muted.swap(on, Ordering::Relaxed);
                // The first report of a session always informs the UI: after a reconnect our own
                // flag starts at false and may not match a phone that was already muted.
                (prev != on || first_report).then_some(on)
            }
        }
    }
}

/// A background thread plus its stop flag, joined on `stop()`.
pub(crate) struct Worker {
    pub(crate) stop: Arc<AtomicBool>,
    pub(crate) handle: Option<JoinHandle<()>>,
}

impl Worker {
    pub(crate) fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

pub struct AppState {
    pub(crate) client_id: String,
    pub(crate) client_name: String,
    /// Long-term X25519 identity, loaded once at startup.
    pub(crate) identity: Arc<StaticSecret>,
    pub(crate) shared: Arc<Shared>,
    // Serializes connect / disconnect / shutdown end to end: they are async commands and could
    // otherwise interleave (a Disconnect landing inside a Connect would leave the new worker alive).
    pub(crate) lifecycle: Mutex<()>,
    pub(crate) net: Mutex<Option<Worker>>,
    pub(crate) discovery: Mutex<Option<Worker>>,
    /// Native cpal output (alive while connected).
    pub(crate) output: Mutex<Option<audio::Output>>,
    /// Linux virtual-mic module indices (to unload on exit).
    pub(crate) pulse_modules: Mutex<Vec<String>>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shared(muted: bool) -> Shared {
        Shared {
            denoise: AtomicBool::new(false),
            vad_bits: AtomicU32::new(0),
            muted: AtomicBool::new(muted),
            mute_req: AtomicU32::new(0),
            code: Mutex::new(String::new()),
        }
    }

    fn muted(s: &Shared) -> bool {
        s.muted.load(Ordering::Relaxed)
    }

    /// THE regression, found on a real phone: both ends muted, the user clicks unmute on the
    /// desktop, and the phone's 500 ms "still muted" report reaches net_loop before its next pass
    /// picks the click up. The report used to be adopted — nothing was outstanding yet — and the
    /// command was then built from the overwritten flag, so the click turned into "mute".
    #[test]
    fn a_report_that_crosses_a_click_does_not_undo_it() {
        let s = shared(true);
        let mut seen = s.mute_req.load(Ordering::Relaxed);
        let mut cmd = None;

        s.request_mute(false); // the click
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, true), None, "the stale report is ignored");
        assert!(!muted(&s), "and the desktop stays unmuted");

        assert_eq!(s.take_mute_request(&mut seen), Some(false), "the command is the click, not the report");
        cmd = Some(false);
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, true), None, "still stale while it is retried");
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, false), Some(false), "confirmed");
        assert_eq!(cmd, None);
        assert!(!muted(&s));
    }

    #[test]
    fn the_same_holds_in_the_mute_direction() {
        let s = shared(false);
        let mut seen = s.mute_req.load(Ordering::Relaxed);
        let mut cmd = None;
        s.request_mute(true);
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, false), None);
        assert!(muted(&s));
        assert_eq!(s.take_mute_request(&mut seen), Some(true));
    }

    #[test]
    fn the_wanted_state_is_the_last_click_even_if_the_flag_was_overwritten() {
        let s = shared(true);
        let mut seen = s.mute_req.load(Ordering::Relaxed);
        s.request_mute(false);
        s.muted.store(true, Ordering::Relaxed); // an adoption that slipped in beside the click
        assert_eq!(s.take_mute_request(&mut seen), Some(false));
        assert!(!muted(&s), "picking the click up repairs the flag");
    }

    #[test]
    fn a_toggle_is_picked_up_once_and_a_double_click_yields_the_last_state() {
        let s = shared(false);
        let mut seen = s.mute_req.load(Ordering::Relaxed);
        assert_eq!(s.take_mute_request(&mut seen), None);
        s.request_mute(true);
        s.request_mute(false);
        assert_eq!(s.pending_mute(seen), Some(false));
        assert_eq!(s.take_mute_request(&mut seen), Some(false));
        assert_eq!(s.take_mute_request(&mut seen), None);
        assert_eq!(s.pending_mute(seen), None);
        // Clicking back to the state we started from is still a new request.
        s.request_mute(false);
        assert_eq!(s.take_mute_request(&mut seen), Some(false));
    }

    #[test]
    fn with_nothing_outstanding_the_phone_is_the_authority() {
        let s = shared(false);
        let seen = s.mute_req.load(Ordering::Relaxed);
        let mut cmd = None;
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, true), Some(true)); // tapped on the phone
        assert!(muted(&s));
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, true), None, "a keepalive changes nothing");
        assert_eq!(s.on_mute_report(seen, &mut cmd, false, false), Some(false));
        assert!(!muted(&s));
        // The first report of a session is always passed on, even when it changes nothing.
        assert_eq!(s.on_mute_report(seen, &mut cmd, true, false), Some(false));
    }
}
