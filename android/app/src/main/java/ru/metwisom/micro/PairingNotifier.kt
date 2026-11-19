package ru.metwisom.micro

/**
 * Owns the two heads-up notifications a pairing produces — "new device, here is the code" on `PR`,
 * and "confirmed — tap Allow" on `PK` — and decides when each may be posted.
 *
 * Both are driven by *unauthenticated* packets from the network, so both need the same two
 * properties, and this project has shipped code that missed each of them in turn:
 *
 *  - **Not stormable.** A `PC`→`PR`→`PK` loop re-derives a code and re-flips the confirmation on
 *    every lap, so posting on the change alone is one heads-up per three attacker packets.
 *  - **Never lost.** A post blocked by the gap stays *owed* and goes out on the next packet once
 *    the gap has passed. Dropping it instead is how a fixed timer swallows the first and only
 *    prompt a real pairing ever gets — which for the user is a phone that simply never asks.
 *
 * So a blocked post is deferred by at most [minGapMs], never discarded. The gap itself is waived
 * exactly once for a revealed code: when there is no prompt in front of the user for this device.
 * Waiving it on the *code* having changed instead handed the bypass straight to the attacker, since
 * the code is `sas(clientPub, serverPub, clientNonce)` and the nonce rides in their own `PR`.
 *
 * Owned by the rx thread. No `android.*` imports — exercised directly by the JVM unit tests.
 */
class PairingNotifier(
    private val minGapMs: Long,
    private val staleMs: Long,
) {
    private var revealOwed = false
    private var confirmOwed = false

    /**
     * `PR`: a code was revealed for this device. [codeChanged] is what `AuthStore.requestPending`
     * returned; [promptVisible] says a prompt for *this* device was already in front of the user,
     * and must be read before `requestPending` makes the request the pending one — otherwise it
     * degrades to "has anything been posted since the service started", and a brand-new device that
     * takes the slot after someone else's prompt is silently denied its own.
     */
    fun onReveal(codeChanged: Boolean, promptVisible: Boolean, sinceLastNotifMs: Long): Boolean {
        if (codeChanged) revealOwed = true
        if (!revealOwed) return false
        // SessionPolicy owns the waiver rule; what this adds is that a post it blocks is kept owed
        // rather than dropped, so it rides the next `PR` once the gap has passed. The waiver belongs
        // to the packet that changed the code — an owed post never earns it a second time.
        val mayPost = SessionPolicy.shouldNotifyPairing(codeChanged, promptVisible, sinceLastNotifMs, minGapMs) ||
            sinceLastNotifMs >= minGapMs
        if (!mayPost) return false
        revealOwed = false
        return true
    }

    /**
     * `PK`: the desktop says it verified the code. [confirmFlipped] is what `AuthStore.markConfirmed`
     * returned — Allow just became available, or the prompt was gone. There is no waiver here: the
     * prompt this upgrades is already on screen, and the flip is attacker-triggerable. A prompt that
     * has gone [staleMs] without a post is re-posted, because the heads-up auto-expires while the
     * desktop keeps knocking.
     */
    fun onConfirm(confirmFlipped: Boolean, sinceLastNotifMs: Long): Boolean {
        if (confirmFlipped) confirmOwed = true
        val due = confirmOwed || sinceLastNotifMs >= staleMs
        if (!SessionPolicy.shouldPostConfirm(due, sinceLastNotifMs, minGapMs)) return false
        confirmOwed = false
        return true
    }

    /** The pairing is over (allowed, denied, timed out, slot handed on): nothing is owed any more. */
    fun reset() {
        revealOwed = false
        confirmOwed = false
    }
}
