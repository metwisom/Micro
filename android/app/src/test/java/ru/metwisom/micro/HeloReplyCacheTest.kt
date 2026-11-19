package ru.metwisom.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the rule that this class never licenses a second `KEYS` tag for one (HELO-auth key,
 * counter) — see [HeloReplyCache] for why that is a security property and not a micro-optimisation.
 *
 * Every test here asks `decide`, the same call the service makes, rather than a helper beside it.
 */
class HeloReplyCacheTest {

    private fun cache() = HeloReplyCache(minGapMs = 500L)

    private fun assertTagFresh(r: HeloReply) = assertTrue("expected TagFresh, got $r", r is HeloReply.TagFresh)
    private fun assertIgnore(r: HeloReply) = assertTrue("expected Ignore, got $r", r is HeloReply.Ignore)
    private fun replayed(r: HeloReply): String {
        assertTrue("expected Replay, got $r", r is HeloReply.Replay)
        return (r as HeloReply.Replay).datagram
    }

    @Test
    fun anUnseenClientMayBeTagged() {
        assertTagFresh(cache().decide("uuid-a", 100L, 1_000L))
    }

    @Test
    fun aHigherCounterMayBeTagged() {
        val c = cache()
        c.remember("uuid-a", 100L, "reply-100", 1_000L)
        assertTagFresh(c.decide("uuid-a", 101L, 2_000L))
    }

    @Test
    fun anExactRepeatGetsTheStoredBytesAndNoNewTag() {
        val c = cache()
        c.remember("uuid-a", 100L, "KEYS|salt|eph|ctr|tag", 1_000L)
        assertEquals("KEYS|salt|eph|ctr|tag", replayed(c.decide("uuid-a", 100L, 2_000L)))
    }

    /**
     * THE regression this class exists for. A counter at or below the last one answered must never be
     * tagged again, however fresh it looks to the disk-backed mark — that mark regresses after an
     * unclean kill, and used to be wiped entirely by a re-pair, while the HELO-auth key does not
     * rotate. Two tags under one nonce expose that nonce's one-time Poly1305 key.
     */
    @Test
    fun aSupersededCounterIsNeverTaggedAgain() {
        val c = cache()
        c.remember("uuid-a", 100L, "reply-100", 1_000L)
        c.remember("uuid-a", 101L, "reply-101", 2_000L)
        assertIgnore(c.decide("uuid-a", 100L, 3_000L))
        assertIgnore(c.decide("uuid-a", 99L, 3_000L))
        // And the current one still replays its own bytes rather than being re-tagged.
        assertEquals("reply-101", replayed(c.decide("uuid-a", 101L, 3_000L)))
    }

    @Test
    fun theSessionMovingOnDoesNotLicenceANewTagForAnOldCounter() {
        val c = cache()
        c.remember("uuid-a", 100L, "KEYS|saltA|ephA", 1_000L)
        // Another desktop binds; the server re-derives, so a fresh tag for counter 100 would now
        // cover a different body under the same key and nonce.
        c.remember("uuid-b", 500L, "KEYS|saltB|ephB", 2_000L)
        assertEquals("KEYS|saltA|ephA", replayed(c.decide("uuid-a", 100L, 3_000L)))
        // uuid-a re-keys; its old counter is dead for good.
        c.remember("uuid-a", 101L, "KEYS|saltC|ephC", 4_000L)
        assertIgnore(c.decide("uuid-a", 100L, 5_000L))
    }

    @Test
    fun clientsDoNotShareAnEntry() {
        val c = cache()
        c.remember("uuid-a", 100L, "reply-for-a", 1_000L)
        assertTagFresh(c.decide("uuid-b", 100L, 2_000L)) // b has its own key and its own counter space
        c.remember("uuid-b", 100L, "reply-for-b", 2_000L)
        assertEquals("reply-for-a", replayed(c.decide("uuid-a", 100L, 3_000L)))
        assertEquals("reply-for-b", replayed(c.decide("uuid-b", 100L, 3_000L)))
    }

    @Test
    fun repeatsAreRateLimited() {
        val c = cache()
        c.remember("uuid-a", 100L, "reply", 1_000L)
        assertEquals("reply", replayed(c.decide("uuid-a", 100L, 1_600L)))
        assertIgnore(c.decide("uuid-a", 100L, 1_700L)) // a flood is not answered packet for packet
        assertIgnore(c.decide("uuid-a", 100L, 2_099L))
        assertEquals("reply", replayed(c.decide("uuid-a", 100L, 2_100L)))
    }

    @Test
    fun theFirstRepeatWaitsForTheGapAfterTheOriginalSend() {
        val c = cache()
        c.remember("uuid-a", 100L, "reply", 1_000L)
        assertIgnore(c.decide("uuid-a", 100L, 1_400L)) // remember() counts as a send
        assertEquals("reply", replayed(c.decide("uuid-a", 100L, 1_500L)))
    }

    @Test
    fun afterForgetThisCacheAloneNoLongerObjects() {
        val c = cache()
        c.remember("uuid-a", 100L, "reply", 1_000L)
        assertIgnore(c.decide("uuid-a", 50L, 2_000L))
        // This is why the service must not rely on this class alone across un-pairing: the HELO-auth
        // key survives that, and what goes on refusing old counters is the persisted mark — kept by
        // AuthStore.removeApproved, and retired by pin() only for a key that changed
        // (SessionPolicyTest.onlyAKeyThatActuallyChangedRetiresTheHeloMark).
        c.forget("uuid-a")
        assertTagFresh(c.decide("uuid-a", 50L, 3_000L))
    }

    @Test
    fun theCacheIsBoundedAndEvictsTheLeastRecentlyUsed() {
        val c = HeloReplyCache(minGapMs = 0L, maxEntries = 3)
        c.remember("a", 1L, "reply-a", 1_000L)
        c.remember("b", 2L, "reply-b", 1_000L)
        c.remember("c", 3L, "reply-c", 1_000L)
        assertEquals("reply-a", replayed(c.decide("a", 1L, 2_000L))) // touch "a"
        c.remember("d", 4L, "reply-d", 2_000L)
        assertTagFresh(c.decide("b", 2L, 3_000L)) // "b" was evicted, so it looks unseen again
        assertEquals("reply-a", replayed(c.decide("a", 1L, 3_000L)))
        assertEquals("reply-c", replayed(c.decide("c", 3L, 3_000L)))
        assertEquals("reply-d", replayed(c.decide("d", 4L, 3_000L)))
    }

    @Test
    fun evictionCannotBeDrivenPastTheCap() {
        val c = HeloReplyCache(minGapMs = 0L, maxEntries = 2)
        for (i in 1..50) c.remember("uuid-$i", i.toLong(), "reply-$i", 1_000L)
        assertTagFresh(c.decide("uuid-1", 1L, 2_000L))
        assertEquals("reply-49", replayed(c.decide("uuid-49", 49L, 2_000L)))
        assertEquals("reply-50", replayed(c.decide("uuid-50", 50L, 2_000L)))
    }
}
