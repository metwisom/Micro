package ru.metwisom.micro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every test drives `onReveal` / `onConfirm`, the two calls MicService makes, and passes the
 * arguments it passes: `codeChanged` from AuthStore.requestPending, `confirmFlipped` from
 * AuthStore.markConfirmed, and the time since the last post.
 */
class PairingNotifierTest {

    private val gap = 3_000L
    private val stale = 60_000L

    private fun notifier() = PairingNotifier(minGapMs = gap, staleMs = stale)

    // --- PR: a code was revealed -------------------------------------------------------------

    @Test
    fun theFirstPromptGoesOutAtOnce() {
        // No prompt is in front of the user (nothing has ever been posted), so the gap is waived.
        assertTrue(notifier().onReveal(codeChanged = true, promptVisible = false, sinceLastNotifMs = 0L))
    }

    @Test
    fun aKeepaliveRevealThatChangesNothingPostsNothing() {
        val n = notifier()
        assertTrue(n.onReveal(true, false, 0L))
        assertFalse(n.onReveal(false, true, 1_000L))
        assertFalse(n.onReveal(false, true, 30_000L))
    }

    @Test
    fun aCodeRotationCannotStormTheShade() {
        val n = notifier()
        assertTrue(n.onReveal(true, false, 0L))
        // The attacker rotates its nonce, so every packet carries a "new" code. The prompt is up, so
        // each rotation has to wait the gap out — it cannot post per packet.
        assertFalse(n.onReveal(true, true, 100L))
        assertFalse(n.onReveal(true, true, 200L))
        assertFalse(n.onReveal(true, true, 2_999L))
        assertTrue(n.onReveal(true, true, 3_000L))
    }

    /**
     * THE loss this class exists to prevent on the `PR` path: a code rotates while its own prompt is
     * up and inside the gap. The old code *dropped* that post, and every later `PR` keepalive
     * carries the same code — `codeChanged` is false — so nothing ever brought it back and the user
     * was left looking at a stale code they could not match.
     */
    @Test
    fun aPromptBlockedByTheGapIsDelayedNotDropped() {
        val n = notifier()
        assertTrue(n.onReveal(true, false, 0L))
        assertFalse(n.onReveal(true, true, 1_000L)) // rotated, but too soon
        assertFalse(n.onReveal(false, true, 2_000L)) // keepalive: same code
        assertTrue(n.onReveal(false, true, 3_000L)) // it goes out as soon as it may
        assertFalse(n.onReveal(false, true, 6_000L)) // exactly once
    }

    @Test
    fun aDeviceThatTakesAFreedSlotIsAnnouncedAtOnce() {
        val n = notifier()
        assertTrue(n.onReveal(true, false, 0L))
        n.reset() // that pairing ends; another device takes the slot
        // `promptVisible` is about *this* device, so the gap is waived again here. That cannot storm
        // the shade: a slot may only change hands every PAIR_SLOT_OPEN_GAP_MS (see mayOpenSlot). If
        // this were keyed on "has anything been posted since boot" instead, the new device would be
        // gated by a prompt that was never about it.
        assertTrue(n.onReveal(true, false, 1_000L))
    }

    @Test
    fun theWaiverIsSpentOnThePacketThatChangedTheCode() {
        val n = notifier()
        // A code changes while a prompt is up: owed, and gated.
        assertFalse(n.onReveal(true, true, 500L))
        // The owed post rides a later keepalive. `promptVisible` is false there (the prompt expired),
        // which must not waive the gap a second time — otherwise a rotation plus a keepalive posts
        // per packet pair.
        assertFalse(n.onReveal(false, false, 1_000L))
        assertTrue(n.onReveal(false, false, 3_000L))
    }

    @Test
    fun resetDropsAnOwedPrompt() {
        val n = notifier()
        assertFalse(n.onReveal(true, true, 0L)) // owed
        n.reset()
        assertFalse(n.onReveal(false, true, 10_000L)) // the pairing is over; nothing is owed
    }

    // --- PK: the desktop verified the code ----------------------------------------------------

    @Test
    fun theConfirmedHeadsUpIsHeldToTheGapEvenOnTheFirstFlip() {
        val n = notifier()
        // Unlike a revealed code there is no waiver: the prompt this upgrades is already on screen.
        assertFalse(n.onConfirm(confirmFlipped = true, sinceLastNotifMs = 0L))
        assertTrue(n.onConfirm(confirmFlipped = false, sinceLastNotifMs = gap))
    }

    /**
     * THE loss on the `PK` path. A legitimate desktop confirms one second after the code prompt went
     * out. The flip is consumed by the gap, and every later `PK` returns markConfirmed = false — so
     * before this latch, "tap Allow" waited for the prompt to age out instead.
     */
    @Test
    fun aConfirmationBlockedByTheGapIsDelayedNotDropped() {
        val n = notifier()
        assertFalse(n.onConfirm(true, 1_000L))
        assertFalse(n.onConfirm(false, 2_000L))
        assertTrue(n.onConfirm(false, 3_000L))
        assertFalse(n.onConfirm(false, 1_000L)) // and then it stops
    }

    @Test
    fun aConfirmLoopCannotStormTheShade() {
        val n = notifier()
        // PC→PR→PK re-flips the confirmation on every lap. Posts are bounded by the gap alone.
        var posts = 0
        var since = 0L
        repeat(300) { // ~3 s of flooding, one packet per 10 ms
            if (n.onConfirm(true, since)) { posts++; since = 0L } else since += 10L
        }
        assertTrue("expected at most one post per gap, got $posts", posts <= 1)
    }

    @Test
    fun anAgedPromptIsRePostedBecauseTheHeadsUpExpires() {
        val n = notifier()
        assertFalse(n.onConfirm(false, 1_000L)) // nothing owed, nothing aged
        assertTrue(n.onConfirm(false, stale))
        assertFalse(n.onConfirm(false, 0L))
    }

    @Test
    fun resetDropsAnOwedConfirmation() {
        val n = notifier()
        assertFalse(n.onConfirm(true, 0L))
        n.reset()
        assertFalse(n.onConfirm(false, 3_000L))
    }

    @Test
    fun theTwoPathsDoNotConsumeEachOthersDebt() {
        val n = notifier()
        assertFalse(n.onReveal(true, true, 0L)) // a reveal is owed
        assertFalse(n.onConfirm(false, 1_000L)) // a confirm that owes nothing does not take it
        assertTrue(n.onReveal(false, true, 3_000L))
    }
}
