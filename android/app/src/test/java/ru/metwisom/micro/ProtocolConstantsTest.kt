package ru.metwisom.micro

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the non-crypto wire constants that are duplicated in the Rust client (`lib.rs`). The crypto
 * outputs are locked by [CryptoSessionTest]; these lock the plain protocol numbers and strings so a
 * one-sided edit fails the build instead of silently breaking interop on the wire. The Rust suite
 * (`protocol_constants_locked`) has the mirror assertions.
 */
class ProtocolConstantsTest {
    @Test
    fun wireConstantsAreLocked() {
        assertEquals(50005, AudioProtocol.PORT)
        assertEquals(48000, AudioProtocol.SAMPLE_RATE)
        assertEquals(480, AudioProtocol.FRAME_SAMPLES)
        assertEquals(3000L, AudioProtocol.CLIENT_TIMEOUT_MS)
        assertEquals(1, AudioProtocol.CT_MUTE)
        assertEquals(2, AudioProtocol.CT_BYE)
        assertEquals(3, AudioProtocol.CT_LOSS)
        assertEquals(4, AudioProtocol.CT_PING)
        assertEquals(5, AudioProtocol.CT_PONG)
    }

    @Test
    fun nonceSpacesAndDirectionsAreLocked() {
        assertEquals(0, AudioProtocol.NONCE_AUDIO)
        assertEquals(1, AudioProtocol.NONCE_CTL)
        assertEquals(2, AudioProtocol.NONCE_HELO)
        assertEquals(3, AudioProtocol.NONCE_KEYS)
        assertEquals(1, AudioProtocol.DIR_S2C)
        assertEquals(2, AudioProtocol.DIR_C2S)
    }

    @Test
    fun bootstrapStringsAreLocked() {
        assertEquals("MICRO?", AudioProtocol.DISCOVERY_PROBE)
        assertEquals("MICRO!", AudioProtocol.ANNOUNCE_PREFIX)
        assertEquals("REPAIR", AudioProtocol.REPAIR)
        assertEquals("BUSY", AudioProtocol.BUSY)
        assertEquals("PA", AudioProtocol.PAIR_APPROVED)
        assertEquals("AUTH:PENDING", AudioProtocol.AUTH_PENDING)
        assertEquals("AUTH:DENIED", AudioProtocol.AUTH_DENIED)
    }

    @Test
    fun qosAndFecPolicyAreLocked() {
        assertEquals(0, AudioProtocol.PKT_AUDIO)        // audio datagram type byte
        assertEquals(0xB8, AudioProtocol.DSCP_EF_TOS)   // DSCP EF → WMM AC_VO
        assertEquals(5, AudioProtocol.FEC_MIN_LOSS_PCT)  // > 0 keeps libopus LBRR coded
        assertEquals(30, AudioProtocol.FEC_MAX_LOSS_PCT)
    }
}
