# Changelog

All notable changes to Micro are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — to be released as 2.0.0

A hardening pass over the whole project: several ways to get microphone access without the phone
owner's consent are closed, two nonce-reuse paths are removed, and the client now recovers from
network changes on its own.

- The desktop window can be resized vertically and stays within the monitor's work area; settings
  scroll when they do not fit.

> **Upgrade note — the wire protocol changed.** Pairing, the key exchange reply and the audio framing
> all moved, so a phone and a desktop running different versions cannot connect at all — not even a
> pair they had already established. Update both ends together. **Existing pairings keep working:** the
> pinned keys are unchanged, and the desktop carries its 1.0.0 pins (which were filed by host) over
> to the new by-identity layout on first launch, so you do not need to re-pair. Anyone who
> paired before this release should still review the trusted devices list on the phone and delete
> entries they do not recognise, because older versions could pin a device without asking.
>
> Every version string moves to **2.0.0** (and `versionCode` to 2) so the two builds are
> distinguishable: there is no version field on the wire, so a mismatch otherwise looks like "the
> phone is found but never connects" with both apps reporting the same version.

### Security

- **A `KEYS` tag is no longer computed twice for one counter.** That tag is ChaCha20-Poly1305 under
  the long-term HELO-auth key with the counter as the nonce, and that key does not rotate, so tagging
  a counter twice over two different bodies exposes that counter's one-time Poly1305 key and lets an
  attacker forge a tag for it. The bodies differ
  whenever the live session has been re-derived in between, which a client restart does routinely.
  The server now keeps the last counter it actually answered per client and refuses to tag anything
  at or below it, persists that mark on every accept rather than once per 30 s, and keeps it across
  a re-pair that does not change the key *and* across deleting the device — each of those was a way
  the old "is this counter new?" answer could regress underneath an unchanged key. An exactly
  repeated `HELO` is answered by re-sending the stored datagram, which computes no tag at all. What
  is left is stated in PROTOCOL.md rather than rounded away: the mark is written asynchronously, so
  an unclean kill can lose its last instant, which exposes at most one counter's one-time key and
  buys an attacker nothing the client would accept.
- **A phone that forgot the desktop can be re-paired again.** Hardening `REPAIR` into a hint that may
  only *shorten* a running timer left nothing that could ever start one, so such a desktop sent
  HELOs forever. Arming now needs both halves: `REPAIR` as evidence the phone is present and does
  not know us, and local state as the check. Neither half alone: without the local check one spoofed
  datagram raises a prompt, and without the evidence a prompt appears every time the phone is merely
  absent for a few seconds.
- **Pairing-slot churn is bounded.** The slot expires 60 s after it was opened and may change hands
  at most once every 10 s, decided on time alone. The per-uuid cool-down this replaces was keyed on
  a value the sender picks, so rotating it defeated the bar outright. To be plain about what this
  does *not* do: a determined host on the same network can still keep the slot occupied, because
  there is one slot and it is offered to unauthenticated senders by design. See SECURITY.md.
- The `HELO` high-water mark is written to a temporary file, flushed and renamed, so an interrupted
  write cannot leave a short file and silently reset the mark below counters already used.

- **A spoofed `REPAIR` can no longer raise a pairing prompt out of nothing.** The plaintext hint is
  meant only to *shorten* a grace timer the pinned-server path already armed, which is what
  PROTOCOL.md promises; a `map_or` made a missing timer default to "armed", so one unauthenticated
  packet could put a re-pair code prompt on a user's screen.
- **One LAN host can no longer block all pairing.** The pairing slot was held for as long as its
  holder kept sending, so roughly one unauthenticated packet every 9 seconds locked every other
  device out indefinitely. A slot now also expires 60 s after it was opened, refreshes included.
- **Recorded audio cannot be replayed across a session pause.** The jitter buffer's sequence cursor
  was cleared whenever a session was declared lost; if the same session was then re-adopted — same
  key — the whole sequence range was accepted again. The cursor now belongs to the key.
- **The HELO counter cannot regress after an unclean exit.** It is the nonce for a tag under a key
  that outlives the process, and it was persisted at most every 30 s *after* use. Counters are now
  handed out from a block reserved on disk before any of them is sent.
- **The 4-digit pairing code is now uniform.** Reducing 16 bits mod 10000 gave the low 5536 codes
  seven preimages and the rest six, making the stated 1-in-10000 man-in-the-middle bound about 7%
  optimistic. The reduction now takes 32 bits. **This changes the codes**, so both ends must be
  updated together (they already must — see the upgrade note).
- **A repeated HELO is answered instead of ignored.** Silence let an on-path relay that had won the
  endpoint binding with its own copy of a HELO keep it, because the real client could never be
  answered. An exact repeat now re-sends the same `KEYS` without rebinding anything.
- The pairing heads-up notification is throttled: the code is derived from the client's nonce, so
  anyone able to send `PR` could rotate it and turn the prompt into a device-level notification
  storm.
