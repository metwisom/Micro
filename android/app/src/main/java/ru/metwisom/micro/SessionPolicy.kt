package ru.metwisom.micro

/**
 * Pure decision rules for the server's session / endpoint / pairing state machine, kept free of
 * `android.*` so they run in plain JVM unit tests. [MicService] applies them; `SessionPolicyTest`
 * locks them.
 */
object SessionPolicy {
    /** HELO anti-replay: the authenticated counter must strictly advance past the last accepted one. */
    fun heloCounterAccepted(ctr: Long, lastAccepted: Long): Boolean = ctr > lastAccepted

    /**
     * May an authenticated HELO (re)bind the audio target to its sender? Yes on cold start, from the
     * endpoint already bound, or once the bound client is stale/unconfirmed. Never while a *different*
     * endpoint is confirmed (proved key possession via CT) and recently seen — that transition is
     * reserved for an authenticated CT, so a duplicated HELO can't disturb a live key holder.
     */
    fun heloMayBind(hasEndpoint: Boolean, sameEndpoint: Boolean, confirmedLive: Boolean): Boolean =
        !hasEndpoint || sameEndpoint || !confirmedLive

    /**
     * The bound client itself coming back with a fresh ephemeral (its app restarted, or it moved to a
     * new port). The HELO is authenticated with the same pinned identity, so this is never a takeover
     * by someone else — let it rebind at once instead of answering BUSY until the old endpoint ages out.
     */
    fun sameClientRekey(uuid: String, boundUuid: String, ephB64: String, boundEphB64: String): Boolean =
        uuid == boundUuid && ephB64 != boundEphB64

    /**
     * May a valid CT from `sameEndpoint` (re)bind the target? Not while a different endpoint is
     * confirmed and live — otherwise a replayed CT sent from another address could yank the stream.
     */
    fun ctMayBind(confirmed: Boolean, sameEndpoint: Boolean, boundLive: Boolean): Boolean =
        !(confirmed && !sameEndpoint && boundLive)

    /** Client liveness: an endpoint whose last authenticated packet is older than the timeout is gone. */
    fun clientLive(nowMs: Long, lastSeenMs: Long, timeoutMs: Long): Boolean = nowMs - lastSeenMs < timeoutMs

    /** Encoder loss % for a client-reported figure: floored so LBRR stays coded, capped for sanity. */
    fun encoderLossPct(reported: Int, fecEnabled: Boolean): Int =
        if (!fecEnabled) 0 else reported.coerceIn(AudioProtocol.FEC_MIN_LOSS_PCT, AudioProtocol.FEC_MAX_LOSS_PCT)

    /**
     * Bitrate for the client-reported loss: shed rate as loss rises (fewer bytes per packet survive
     * a congested link better). Independent of FEC — applies in low-latency / FEC-off modes too.
     */
    fun bitrateFor(reportedLossPct: Int): Int = when {
        reportedLossPct > 15 -> 24000
        reportedLossPct > 5 -> 28000
        else -> AudioProtocol.BITRATE
    }

    /**
     * Pairing key-substitution guard: once a client key was revealed for the in-flight pairing, a later
     * reveal for the same uuid must present the same key. A nonce rotation (wrong code) keeps the key;
     * a LAN host trying to swap its own key under a pairing the user is looking at is rejected.
     */
    fun revealAccepted(previousPub: ByteArray?, revealedPub: ByteArray): Boolean =
        previousPub == null || previousPub.contentEquals(revealedPub)

    /** What a `PC` (commit) does to the single pairing slot. */
    enum class CommitDecision {
        /** Another uuid is mid-pairing: answer `PS` (the identity is public) but leave the slot alone. */
        REVEAL_ONLY,
        /** Same uuid restarting its round (nonce rotation): new commit, revealed key kept. */
        RESTART_SAME,
        /** Slot free (or aged out): brand-new pairing state. */
        FRESH,
    }

