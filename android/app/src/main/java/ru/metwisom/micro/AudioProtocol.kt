package ru.metwisom.micro

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shared wire-protocol constants for the Micro mic-bridge (the normative text is PROTOCOL.md).
 *
 * Android is the UDP server: it binds [PORT], answers discovery, runs the SAS pairing handshake and,
 * for a paired + approved client, streams Opus-encoded 10 ms audio frames to the endpoint that keeps
 * an authenticated `HELO` / `CT` heartbeat alive. If neither arrives within [CLIENT_TIMEOUT_MS] the
 * stream pauses and the session is dropped.
 *
 * Audio packet layout (Android -> desktop):
 *   byte 0     : [PKT_AUDIO] (0x00) — every other message starts with an ASCII letter
 *   bytes 1..4 : sequence number, big-endian uint32
 *   bytes 5..N : ChaCha20-Poly1305 ciphertext+tag of one 10 ms Opus frame
 *
 * Every constant here is mirrored by the Rust client (`lib.rs`) and pinned by `ProtocolConstantsTest`
 * / `protocol_constants_locked`, so a one-sided edit fails a build instead of breaking the wire.
 */
object AudioProtocol {
    const val PORT = 50005

    const val SAMPLE_RATE = 48000   // Opus native rate
    const val CHANNELS = 1
    const val FRAME_SAMPLES = 480   // 10 ms @ 48 kHz (low latency)
    const val BITRATE = 32000       // 32 kbps, good for voice
    // Opus encoder complexity 0..10 (CPU vs quality). 5 is a balanced mobile default.
    const val OPUS_COMPLEXITY = 5

    /**
     * Packet-loss % handed to the encoder while FEC is on. libopus codes LBRR (in-band FEC) only when
     * the expected loss is > 0, so the client-reported figure is floored here — otherwise FEC would be
     * silently off in the common 0 %-loss steady state and only switch on *after* a burst of loss.
     */
    const val FEC_MIN_LOSS_PCT = 5
    const val FEC_MAX_LOSS_PCT = 30

    /** No authenticated HELO/CT from the bound client for this long → pause + drop the session. */
    const val CLIENT_TIMEOUT_MS = 3000L
    /** No live client for this long → stop the recorder (battery, "mic in use" indicator). */
    const val IDLE_CAPTURE_STOP_MS = 5000L

    /** IP ToS byte for DSCP EF (46 << 2): Wi-Fi WMM maps it to the voice access category (AC_VO). */
    const val DSCP_EF_TOS = 0xB8

    /** First byte of an audio datagram; control/bootstrap messages all start with an ASCII letter. */
    const val PKT_AUDIO = 0

    // AEAD nonce byte 0 — one value per nonce space, so streams never collide under a shared key.
    const val NONCE_AUDIO = 0   // session key: audio frames, last 4 bytes = seq
    const val NONCE_CTL = 1     // session key: control (CT), byte 1 = direction, last 4 bytes = counter
    const val NONCE_HELO = 2    // HELO-auth key: client → server HELO tag, last 8 bytes = HELO counter
    const val NONCE_KEYS = 3    // HELO-auth key: server → client KEYS tag, last 8 bytes = HELO counter

    // Control-plane direction (nonce byte 1) — stops a sealed CT being reflected back at its sender.
    const val DIR_S2C = 1
    const val DIR_C2S = 2

    // CT (authenticated control) message types — the first plaintext byte of the sealed CT body.
    const val CT_MUTE = 1
    const val CT_BYE = 2
    const val CT_LOSS = 3
    const val CT_PING = 4
    const val CT_PONG = 5

    // Auto-discovery: client broadcasts "MICRO?", server replies "MICRO!<port>|<name>|<id>".
    const val DISCOVERY_PROBE = "MICRO?"
    const val ANNOUNCE_PREFIX = "MICRO!"

    // Plaintext bootstrap replies (server → client). Everything after pairing is sealed.
    const val REPAIR = "REPAIR"              // uuid not pinned/approved → run the SAS pairing
    const val BUSY = "BUSY"                  // another confirmed client holds the stream right now
    const val PAIR_APPROVED = "PA"           // PK accepted: pinned + approved, proceed to HELO
    const val AUTH_PENDING = "AUTH:PENDING"  // PK seen, waiting for the user to tap Allow on the phone
    const val AUTH_DENIED = "AUTH:DENIED"    // the user rejected this device

    /** First non-loopback IPv4 of an up interface (prefers wlan), or null. Blocking: call off-main. */
    fun localIpv4(): String? {
        var fallback: String? = null
        val ifaces = try { NetworkInterface.getNetworkInterfaces() } catch (_: Exception) { null } ?: return null
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (nif.name.startsWith("wlan")) return ip
                    if (fallback == null) fallback = ip
                }
            }
        }
        return fallback
    }
}
