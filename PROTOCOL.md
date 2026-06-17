# Micro wire protocol

Transport: **UDP**. Android is the **server**, Windows is the **client**.

```
Windows ──HELO──▶ Android      (Windows announces itself; repeated every 1 s as keepalive)
Windows ◀─audio── Android      (Opus frames, only while a recent HELO was seen)
Windows ──BYE!──▶ Android      (optional, on clean shutdown)
```

Default port: **50005** (UDP).

## Audio settings
| Field        | Value                     |
|--------------|---------------------------|
| Codec        | Opus (VOIP mode)          |
| Sample rate  | 48000 Hz                  |
| Channels     | 1 (mono)                  |
| Frame size   | 480 samples = 10 ms       |
| Bitrate      | 32 kbps                   |

## Packet formats

**Control (Windows → Android):**
- `HELO|<uuid>|<clientName>|<clientPubKey>|<code?>` — register/keepalive + auth + key
  exchange. `<uuid>` = stable client id; `<clientName>` = label; `<clientPubKey>` = the
  client's X25519 public key (base64); `<code>` = the 4-digit pairing code (only while
  pairing, empty otherwise).
- `BYE!` — deregister. Android stops streaming and drops the session key.
- `LOSS|<percent>` — client's measured packet-loss %, sent ~1/s. Android tunes Opus FEC
  strength (`packetLossPercent`) and bitrate accordingly.
- `MUTE|0` / `MUTE|1` — **bidirectional** mute sync, sent on change by either side. While muted
  Android stops transmitting (not silence — no packets). The other side echoes/reflects it, so
  the desktop button and the phone button stay in sync.

If no `HELO` arrives for **3 s**, Android pauses the stream.

## Resilience (Opus FEC / DTX)
- **FEC**: Android encodes with in-band forward error correction; each packet carries a
  redundant copy of the previous frame. On a single loss the client recovers it by decoding
  the next packet with `decode_fec`. Older/multiple losses fall back to PLC.
- **DTX**: during silence the encoder emits nothing — Android sends no packet (seq doesn't
  advance), the client simply plays silence. Saves bandwidth/battery.

## Pairing — authenticated key exchange (SAS + commitment)
Both sides have a **long-term X25519 identity** (persisted). First contact runs a
commitment-protected handshake so a short code actually authenticates the keys (defeats MITM):

```
client ──PC|uuid|name|H(clientPub)──▶ server     (commit: hash of client's pub)
client ◀──PS|<serverPub>──────────── server     (server reveals its long-term pub)
client ──PR|uuid|<clientPub>───────▶ server     (client reveals; server checks H == commit)
                                                  server computes SAS, shows it on the phone
client ──PK|uuid───────────────────▶ server     (sent after the user types the matching SAS)
client ◀──KEYS|<salt>──────────────  server     (server pins clientPub, opens the session)
```

- **SAS** = first 4 decimal digits of `SHA256("MICRO-SAS-v1" || clientPub || serverPub)`,
  computed identically on both sides. The phone shows it; the user types it into the client,
  which compares it to its own value. The commitment (`H(clientPub)` sent before the server
  reveals its key) stops an active MITM from grinding keys to force a match → MITM success is
  ≈ 1/10000.
- On a **wrong/typo** entry the client emits `sas-bad`; the user re-enters (or re-pairs).
- On success both sides **pin** each other's long-term key (TOFU). `REPAIR` is sent if a side
  lost its pin (e.g. reinstall) → re-pair.

## Sessions + encryption (with forward secrecy)
Each session uses fresh ephemeral keys plus the pinned long-term keys:
- Client → `HELO|uuid|name|<clientEphPub>` (fresh ephemeral per session).
- Server → `KEYS|<salt>|<serverEphPub>` (fresh ephemeral + random salt).
- Both derive `key = HKDF(salt, DH(eph_c, eph_s) ‖ DH(static_c, static_s))`:
  - **ephemeral × ephemeral** → forward secrecy (ephemerals are discarded after the session),
  - **static × static** (pinned identities) → authentication (a MITM without the long-term
    private keys can't compute it).

Each Opus frame is **ChaCha20-Poly1305** encrypted, nonce = sequence number. A leaked long-term
key does *not* expose past sessions (their ephemerals are gone).

Control replies are ASCII (`PS|…`, `KEYS|…`, `REPAIR`, `AUTH:DENIED`); audio packets are binary
(`[seq][ciphertext+tag]`).

## Auto-discovery (optional)
Same UDP port (50005). The client finds the phone without a typed IP:
- Client → **broadcast** `MICRO?` to `255.255.255.255:50005` (and per-interface broadcast addrs).
- Android (already listening on 50005) → **unicast** reply `MICRO!<audioPort>|<deviceName>`
  (e.g. `MICRO!50005|Pixel 6a`). The client reads the sender's IP from the reply.

The phone must have pressed Start (service running) to answer.

**Audio (Android → Windows):**
```
byte 0..3 : sequence number, big-endian uint32
byte 4..N : Opus payload for one 20 ms frame
```
Sequence is used by the client to drop duplicate/out-of-order packets. Lost packets
are simply skipped (UDP); the jitter buffer outputs silence on underrun.
```