    fun pairCommit(
        inFlightUuid: String?,
        progressAtMs: Long,
        openedAtMs: Long,
        nowMs: Long,
        ttlMs: Long,
        maxMs: Long,
        uuid: String,
    ): CommitDecision {
        // The slot is held while the same uuid keeps making progress, but never beyond `maxMs`
        // from when it was opened. Without that ceiling one unauthenticated host on the LAN could
        // refresh the slot forever at roughly one packet per `ttlMs` and no other device could
        // ever pair. A genuine pairing is a person reading four digits: a minute is generous.
        val recent = nowMs - progressAtMs < ttlMs
        val young = nowMs - openedAtMs < maxMs
        val inFlight = inFlightUuid != null && recent && young
        // FRESH only says the slot is free. How often it may actually change hands is decided by
        // `mayOpenSlot` at the call site, on time alone: a cool-down keyed on the uuid was defeated
        // by rotating the uuid, which the sender chooses.
        return when {
            inFlight && inFlightUuid != uuid -> CommitDecision.REVEAL_ONLY
            inFlight -> CommitDecision.RESTART_SAME
            else -> CommitDecision.FRESH
        }
    }

    /**
     * Should a pairing heads-up be posted now?
     *
     * Two ways to get this wrong, and this project has shipped both. Posting on every changed code
     * lets a LAN host storm the shade: the code is `sas(clientPub, serverPub, clientNonce)` and the
     * nonce arrives in the attacker's own plaintext `PR`, so the "code" is a value it picks. Posting
     * only on a fixed timer silently swallows the first and only prompt a real pairing ever gets.
     *
     * So the gap is waived exactly once — when there is no prompt in front of the user for this
     * device — and enforced for every rotation after that.
     */
    fun shouldNotifyPairing(
        codeChanged: Boolean,
        promptAlreadyVisible: Boolean,
        sinceLastNotifMs: Long,
        minGapMs: Long,
    ): Boolean = codeChanged && (!promptAlreadyVisible || sinceLastNotifMs >= minGapMs)

    /**
     * May a *new* pairing slot be opened now?
     *
     * The per-uuid cool-down that used to guard this was keyed on a value the sender chooses, so a
     * flooder simply rotated its uuid. This bounds how often the slot may change hands at all, which
     * nothing in the datagram can influence. It does not make the slot un-occupiable by a determined
     * LAN host — see SECURITY.md, that is a property of having one slot — it bounds the churn.
     */
    fun mayOpenSlot(nowMs: Long, lastOpenedAtMs: Long, minGapMs: Long): Boolean =
        lastOpenedAtMs <= 0L || nowMs - lastOpenedAtMs >= minGapMs

    /**
     * Does pinning [newPub] retire the HELO anti-replay baseline kept for this device?
     *
     * Only a *different* key does. The HELO-auth key is the static×static DH of the two identities,
     * so re-pairing the same public key — which is what deleting a device and adding it back does —
     * leaves it identical, and clearing the baseline there would licence tagging counters this
     * install has already tagged under that key. Two tags under one nonce expose that nonce's
     * one-time Poly1305 key. A key that really changed is a new counter space and starts clean.
     */
    fun pinRetiresHeloMark(previousPub: ByteArray?, newPub: ByteArray): Boolean =
        previousPub != null && !previousPub.contentEquals(newPub)

    /**
     * May the "confirmed — tap Allow" heads-up go out now? [due] says one is owed (the confirmation
     * just flipped, or the prompt has aged); the gap is unconditional, because a PC→PR→PK loop
     * re-flips the confirmation on every lap and would otherwise post once per three packets.
     */
    fun shouldPostConfirm(due: Boolean, sinceLastNotifMs: Long, minGapMs: Long): Boolean =
        due && sinceLastNotifMs >= minGapMs

    /** What a `PK` (desktop verified the code) does. */
    enum class ConfirmDecision {
        /** Nothing in flight for this uuid and it isn't trusted: a bare PK proves nothing. */
        IGNORE,
        /** Already pinned + approved (lost `PA`, or a late retry): answer `PA` again. */
        ALREADY_PAIRED,
        /** Recorded; waiting for the user to tap Allow. */
        PENDING,
        /** Both sides agreed: pin + approve + `PA`. */
        COMPLETE,
    }

    fun pairConfirm(inFlightForUuid: Boolean, hasClientPub: Boolean, userAllowed: Boolean, alreadyTrusted: Boolean): ConfirmDecision =
        when {
            !inFlightForUuid -> if (alreadyTrusted) ConfirmDecision.ALREADY_PAIRED else ConfirmDecision.IGNORE
            !hasClientPub -> ConfirmDecision.IGNORE
            userAllowed -> ConfirmDecision.COMPLETE
            else -> ConfirmDecision.PENDING
        }
}