- A microphone-permission denial is no longer erased from the screen half a second later, and the
  unused `global-shortcut:allow-is-registered` permission was dropped from the capability list.

- **Pairing now requires authorization on both ends.** Previously any host on the LAN could send a
  plaintext key announcement and pin itself as trusted, giving it access to the microphone with no
  interaction on the phone. Pairing now completes only after the desktop confirms the 4-digit code
  *and* the phone's owner taps Allow, and a Deny is enforced at every step of the exchange. This is
  the main reason to upgrade.
- **Key-exchange replies are authenticated.** The phone's reply to a session key request now carries
  a tag computed under the paired identities and must echo a counter the client actually sent, so a
  recorded reply from an earlier session can no longer be replayed at a client.
- **Removed two ways an encryption nonce could repeat.** A capture thread that was slow to stop could
  briefly share the audio sequence counter with its replacement, and the rekey triggered by sequence
  wrap could restart the counter while the old key was still in use. The audio sequence and the
  control counters now live inside the session object, so a key is never used with a stale counter.
  Repeating a nonce with ChaCha20-Poly1305 leaks plaintext, so this is worth updating for even if
  nobody is attacking you.
- **Deleting a trusted device ends its session.** Removing a computer from the phone's trusted list
  used to leave the running stream untouched until it stopped on its own; it is now disconnected
  immediately.
- **Mute is enforced on the desktop.** While the client is muted, no audio reaches the virtual
  microphone regardless of what the phone sends.

### Added

- Session liveness detection on the client (5 s) with automatic recovery: it re-pairs, follows the
  phone to a new IP address, and rebinds its socket after the desktop switches network interface, so
  a Wi-Fi blip or a phone restart no longer needs a manual reconnect.
- The phone surfaces UDP bind and microphone capture failures in the UI instead of failing silently,
  and retries them with backoff.
- A Wi-Fi multicast lock on the phone keeps auto-discovery answering with the screen off.
- Per-OS build scripts under `scripts/` for Linux, macOS and Windows.
- Unit tests across all three languages: Rust, Android and JavaScript suites, all run in CI.

### Changed

- Audio datagrams carry a leading type byte, so an audio packet can never be parsed as a control
  message.
- Packets are marked DSCP EF on both sockets. The previous marking mapped to the best-effort queue on
  Wi-Fi, which is exactly where voice should not be.
- The phone releases the microphone and the CPU wake lock whenever no computer is connected.
- The desktop client validates a saved window position against the monitors that exist now, so a
  window last seen minimized on Windows is no longer restored off-screen; the tray "Show" entry
  re-centres a window that ended up outside every monitor.
- `PROTOCOL.md` now matches the implementation, and the incorrect claim that Windows builds need
  cmake and nasm has been removed — they are not required.

### Fixed

- **The "another app is using the microphone" notice actually appears.** The notification is
  deduplicated by a key that did not include the error, so the one message telling the user why the
  line went quiet was swallowed.
- **The pairing prompt is neither lost nor stormable, on either path that posts it.** Both are
  decided by one place now (`PairingNotifier`), under one rule: a post the rate limit blocks stays
  *owed* and goes out on the next packet once the gap has passed, so it is delayed by at most that
  gap and never dropped.
  - For a new code the gap is waived exactly once — when no prompt for *that device* is in front of
    the user — and enforced for every rotation after that. Waiving it on a changed *code* handed the
    bypass to the attacker, since the code derives from a nonce in their own packet. The test for
    "is a prompt already up" also ran after the new request had been stored, which made it true for
    every device: a phone that handed its pairing slot to a second computer within the gap showed
    that computer's code nowhere, and its later packets carry the same code, so nothing brought it
    back.
  - The "confirmed — tap Allow" update is held to the same gap. A `PC`→`PR`→`PK` loop re-flips the
    confirmation every lap and used to post once per three packets, while a desktop that confirmed
    inside the gap had its update swallowed until the prompt aged out a minute later.
- **Tapping Deny on an unexpected prompt can no longer lock your own desktop out.** A denied uuid
  was refused `PS`, which every connection needs before it can send its authenticated `HELO` — and
  the uuid travels in plaintext, so anyone on the network could raise a prompt under a trusted one.
  A uuid that is already pinned and approved now still gets `PS`.
- **The window no longer twitches on every mute.** It is sized to its content, and the telemetry
  line measured shorter when empty.
- The buffer setting explains itself in visible text rather than a tooltip on a disabled input.
- Connection quality is readable by a screen reader again — it is outside the live region, not
  hidden from assistive technology — and it kept its tooltip.
- Switching language no longer re-enters global-shortcut registration; it only re-renders the text.
- The system accent is corrected to a luminance that actually reads against the page, and the
  correction now applies to focus rings and borders as well as text.
- The tray icon decoder handles grayscale and grayscale-alpha PNGs instead of building a buffer of
  the wrong length, and says so in the log if it ever meets something it cannot convert.
- A `HELO` counter block is reserved when the first HELO is about to be sent rather than on every
  connection attempt, so attempts that never reach that stage no longer push the stored mark away
  from the clock.

