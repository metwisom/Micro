package ru.metwisom.micro

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shared wire-protocol constants for the Micro mic-bridge.
 *
 * Android is the UDP server: it binds [PORT] and waits for a "HELO" datagram from the
 * Windows client. Once seen, it streams Opus-encoded 20 ms audio frames back to the
 * sender's address. Windows must keep sending "HELO" keepalives; if none arrive within
 * [CLIENT_TIMEOUT_MS] the stream pauses.
 *
 * Audio packet layout (Android -> Windows):
 *   bytes 0..3 : sequence number, big-endian uint32
 *   bytes 4..N : Opus payload for one 20 ms frame
 */
object AudioProtocol {
    const val PORT = 50005

    const val SAMPLE_RATE = 48000   // Opus native rate
    const val CHANNELS = 1
    const val FRAME_SAMPLES = 480   // 10 ms @ 48 kHz (low latency)
    const val BITRATE = 32000       // 32 kbps, good for voice

    const val CLIENT_TIMEOUT_MS = 3000L

    val HELLO = "HELO".toByteArray(Charsets.US_ASCII)
    val BYE = "BYE!".toByteArray(Charsets.US_ASCII)

    // Auto-discovery: client broadcasts DISCOVER, server replies "MICRO!<port>|<name>".
    val DISCOVER = "MICRO?".toByteArray(Charsets.US_ASCII)
    const val ANNOUNCE_PREFIX = "MICRO!"

    // Authorization. Client keepalive is "HELO|<uuid>|<clientName>". Server replies with
    // one of these when the client isn't (yet) approved.
    val AUTH_PENDING = "AUTH:PENDING".toByteArray(Charsets.US_ASCII)
    val AUTH_DENIED = "AUTH:DENIED".toByteArray(Charsets.US_ASCII)
    val AUTH_BADCODE = "AUTH:BADCODE".toByteArray(Charsets.US_ASCII)

    // Client → server packet-loss feedback: "LOSS|<percent>" (drives FEC + bitrate).
    val LOSS = "LOSS".toByteArray(Charsets.US_ASCII)

    fun matches(buf: ByteArray, len: Int, magic: ByteArray): Boolean {
        if (len < magic.size) return false
        for (i in magic.indices) if (buf[i] != magic[i]) return false
        return true
    }

    /** First non-loopback IPv4 of an up interface (prefers wlan), or null. */
    fun localIpv4(): String? {
        var fallback: String? = null
        for (nif in NetworkInterface.getNetworkInterfaces()) {
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
