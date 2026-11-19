package ru.metwisom.micro

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * One audio-encryption session. The key mixes two DH's:
 *  - ephemeral-ephemeral  → forward secrecy (ephemerals discarded after the session),
 *  - static-static (server identity × pinned client key) → authentication (a MITM can't derive it).
 * `HKDF(salt, dh_eph || dh_static, info "micro-session")` → ChaCha20-Poly1305 key. The salt and the
 * server ephemeral pub travel to the client in the (authenticated) `KEYS` reply.
 *
 * The session also owns its control-plane counters, so installing a new session is a single volatile
 * write — the counters can never be observed "reset" separately from the key they belong to.
 *
 * No `android.*` imports: the class is exercised directly by the JVM unit tests.
 */
class CryptoSession internal constructor(key: ByteArray) {

    // Reused per frame (hot path) — no per-frame cipher/buffer allocation.
    private val aead = ChaCha20Poly1305()
    private val keyParam = KeyParameter(key)
    private val nonce = ByteArray(12).also { it[0] = AudioProtocol.NONCE_AUDIO.toByte() }
    private val keyBytes = key.copyOf() // for the (rare) control path, which runs on other threads

    // Nonce material — all owned by the session so a fresh key always comes with fresh counters, and a
    // frame is always numbered from the same object it is encrypted with (no (key, seq) can repeat).
    // `send` is bumped from both the tx thread (mute keepalive) and the rx thread (PONG echo), hence
    // atomic; `seq` is atomic so a capture-thread handover can never hand out one value twice.
    private val seq = AtomicInteger(0)
    private val sendCtr = AtomicInteger(0)
    private val recvCtr = AtomicInteger(0)

    /** Next audio sequence number (also the AEAD nonce) under this key. */
    fun nextSeq(): Int = seq.getAndIncrement()

    /** Approaching the 32-bit wrap → the caller must re-key (a new session starts at 0). */
    fun seqNearWrap(): Boolean = seq.get() >= SEQ_REKEY_LIMIT

    /** Encrypt `len` bytes of `plain` directly into `out` at `off`; returns ciphertext length (+16 tag). */
    fun encryptInto(plain: ByteArray, len: Int, seq: Int, out: ByteArray, off: Int): Int {
        nonce[8] = (seq ushr 24).toByte()
        nonce[9] = (seq ushr 16).toByte()
        nonce[10] = (seq ushr 8).toByte()
        nonce[11] = seq.toByte()

        aead.init(true, AEADParameters(keyParam, 128, nonce))
        var p = aead.processBytes(plain, 0, len, out, off)
        p += aead.doFinal(out, off + p)
        return p
    }

    /** Fresh, strictly increasing counter for an outbound control message. */
    fun nextSendCtr(): Int = sendCtr.incrementAndGet()

    /** Inbound control anti-replay: true if `ctr` is strictly above the last accepted counter. */
    fun recvCtrFresh(ctr: Int): Boolean = ctr > recvCtr.get()

    /** Record an inbound counter as accepted (call only after the message opened successfully). */
    fun commitRecvCtr(ctr: Int) = recvCtr.set(ctr)

    /**
     * Authenticated control plane, separate from the audio nonce space: byte0 = [AudioProtocol.NONCE_CTL],
     * byte1 = direction, last 4 bytes = the counter. A fresh cipher per call keeps the audio hot path's
     * reused [aead] off the rx/tx control threads.
     */
    fun sealControl(dir: Int, ctr: Int, plain: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(keyBytes), 128, ctlNonce(dir, ctr)))
        val out = ByteArray(c.getOutputSize(plain.size))
        var p = c.processBytes(plain, 0, plain.size, out, 0)
        p += c.doFinal(out, p)
        return if (p == out.size) out else out.copyOf(p)
    }

    /** Open a sealed control message; returns plaintext, or null on auth failure. */
    fun openControl(dir: Int, ctr: Int, ct: ByteArray, off: Int, len: Int): ByteArray? = try {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(keyBytes), 128, ctlNonce(dir, ctr)))
        val out = ByteArray(c.getOutputSize(len))
        var p = c.processBytes(ct, off, len, out, 0)
        p += c.doFinal(out, p)
        if (p == out.size) out else out.copyOf(p)
    } catch (_: Exception) { null }

    /**
     * Best-effort wipe of the in-memory key material at end of session (mirrors the Rust client's
     * zeroize). Call only once both audio/control threads have stopped using this session.
     */
    fun destroy() {
        Arrays.fill(keyBytes, 0)
        try { Arrays.fill(keyParam.key, 0) } catch (_: Throwable) {}
    }

    private fun ctlNonce(dir: Int, ctr: Int): ByteArray {
        val n = ByteArray(12)
        n[0] = AudioProtocol.NONCE_CTL.toByte()
        n[1] = dir.toByte()
        n[8] = (ctr ushr 24).toByte()
        n[9] = (ctr ushr 16).toByte()
        n[10] = (ctr ushr 8).toByte()
        n[11] = ctr.toByte()
        return n
    }

    /** What to send the client so it can derive the same key. */
    class Setup(val session: CryptoSession, val saltB64: String, val serverEphB64: String)

    companion object {
        // Force a fresh-key handshake before the 32-bit audio sequence (the AEAD nonce) wraps:
        // ~231 days of continuous unmuted streaming at 100 frames/s — purely a safety backstop.
        const val SEQ_REKEY_LIMIT = 2_000_000_000

        private val INFO_SESSION = "micro-session".toByteArray(Charsets.US_ASCII)
        private val INFO_HELO = "micro-helo-v1".toByteArray(Charsets.US_ASCII)
        private val SAS_DOMAIN = "MICRO-SAS-v1".toByteArray(Charsets.US_ASCII)

        fun forServer(
            serverStatic: X25519PrivateKeyParameters,
            clientStaticPub: ByteArray,
            clientEphPub: ByteArray,
        ): Setup {
            val ephPriv = X25519PrivateKeyParameters(SecureRandom())
            val ephPub = ephPriv.generatePublicKey().encoded
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val session = derive(agree(ephPriv, clientEphPub), agree(serverStatic, clientStaticPub), salt)
            val b64 = Base64.getEncoder()
            return Setup(session, b64.encodeToString(salt), b64.encodeToString(ephPub))
        }

        /** Client-side mirror of [forServer] — what the Rust client computes. Used by tests to prove symmetry. */
        internal fun forClient(
            clientStatic: X25519PrivateKeyParameters,
            clientEph: X25519PrivateKeyParameters,
            serverStaticPub: ByteArray,
            serverEphPub: ByteArray,
            salt: ByteArray,
        ): CryptoSession = derive(agree(clientEph, serverEphPub), agree(clientStatic, serverStaticPub), salt)

        private fun derive(dhEph: ByteArray, dhStatic: ByteArray, salt: ByteArray): CryptoSession {
            val ikm = dhEph + dhStatic
            val key = hkdf(ikm, salt, INFO_SESSION)
            val session = CryptoSession(key) // copies the key
            Arrays.fill(key, 0); Arrays.fill(ikm, 0); Arrays.fill(dhEph, 0); Arrays.fill(dhStatic, 0)
            return session
        }

        /**
         * The HELO/KEYS-auth key: static×static DH of the two pinned identities → HKDF(info "micro-helo-v1").
         * Depends only on the long-term keys, so the server caches it per pinned client instead of
         * repeating the X25519 agreement on every ~1/s HELO keepalive.
         */
        fun heloKey(serverStatic: X25519PrivateKeyParameters, clientStaticPub: ByteArray): ByteArray =
            hkdf(agree(serverStatic, clientStaticPub), null, INFO_HELO)

        /**
         * 16-byte ChaCha20-Poly1305 tag over [aad] (empty plaintext) under the HELO-auth [key]: nonce
         * byte0 = [kind] ([AudioProtocol.NONCE_HELO] for a client HELO, [AudioProtocol.NONCE_KEYS] for
         * the server's KEYS reply — distinct spaces, since both ride the same key with the same counter),
         * last 8 bytes = the HELO counter. Mirrors the Rust client's `auth_tag` (KAT-locked on both sides).
         */
        fun authTag(key: ByteArray, kind: Int, ctr: Long, aad: ByteArray): ByteArray {
            val nonce = ByteArray(12)
            nonce[0] = kind.toByte()
            for (i in 0..7) nonce[11 - i] = (ctr ushr (8 * i)).toByte()
            val c = ChaCha20Poly1305()
            c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(c.getOutputSize(0)) // tag only (empty plaintext)
            var p = c.processBytes(ByteArray(0), 0, 0, out, 0)
            p += c.doFinal(out, p)
            return if (p == out.size) out else out.copyOf(p)
        }

        fun heloTag(key: ByteArray, ctr: Long, aad: ByteArray): ByteArray = authTag(key, AudioProtocol.NONCE_HELO, ctr, aad)
        fun keysTag(key: ByteArray, ctr: Long, aad: ByteArray): ByteArray = authTag(key, AudioProtocol.NONCE_KEYS, ctr, aad)

        /**
         * 4-digit Short Authentication String over both long-term public keys plus the client's fresh
         * per-pairing nonce (committed to in `PC`, so a MITM can't grind it). `Locale.ROOT` → always ASCII
         * digits; the desktop computes the same value and the user types ASCII.
         */
        fun sas(clientPub: ByteArray, serverPub: ByteArray, clientNonce: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(SAS_DOMAIN)
            md.update(clientPub)
            md.update(serverPub)
            md.update(clientNonce)
            val d = md.digest()
            // Reduce 32 bits, not 16 — see the matching comment in the client's crypto.rs. Folding
            // 65536 values into 10000 biased the low codes and made the 1/10000 MITM bound ~7%
            // optimistic. Must stay bit-identical to the client: the KAT vector pins it.
            val v = ((d[0].toLong() and 0xff shl 24) or (d[1].toLong() and 0xff shl 16) or
                (d[2].toLong() and 0xff shl 8) or (d[3].toLong() and 0xff)) % 10000L
            return String.format(Locale.ROOT, "%04d", v)
        }

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray): ByteArray {
            val h = HKDFBytesGenerator(SHA256Digest())
            h.init(HKDFParameters(ikm, salt, info))
            val key = ByteArray(32)
            h.generateBytes(key, 0, 32)
            return key
        }

        /** X25519; BouncyCastle rejects an all-zero (low-order point) result by throwing. */
        private fun agree(priv: X25519PrivateKeyParameters, pub: ByteArray): ByteArray {
            val a = X25519Agreement()
            a.init(priv)
            val out = ByteArray(a.agreementSize)
            a.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
            return out
        }
    }
}
