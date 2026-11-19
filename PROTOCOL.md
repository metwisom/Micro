# Micro wire protocol

Transport: **UDP**. Android is the **server**, the desktop app (Windows / macOS / Linux) is the
**client**. Default port: **50005**.

```
desktop ──HELO──▶ phone      authenticated register / (re)key + keepalive, every 1 s
desktop ◀─KEYS── phone      authenticated session parameters (reply to every HELO)
desktop ──CT───▶ phone      sealed control: mute / loss report / ping / bye
desktop ◀─CT──── phone      sealed control: mute sync / pong
desktop ◀─audio─ phone      sealed Opus frames, while an authenticated HELO/CT was seen ≤ 3 s ago
```

## Audio settings
| Field        | Value                     |
|--------------|---------------------------|
| Codec        | Opus (VOIP mode; CELT-only in "low latency" mode) |
| Sample rate  | 48000 Hz                  |
| Channels     | 1 (mono)                  |
| Frame size   | 480 samples = 10 ms       |
| Bitrate      | 32 kbps (28 / 24 kbps under reported loss > 5 % / > 15 %) |

> **Normative constants** — duplicated in both implementations and locked by `ProtocolConstantsTest`
> (Android) and `protocol_constants_locked` (Rust); change both sides together:
> port `50005`, sample rate `48000`, frame `480` samples, client timeout `3000 ms`;
> CT type tags `MUTE=1 BYE=2 LOSS=3 PING=4 PONG=5`;
> AEAD nonce `byte0`: audio `0` / control `1` / HELO tag `2` / KEYS tag `3`;
> control nonce `byte1` = direction `S2C=1 C2S=2`; DSCP ToS `0xB8` (EF); audio datagram type byte `0x00`;
> bootstrap replies `REPAIR`, `BUSY`, `PA`, `AUTH:PENDING`, `AUTH:DENIED`.

## Cryptography
Both sides hold a **long-term X25519 identity** (Android: sealed with an Android Keystore AES-GCM
key; desktop: OS secret store). Keys are pinned at pairing (TOFU after the SAS check).

| Purpose | Key | AEAD | Nonce (12 bytes) |
|---|---|---|---|
| Audio frames | session key | ChaCha20-Poly1305 | `byte0=0`, bytes 8..11 = seq (BE) |
| Control `CT` | session key | ChaCha20-Poly1305 | `byte0=1`, `byte1=dir`, bytes 8..11 = counter (BE) |
| `HELO` tag | HELO-auth key | ChaCha20-Poly1305 tag only | `byte0=2`, bytes 4..11 = HELO counter (BE) |
| `KEYS` tag | HELO-auth key | ChaCha20-Poly1305 tag only | `byte0=3`, bytes 4..11 = HELO counter (BE) |

- **HELO-auth key** = `HKDF-SHA256(ikm = DH(static_client, static_server), salt = ∅, info = "micro-helo-v1")`.
  It depends only on the two pinned identities, so only the genuine paired pair can compute it. The
  server caches it per client.
- **Session key** = `HKDF-SHA256(ikm = DH(eph_client, eph_server) ‖ DH(static_client, static_server),
  salt = <16 random bytes from KEYS>, info = "micro-session")`. Ephemeral×ephemeral gives forward
  secrecy; static×static gives authentication. Both sides refuse a non-contributory (low-order) DH.
- All control counters are strictly increasing per direction; the receiver rejects anything not above
  the last accepted value (replay). Counters restart at 0 with every new session key on both sides.
- Cross-implementation known-answer vectors (SAS, HKDF, audio AEAD, HELO tag, KEYS tag, sealed CT)
  are asserted by `CryptoSessionTest.kt` and the `crypto.rs` tests; they were computed by a third stack
  (Node/OpenSSL).

## Packet formats

