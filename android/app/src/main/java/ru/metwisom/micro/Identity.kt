package ru.metwisom.micro

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/** The server's long-term X25519 identity, persisted; pinned by clients at SAS pairing. */
object Identity {
    private const val PREFS = "micro_identity"
    private const val KEY = "priv"

    fun secret(ctx: Context): X25519PrivateKeyParameters {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let {
            return X25519PrivateKeyParameters(Base64.decode(it, Base64.DEFAULT), 0)
        }
        val priv = X25519PrivateKeyParameters(SecureRandom())
        prefs.edit().putString(KEY, Base64.encodeToString(priv.encoded, Base64.NO_WRAP)).apply()
        return priv
    }

    fun pub(ctx: Context): ByteArray = secret(ctx).generatePublicKey().encoded
}
