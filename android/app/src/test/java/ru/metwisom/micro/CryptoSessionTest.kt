package ru.metwisom.micro

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Cross-implementation known-answer tests, run against the REAL [CryptoSession] class. The desktop
 * client (Rust) has a mirror suite asserting the SAME vectors, and they were computed by a third,
 * independent stack (Node/OpenSSL). If BouncyCastle ever derived a different SAS, session key, tag or
 * ciphertext than the client, one of these fails before the mismatch could break the wire.
 */
class CryptoSessionTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun fill(value: Int, n: Int) = ByteArray(n) { value.toByte() }
    private fun fromHex(s: String) = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    // Shared vectors (identical in lib.rs tests).
    private val katSessionKey = "2d2a66ea48d6bc8bf032a70bbee4a6e0122c7c6b10d6d6687bc22b2250eb470f"
    private val katHeloKey = "07a9697c252ce982488e1b965f63af4129bede3fb851f4b5d8bf1f8993ca8a8b"

    @Test
    fun sasMatchesClientAndNode() {
        assertEquals("9555", CryptoSession.sas(fill(0x11, 32), fill(0x22, 32), fill(0x33, 16)))
    }

    @Test
    fun sessionKdfMatchesClientAndNode() {
        // ikm = ephemeral-DH || static-DH (fixed here); salt + info exactly as CryptoSession.derive().
        val ikm = fill(0x33, 32) + fill(0x44, 32)
        val h = HKDFBytesGenerator(SHA256Digest())
        h.init(HKDFParameters(ikm, fill(0x55, 16), "micro-session".toByteArray()))
        val key = ByteArray(32)
        h.generateBytes(key, 0, 32)
        assertEquals(katSessionKey, hex(key))
    }

    @Test
    fun audioFrameAeadMatchesClientAndNode() {
        // Real hot path: encryptInto with seq 42 under the KAT key → ciphertext || 16-byte tag.
        val s = CryptoSession(fromHex(katSessionKey))
        val plain = "micro test frame".toByteArray()
        val out = ByteArray(4 + plain.size + 16)
        val n = s.encryptInto(plain, plain.size, 42, out, 4)
        assertEquals(plain.size + 16, n)
        assertEquals("516b4ccb79d49eeec40db5d35a7dd90195409e45e81a9140be4b7960342091a0", hex(out.copyOfRange(4, 4 + n)))
    }

    @Test
    fun heloAndKeysTagsMatchClientAndNode() {
        val h = HKDFBytesGenerator(SHA256Digest())
        h.init(HKDFParameters(fill(0x77, 32), null, "micro-helo-v1".toByteArray()))
        val key = ByteArray(32)
        h.generateBytes(key, 0, 32)
        assertEquals(katHeloKey, hex(key))
        // Same key + same counter, two nonce spaces → two unrelated tags (byte0 = 2 vs 3).
        assertEquals("a21c2cb564bdd5024b9e2d7bdc92852e", hex(CryptoSession.heloTag(key, 42, "HELO|kat".toByteArray())))
        assertEquals("57b75c149e34e51764bb7b4bc811d7a3", hex(CryptoSession.keysTag(key, 42, "KEYS|kat".toByteArray())))
    }

    @Test
    fun controlSealMatchesClientAndNode() {
        // S2C, counter 7, body = PONG + token 01..08 → sealed bytes as computed by Node.
        val s = CryptoSession(fromHex(katSessionKey))
        val body = byteArrayOf(AudioProtocol.CT_PONG.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        val sealed = s.sealControl(AudioProtocol.DIR_S2C, 7, body)
        assertEquals("32ebb904de32f6a521bbcf493ae3cebe09ffef7bde7ba6631c", hex(sealed))
        // Opens only in the matching direction, with the matching counter, and only untampered.
        assertArrayEquals(body, s.openControl(AudioProtocol.DIR_S2C, 7, sealed, 0, sealed.size))
        assertNull(s.openControl(AudioProtocol.DIR_C2S, 7, sealed, 0, sealed.size))
        assertNull(s.openControl(AudioProtocol.DIR_S2C, 8, sealed, 0, sealed.size))
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(s.openControl(AudioProtocol.DIR_S2C, 7, tampered, 0, tampered.size))
    }

    @Test
    fun audioSequenceIsOwnedByTheSession() {
        val s = CryptoSession(fromHex(katSessionKey))
        assertEquals(0, s.nextSeq())
        assertEquals(1, s.nextSeq())
        assertFalse(s.seqNearWrap())
        assertEquals(0, CryptoSession(fromHex(katSessionKey)).nextSeq()) // a new key → a new nonce space
    }

    @Test
    fun controlCountersAreFreshPerSessionAndReplayProof() {
        val s = CryptoSession(fromHex(katSessionKey))
        assertEquals(1, s.nextSendCtr())
        assertEquals(2, s.nextSendCtr())
        assertTrue(s.recvCtrFresh(1))
        s.commitRecvCtr(5)
        assertFalse(s.recvCtrFresh(5))   // replay
        assertFalse(s.recvCtrFresh(3))   // out of order
        assertTrue(s.recvCtrFresh(6))
        // A new session starts over — counters travel with the key, never separately.
        assertEquals(1, CryptoSession(fromHex(katSessionKey)).nextSendCtr())
    }

    @Test
    fun heloKeyThroughTheRealEntryPointMatchesClientAndNode() {
        // Fixed scalars: client 0x11.., server 0x22.. (Node/OpenSSL computed the same after clamping).
        val clientStatic = X25519PrivateKeyParameters(fill(0x11, 32), 0)
        val serverStatic = X25519PrivateKeyParameters(fill(0x22, 32), 0)
        assertEquals("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13", hex(clientStatic.generatePublicKey().encoded))
        val key = CryptoSession.heloKey(serverStatic, clientStatic.generatePublicKey().encoded)
        assertEquals("718d71c0315ad8ba99e1936a380817e02ba03e8a01d10312cbadfdee3922320c", hex(key))
        assertEquals("d24ab59a17da02202f5e6f6220781fc4", hex(CryptoSession.heloTag(key, 42, "HELO|kat".toByteArray())))
    }

    @Test
    fun sessionThroughTheRealEntryPointMatchesClientAndNode() {
        // forClient with fixed keys + fixed salt derives the KAT session key: sealing [BYE] as C2S ctr 1
        // must produce the Node vector, which the Rust client asserts for the same inputs.
        val clientStatic = X25519PrivateKeyParameters(fill(0x11, 32), 0)
        val serverStatic = X25519PrivateKeyParameters(fill(0x22, 32), 0)
        val clientEph = X25519PrivateKeyParameters(fill(0x33, 32), 0)
        val serverEph = X25519PrivateKeyParameters(fill(0x44, 32), 0)
        val client = CryptoSession.forClient(
            clientStatic, clientEph, serverStatic.generatePublicKey().encoded, serverEph.generatePublicKey().encoded, fill(0x55, 16)
        )
        val sealed = client.sealControl(AudioProtocol.DIR_C2S, 1, byteArrayOf(AudioProtocol.CT_BYE.toByte()))
        assertEquals("1b0b2f158471bec4b85a84576dbbc4034f", hex(sealed))
    }

    @Test
    fun serverAndClientDeriveTheSameSessionAndOnlyWithTheRightStaticKey() {
        val rnd = SecureRandom()
        val serverStatic = X25519PrivateKeyParameters(rnd)
        val clientStatic = X25519PrivateKeyParameters(rnd)
        val clientEph = X25519PrivateKeyParameters(rnd)

        val setup = CryptoSession.forServer(
            serverStatic, clientStatic.generatePublicKey().encoded, clientEph.generatePublicKey().encoded
        )
        val salt = Base64.getDecoder().decode(setup.saltB64)
        val serverEphPub = Base64.getDecoder().decode(setup.serverEphB64)
        val client = CryptoSession.forClient(
            clientStatic, clientEph, serverStatic.generatePublicKey().encoded, serverEphPub, salt
        )

        // Both directions of the control plane open on the other side → identical keys.
        val s2c = setup.session.sealControl(AudioProtocol.DIR_S2C, 1, byteArrayOf(AudioProtocol.CT_MUTE.toByte(), 1))
        assertNotNull(client.openControl(AudioProtocol.DIR_S2C, 1, s2c, 0, s2c.size))
        val c2s = client.sealControl(AudioProtocol.DIR_C2S, 1, byteArrayOf(AudioProtocol.CT_BYE.toByte()))
        assertNotNull(setup.session.openControl(AudioProtocol.DIR_C2S, 1, c2s, 0, c2s.size))

        // An impostor with the wrong long-term key derives a different session.
        val impostor = CryptoSession.forClient(
            X25519PrivateKeyParameters(rnd), clientEph, serverStatic.generatePublicKey().encoded, serverEphPub, salt
        )
        assertNull(impostor.openControl(AudioProtocol.DIR_S2C, 1, s2c, 0, s2c.size))
    }
}