### Session (paired devices)
- **`HELO|<uuid>|<clientName>|<clientEphPub>|<ctr>|<tag>`** (desktop → phone, every 1 s). `<uuid>` =
  stable client id; `<clientName>` = label (`|` never appears in it); `<clientEphPub>` = the client's
  fresh X25519 ephemeral for this session (base64); `<ctr>` = 8-byte big-endian **strictly
  monotonic** counter (persisted client-side, seeded from epoch ms, never regresses even across a
  clock step; base64); `<tag>` = 16-byte tag over the packet prefix `HELO|…|<ctr>` under the
  HELO-auth key. Counters are handed out from a block the client reserves **on disk before using
  any of them**, so an unclean exit can only skip counters, never repeat one under a key that
  outlives the process. The server verifies the tag and that `<ctr>` strictly exceeds the last accepted
  value for this uuid (persisted, survives restarts), so a sniffed uuid lets an attacker neither forge
  nor replay a HELO. The server keeps, per client, the last counter it actually *answered*, and will
  not compute a tag at or below it however fresh that counter looks to the persisted mark — that
  mark is exactly the thing that can regress. An *exactly repeated* counter is answered by
  **re-sending the stored datagram byte for byte**, never by computing a fresh tag; the answer is
  best effort, dropped after a service restart and rate-limited to one per 500 ms per client. The body a fresh tag would cover belongs
  to whichever session is live now, not to the counter being answered, so re-tagging would put a
  second Poly1305 tag under the same (key, counter) nonce over a different message the moment the
  live session had moved on. The stored answer is also rate-limited, so one recorded `HELO` cannot
  be turned into a send loop. The reply rebinds nothing: the tag already proved the sender, and the repeat is how
  a genuine client asks again after its `KEYS` was lost. Staying silent instead let an on-path relay
  that had won the binding with its own copy of that HELO keep it, because the real client could
  never be answered.
  Replies: `KEYS` (below); `REPAIR` if the uuid is not pinned/approved (run pairing); `BUSY` if another
  confirmed client is streaming right now (the newcomer must not mistake this for a lost pairing).
  A `HELO` is never answered `AUTH:DENIED`: Deny refuses pairings, not authenticated sessions.
- **`KEYS|<salt>|<serverEphPub>|<ctr>|<tag>`** (phone → desktop, in reply to every accepted HELO).
  `<salt>` = 16 random bytes and `<serverEphPub>` = the server ephemeral, both base64, generated once
  per session; `<ctr>` = the HELO counter being answered; `<tag>` = 16-byte tag over
  `KEYS|<salt>|<serverEphPub>|<ctr>` under the HELO-auth key (nonce byte0 = 3, so it never collides
  with the HELO tag under the same key and counter). The client checks the tag **before any DH** and
  that `<ctr>` is one of its last four HELO counters (binds the reply to its own request). A KEYS whose
  `salt|eph` differ from the live session is adopted immediately if its `<ctr>` is newer than the one
  the live session was adopted with — the server has already switched to it, and only the genuine
  server can produce it (an older genuine KEYS replayed inside the counter window is not newer and is
  ignored). A repeat of the live session's KEYS is ignored; after a client-side liveness drop the same
  session is re-adopted with its control counters continuing (the server never reset them).
- **Bootstrapping a paired desktop.** A new connection does not know which phone it reached until the
  phone reveals its identity, so it still opens with `PC`; the phone answers `PS` to every `PC`
  (the identity is public) but hands the pairing slot only to one uuid at a time. The one exception
  is a uuid the user has denied this run *and* that is not already trusted: that gets
  `AUTH:DENIED`. A denied but trusted uuid still gets `PS`, so it can go on to `HELO`. A pinned
  identity in `PS` sends the desktop straight to `HELO`; an unknown one runs the pairing below.
- **The pairing slot expires twice over**: 10 s after the last progress *and*, unconditionally, 60 s
  after it was opened. The second bound is what stops one unauthenticated host from re-sending `PC`
  just inside the idle timeout — roughly one packet every 9 s — and locking every other device on the
  LAN out of pairing indefinitely. A genuine pairing is a person reading four digits; a minute is
  ample. Expiry alone is not enough, because the flooder is also the one still sending and would
  retake the slot with its next packet, so handovers are additionally rate-limited to one per 10 s.
  That decision takes no field from the datagram — an earlier version keyed a cool-down on the uuid,
  which the sender chooses, and rotating it defeated the cool-down entirely. This bounds churn; it
  does not make the slot un-occupiable (see SECURITY.md).
