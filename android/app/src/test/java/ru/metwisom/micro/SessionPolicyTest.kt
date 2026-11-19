package ru.metwisom.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the server's session / endpoint / pairing decision rules (see [SessionPolicy]). */
class SessionPolicyTest {

    @Test
    fun heloCounterMustStrictlyAdvance() {
        assertTrue(SessionPolicy.heloCounterAccepted(1, -1))
        assertTrue(SessionPolicy.heloCounterAccepted(1001, 1000))
        assertFalse(SessionPolicy.heloCounterAccepted(1000, 1000)) // replay
        assertFalse(SessionPolicy.heloCounterAccepted(999, 1000))  // regressed
    }

    @Test
    fun heloNeverMovesAConfirmedLiveClient() {
        assertTrue(SessionPolicy.heloMayBind(hasEndpoint = false, sameEndpoint = false, confirmedLive = false)) // cold start
        assertTrue(SessionPolicy.heloMayBind(hasEndpoint = true, sameEndpoint = true, confirmedLive = true))    // keepalive
        assertTrue(SessionPolicy.heloMayBind(hasEndpoint = true, sameEndpoint = false, confirmedLive = false))  // stale → take over
        assertFalse(SessionPolicy.heloMayBind(hasEndpoint = true, sameEndpoint = false, confirmedLive = true))  // BUSY
    }

    @Test
    fun theBoundClientMayComeBackWithAFreshEphemeral() {
        assertTrue(SessionPolicy.sameClientRekey("u1", "u1", "ephB", "ephA"))   // restart / new port
        assertFalse(SessionPolicy.sameClientRekey("u1", "u1", "ephA", "ephA"))  // plain keepalive (handled by heloMayBind)
        assertFalse(SessionPolicy.sameClientRekey("u2", "u1", "ephB", "ephA"))  // someone else → BUSY rules apply
    }

    @Test
    fun ctMovesTheTargetOnlyWhenTheBoundClientIsGone() {
        assertTrue(SessionPolicy.ctMayBind(confirmed = false, sameEndpoint = false, boundLive = true))  // unconfirmed → first CT binds
        assertTrue(SessionPolicy.ctMayBind(confirmed = true, sameEndpoint = true, boundLive = true))    // same endpoint
        assertTrue(SessionPolicy.ctMayBind(confirmed = true, sameEndpoint = false, boundLive = false))  // old one timed out
        assertFalse(SessionPolicy.ctMayBind(confirmed = true, sameEndpoint = false, boundLive = true))  // replayed from elsewhere
    }

    @Test
    fun livenessUsesTheTimeout() {
        assertTrue(SessionPolicy.clientLive(nowMs = 5000, lastSeenMs = 2001, timeoutMs = 3000))
        assertFalse(SessionPolicy.clientLive(nowMs = 5000, lastSeenMs = 2000, timeoutMs = 3000))
    }

    @Test
    fun theConfirmedHeadsUpIsOwedButNeverFasterThanTheGap() {
        val gap = 3_000L
        // Nothing owed -> nothing posted.
        assertFalse(SessionPolicy.shouldPostConfirm(false, 999_999L, gap))
        // Owed, and the last post is old enough.
        assertTrue(SessionPolicy.shouldPostConfirm(true, gap, gap))
        // Owed but too soon: a PC->PR->PK loop re-flips `confirmed` every lap, and posting on the flip
        // alone was one heads-up per three packets. That the post then *stays* owed is PairingNotifier's
        // job, and PairingNotifierTest is where it is checked; this function only answers "may it now?".
        assertFalse(SessionPolicy.shouldPostConfirm(true, 0L, gap))
        assertFalse(SessionPolicy.shouldPostConfirm(true, gap - 1, gap))
    }

