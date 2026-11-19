package ru.metwisom.micro

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The server's long-term X25519 identity, persisted; pinned by clients at SAS pairing.
 *
 * The 32-byte secret is sealed with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed / TEE when the device offers it, never exportable). Only the wrapped blob is
 * written to SharedPreferences, so a copy of the prefs — from a cloud backup, `adb backup`, or a
 * rooted filesystem dump — is useless without the device's Keystore key. That key cannot leave the
 * device, which is what actually protects the identity that the whole authentication model rests on.
 */
object Identity {
    private const val PREFS = "micro_identity"
    private const val KEY_WRAPPED = "priv_enc"   // base64(iv ‖ ciphertext+tag), AES-GCM under the KS key
    private const val KEY_LEGACY = "priv"        // pre-Keystore plaintext (migrated, then erased)
    private const val KEY_DISCO = "disco_id"     // opaque per-install id broadcast in discovery replies
    private const val KS_ALIAS = "micro_identity_aead"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** The Keystore is temporarily unavailable; the identity is intact and the caller should retry later. */
    class Unavailable(message: String) : IllegalStateException(message)

    @Volatile private var cached: X25519PrivateKeyParameters? = null

    @Synchronized
    fun secret(ctx: Context): X25519PrivateKeyParameters {
        cached?.let { return it }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        prefs.getString(KEY_WRAPPED, null)?.let { wrapped ->
            when (val r = decrypt(wrapped)) {
                is Dec.Ok -> return cache(X25519PrivateKeyParameters(r.bytes, 0))
                // Key permanently gone (e.g. lock-screen reset invalidated it) or blob unrecoverable.
                // Drop it and mint a fresh identity — a forced re-pair is strictly safer than failing
                // every session forever.
                Dec.Lost -> prefs.edit().remove(KEY_WRAPPED).apply()
                // A *transient* Keystore failure (daemon hiccup, provider busy, etc.) must NOT destroy
                // the identity the whole auth model rests on. Bail so the caller retries next time; the
                // wrapped blob is left intact and decrypts fine once the Keystore recovers.
                Dec.Transient -> throw Unavailable("Keystore temporarily unavailable")
            }
        }

        // One-time migration of a legacy plaintext identity, then wipe it from prefs.
        prefs.getString(KEY_LEGACY, null)?.let { legacy ->
            val raw = try { Base64.decode(legacy, Base64.DEFAULT) } catch (_: Exception) { null }
            if (raw != null && raw.size == 32) {
                prefs.edit().putString(KEY_WRAPPED, encrypt(raw)).remove(KEY_LEGACY).apply()
                return cache(X25519PrivateKeyParameters(raw, 0))
            }
            prefs.edit().remove(KEY_LEGACY).apply()
        }

        val priv = X25519PrivateKeyParameters(SecureRandom())
        prefs.edit().putString(KEY_WRAPPED, encrypt(priv.encoded)).apply()
        return cache(priv)
    }

    fun pub(ctx: Context): ByteArray = secret(ctx).generatePublicKey().encoded

    /**
     * Opaque, stable-per-install id advertised in `MICRO!` discovery replies so the client can dedup
     * one phone's multiple IPs (Wi-Fi + USB tether) into a single entry. Deliberately NOT the
     * long-term identity public key: discovery is answered to any unauthenticated `MICRO?` sender, and
     * leaking the crypto identity there would let a passive LAN observer fingerprint/track the device
     * across networks. This id is purely a display-dedup token — pinning still uses the real identity
     * key revealed during SAS pairing (`PS`).
     */
    @Synchronized
    fun discoveryId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DISCO, null)?.let { return it }
        val raw = ByteArray(16)
        SecureRandom().nextBytes(raw)
        val id = Base64.encodeToString(raw, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DISCO, id).apply()
        return id
    }

    private fun cache(k: X25519PrivateKeyParameters): X25519PrivateKeyParameters {
        cached = k
        return k
    }

    /** The AES-256-GCM wrapping key, created once in the Keystore and reused thereafter. */
    private fun aeadKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aeadKey())
        val iv = cipher.iv // GCM IV chosen by the provider
        val ct = cipher.doFinal(plain)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Outcome of a wrapped-identity decrypt: usable bytes, permanently lost, or a transient failure. */
    private sealed interface Dec {
        class Ok(val bytes: ByteArray) : Dec
        object Lost : Dec        // key invalidated or blob corrupt → safe to re-mint the identity
        object Transient : Dec   // unknown / transient Keystore failure → do NOT destroy the identity
    }

    private fun decrypt(wrapped: String): Dec = try {
        val blob = Base64.decode(wrapped, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aeadKey(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LEN))
        Dec.Ok(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN))
    } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
        Dec.Lost        // KS key invalidated (biometric/lock-screen reset) — unrecoverable
    } catch (_: java.security.UnrecoverableKeyException) {
        Dec.Lost        // KS entry corrupt or gone
    } catch (_: javax.crypto.AEADBadTagException) {
        Dec.Lost        // wrong key / tampered blob — this identity can't be recovered
    } catch (_: IllegalArgumentException) {
        Dec.Lost        // malformed base64 / blob
    } catch (_: Exception) {
        Dec.Transient   // KeyStoreException, provider hiccup, etc. — retry, don't reset
    }
}
