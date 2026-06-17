package ru.metwisom.micro

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * One audio-encryption session. The key mixes two DH's:
 *  - ephemeral-ephemeral  → forward secrecy (ephemerals discarded after the session),
 *  - static-static (server identity × pinned client key) → authentication (MITM can't derive it).
 * HKDF(salt, dh_eph || dh_static) → ChaCha20-Poly1305 key. The salt and server ephemeral pub are
 * sent to the client in `KEYS|<salt>|<serverEphPub>`.
 */
class CryptoSession private constructor(key: ByteArray) {

    // Reused per frame (hot path) — no per-frame cipher/buffer allocation.
    private val aead = ChaCha20Poly1305()
    private val keyParam = KeyParameter(key)
    private val nonce = ByteArray(12)

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

    /** What to send the client so it can derive the same key. */
    class Setup(val session: CryptoSession, val saltB64: String, val serverEphB64: String)

    companion object {
        fun forServer(
            serverStatic: X25519PrivateKeyParameters,
            clientStaticPub: ByteArray,
            clientEphPub: ByteArray,
        ): Setup {
            val ephPriv = X25519PrivateKeyParameters(SecureRandom())
            val ephPub = ephPriv.generatePublicKey().encoded
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)

            val dhEph = agree(ephPriv, clientEphPub)
            val dhStatic = agree(serverStatic, clientStaticPub)
            val key = hkdf(dhEph + dhStatic, salt)

            return Setup(
                CryptoSession(key),
                Base64.encodeToString(salt, Base64.NO_WRAP),
                Base64.encodeToString(ephPub, Base64.NO_WRAP),
            )
        }

        private fun agree(priv: X25519PrivateKeyParameters, pub: ByteArray): ByteArray {
            val a = X25519Agreement()
            a.init(priv)
            val out = ByteArray(a.agreementSize)
            a.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
            return out
        }

        private fun hkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
            val h = HKDFBytesGenerator(SHA256Digest())
            h.init(HKDFParameters(ikm, salt, "micro-session".toByteArray()))
            val key = ByteArray(32)
            h.generateBytes(key, 0, 32)
            return key
        }
    }
}
