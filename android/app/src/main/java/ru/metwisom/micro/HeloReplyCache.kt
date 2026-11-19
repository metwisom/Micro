package ru.metwisom.micro

/** What to do with an authenticated `HELO` whose counter has been checked against the disk mark. */
sealed class HeloReply {
    /** Re-send exactly these bytes. No tag is computed, so no nonce can be reused. */
    data class Replay(val datagram: String) : HeloReply()

    /** This counter has never been answered: derive/serve the session and tag it. */
    object TagFresh : HeloReply()

    /** Stay silent — a superseded counter, or a repeat arriving faster than the rate limit. */
    object Ignore : HeloReply()
}

/**
 * The single authority on whether a `KEYS` tag may be computed for a given (client, counter).
 *
 * The `KEYS` tag is ChaCha20-Poly1305 under the long-term HELO-auth key with the HELO counter as the
 * nonce. That key does not rotate — it is the static×static DH of two pinned identities — so tagging
 * one counter twice over two different bodies is Poly1305 one-time-key reuse: two tags under one
 * nonce expose that nonce's one-time key and let an attacker forge tags for that counter (not the
 * long-term key — but there is no reason to hand out either). The bodies differ whenever the
 * live session has been re-derived in between, which a client restart or a second desktop binding
 * does routinely.
 *
 * Guarding that with "is this counter newer than the last one we accepted?" is not enough, because
 * the answer can regress: an unclean process death falls back to a disk mark that lags, and a re-pair
 * used to clear the mark entirely. So this class keeps the last counter it actually *answered* and
 * refuses to tag anything at or below it, replaying the stored datagram for an exact repeat and
 * staying silent otherwise.
 *
 * Replays are also rate-limited: without that, one captured `HELO` gives any host on the LAN a
 * packet-for-packet send loop on the phone.
 *
 * No `android.*` imports — exercised directly by the JVM unit tests.
 */
class HeloReplyCache(
    private val minGapMs: Long = DEFAULT_MIN_GAP_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private class Entry(val ctr: Long, val reply: String, var lastSentAtMs: Long)

    // Access-ordered so the eviction below drops the client that has gone longest without being
    // answered. Only ever touched from the rx thread, like `heloKeys` beside it in MicService.
    private val byUuid = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    /**
     * Decide what this `HELO` gets. Call after the tag has verified and before anything is sent;
     * [remember] records the answer once it has actually been built.
     */
    fun decide(uuid: String, ctr: Long, nowMs: Long): HeloReply {
        val e = byUuid[uuid] ?: return HeloReply.TagFresh
        if (ctr > e.ctr) return HeloReply.TagFresh
        if (ctr < e.ctr) return HeloReply.Ignore // superseded: tagging it again would reuse the nonce
        if (nowMs - e.lastSentAtMs < minGapMs) return HeloReply.Ignore
        e.lastSentAtMs = nowMs
        return HeloReply.Replay(e.reply)
    }

    /** Record the datagram just sent for [ctr]. Replaces any earlier answer for this client. */
    fun remember(uuid: String, ctr: Long, reply: String, nowMs: Long) {
        byUuid[uuid] = Entry(ctr, reply, nowMs)
    }

    /**
     * Drop this client's cached answer, on un-pairing.
     *
     * Un-pairing does not retire the HELO-auth key — re-pairing the same desktop restores the very
     * same key — so this is only safe because the *persisted* mark is kept across un-pairing (see
     * AuthStore.removeApproved) and still refuses every counter this entry covered. Tearing a session
     * down must not call this at all.
     */
    fun forget(uuid: String) {
        byUuid.remove(uuid)
    }

    companion object {
        /** A genuine client retries its `HELO` once a second; anything faster is not worth answering. */
        const val DEFAULT_MIN_GAP_MS = 500L

        /**
         * Only a client whose `HELO` tag verified can put an entry here, so growth is already bounded
         * by the trusted-device list. Capped anyway, to match the `heloKeys` cache it sits beside and
         * so that the bound is a property of this class rather than of its caller.
         */
        const val DEFAULT_MAX_ENTRIES = 16
    }
}
