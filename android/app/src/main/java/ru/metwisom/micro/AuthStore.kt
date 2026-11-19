package ru.metwisom.micro

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.util.Collections

/**
 * A client awaiting the user's decision, with the pairing code to show. [confirmed] flips once the
 * desktop has verified the code (`PK` seen) — only then may the user tap Allow, which enforces the safe
 * order: the computer compares the codes first, the phone authorizes second.
 */
data class PendingReq(val uuid: String, val ip: String, val name: String, val code: String, val confirmed: Boolean)

/** An approved client (for the trusted-devices list). */
data class TrustedDevice(val uuid: String, val name: String)

/**
 * Tracks which client UUIDs may use the microphone.
 *
 * - Approvals are persisted (uuid set + uuid→name map + pinned key) and survive restarts.
 * - Denials and the pending prompt live only for the current service run ([clearSession]).
 * - [pending] holds the in-flight pairing request so the UI can prompt the user.
 */
object AuthStore {
    private const val PREFS = "micro_auth"
    private const val PREFS_NAMES = "micro_auth_names"
    private const val PREFS_PINS = "micro_pins"
    private const val PREFS_HELO = "micro_helo_ctr"
    private const val KEY_APPROVED = "approved_uuids"
    const val PENDING_TTL_MS = 120_000L

    private val sessionDenied = Collections.synchronizedSet(HashSet<String>())

    @Volatile var pending: PendingReq? = null
        private set
    @Volatile private var pendingAt: Long = 0L
    @Volatile private var dismissedCode: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun names(ctx: Context) = ctx.getSharedPreferences(PREFS_NAMES, Context.MODE_PRIVATE)
    private fun pins(ctx: Context) = ctx.getSharedPreferences(PREFS_PINS, Context.MODE_PRIVATE)
    private fun heloCtrs(ctx: Context) = ctx.getSharedPreferences(PREFS_HELO, Context.MODE_PRIVATE)
    private fun now() = SystemClock.elapsedRealtime()

    /** Persisted HELO anti-replay high-water mark per uuid; survives a service/process restart. */
    fun heloCtr(ctx: Context, uuid: String): Long = heloCtrs(ctx).getLong(uuid, -1L)
    fun setHeloCtr(ctx: Context, uuid: String, ctr: Long) =
        heloCtrs(ctx).edit().putLong(uuid, ctr).apply()

    /** Pinned long-term public key of a paired client (set when the pairing is allowed). */
    fun pin(ctx: Context, uuid: String, pub: ByteArray) {
        val previous = pinnedPub(ctx, uuid)
        pins(ctx).edit().putString(uuid, Base64.encodeToString(pub, Base64.NO_WRAP)).apply()
        // Only a key that really changed retires the HELO anti-replay baseline — see
        // SessionPolicy.pinRetiresHeloMark, which is where that rule is stated and tested.
        if (SessionPolicy.pinRetiresHeloMark(previous, pub)) {
            heloCtrs(ctx).edit().remove(uuid).apply()
        }
    }
    fun pinnedPub(ctx: Context, uuid: String): ByteArray? =
        pins(ctx).getString(uuid, null)?.let { Base64.decode(it, Base64.DEFAULT) }
    private fun approvedSet(ctx: Context) =
        prefs(ctx).getStringSet(KEY_APPROVED, emptySet())!!.toMutableSet()

    fun isApproved(ctx: Context, uuid: String): Boolean = approvedSet(ctx).contains(uuid)

    fun isDenied(uuid: String): Boolean = sessionDenied.contains(uuid)

    /**
     * Record an in-flight pairing so the UI can show the code. Returns true when the prompt changed
     * (a new device, or a rotated code after a wrong entry) so the caller can (re)notify; an unchanged
     * repeat (keepalive PR, ~1/s while the desktop waits for the code) only keeps the prompt alive.
     */
    fun requestPending(req: PendingReq): Boolean {
        val cur = pending
        if (cur?.uuid == req.uuid && cur.code == req.code) {
            if (cur.confirmed != req.confirmed) pending = req
            pendingAt = now() // the desktop is still there → the prompt must not expire under it
            return false
        }
        pending = req
        pendingAt = now()
        dismissedCode = null
        return true
    }

    /**
     * The desktop verified the code (`PK`, repeated ~1/s until answered) → the phone may now offer
     * Allow. Keeps the prompt alive and brings it back if it was dismissed with "Later" or expired.
     * Returns true when the UI should be (re)notified: the Allow button just became available, or the
     * prompt had been hidden.
     */
    fun markConfirmed(req: PendingReq): Boolean {
        val cur = pending
        val hidden = cur == null || cur.uuid != req.uuid || cur.code == dismissedCode
        val flipped = cur != null && cur.uuid == req.uuid && !cur.confirmed
        if (cur == null || cur.uuid != req.uuid) pending = req.copy(confirmed = true)
        else if (!cur.confirmed) pending = cur.copy(confirmed = true)
        pendingAt = now()
        dismissedCode = null
        return hidden || flipped
    }

    /** Pending request, or null once stale (client abandoned pairing) or dismissed for this code. */
    fun pendingFresh(): PendingReq? {
        val p = pending ?: return null
        if (now() - pendingAt > PENDING_TTL_MS) { pending = null; return null }
        if (p.code == dismissedCode) return null
        return p
    }

    /** Hide the prompt for the current code without deciding; a rotated code (or `PK`) shows it again. */
    fun dismissPending() {
        dismissedCode = pending?.code
    }

    fun clearPending() {
        pending = null
        dismissedCode = null
    }

    // @Synchronized: approve() (receiver thread) and removeApproved() (UI thread) both do a
    // read-modify-write of the persisted approved-uuid set, which isn't atomic on SharedPreferences;
    // serializing them stops a concurrent edit from clobbering the other's change.
    @Synchronized
    fun approve(ctx: Context, uuid: String, name: String) {
        sessionDenied.remove(uuid)
        val set = approvedSet(ctx)
        set.add(uuid)
        prefs(ctx).edit().putStringSet(KEY_APPROVED, set).apply()
        names(ctx).edit().putString(uuid, name).apply()
        if (pending?.uuid == uuid) clearPending()
    }

    /** Reject a device for the rest of this service run (cleared by [clearSession]). */
    fun deny(uuid: String) {
        sessionDenied.add(uuid)
        if (pending?.uuid == uuid) clearPending()
    }

    /** Session-scoped state (denials, the prompt) dies with the service. */
    fun clearSession() {
        sessionDenied.clear()
        clearPending()
    }

    /** Persisted trusted devices, for the management UI. */
    fun trustedDevices(ctx: Context): List<TrustedDevice> =
        approvedSet(ctx).map { TrustedDevice(it, names(ctx).getString(it, it) ?: it) }
            .sortedBy { it.name.lowercase() }

    /** Forget a trusted device (so it must be approved again next time). */
    @Synchronized
    fun removeApproved(ctx: Context, uuid: String) {
        val set = approvedSet(ctx)
        set.remove(uuid)
        prefs(ctx).edit().putStringSet(KEY_APPROVED, set).apply()
        names(ctx).edit().remove(uuid).apply()
        pins(ctx).edit().remove(uuid).apply()
        // The HELO mark is deliberately kept. Deleting a device changes neither identity, so
        // re-pairing the same desktop restores the *identical* HELO-auth key — and a baseline wiped
        // here would let a recorded HELO be tagged a second time during that re-pair. Client
        // counters are seeded from the wall clock, so a retained mark can never lock a genuine
        // device out.
    }
}
