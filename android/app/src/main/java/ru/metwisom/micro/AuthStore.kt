package ru.metwisom.micro

import android.content.Context
import android.util.Base64
import java.util.Collections

/** A client awaiting the user's approval, with the pairing code to show. */
data class PendingReq(val uuid: String, val ip: String, val name: String, val code: String)

/** An approved client (for the trusted-devices list). */
data class TrustedDevice(val uuid: String, val name: String)

/**
 * Tracks which client UUIDs may use the microphone.
 *
 * - "Always allow" approvals are persisted (uuid set + uuid→name map) and survive restarts.
 * - One-shot approvals and denials live only for the current run (in-memory sets).
 * - [pending] holds the most recent unknown client so the UI can prompt the user.
 */
object AuthStore {
    private const val PREFS = "micro_auth"
    private const val PREFS_NAMES = "micro_auth_names"
    private const val PREFS_PINS = "micro_pins"
    private const val KEY_APPROVED = "approved_uuids"

    private val sessionApproved = Collections.synchronizedSet(HashSet<String>())
    private val sessionDenied = Collections.synchronizedSet(HashSet<String>())

    @Volatile var pending: PendingReq? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun names(ctx: Context) = ctx.getSharedPreferences(PREFS_NAMES, Context.MODE_PRIVATE)
    private fun pins(ctx: Context) = ctx.getSharedPreferences(PREFS_PINS, Context.MODE_PRIVATE)

    /** Pinned long-term public key of a paired client (set when SAS is confirmed). */
    fun pin(ctx: Context, uuid: String, pub: ByteArray) {
        pins(ctx).edit().putString(uuid, Base64.encodeToString(pub, Base64.NO_WRAP)).apply()
    }
    fun pinnedPub(ctx: Context, uuid: String): ByteArray? =
        pins(ctx).getString(uuid, null)?.let { Base64.decode(it, Base64.DEFAULT) }
    private fun approvedSet(ctx: Context) =
        prefs(ctx).getStringSet(KEY_APPROVED, emptySet())!!.toMutableSet()

    fun isApproved(ctx: Context, uuid: String): Boolean =
        sessionApproved.contains(uuid) || approvedSet(ctx).contains(uuid)

    fun isDenied(uuid: String): Boolean = sessionDenied.contains(uuid)

    /** Record an unknown client so the UI can show an approval prompt (once per uuid). */
    fun requestPending(req: PendingReq) {
        if (pending?.uuid != req.uuid) pending = req
    }

    /** Get the pairing code for this client, generating a fresh 4-digit one if needed. */
    fun ensurePending(uuid: String, ip: String, name: String): String {
        val cur = pending
        if (cur?.uuid == uuid) return cur.code
        val code = "%04d".format(java.util.Random().nextInt(10000))
        pending = PendingReq(uuid, ip, name, code)
        return code
    }

    /** One-time code: a wrong attempt rotates it so it can't be brute-forced. */
    fun rotateCode(uuid: String, ip: String, name: String): String {
        val code = "%04d".format(java.util.Random().nextInt(10000))
        pending = PendingReq(uuid, ip, name, code)
        return code
    }

    fun clearPending() {
        pending = null
    }

    fun approve(ctx: Context, uuid: String, name: String, always: Boolean) {
        sessionApproved.add(uuid)
        sessionDenied.remove(uuid)
        if (always) {
            val set = approvedSet(ctx)
            set.add(uuid)
            prefs(ctx).edit().putStringSet(KEY_APPROVED, set).apply()
            names(ctx).edit().putString(uuid, name).apply()
        }
        if (pending?.uuid == uuid) pending = null
    }

    fun deny(uuid: String) {
        sessionDenied.add(uuid)
        if (pending?.uuid == uuid) pending = null
    }

    /** Persisted trusted devices, for the management UI. */
    fun trustedDevices(ctx: Context): List<TrustedDevice> =
        approvedSet(ctx).map { TrustedDevice(it, names(ctx).getString(it, it) ?: it) }
            .sortedBy { it.name.lowercase() }

    /** Forget a trusted device (so it must be approved again next time). */
    fun removeApproved(ctx: Context, uuid: String) {
        sessionApproved.remove(uuid)
        val set = approvedSet(ctx)
        set.remove(uuid)
        prefs(ctx).edit().putStringSet(KEY_APPROVED, set).apply()
        names(ctx).edit().remove(uuid).apply()
        pins(ctx).edit().remove(uuid).apply()
    }
}
