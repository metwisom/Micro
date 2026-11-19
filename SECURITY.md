# Security Policy

Micro streams live microphone audio from an Android phone to a desktop computer over the local
network. A flaw in the pairing flow or the wire protocol can mean a stranger on the same Wi-Fi hears
your microphone, so security reports are welcome and taken seriously — within the limits of what a
hobby project with a single maintainer can promise.

## Supported versions

| Version | Supported | Notes |
|---------|-----------|-------|
| `master` (current) | Yes | Fixes land here first. |
| 2.0.x | Yes | Current release series; fixes are released as a new 2.0.x. |
| 1.0.x | No | Superseded by 2.0.0, which changed the wire format. Update both ends. |

There is one maintainer and no long-term support branches. Only the latest 2.0.x and the `master`
branch receive fixes; older builds are expected to be updated rather than patched in place.

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** on the repository:

> github.com/metwisom/Micro → **Security** tab → **Report a vulnerability**

That channel is private until a report is published, which is what a vulnerability needs.

**Please do not use issues or pull requests for this.** Both are public the moment they are created,
including their titles, so filing a vulnerability there publishes a working attack against every
user who has not updated yet. Use issues for ordinary bugs and feature requests.

A useful report contains:

| Item | Why it helps |
|------|--------------|
| Affected component | `android/` (server), `tauri/` (client), or the protocol itself. |
| Version or commit | A release tag such as `v1.0.0`, or the commit hash you tested. |
| Reproduction | Steps, packet captures, or a script — enough to see the problem happen. |
| Impact you believe it has | What an attacker gains: audio, impersonation, session takeover. |

Please include the platform you reproduced on (Windows / macOS / Linux client, Android version) when
it is relevant.

**What to expect.** This is a spare-time project, so there is no service-level agreement and no
guaranteed response window. Reports are handled on a best-effort basis: acknowledgement when the
maintainer next reads the queue, then a fix on `master` and a patch release if the issue is
confirmed. If a report goes unanswered for a long time, a polite nudge on the same private thread is
reasonable. Credit in the release notes is offered to reporters who want it.

Please give a reasonable amount of time for a fix before publishing details, and do not test against
devices or networks that are not yours.

## In scope

- **The wire protocol** — discovery, `HELO` / `KEYS`, the sealed `CT` control channel, and audio
  datagrams, as specified in [PROTOCOL.md](PROTOCOL.md).