    /**
     * The regression this guards: `pin()` used to clear the HELO mark on every pair, so deleting a
     * trusted desktop and pairing it again handed back counters the phone had already answered under
     * an unchanged HELO-auth key — a recorded `HELO` could then be tagged a second time.
     */
    @Test
    fun onlyAKeyThatActuallyChangedRetiresTheHeloMark() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 1).toByte() }
        // First pairing: there is no baseline to retire.
        assertFalse(SessionPolicy.pinRetiresHeloMark(null, a))
        // Re-pairing the same identity (delete the device, add it back) keeps the same HELO-auth key.
        assertFalse(SessionPolicy.pinRetiresHeloMark(a, a))
        assertFalse(SessionPolicy.pinRetiresHeloMark(a.copyOf(), a))
        // A different identity is a different key and therefore a fresh counter space.
        assertTrue(SessionPolicy.pinRetiresHeloMark(a, b))
    }

    @Test
    fun fecLossIsFlooredAndCappedOnlyWhenEnabled() {
        assertEquals(0, SessionPolicy.encoderLossPct(reported = 0, fecEnabled = false))
        assertEquals(0, SessionPolicy.encoderLossPct(reported = 50, fecEnabled = false))
        assertEquals(AudioProtocol.FEC_MIN_LOSS_PCT, SessionPolicy.encoderLossPct(reported = 0, fecEnabled = true))
        assertEquals(12, SessionPolicy.encoderLossPct(reported = 12, fecEnabled = true))
        assertEquals(AudioProtocol.FEC_MAX_LOSS_PCT, SessionPolicy.encoderLossPct(reported = 90, fecEnabled = true))
    }

    @Test
    fun bitrateShedsWithReportedLossRegardlessOfFec() {
        assertEquals(AudioProtocol.BITRATE, SessionPolicy.bitrateFor(0))
        assertEquals(AudioProtocol.BITRATE, SessionPolicy.bitrateFor(5))
        assertEquals(28000, SessionPolicy.bitrateFor(6))
        assertEquals(24000, SessionPolicy.bitrateFor(16))
    }

    @Test
    fun revealKeepsTheFirstKeyOfAnInFlightPairing() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertTrue(SessionPolicy.revealAccepted(previousPub = null, revealedPub = a))
        assertTrue(SessionPolicy.revealAccepted(previousPub = a, revealedPub = a.copyOf())) // nonce rotation
        assertFalse(SessionPolicy.revealAccepted(previousPub = a, revealedPub = b))         // substitution
    }

    @Test
    fun commitRevealsToEveryoneButOnlyOneUuidHoldsTheSlot() {
        val ttl = 10_000L
        val max = 60_000L
        // Slot free → fresh pairing.
        assertEquals(
            SessionPolicy.CommitDecision.FRESH,
            SessionPolicy.pairCommit(null, 0, 0, 1000, ttl, max, "a"),
        )
        // Same uuid mid-round (wrong code → nonce rotation) restarts, keeping its revealed key.
        assertEquals(
            SessionPolicy.CommitDecision.RESTART_SAME,
            SessionPolicy.pairCommit("a", 1000, 1000, 2000, ttl, max, "a"),
        )
        // A different uuid gets PS (identity is public) but cannot take the slot: a paired desktop can
        // still bootstrap, an attacker cannot hijack / spam the prompt.
        assertEquals(
            SessionPolicy.CommitDecision.REVEAL_ONLY,
            SessionPolicy.pairCommit("a", 1000, 1000, 2000, ttl, max, "b"),
        )
        // Abandoned pairing ages out → the slot is free again.
        assertEquals(
            SessionPolicy.CommitDecision.FRESH,
            SessionPolicy.pairCommit("a", 1000, 1000, 1000 + ttl, ttl, max, "b"),
        )
    }

    @Test
    fun aRefreshedSlotStillExpiresSoOneHostCannotBlockPairingForever() {
        val ttl = 10_000L
        val max = 60_000L
        // An unauthenticated host holds the slot by re-sending `PC` just inside the TTL. Each packet
        // refreshes `progressAt`, so the TTL alone never expires it: before the absolute cap existed,
        // roughly one packet every 9 s locked every other device out of pairing indefinitely.
        var now = 0L
        val opened = 0L
        var progress = 0L
        while (now < max - ttl) {
            now += ttl - 1_000L
            assertEquals(
                "attacker still holds the slot at t=$now",
                SessionPolicy.CommitDecision.REVEAL_ONLY,
                SessionPolicy.pairCommit("attacker", progress, opened, now, ttl, max, "victim"),
            )
            progress = now // the attacker's own packet refreshes it
        }
        // Past the absolute lifetime the slot is forfeit however fresh the last packet was, so a real
        // device can open its own pairing.
        assertEquals(
            SessionPolicy.CommitDecision.FRESH,
            SessionPolicy.pairCommit("attacker", max, opened, max, ttl, max, "victim"),
        )
    }

    @Test
    fun theFirstPromptIsNeverLostAndRotationsCannotStorm() {
        val gap = 3_000L
        // Nothing changed -> nothing posted, whatever the timing.
        assertFalse(SessionPolicy.shouldNotifyPairing(false, false, 999_999L, gap))
        // A new code with no prompt in front of the user: post at once. This is the case a plain
        // timer used to swallow, losing the only prompt a legitimate pairing gets.
        assertTrue(SessionPolicy.shouldNotifyPairing(true, false, 0L, gap))
        // A rotation while a prompt is already up must wait out the gap. The code is derived from a
        // nonce the sender chooses, so without this a LAN host posts one heads-up per packet.
        assertFalse(SessionPolicy.shouldNotifyPairing(true, true, 0L, gap))
        assertFalse(SessionPolicy.shouldNotifyPairing(true, true, gap - 1, gap))
        assertTrue(SessionPolicy.shouldNotifyPairing(true, true, gap, gap))
    }

    @Test
    fun slotHandoverIsRateLimitedByTimeAloneNotByAnythingTheSenderPicks() {
        val gap = 10_000L
        assertTrue("first ever open", SessionPolicy.mayOpenSlot(1_000L, 0L, gap))
        assertFalse(SessionPolicy.mayOpenSlot(5_000L, 1_000L, gap))
        assertFalse(SessionPolicy.mayOpenSlot(10_999L, 1_000L, gap))
        assertTrue(SessionPolicy.mayOpenSlot(11_000L, 1_000L, gap))
        // The point of the rewrite: the decision takes no uuid, no address and no attacker-supplied
        // field, so rotating any of them cannot get a faster handover.
    }

    @Test
    fun confirmRequiresBothTheRevealedKeyAndTheUsersAllow() {
        // Nothing in flight: a bare PK proves nothing — unless the uuid is already trusted (lost PA).
        assertEquals(SessionPolicy.ConfirmDecision.IGNORE, SessionPolicy.pairConfirm(false, false, false, false))
        assertEquals(SessionPolicy.ConfirmDecision.ALREADY_PAIRED, SessionPolicy.pairConfirm(false, false, false, true))
        // In flight but the key was never revealed → ignore.
        assertEquals(SessionPolicy.ConfirmDecision.IGNORE, SessionPolicy.pairConfirm(true, false, true, false))
        // Key revealed, user hasn't allowed yet → PENDING (Allow becomes available on the phone).
        assertEquals(SessionPolicy.ConfirmDecision.PENDING, SessionPolicy.pairConfirm(true, true, false, false))
        // Key revealed + Allow → complete.
        assertEquals(SessionPolicy.ConfirmDecision.COMPLETE, SessionPolicy.pairConfirm(true, true, true, false))
    }
}
