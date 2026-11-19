package ru.metwisom.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every test drives `due`, the one call the capture loop makes each pass. */
class MuteReporterTest {

    private val t0 = 1_000_000L // elapsedRealtime is never near zero on a running phone

    /** Run one pass every 10 ms over [fromMs, toMs) and return the times a report went out. */
    private fun run(r: MuteReporter, muted: Boolean, fromMs: Long, toMs: Long): List<Long> {
        val sent = ArrayList<Long>()
        var now = fromMs
        while (now < toMs) {
            if (r.due(muted, false, now)) sent.add(now - fromMs)
            now += 10
        }
        return sent
    }

    @Test
    fun anIdleUnmutedPhoneReportsNothing() {
        assertEquals(emptyList<Long>(), run(MuteReporter(), false, t0, t0 + 5_000))
    }

    @Test
    fun aChangeIsReportedAtOnceAndRepeatedSoOneLostDatagramCannotDesyncTheEnds() {
        val r = MuteReporter()
        run(r, false, t0, t0 + 1_000)
        // Muted: a burst of four 250 ms apart, then the 500 ms keepalive takes over.
        assertEquals(listOf(0L, 250L, 500L, 750L, 1250L, 1750L), run(r, true, t0 + 1_000, t0 + 3_000))
        // Unmuted: the same burst, then silence — audio is the liveness signal again.
        assertEquals(listOf(0L, 250L, 500L, 750L), run(r, false, t0 + 3_000, t0 + 6_000))
    }

    /**
     * THE regression: the desktop commands "unmute" while the phone already is unmuted (the two ends
     * had drifted apart). Nothing changes, and unmuted there is no keepalive, so no report ever went
     * out — the desktop retried the command every second for the rest of the session, and the next
     * mute tapped on the phone was undone by that retry.
     */
    @Test
    fun aCommandThatChangesNothingIsStillAnswered() {
        val r = MuteReporter()
        run(r, false, t0, t0 + 1_000)
        assertTrue(r.due(false, true, t0 + 1_000))
        assertEquals("answered once, not as a burst", emptyList<Long>(), run(r, false, t0 + 1_010, t0 + 3_000))
    }

    @Test
    fun answersToAFloodOfCommandsAreRateLimited() {
        val r = MuteReporter()
        run(r, false, t0, t0 + 1_000)
        var sent = 0
        var now = t0 + 1_000
        while (now < t0 + 2_000) { // a command on every pass for one second
            if (r.due(false, true, now)) sent++
            now += 10
        }
        assertEquals(4, sent) // one per 250 ms, however many no-op commands arrive
    }

    @Test
    fun aCommandDuringABurstDoesNotExtendIt() {
        val r = MuteReporter()
        run(r, false, t0, t0 + 1_000)
        assertTrue(r.due(true, true, t0 + 1_000)) // the command that caused the change
        assertFalse(r.due(true, true, t0 + 1_010))
        // Three more from the burst, then keepalives: exactly what a change alone produces.
        assertEquals(listOf(240L, 490L, 740L, 1240L), run(r, true, t0 + 1_010, t0 + 2_500))
    }

    /**
     * The rate limit above covers commands that change nothing. A command that flips the state is a
     * change, and a change is always reported at once — deferring it would clip the start of speech
     * after an unmute, because the desktop drops incoming audio until the report arrives. Pinned
     * here so the documented limit is not read as covering this case too.
     */
    @Test
    fun aCommandThatFlipsTheStateIsReportedAtOnceEveryTime() {
        val r = MuteReporter()
        var muted = false
        var now = t0
        repeat(20) {
            muted = !muted
            assertTrue("flip at $now was not reported", r.due(muted, true, now))
            now += 10
        }
    }

    @Test
    fun aPhoneThatStartsMutedSaysSoOnTheFirstPass() {
        assertTrue(MuteReporter().due(true, false, t0))
    }
}