- **The pairing and authorization flow** — the SAS commitment handshake, the dual authorization
  (desktop confirms the code, then the phone's user taps Allow), Deny handling, and key pinning.
- **Key handling and storage** — identity key generation, the Android Keystore sealing on the phone,
  the OS secret store (and its owner-only file fallback) on the desktop, session key derivation, and
  nonce and counter management.
- **Anything that lets a host on the same LAN** obtain audio, impersonate a paired device, take over
  a live session, move the audio target, or re-key, mute or end someone else's session.
- Memory-safety or parsing bugs reachable from the network in either implementation, including the
  vendored RNNoise and the Opus decode path.
- Any discrepancy between [PROTOCOL.md](PROTOCOL.md) and what the code actually does, where the code
  is the weaker of the two.

## Out of scope

| Not covered | Why |
|-------------|-----|
| A compromised phone or PC | Both endpoints are trusted by definition; an attacker with code execution there already has the microphone. |
| Other malicious apps on the same device | Micro relies on the OS for app isolation and cannot defend against a hostile app running beside it. |
| Denial of service on the local network | Anyone who can reach the LAN can flood UDP or jam Wi-Fi; that is not something the protocol can fix. |
| Traffic analysis | A real-time codec sends 100 packets per second, and packet timing and sizes leak speech activity by design. Padding or cover traffic is not implemented. |
| Unsigned builds | Releases are unsigned on purpose, so SmartScreen, Gatekeeper and "unknown source" warnings are expected, not a vulnerability. Build from source if that matters to you. |
| Plaintext discovery and pairing bootstrap | Deliberate. Discovery answers any prober with an opaque per-install id, never an identity key, and no plaintext message can move, mute, re-key or end a live session. |
| Physical access to an unlocked device | Out of the threat model; a paired device can be revoked by deleting it in the phone's Settings, which also ends its live session. |

Reports about these are still interesting as bug reports if something behaves worse than described
above — they are simply not treated as vulnerabilities.

## Security model

The normative detail is in [PROTOCOL.md](PROTOCOL.md); this is the summary.

- **Identities.** Both ends hold a long-term X25519 key. The phone seals its private key with an
  AES-256-GCM key held in the Android Keystore; the desktop stores its key in the OS secret store
  (Windows Credential Manager, macOS Keychain, Linux Secret Service), falling back to an owner-only
  file where no secret store is available. Keys are pinned at pairing (trust on first use, after the
  SAS check).
- **Pairing.** A 4-digit SAS derived from both public keys and a client nonce that was committed to
  beforehand, so an active man-in-the-middle cannot grind keys to force a match and succeeds roughly
  1 in 10000 per attempt. Authorization is required at both ends: the desktop verifies the code
  first, and only then does the phone enable **Allow**. Deny refuses every step that can change
  pairing state (`PR`, `PK`, and `PC` from a uuid that is not already trusted). A trusted uuid still
  gets the public `PS` reply, because its uuid travels in plaintext and refusing it would let anyone
  on the network turn the user's own Deny tap into a lock-out of their desktop.
- **Sessions.** The session key is HKDF-SHA256 over ephemeral×ephemeral DH (forward secrecy)
  concatenated with static×static DH (authentication), salted with 16 random bytes from `KEYS`.
  Non-contributory (low-order) DH results are refused on both sides.
- **AEAD.** ChaCha20-Poly1305 for both audio and control, in disjoint nonce spaces selected by the
  first nonce byte (0 audio, 1 control, 2 `HELO` tag, 3 `KEYS` tag), with the control direction in
  the second byte. Counters live inside the session object, so a key is never used with a counter
  left over from an earlier one.
- **Replay.** Control counters are strictly increasing per direction and restart with every new
  session key. `HELO` carries a persisted, strictly monotonic counter per client, and `KEYS` is
  authenticated under the static-static HELO key and must echo a counter the client actually sent,
  so an older genuine `KEYS` cannot be replayed.
- **Cross-implementation agreement.** Known-answer vectors for the SAS, HKDF, audio AEAD, the `HELO`
  and `KEYS` tags and sealed control messages are asserted by both the Kotlin and the Rust test
  suites, from values computed by a third stack (Node/OpenSSL), so the two implementations cannot
  drift apart silently.

## Known limitations

These are design consequences, not bugs to report — they are written down so nobody has to
rediscover them, and so the claims elsewhere in this repository stay honest.

- **A host on the same network can occupy the pairing slot.** Pairing is offered to unauthenticated
  senders (it has to be — a new device has nothing to authenticate with yet) and there is one slot at
  a time, so that anyone can be shown one code rather than a queue of prompts. A host that keeps
  sending `PC` can therefore keep a *new* device from pairing. Handovers are rate-limited and the
  slot expires 60 s after it opens, which bounds the churn and the notification noise, but it does
  not make the slot un-occupiable. Already-paired devices are unaffected: they never touch the slot.
- **A spoofed plaintext `BUSY` delays the re-pair prompt.** `BUSY` clears the grace timer that leads
  to a re-pair. A host that sends one every few seconds keeps that prompt away, so a phone that
  genuinely forgot the desktop stays unreachable while the attacker keeps it up. Nothing is
  disclosed and no pin changes; the recovery is to move the desktop to another network, or to delete
  its config directory.
- **Discovery is public.** The phone answers `MICRO?` with its name and an opaque per-install id to
  anyone on the network. That is what makes "no typing IP addresses" work; the id is not a
  credential for anything but following the phone to a new address, and a session still requires the
  pinned keys.
- **The desktop has no "forget this phone" button.** Un-pairing is done on the phone, in its trusted
  devices list. Clearing the desktop's own pin means deleting its config directory, or using Reset.

## No independent audit

Micro has **not** been independently audited. The cryptography is assembled from standard primitives
(X25519, HKDF-SHA256, ChaCha20-Poly1305) rather than invented, and the protocol is written down and
tested on both sides — but that is not the same as review by someone who does this professionally.
Treat it as a hobby project that takes security seriously, not as an audited security product, and
judge it accordingly before relying on it for anything sensitive.
