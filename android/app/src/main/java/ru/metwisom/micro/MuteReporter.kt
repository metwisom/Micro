package ru.metwisom.micro

/**
 * Decides, once per capture pass, whether the phone's mute state has to be reported to the client.
 *
 * The client never re-asserts its own view of mute (that fought toggles made on the phone), so these
 * reports are the only thing that keeps the two ends agreeing:
 *
 *  - a **change** is reported [repeats] times, [repeatMs] apart, so one lost datagram cannot leave
 *    the ends disagreeing until the next toggle;
 *  - while **muted** no audio is sent, so the report also goes out every [keepaliveMs] to tell the
 *    client the phone is still there;
 *  - a **command** that changes nothing is answered anyway, at most one such answer per [repeatMs].
 *    The client retries a command until it sees that state reported back, and while unmuted there is
 *    neither a change nor a keepalive to do that — so a command for the state the phone was already
 *    in used to be retried for the rest of the session, and the next mute tapped on the phone was
 *    undone by it within a second. A command that *does* change the state needs nothing special: it
 *    is reported as the change it is.
 *
 * Only the capture loop calls this, so nothing is reported while capture is down; the client's
 * `PING`/`PONG` keeps the session alive and the answer follows on the first pass after it returns.
 *
 * Owned by the capture thread. No `android.*` imports — exercised directly by the JVM unit tests.
 */
class MuteReporter(
    private val repeats: Int = DEFAULT_REPEATS,
    private val repeatMs: Long = DEFAULT_REPEAT_MS,
    private val keepaliveMs: Long = DEFAULT_KEEPALIVE_MS,
) {
    private var lastSent = false
    private var lastAtMs = 0L
    private var left = 0

    /**
     * True when a report of [muted] has to be sent on this pass. [commanded] says the client has sent
     * a mute command since the previous pass.
     */
    fun due(muted: Boolean, commanded: Boolean, nowMs: Long): Boolean {
        if (muted != lastSent) {
            lastSent = muted
            left = repeats
            lastAtMs = nowMs - repeatMs // due on this pass
        }
        if (commanded && left == 0) left = 1
        val burst = left > 0 && nowMs - lastAtMs >= repeatMs
        val keepalive = muted && nowMs - lastAtMs >= keepaliveMs
        if (!burst && !keepalive) return false
        if (left > 0) left--
        lastAtMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_REPEATS = 4
        const val DEFAULT_REPEAT_MS = 250L
        const val DEFAULT_KEEPALIVE_MS = 500L
    }
}