- **`CT|<ctr(4 BE)>|<sealed>`** — the whole post-pairing control channel. The body is sealed under the
  session key in a nonce space disjoint from audio and decodes to `<type><payload…>`:

  | type | name | direction | payload |
  |-----:|------|-----------|---------|
  | 1 | `MUTE` | both | 1 byte (`0` unmute / `1` mute) |
  | 2 | `BYE`  | client → server | — (clean shutdown; drops the session) |
  | 3 | `LOSS` | client → server | 1 byte packet-loss % `0–100` (tunes Opus FEC/bitrate) |
  | 4 | `PING` | client → server | 8-byte token (server replies with a `PONG`) |
  | 5 | `PONG` | server → client | 8-byte token echo (RTT probe **and** the client's liveness signal) |

  On the server, a successfully opened CT **authenticates the sender's endpoint as the audio target**.
  Once a client is confirmed, the target moves only via such a CT or via the bound client's own
  authenticated HELO carrying a new ephemeral (its restart / new port); anyone else's HELO gets `BUSY`,
  and a CT that would steal a live endpoint is ignored without consuming its counter.
- **Audio** (phone → desktop): `byte 0` = `0x00` (type — every other message starts with an ASCII
  letter, so the two can never be confused), `byte 1..4` = sequence (BE uint32), `byte 5..N` = sealed
  10 ms Opus frame. The client drops duplicates/late frames before decrypting, decodes strictly in
  order, recovers a single loss from the next packet's in-band FEC and conceals older gaps with PLC
  (max 5 frames per gap; a larger gap resyncs). While the desktop is muted nothing is played whatever
  arrives — the desktop's "Muted" is enforced locally, not only requested from the phone.

  The sequence space belongs to the **key**, so the output cursor is rewound only when a genuinely
  new key is adopted. A session that was declared lost and later re-adopted unchanged (the
  asymmetric-loss case below) keeps its cursor: rewinding it there would accept every frame of that
  key's sequence range a second time, which is exactly what a recorded-datagram replay needs.

### Liveness
- **Server**: the audio target must produce an authenticated HELO or CT within **3 s**; otherwise the
  stream pauses, the session is dropped (a reconnect re-keys) and the CPU wake lock is released; the
  recorder itself is stopped (mic released) after a further 5 s without a live client. While idle a
  Wi-Fi multicast lock is held instead so discovery broadcasts still arrive with the screen off. The
  bound client itself coming back with a fresh ephemeral (app restart, new port) rebinds at once;
  anyone else gets `BUSY` until the old endpoint ages out. The HELO high-water mark is persisted on
  **every** accept (an asynchronous, coalescing write — about one small write a second while a
  desktop is connected), and it survives both re-pairing with an unchanged key and deleting the
  device: neither changes the HELO-auth key, so neither may reopen its counters. An earlier version
  wrote it at most every 30 s and argued that a replay inside that window "only yields ciphertext";
  that was wrong — the reply carries a tag, and a second tag under one counter is a nonce reuse.
  The residue is honest rather than zero: the write is not fsynced per packet, so an unclean kill can
  lose the last moments of marks, and a `HELO` recorded in that instant and replayed before the
  genuine client's next one (≤ 1 s later) would be tagged again. That exposes one counter's one-time
  Poly1305 key, which buys nothing: the client accepts a `KEYS` only for its last four counters, and
  no session can be derived without the DH secrets. The audio sequence lives inside the session
  object and starts at 0 with every new key; when it nears the 32-bit wrap the phone drops the
  session so the next HELO re-keys (a safety backstop, ~231 days of continuous streaming).
- **Client**: the desktop sends `LOSS` + `PING` every second; the sealed `PONG` (or any audio/CT that
  opens) refreshes liveness. Nothing decrypted for **5 s** → the session key is dropped and the client
  is back in bootstrap: its HELOs continue, and the phone answers `KEYS` (still knows us) or `REPAIR`
  (forgot us, e.g. reinstalled or the device was deleted on the phone). This also covers a phone
  restart, a re-key that happened while the desktop was muted, and a phone IP change (the client
  follows the phone's new address announced by discovery).
- **Mute** is bidirectional but deliberately **asymmetric**, and the two directions already ride in
  separate nonce spaces, so no extra field is needed to tell them apart:
  - client → server `MUTE` is a **command** ("set mute to X"). It is sent only when the *user*
    toggles mute on the desktop, and repeated once a second until the phone reports that state back
    (and again under a new key after a re-key). Nothing else ever emits one.
  - server → client `MUTE` is a **report** ("mine is X now"). The phone sends a short burst of
    four, 250 ms apart, on every change, and one every 500 ms while muted; no audio is sent while
    muted. The burst matters because the desktop never re-asserts (below): for a phone-side toggle
    this report is the only signal the desktop gets, so a single dropped datagram would otherwise
    strand the two sides disagreeing until the next toggle.
  - A command that changes **nothing** is answered anyway, at most one such answer per 250 ms. The
    desktop retries until it sees the commanded state reported, and an unmuted phone has neither a
    change nor a keepalive to report — so without the answer, a command for the state the phone was
    already in would be retried for the rest of the session and would undo the next mute tapped on
    the phone. A command that *does* change the state is reported like any other change, at once.
    Both answers are produced by the capture loop, so neither goes out while capture is down (a
    microphone the phone cannot open, for instance); the session survives on `PING`/`PONG` and the
    answer follows on the first pass after capture is back.
  - The desktop adopts any report it receives unless it has a command of its own outstanding, in
    which case a report of the old state is ignored — it was in flight before the command arrived.
    "Outstanding" starts at the user's click, not at the first send: while muted the reports
    arrive every 500 ms and are most of what wakes the receive loop, so a report routinely lands
    between the click and the send. The `CT` counter already orders each direction, so the phone
    converges on the last command it was given.

  This is why the desktop never re-asserts its state on a timer: doing so makes mute last-writer-wins
  on a lossy link, and a toggle on the phone racing a re-assert from the desktop leaves the two
  flapping. Independently of all this, a muted desktop drops incoming audio locally, so a lost
  command can waste bandwidth but can never let sound through.
- **Desktop socket**: the client's UDP socket is `connect()`ed; after five consecutive send failures,
  or whenever the session is declared lost, it is re-created (a desktop interface/IP change would
  otherwise leave it writing into a dead source address forever; the phone rebinds to the new port on
  the next authenticated HELO/CT).

### Resilience (Opus FEC)
Android encodes with in-band FEC; each packet carries a low-bitrate copy of the previous frame.
libopus only codes that redundancy when its expected-loss setting is > 0, so the server floors the
client-reported loss at **5 %** (cap 30 %) whenever FEC is enabled — FEC is on from the first
packet, not only after loss has already been measured. Independently of FEC, the bitrate steps down
to 28 / 24 kbps when the reported loss exceeds 5 % / 15 %. DTX is deliberately **not** used: the
client's adaptive jitter buffer treats a sender pause as an underrun, so a constant 100 pps stream
is the stable choice.

### QoS
Both sockets set the IP ToS byte to **0xB8 (DSCP EF)**, which Wi-Fi WMM maps to the voice access
category (AC_VO). (The desktop side is best effort — Windows ignores IP_TOS without the QoS API.)

## Pairing — SAS with commitment, authorized on both ends
First contact runs a commitment-protected handshake so a short code actually authenticates the keys
(defeats MITM), and **both** humans/devices must agree before anything is trusted:

```
desktop ──PC|uuid|name|H(clientPub‖nonce)─▶ phone   commit (hash of client pub + fresh nonce), 1/s
desktop ◀──PS|<serverPub>─────────────────  phone   server reveals its long-term pub
desktop ──PR|uuid|<clientPub>|<nonce>─────▶ phone   reveal; server checks H == commit, computes the SAS,
                                                    shows it (dialog + notification) with Deny / Later / Allow
        user types the phone's code into the desktop; the desktop compares it to its own SAS
desktop ──PK|uuid──────────────────────────▶ phone   "code matched", repeated every 1 s until answered
desktop ◀──AUTH:PENDING───────────────────  phone   code confirmed on the desktop; waiting for Allow
        user taps Allow on the phone (the button is enabled only after PK arrived)
desktop ◀──PA─────────────────────────────  phone   pinned + approved → desktop switches to HELO
desktop ◀──AUTH:DENIED────────────────────  phone   user tapped Deny (for the rest of this run)
```

- **SAS** = `u32be(d[0..4]) % 10000` as 4 ASCII digits, `d = SHA256("MICRO-SAS-v1" ‖ clientPub ‖
  serverPub ‖ clientNonce)`, computed identically on both sides. The nonce is fresh per attempt and
  committed to in `PC`, so a MITM can't grind keys/nonce to force a match → MITM success ≈ 1/10000.
  The reduction takes 32 bits, not 16: folding 65536 values into 10000 gave the low 5536 codes seven
  preimages each and the rest six, which made the bound ~7% optimistic. Over 2^32 the residual bias
  is ~2e-6. Both implementations must reduce identically — the KAT vector pins it.
- **Order of authorization**: the desktop verifies the code first and only then sends `PK`; the phone
  enables **Allow** only after `PK` — so the person tapping Allow has already seen the desktop accept
  the code. A wrong entry emits `sas-bad` on the desktop, rotates the nonce and restarts `PC`→`PR`;
  the phone shows a new code and any earlier Allow is void.
- **Key substitution guard**: once a client key was revealed for the in-flight pairing, a later `PR`
  for the same uuid must present the same key; a `PC` for a *different* uuid can't interrupt an
  in-flight pairing (10 s TTL). A bare `PK` for an unknown uuid does nothing; a `PK` for an already
  pinned + approved uuid is answered `PA` idempotently (lost reply).
- **Deny** rejects further *pairing attempts* for that uuid for the rest of the service run
  (`PC`/`PR`/`PK` → `AUTH:DENIED`). A uuid that is already pinned and approved is the exception for
  `PC`: it still receives `PS`, because every connection has to open with `PC` to learn which phone
  it reached, and an authenticated `HELO` is never refused. So a LAN host spoofing a pairing prompt
  under a trusted uuid — the uuid travels in plaintext — cannot get that device locked out when
  the user, correctly, taps Deny. Revoking a trusted device is done by deleting it in Settings. The
  desktop shows a denial and backs off for 5 s between attempts (the reply is plaintext, so it is
  never treated as final). **Later** hides the prompt for this code without deciding; the desktop's
  `PK` brings it back (the phone must act now), and neither the prompt nor its heads-up notification
  expires while the desktop keeps knocking. While waiting for `PS`/`PR` to be honored, every fifth
  keepalive is a `PC` again, so a desktop whose `PR` is being ignored (another uuid holds the slot)
  picks the slot up as soon as it frees. A desktop that gets no `AUTH:PENDING`/`PA` for 20 s assumes
  the phone lost the pairing slot and starts a fresh commit round (new code). Deleting a trusted
  device on the phone also ends its live session at once.
- On success both sides **pin** each other's long-term key. Re-pairing a pinned phone is gated by a
  grace timer, and arming it needs **both** halves: `REPAIR` as evidence that the phone is reachable
  and does not know us, and local state as the sanity check (a pin held, no session up, not already
  pairing or waiting for Allow). Only the timer's expiry re-arms the SAS prompt; `REPAIR` may
  otherwise only *shorten* a timer already running, and any `KEYS` clears both the timer and the
  evidence.

  Each half exists to stop the other going too far. Without the local check, one spoofed plaintext
  datagram could raise a pairing prompt out of nothing. Without the `REPAIR` evidence, the prompt
  would appear whenever the phone was merely *absent* for a few seconds — a restart or a Wi-Fi blip
  — because a phone that still knows the desktop answers `KEYS`, never `REPAIR`. And without
  either, a phone that genuinely forgot the desktop leaves it sending HELOs forever with no way back.

## Auto-discovery
Same UDP port. The desktop broadcasts `MICRO?` to `255.255.255.255:50005` and every interface's
directed broadcast every 5 s (also while connected, so an IP change is noticed). The phone (service
running) answers by unicast `MICRO!<port>|<deviceName>|<id>`, at most ~10 replies/s in total
(amplification cap). `<id>` is an **opaque per-install token**, not the identity key — discovery
answers any prober, so the crypto identity is never leaked there; the desktop uses it only to collapse
multiple IPs of one phone (Wi-Fi + USB tether) into one entry and to follow the phone across IPs
(only once the old address has stopped answering discovery, so a dual-homed phone is not "followed"
back and forth). While no client is live the phone holds a Wi-Fi multicast lock so these broadcasts
are delivered with the screen off; it is released while streaming (battery).

## What stays plaintext
Discovery (`MICRO?`/`MICRO!`), the pairing bootstrap (`PC`/`PS`/`PR`/`PK`, `PA`, `AUTH:*`), and the
`REPAIR`/`BUSY` hints. Everything else — `HELO` (authenticated), `KEYS` (authenticated), `CT`
(sealed), audio (sealed) — is protected. None of the plaintext messages can move, mute, re-key or
end a live session.