- **Rotating the phone no longer restarts the microphone after you pressed Stop.** The activity
  auto-started the server in `onCreate`, which runs again on every configuration change.
- **The phone has a landscape layout.** The mute button and the level ring were sized for the
  portrait column and were clipped when rotated.
- **The phone notices when Android silences the microphone** for a higher-priority app (a call, the
  assistant) and says so, instead of streaming perfectly encoded silence with no error at either end.
- **The desktop window can no longer be taller than the screen.** Its height was clamped to a
  hard-coded 1000 px rather than the monitor work area, and the window is not resizable, so the
  bottom of the settings panel could end up unreachable.
- The Buffer field is clamped to the range it advertises — a larger value produced permanent
  silence — and is disabled while connected, since it only takes effect on the next connection.
- Switching language now also re-renders the strings built in JavaScript (the hotkey hint and the
  connected status line), which used to stay in the previous language.
- Live network statistics are no longer announced by screen readers once a second: they moved out
  of the status live region into a sibling element.
- Keyboard focus survives a device-list refresh instead of being thrown back to the document.
- A long phone name from the network is truncated instead of pushing the controls out of the row.
- The system accent colour is adjusted for contrast before being used as text.
- Discovery is stopped on shutdown (its stop flag existed but was never used), a restart intent
  delivered to a stopped service no longer leaves it running, and the tray icon decoder handles
  grayscale and palette PNGs instead of reading past the end of the buffer.
- Commands that join threads or enumerate audio devices run on the blocking pool, so they no longer
  occupy a shared async worker (and no lock is held across an await).

- Opus in-band FEC is now actually coded: the encoder's expected-loss value is floored at 5 %,
  because libopus emits no FEC at all at 0 %. Packet loss recovery works as documented.
- A deadlock between muting and rekeying that could leave a session silently muted until it was
  restarted.
- Unmuting from the phone no longer needs several attempts. While muted, the desktop re-asserted
  its state once a second and ignored any report that disagreed with it, so a toggle on the phone
  was usually discarded and the next heartbeat muted the phone again. Mute is now a command in one
  direction and a report in the other: the desktop commands only when its own user toggles, and
  otherwise follows the phone, so either end takes effect on the first try. The phone repeats a
  mute change a few times instead of announcing it once, so a single dropped packet cannot leave the
  two ends disagreeing either.
- Unmuting from the desktop no longer fails about every second time. The click set a flag that the
  phone's reports also write, and the command was built from that flag on the network thread's next
  pass; while muted, the phone's 500 ms "still muted" report is most of what wakes that thread, so
  it regularly overwrote the click first and the desktop then commanded *mute*. The wanted state now
  travels with the click itself, and a report that crosses a click is treated as stale. Found by the
  two-way mute test on a real phone.
- The phone answers every mute command, including one that changes nothing. It used to stay silent,
  the desktop kept retrying once a second for the rest of the session, and the next mute tapped on
  the phone was undone by that retry.
- The Android service no longer crashes when Android restarts it in the background: it starts
  non-sticky and guards the foreground-service start so a microphone-type service is never started
  without the permission state it needs.

### Removed

- The documented DTX (discontinuous transmission) support, which was never implemented. The claim was
  removed rather than the feature added; the client's adaptive jitter buffer treats a pause from the
  sender as an underrun.

## [1.0.0] - 2026-06-17

First public release.

### Added

- **Native audio pipeline** — libopus encoding on the phone through the NDK, libopus decoding plus
  RNNoise noise suppression on the PC, in 10 ms frames for low latency.
- **End-to-end encryption** — X25519 key agreement with ChaCha20-Poly1305, forward secrecy from
  ephemeral keys, and every session authenticated against pinned identities.
- **SAS pairing** — a 4-digit code shown on the phone and typed on the PC, with a commitment step so
  an active man-in-the-middle succeeds roughly 1 time in 10000 per attempt. Keys are pinned on first
  use.
- **Resilience** — Opus in-band FEC, packet-loss concealment, adaptive bitrate and an adaptive jitter
  buffer.
- **Auto-discovery** — the client finds the phone on the local network by itself; a manual IP address
  also works.
- **Live controls** — volume, RNNoise toggle, VAD gate, buffer and latency, mute synced in both
  directions, headphone monitoring, a global mute hotkey and a ping and latency readout.
- **Desktop app** — Tauri (Rust plus webview) with a tray icon, system theme and accent colour, and
  English and Russian interfaces.
- **Virtual microphone per OS** — created automatically on Linux (PulseAudio/PipeWire sink "Micro"),
  via VB-CABLE on Windows, via BlackHole on macOS.

### Notes

- The phone and the PC must be on the same network.
- Builds are unsigned, so Windows SmartScreen, macOS Gatekeeper or Android's unknown-source warning
  may appear on first launch.

[Unreleased]: https://github.com/metwisom/Micro/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/metwisom/Micro/releases/tag/v1.0.0
