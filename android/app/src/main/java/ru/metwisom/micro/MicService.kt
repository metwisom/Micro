package ru.metwisom.micro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** The client's UDP return address, grouped so the capture loop reads addr+port as one volatile. */
private data class Endpoint(val addr: InetAddress, val port: Int)

/**
 * Foreground service that captures the mic and streams Opus frames to the desktop client over UDP.
 * Lives independently of the UI so the bridge keeps running with the screen off.
 *
 * Threads and ownership:
 *  - `mic-rx` owns every session/endpoint mutation (HELO, CT, timeouts, re-key, device removal). Other
 *    threads never write that state directly: the capture thread and the UI enqueue a [resetRequests]
 *    entry that the rx loop applies at the top of its next iteration (≤ 1 s, the socket timeout). The
 *    capture thread only *reads* endpoint/crypto and treats a stale endpoint as inactive; nonce
 *    counters live inside [CryptoSession], so they change hands together with the key.
 *  - `mic-tx` (capture → encode → encrypt → send) owns the recorder, the encoder and the wake/multicast
 *    lock transitions.
 *  - Pairing state is guarded by [pairLock] because the rx thread and the UI (Allow/Deny) both touch it.
 */
class MicService : Service() {

    private val running = AtomicBoolean(false)
    private val captureLock = Any()        // serializes off-main-thread capture restarts
    private val pairLock = Any()           // rx thread + UI thread share the pairing state
    @Volatile private var captureStop = false
    private var socket: DatagramSocket? = null
    private var captureThread: Thread? = null
    private var receiverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var endpoint: Endpoint? = null
    @Volatile private var lastClientSeen: Long = 0L          // SystemClock.elapsedRealtime()
    // True once the current audio target proved it holds the session key (sent a valid encrypted CT).
    // A confirmed live target is only ever moved by another authenticated CT or by the bound client's
    // own authenticated HELO carrying a fresh ephemeral — never by anyone else's HELO.
    @Volatile private var clientConfirmed: Boolean = false
    @Volatile private var crypto: CryptoSession? = null
    @Volatile private var cryptoUuid: String = ""
    @Volatile private var cryptoClientEph: String = ""
    @Volatile private var keysBody: String? = null          // "KEYS|<salt>|<serverEphPub>" of the live session
    // Resets the rx thread must apply (see class doc): RESET_TIMEOUT = the capture loop saw the client
    // time out (re-validated before acting); RESET_REKEY = the audio nonce space is nearly exhausted
    // (applied unconditionally); anything else = the uuid whose session must be dropped (deleted in
    // Settings). A queue, so two requests inside one rx iteration are both honored.
    private val resetRequests = ConcurrentLinkedQueue<String>()
    // HELO-auth keys (static×static DH → HKDF) cached per uuid, keyed by the pinned identity they were
    // derived from so a re-pair re-derives. rx thread only; wiped on shutdown after both loops joined.
    private val heloKeys = LinkedHashMap<String, Pair<ByteArray, ByteArray>>()
    // HELO anti-replay high-water marks per uuid (in-memory authoritative; disk across restarts).
    // Concurrent: a re-pair (UI thread, Allow) invalidates the mark for its uuid.
    private val heloMarks = ConcurrentHashMap<String, Long>()
    /**
     * Last `KEYS` datagram sent per uuid, so a repeated HELO is answered with the same bytes rather
     * than a freshly computed tag (see [HeloReplyCache]). rx thread only.
     */
    private val heloReplies = HeloReplyCache()
    @Volatile private var targetLossPct: Int = 0            // reported by the client
    @Volatile private var muteCommanded: Boolean = false    // rx thread sets, capture thread answers
    private val pairNotifier = PairingNotifier(PAIR_NOTIF_MIN_MS, AuthStore.PENDING_TTL_MS / 2)

    // In-flight SAS pairing (one client at a time). All access under [pairLock].
    private var pairUuid: String? = null
    private var pairCommit: ByteArray? = null
    private var pairClientPub: ByteArray? = null
    private var pairName: String = ""
    private var pairIp: String = ""
    private var pairCode: String = ""
    private var pairProgressAt: Long = 0L
    private var pairConfirmed = false   // PK seen: the desktop verified the SAS matched
    private var pairAllowed = false     // the user tapped Allow on the phone
    private var pairNotifAt: Long = 0L  // when the pairing heads-up was last posted (it auto-expires)
    private var pairOpenedAt: Long = 0L // when the current pairing slot was opened (bounds its life)
    private var pairLastOpenedAt: Long = 0L // when the slot last changed hands (bounds the churn)

    @Volatile private var notifKey: String = ""             // last rendered FGS notification variant

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Only reachable if the system revived us after process death. A microphone foreground
            // service may not be (re)started from the background on Android 14+, and MainActivity
            // auto-starts the bridge on launch anyway — bow out instead of crashing in startForeground().
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_CAPTURE -> {
                // Apply new capture settings (source / input device) without rebinding the socket.
                // Delivering this intent *creates* the service if it was not running, so a stopped
                // service must stop itself again rather than linger at service priority.
                if (running.get()) restartCapture() else stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (running.compareAndSet(false, true)) {
            instance = this
            lastError = 0
            if (!startForegroundSafely()) { failStart(ERR_FOREGROUND); return START_NOT_STICKY }
            if (!start()) { failStart(ERR_PORT); return START_NOT_STICKY }
            setStreamingLocks(streaming = false) // idle: answer discovery with the screen off, no CPU lock
            isRunning = true
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    /** Startup failed after `running` was claimed: undo it, record why for the UI, and stop. */
    private fun failStart(code: Int) {
        lastError = code
        running.set(false)
        isRunning = false
        instance = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundSafely(): Boolean = try {
        createChannels()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException / SecurityException on 12+ when the app is in the
        // background or lacks the mic permission at this instant.
        Log.e(TAG, "startForeground failed", e)
        false
    }

    private fun start(): Boolean {
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1000
                // DSCP EF: Wi-Fi WMM puts the packets in the voice access category (AC_VO).
                try { trafficClass = AudioProtocol.DSCP_EF_TOS } catch (_: Exception) {}
                bind(InetSocketAddress(AudioProtocol.PORT))
            }
        } catch (e: Exception) {
            Log.e(TAG, "bind ${AudioProtocol.PORT} failed", e)
            return false
        }
        socket = sock
        receiverThread = Thread({ receiveLoop(sock) }, "mic-rx").apply { start() }
        startCapture(sock)
        return true
    }

    private fun startCapture(sock: DatagramSocket) {
        captureStop = false
        captureThread = Thread({ captureLoop(sock) }, "mic-tx").apply { start() }
    }

    private fun restartCapture() {
        val sock = socket ?: return
        // Off the main/binder thread (join blocks up to one read cycle → ANR risk), serialized so rapid
        // setting changes can't interleave. The join is deliberately unbounded: a second capture thread
        // must never run concurrently with the first (they'd share the encoder/session state).
        Thread({
            synchronized(captureLock) {
                captureStop = true
                captureThread?.let { t -> try { t.join() } catch (_: InterruptedException) {} }
                if (running.get()) startCapture(sock)
            }
        }, "mic-restart").start()
    }

    // --- Receive loop (owns session state) ---------------------------------------------------------

    private fun receiveLoop(sock: DatagramSocket) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val buf = ByteArray(2048)   // ≥ MTU: a HELO with a long UTF-8 device name must not truncate
        val pkt = DatagramPacket(buf, buf.size)
        val ctx = applicationContext
        var lastDiscoveryReply = 0L
        while (running.get()) {
            applyResetRequest()
            try {
                sock.receive(pkt)
                val len = pkt.length
                // Authenticated control is binary — handle it from raw bytes before the lossy UTF-8
                // decode below would mangle the counter/ciphertext.
                if (len >= 7 && buf[0] == 'C'.code.toByte() && buf[1] == 'T'.code.toByte() && buf[2] == '|'.code.toByte()) {
                    handleControl(sock, buf, len, pkt)
                    continue
                }
                val text = String(buf, 0, len, Charsets.UTF_8)
                when {
                    text.startsWith("PC|") -> synchronized(pairLock) { handlePairCommit(sock, pkt, text, ctx) }
                    text.startsWith("PR|") -> synchronized(pairLock) { handlePairReveal(sock, pkt, text, ctx) }
                    text.startsWith("PK|") -> synchronized(pairLock) { handlePairConfirm(sock, pkt, text, ctx) }
                    text.startsWith("HELO|") -> handleSession(sock, pkt, text, ctx)
                    text == AudioProtocol.DISCOVERY_PROBE -> {
                        // Reply at most ~10/s total: caps using the phone as a UDP reflector under a
                        // spoofed-source flood (the reply is larger than the probe). A real client
                        // broadcasts only every 5 s, so discovery is unaffected.
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastDiscoveryReply >= 100) {
                            lastDiscoveryReply = nowMs
                            // Opaque per-install dedup id (NOT the identity key — discovery answers any
                            // unauthenticated prober). Pinning still uses the real key revealed at pairing.
                            val sid = Identity.discoveryId(ctx)
                            val reply = "${AudioProtocol.ANNOUNCE_PREFIX}${AudioProtocol.PORT}|${Build.MODEL.replace('|', ' ')}|$sid"
                                .toByteArray(Charsets.UTF_8)
                            sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                        }
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // loop and re-check running flag
            } catch (e: Identity.Unavailable) {
                // Transient Keystore failure. The client retries every second and the identity caches on
                // first success, so this self-heals; just don't spam the log.
                Log.w(TAG, "identity temporarily unavailable, will retry: ${e.message}")
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "rx error", e)
            }
        }
    }

    /** Apply the resets posted by other threads (see class doc). Runs on the rx thread only. */
    private fun applyResetRequest() {
        var any = false
        while (true) {
            val r = resetRequests.poll() ?: break
            any = true
            when (r) {
                RESET_TIMEOUT -> {
                    // Re-validate: a HELO/CT may have landed since the capture loop looked.
                    val now = SystemClock.elapsedRealtime()
                    if (endpoint != null && !SessionPolicy.clientLive(now, lastClientSeen, AudioProtocol.CLIENT_TIMEOUT_MS)) resetClient()
                }
                RESET_REKEY -> resetClient() // the next HELO derives a fresh key with a fresh seq space
                else -> {
                    if (cryptoUuid == r) resetClient()
                    heloKeys.remove(r)
                    heloMarks.remove(r)
                    heloReplies.forget(r)
                }
            }
        }
        if (any) refreshNotification()
    }

    private fun requestReset(kind: String) {
        if (!resetRequests.contains(kind)) resetRequests.add(kind)
    }

    // --- Pairing (SAS with commitment; the phone authorizes with Allow) ----------------------------
    // All three handlers run under pairLock.

    private fun clearPairing() {
        pairUuid = null; pairCommit = null; pairClientPub = null; pairName = ""; pairIp = ""; pairCode = ""
        pairConfirmed = false; pairAllowed = false; pairOpenedAt = 0L
        pairNotifier.reset()
    }

    // PC|uuid|name|H(clientPub‖nonce) — client commits, server reveals its long-term pub.
    private fun handlePairCommit(sock: DatagramSocket, pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        val uuid = p.getOrNull(1)?.trim().orEmpty()
        if (uuid.isEmpty()) return
        val name = p.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() } ?: (pkt.address.hostAddress ?: "?")
        if (AuthStore.isDenied(uuid)) {
            // Deny refuses a *pairing*. A desktop that is already pinned and approved still needs `PS`
            // to learn which phone it reached before it can send its authenticated HELO; answering
            // AUTH:DENIED here left it knocking forever. That mattered because the uuid travels in
            // plaintext: a LAN host could raise a prompt under a trusted uuid, and the user tapping
            // Deny — the right reaction — locked their own desktop out until the service restarted.
            // `PS` is public and touches no pairing state, so it is safe to send.
            val trusted = AuthStore.pinnedPub(ctx, uuid) != null && AuthStore.isApproved(ctx, uuid)
            if (trusted) reply(sock, "PS|" + Base64.encodeToString(Identity.pub(ctx), Base64.NO_WRAP), pkt)
            else reply(sock, AudioProtocol.AUTH_DENIED, pkt)
            return
        }
        val commit = try { Base64.decode(p.getOrNull(3)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        if (commit == null || commit.size != 32) return
        // May throw Identity.Unavailable — before any state is touched.
        val ps = "PS|" + Base64.encodeToString(Identity.pub(ctx), Base64.NO_WRAP)

        // `PC` is unauthenticated plaintext. Every sender gets `PS` (the identity is public, and a paired
        // desktop needs it to learn which phone answered), but only one uuid at a time holds the pairing
        // slot: a *different* uuid can't interrupt an in-flight pairing (anti-hijack / anti-dialog-spam);
        // the same uuid restarts its round after a wrong code to rotate to a fresh SAS.
        val now = SystemClock.elapsedRealtime()
        when (SessionPolicy.pairCommit(
            pairUuid, pairProgressAt, pairOpenedAt, now, PAIR_INFLIGHT_TTL_MS, PAIR_SLOT_MAX_MS, uuid,
        )) {
            SessionPolicy.CommitDecision.REVEAL_ONLY -> { reply(sock, ps, pkt); return }
            SessionPolicy.CommitDecision.FRESH -> {
                // Bound how often the slot may change hands. The cool-down this replaces was keyed
                // on the uuid, which the sender picks, so rotating it defeated the bar outright.
                if (!SessionPolicy.mayOpenSlot(now, pairLastOpenedAt, PAIR_SLOT_OPEN_GAP_MS)) {
                    reply(sock, ps, pkt); return
                }
                clearPairing(); pairOpenedAt = now; pairLastOpenedAt = now
            }
            SessionPolicy.CommitDecision.RESTART_SAME -> {} // keep pairClientPub (see revealAccepted)
        }
        pairUuid = uuid
        pairCommit = commit
        pairName = name
        pairIp = pkt.address.hostAddress ?: "?"
        pairProgressAt = now
        pairConfirmed = false // the code changes with the nonce → any earlier decision is void
        pairAllowed = false
        reply(sock, ps, pkt)
    }

    // PR|uuid|clientPub|nonce — client reveals; verify against the commit, then show the SAS on the phone.
    private fun handlePairReveal(sock: DatagramSocket, pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        val uuid = p.getOrNull(1)?.trim().orEmpty()
        if (uuid.isEmpty()) return
        if (AuthStore.isDenied(uuid)) {
            reply(sock, AudioProtocol.AUTH_DENIED, pkt)
            if (uuid == pairUuid) { clearPairing(); cancelPairingNotification() }
            return
        }
        if (uuid != pairUuid) return
        val commit = pairCommit ?: return
        val clientPub = try { Base64.decode(p.getOrNull(2)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        if (clientPub == null || clientPub.size != 32) return
        val clientNonce = try { Base64.decode(p.getOrNull(3)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        if (clientNonce == null || clientNonce.size != 16) return
        if (!CryptoSession.sha256(clientPub + clientNonce).contentEquals(commit)) return  // commitment violation
        if (!SessionPolicy.revealAccepted(pairClientPub, clientPub)) return              // key substitution

        val code = CryptoSession.sas(clientPub, Identity.pub(ctx), clientNonce) // may throw Unavailable
        pairClientPub = clientPub
        pairCode = code
        pairIp = pkt.address.hostAddress ?: "?"
        pairProgressAt = SystemClock.elapsedRealtime()
        val req = PendingReq(uuid, pairIp, pairName, code, pairConfirmed)
        // Read before `requestPending` makes this the pending request — afterwards it is true for
        // every device, and the waiver would degrade to "nothing has been posted since boot".
        val visible = AuthStore.pendingFresh()?.uuid == uuid && pairNotifAt != 0L
        val changed = AuthStore.requestPending(req)
        val sinceNotif = SystemClock.elapsedRealtime() - pairNotifAt
        if (pairNotifier.onReveal(changed, visible, sinceNotif)) showPairingNotification(req)
    }

    // PK|uuid — the desktop verified the SAS. Pin + approve only once the user has also tapped Allow.
    private fun handlePairConfirm(sock: DatagramSocket, pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        val uuid = p.getOrNull(1)?.trim().orEmpty()
        if (uuid.isEmpty()) return
        if (AuthStore.isDenied(uuid)) { reply(sock, AudioProtocol.AUTH_DENIED, pkt); return }
        val inFlight = uuid == pairUuid
        val trusted = AuthStore.pinnedPub(ctx, uuid) != null && AuthStore.isApproved(ctx, uuid)
        when (SessionPolicy.pairConfirm(inFlight, pairClientPub != null, pairAllowed, trusted)) {
            SessionPolicy.ConfirmDecision.IGNORE -> return
            SessionPolicy.ConfirmDecision.ALREADY_PAIRED -> { reply(sock, AudioProtocol.PAIR_APPROVED, pkt); return }
            SessionPolicy.ConfirmDecision.PENDING -> {
                pairConfirmed = true
                val now = SystemClock.elapsedRealtime()
                pairProgressAt = now
                // Keeps the prompt alive while the desktop waits, and brings it back after "Later" or
                // after the prompt aged out — the phone's user must act now. The heads-up auto-expires,
                // so re-post it while the desktop keeps knocking.
                val req = PendingReq(uuid, pairIp, pairName, pairCode, true)
                // A PC→PR→PK loop with one commit flips `confirmed` on every lap, so posting on the
                // flip alone was an unthrottled heads-up per three packets — see PairingNotifier for
                // how the flip is held to the gap without being dropped.
                if (pairNotifier.onConfirm(AuthStore.markConfirmed(req), now - pairNotifAt)) {
                    showPairingNotification(req)
                }
                reply(sock, AudioProtocol.AUTH_PENDING, pkt)
            }
            SessionPolicy.ConfirmDecision.COMPLETE -> {
                pairConfirmed = true
                completePairing(ctx, uuid, pairClientPub!!)
                reply(sock, AudioProtocol.PAIR_APPROVED, pkt)
            }
        }
    }

    /** Both parties agreed (desktop: code matched; phone: Allow) → pin the key and trust the uuid. */
    private fun completePairing(ctx: Context, uuid: String, clientPub: ByteArray) {
        AuthStore.pin(ctx, uuid, clientPub)
        AuthStore.approve(ctx, uuid, pairName)
        // A re-pair binds this uuid to a (possibly new) identity, so drop the in-memory HELO mark and
        // fall back to the persisted one: pin() cleared it if the key changed and kept it if not.
        // The cached HELO key re-derives on its own when the pin changed.
        heloMarks.remove(uuid)
        clearPairing()
        cancelPairingNotification()
    }

    /** UI: the user tapped Allow. Completes immediately if the desktop already confirmed the code. */
    private fun allowPairingInternal(uuid: String) = synchronized(pairLock) {
        if (uuid != pairUuid) return@synchronized
        pairAllowed = true
        val pub = pairClientPub
        if (pairConfirmed && pub != null) completePairing(applicationContext, uuid, pub)
    }

    private fun denyPairingInternal(uuid: String) = synchronized(pairLock) {
        if (uuid == pairUuid) clearPairing()
        cancelPairingNotification()
    }

    // --- Session --------------------------------------------------------------------------------

    // HELO|uuid|name|<clientEphPub>|<ctr>|<tag> — a paired device opens/keeps a session.
    private fun handleSession(sock: DatagramSocket, pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        val uuid = p.getOrNull(1)?.trim().orEmpty()
        if (uuid.isEmpty()) return
        val name = p.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() } ?: (pkt.address.hostAddress ?: "?")
        val ephB64 = p.getOrNull(3)?.trim().orEmpty()
        val clientEph = try { Base64.decode(ephB64, Base64.DEFAULT) } catch (_: Exception) { null }
        if (clientEph == null || clientEph.size != 32) return

        // No session-denial check here: a denial answers an unauthenticated *pairing attempt* for a
        // uuid (PC/PR/PK). A HELO that verifies under the pinned identity below is the genuine paired
        // device, which a LAN host spoofing a pairing prompt for its uuid must not be able to lock out;
        // revoking a trusted device is done by deleting it in Settings.
        val pinned = AuthStore.pinnedPub(ctx, uuid)
        if (pinned == null || !AuthStore.isApproved(ctx, uuid)) {
            reply(sock, AudioProtocol.REPAIR, pkt)
            return
        }

        // Authenticate the HELO before it can do anything. The tag is keyed by the static×static DH of
        // the two pinned identities, so only the genuine paired client can produce it. AAD = the packet
        // prefix (everything up to the trailing `|<tag>`), re-encoded as UTF-8 exactly as the client
        // signed it (the device name may be non-ASCII).
        val ctrBytes = try { Base64.decode(p.getOrNull(4)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        val mac = try { Base64.decode(p.getOrNull(5)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        if (ctrBytes == null || ctrBytes.size != 8 || mac == null || mac.size != 16) return
        var ctr = 0L
        for (b in ctrBytes) ctr = (ctr shl 8) or (b.toLong() and 0xff)
        val aad = text.substringBeforeLast("|").toByteArray(Charsets.UTF_8)
        val key = heloKeyFor(ctx, uuid, pinned)
        if (!MessageDigest.isEqual(CryptoSession.heloTag(key, ctr, aad), mac)) return  // forged / wrong identity
        // Before anything else: may this counter be tagged at all? HeloReplyCache is the authority,
        // because the question is not "is this counter new" but "have we ever put a Poly1305 tag
        // under this (HELO-auth key, counter)". Those differ whenever the disk mark has regressed.
        val decision = heloReplies.decide(uuid, ctr, SystemClock.elapsedRealtime())
        if (decision is HeloReply.Replay) {
            // The genuine client asking again because our KEYS went missing. Answer with the stored
            // bytes and bind nothing: staying silent here let an on-path relay that won the endpoint
            // with its own copy of the same HELO keep it, since the real client could never be
            // answered. No tag is computed, so no nonce can repeat.
            reply(sock, decision.datagram, pkt)
            return
        }
        if (decision is HeloReply.Ignore) {
            Log.w(TAG, "HELO for $uuid at $ctr is superseded or rate-limited; not answering")
            return
        }

        // Anti-replay high-water mark per uuid, persisted so a captured HELO can't be replayed even
        // across a service/process restart. Survives a re-pair with the same key (see AuthStore.pin).
        val lastCtr = heloMarks[uuid] ?: AuthStore.heloCtr(ctx, uuid)
        if (!SessionPolicy.heloCounterAccepted(ctr, lastCtr)) {
            // Nothing cached for this counter (a restart lost it), and the disk mark says it is not
            // new. Refuse rather than tag: the mark is exactly the thing that can have regressed.
            Log.w(TAG, "HELO counter for $uuid not advancing: $ctr <= $lastCtr")
            return
        }
        heloMarks[uuid] = ctr
        // Persisted on every accept, before the answer is built. This used to be throttled to one
        // write per 30 s, which left the disk mark up to thirty counters behind: an unclean kill
        // then resumed below counters already tagged, and tagging one of them again would repeat a
        // Poly1305 nonce. `apply()` is asynchronous and coalescing, so ~1 write/s costs little.
        val nowMs = SystemClock.elapsedRealtime()
        AuthStore.setHeloCtr(ctx, uuid, ctr)

        // Never let a HELO move a confirmed, recently-live *other* client off its endpoint — that
        // transition happens only via an authenticated CT (handleControl) or, as here, the bound
        // client's own authenticated HELO with a fresh ephemeral (restart / new port). Anyone else is
        // told BUSY so it doesn't mistake silence for a forgotten pairing.
        val ep = endpoint
        val sameEndpoint = ep != null && ep.addr == pkt.address && ep.port == pkt.port
        val confirmedLive = clientConfirmed && SessionPolicy.clientLive(nowMs, lastClientSeen, AudioProtocol.CLIENT_TIMEOUT_MS)
        val mayBind = SessionPolicy.heloMayBind(ep != null, sameEndpoint, confirmedLive) ||
            SessionPolicy.sameClientRekey(uuid, cryptoUuid, ephB64, cryptoClientEph)
        if (!mayBind) {
            reply(sock, AudioProtocol.BUSY, pkt)
            return
        }

        // (Re)derive when there's no session yet, the client changed, or its ephemeral changed. The
        // session owns its seq and control counters, so publishing it is one write — nothing can
        // observe a new key with stale counters, and the capture thread always numbers a frame from
        // the very object it encrypts it with.
        if (crypto == null || cryptoUuid != uuid || cryptoClientEph != ephB64) {
            val setup = try {
                CryptoSession.forServer(Identity.secret(ctx), pinned, clientEph)
            } catch (e: IllegalStateException) {
                if (e is Identity.Unavailable) throw e
                Log.w(TAG, "HELO from $uuid carried a non-contributory ephemeral, dropped")
                return
            }
            keysBody = "KEYS|${setup.saltB64}|${setup.serverEphB64}"
            cryptoUuid = uuid
            cryptoClientEph = ephB64
            crypto = setup.session
        }

        if (!sameEndpoint) clientConfirmed = false // a new endpoint must re-prove via CT
        lastClientSeen = nowMs                     // before the endpoint: never publish a stale-looking one
        endpoint = Endpoint(pkt.address, pkt.port)
        connectedName = name
        // KEYS is authenticated per HELO: tag over "KEYS|salt|eph|ctr" under the HELO-auth key, nonce
        // byte0 = NONCE_KEYS with *this* HELO's counter (unique — the counter strictly advances), so a
        // LAN host can neither forge a KEYS to burn client CPU nor displace a genuine re-key.
        sendKeys(sock, pkt, key, ctr, ctrBytes, uuid)
    }

    /**
     * Answer one authenticated HELO with the live session's `KEYS`, tagged under the HELO-auth key
     * with *this* HELO's counter. Pure reply: it binds nothing, so it is safe to call for a repeated
     * counter as well as a fresh one.
     */
    private fun sendKeys(
        sock: DatagramSocket,
        pkt: DatagramPacket,
        key: ByteArray,
        ctr: Long,
        ctrBytes: ByteArray,
        uuid: String,
    ) {
        val body = keysBody ?: return
        val signed = "$body|${Base64.encodeToString(ctrBytes, Base64.NO_WRAP)}"
        val tag = CryptoSession.keysTag(key, ctr, signed.toByteArray(Charsets.UTF_8))
        val datagram = "$signed|${Base64.encodeToString(tag, Base64.NO_WRAP)}"
        // Stored so an exact repeat of this counter replays these bytes instead of tagging again.
        heloReplies.remember(uuid, ctr, datagram, SystemClock.elapsedRealtime())
        reply(sock, datagram, pkt)
    }

    /** rx thread only. */
    private fun resetClient() {
        endpoint = null
        clientConfirmed = false
        crypto = null
        cryptoUuid = ""
        cryptoClientEph = ""
        keysBody = null
    }

    /** HELO-auth key for [uuid], cached and re-derived only when the pinned identity actually changes. */
    private fun heloKeyFor(ctx: Context, uuid: String, pinned: ByteArray): ByteArray {
        heloKeys[uuid]?.let { (pub, key) -> if (pinned.contentEquals(pub)) return key }
        val k = CryptoSession.heloKey(Identity.secret(ctx), pinned)
        if (heloKeys.size >= HELO_CACHE_MAX) {
            val eldest = heloKeys.keys.first()
            heloKeys.remove(eldest)?.let { java.util.Arrays.fill(it.second, 0) }
        }
        heloKeys[uuid] = pinned.copyOf() to k
        return k
    }

    private fun reply(sock: DatagramSocket, text: String, pkt: DatagramPacket) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try { sock.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port)) } catch (_: Exception) {}
    }

    // Authenticated control from the client: "CT|" + counter(4 BE) + sealed [type, payload…]. A valid
    // CT proves the sender holds the session key and (re)binds the audio target (besides the bound
    // client's own re-keying HELO, the only way the target moves once a client is confirmed).
    private fun handleControl(sock: DatagramSocket, buf: ByteArray, len: Int, pkt: DatagramPacket) {
        val cs = crypto ?: return
        val ctr = ((buf[3].toInt() and 0xff) shl 24) or ((buf[4].toInt() and 0xff) shl 16) or
            ((buf[5].toInt() and 0xff) shl 8) or (buf[6].toInt() and 0xff)
        if (!cs.recvCtrFresh(ctr)) return // replay / out-of-order
        val pt = cs.openControl(AudioProtocol.DIR_C2S, ctr, buf, 7, len - 7) ?: return

        // If a *different* endpoint is confirmed and still live, ignore this packet WITHOUT consuming
        // its counter, so a replayed CT from another address can't yank a live stream. A genuine IP
        // change is honored once the old endpoint times out (≤ CLIENT_TIMEOUT_MS).
        val now = SystemClock.elapsedRealtime()
        val ep = endpoint
        val sameEndpoint = ep != null && ep.addr == pkt.address && ep.port == pkt.port
        val boundLive = SessionPolicy.clientLive(now, lastClientSeen, AudioProtocol.CLIENT_TIMEOUT_MS)
        if (!SessionPolicy.ctMayBind(clientConfirmed, sameEndpoint, boundLive)) return

        cs.commitRecvCtr(ctr)
        lastClientSeen = now
        endpoint = Endpoint(pkt.address, pkt.port)
        clientConfirmed = true
        if (pt.isEmpty()) return
        when (pt[0].toInt()) {
            AudioProtocol.CT_MUTE -> if (pt.size >= 2) {
                val m = pt[1].toInt() != 0
                if (m != muted) { muted = m; refreshNotification() }
                muteCommanded = true // answered even when nothing changed — see MuteReporter
            }
            AudioProtocol.CT_BYE -> { resetClient(); refreshNotification() }
            AudioProtocol.CT_LOSS -> if (pt.size >= 2) targetLossPct = (pt[1].toInt() and 0xff).coerceIn(0, 100)
            AudioProtocol.CT_PING -> if (pt.size >= 9) {
                // Echo the token back, authenticated, as PONG — the client's RTT can't be spoofed, and the
                // PONG doubles as the client's liveness signal while muted / silent.
                val pong = ByteArray(9)
                pong[0] = AudioProtocol.CT_PONG.toByte()
                System.arraycopy(pt, 1, pong, 1, 8)
                sendCtl(sock, pkt.address, pkt.port, pong)
            }
        }
    }

    // Seal a typed control message under the session key and send it (server → client).
    private fun sendCtl(sock: DatagramSocket, addr: InetAddress?, port: Int, body: ByteArray) {
        val cs = crypto ?: return
        val ctr = cs.nextSendCtr()
        val sealed = try { cs.sealControl(AudioProtocol.DIR_S2C, ctr, body) } catch (_: Exception) { return }
        val pkt = ByteArray(7 + sealed.size)
        pkt[0] = 'C'.code.toByte(); pkt[1] = 'T'.code.toByte(); pkt[2] = '|'.code.toByte()
        pkt[3] = (ctr ushr 24).toByte(); pkt[4] = (ctr ushr 16).toByte()
        pkt[5] = (ctr ushr 8).toByte(); pkt[6] = ctr.toByte()
        System.arraycopy(sealed, 0, pkt, 7, sealed.size)
        try { sock.send(DatagramPacket(pkt, pkt.size, addr, port)) } catch (_: Exception) {}
    }

    // --- Capture ---------------------------------------------------------------------------------

    /** Owns the capture thread: runs [runCapture] and rebuilds it with backoff after a hard audio error. */
    private fun captureLoop(sock: DatagramSocket) {
        // Audio-urgent scheduling → minimal timing jitter on the capture→encode→send loop, so the
        // client can run a smaller jitter buffer (lower end-to-end latency) without underruns.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var backoffMs = 1000L
        while (running.get() && !captureStop) {
            val clean = runCapture(sock)
            if (clean || !running.get() || captureStop) break
            lastError = ERR_CAPTURE
            setStreamingLocks(streaming = false)
            if (isClientConnected) { isClientConnected = false; refreshNotification() }
            sleepQuiet(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(8000L)
        }
    }

    /** Sleep in small steps so a stop request is honored promptly. */
    private fun sleepQuiet(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (running.get() && !captureStop && SystemClock.elapsedRealtime() < until) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * One capture session: build the recorder + encoder, then loop until stopped. Returns true on a
     * requested stop, false on a hard error (the caller retries with backoff). The recorder runs only
     * while a live client exists (plus a short grace) — otherwise the mic, the "mic in use" indicator
     * and the CPU wake lock are released.
     */
    private fun runCapture(sock: DatagramSocket): Boolean {
        val ctx = applicationContext
        val minBuf = AudioRecord.getMinBufferSize(
            AudioProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { Log.e(TAG, "getMinBufferSize failed: $minBuf"); return false }
        // 2× the HAL minimum: one extra buffer of headroom against overruns when the thread is
        // preempted, at no cost to latency (we read 10 ms at a time regardless).
        val recBuf = maxOf(minBuf * 2, AudioProtocol.FRAME_SAMPLES * 2 * 4)

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(AppSettings.audioSource(ctx))
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioProtocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(recBuf)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            record.release()
            return false
        }

        // Optionally pin a specific input device (mic); Bluetooth SCO inputs need the SCO link up.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val routeCleanup = routeInput(am, record, AppSettings.inputDeviceId(ctx))
        // Since Android 10 a higher-priority capture (a phone call, the assistant) does not fail
        // our AudioRecord — the OS just feeds it zeros. Without this the desktop receives perfectly
        // encoded silence and neither end reports anything wrong.
        val silenceCleanup = watchSilencing(am, record)

        // Low-latency mode forces CELT-only (RESTRICTED_LOWDELAY) — lowest delay, but no FEC.
        val lowLatency = AppSettings.lowLatency(ctx)
        val opusApp = if (lowLatency) OpusNative.APPLICATION_RESTRICTED_LOWDELAY else OpusNative.APPLICATION_VOIP
        val enc = OpusNative.create(
            AudioProtocol.SAMPLE_RATE, AudioProtocol.CHANNELS, opusApp, AudioProtocol.BITRATE, AudioProtocol.OPUS_COMPLEXITY
        )
        if (enc == 0L) {
            Log.e(TAG, "Opus encoder create failed")
            silenceCleanup()
            routeCleanup()
            record.release()
            return false
        }
        val fecEnabled = AppSettings.fecEnabled(ctx) && !lowLatency
        OpusNative.setFec(enc, if (fecEnabled) 1 else 0)
        if (lastError == ERR_CAPTURE) lastError = 0 // recorder + encoder are back
        var appliedLoss = -1
        val muteReporter = MuteReporter()

        // Spectrum analyzer for the UI VU ring. Triple-buffered: the UI reads the last-published
        // buffer while the capture thread writes the next one — the single writer can't lap the single
        // reader within one frame, so the published buffer is never overwritten mid-read.
        val analyzer = Spectrum()
        val specBufs = Array(3) { FloatArray(VU_BANDS) }
        var specIdx = 0

        val pcm = ShortArray(AudioProtocol.FRAME_SAMPLES)
        val opusBuf = ByteArray(4000)                 // raw Opus payload
        val out = ByteArray(AUDIO_HDR + 4000 + 16)    // type + seq + encrypted payload (+ AEAD tag)
        val packet = DatagramPacket(out, out.size)    // reused per send

        var recording = false
        var idleSince = -1L
        var clean = true
        try {
            while (running.get() && !captureStop) {
                val now = SystemClock.elapsedRealtime()
                val ep = endpoint
                val cs = crypto
                val active = ep != null && cs != null &&
                    SessionPolicy.clientLive(now, lastClientSeen, AudioProtocol.CLIENT_TIMEOUT_MS)
                if (active != isClientConnected) {
                    isClientConnected = active
                    setStreamingLocks(streaming = active)
                    refreshNotification()
                }
                if (!active) {
                    for (s in specBufs) java.util.Arrays.fill(s, 0f)
                    // Client timed out → ask the rx thread to drop the session so a reconnect re-keys.
                    if (ep != null && !SessionPolicy.clientLive(now, lastClientSeen, AudioProtocol.CLIENT_TIMEOUT_MS)) {
                        requestReset(RESET_TIMEOUT)
                    }
                    if (recording) {
                        if (idleSince < 0) idleSince = now
                        if (now - idleSince >= AudioProtocol.IDLE_CAPTURE_STOP_MS) {
                            try { record.stop() } catch (_: Exception) {}
                            recording = false
                        } else if (record.read(pcm, 0, pcm.size) < 0) {
                            // Dead recorder during the grace window → rebuild instead of spinning on it.
                            Log.e(TAG, "AudioRecord.read failed while idle"); clean = false; break
                        }
                    } else {
                        sleepQuiet(200)
                    }
                    continue
                }
                idleSince = -1L
                if (!recording) {
                    try { record.startRecording() } catch (e: Exception) { Log.e(TAG, "startRecording threw", e) }
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.e(TAG, "startRecording failed"); clean = false; break
                    }
                    recording = true
                    if (lastError == ERR_CAPTURE) lastError = 0 // recovered
                }

                // Adapt FEC strength + bitrate to the client-reported loss. The floor keeps LBRR coded
                // at 0 % reported loss (libopus disables in-band FEC entirely at loss 0); bitrate shedding
                // follows the raw report regardless of FEC.
                val reported = targetLossPct
                val want = SessionPolicy.encoderLossPct(reported, fecEnabled)
                if (reported != appliedLoss) {
                    appliedLoss = reported
                    OpusNative.setPacketLoss(enc, want)
                    OpusNative.setBitrate(enc, SessionPolicy.bitrateFor(reported))
                }

                // Fill a full 10 ms frame (AudioRecord may return partial reads). A negative result is
                // a hard error (e.g. the pinned input device was unplugged → ERROR_DEAD_OBJECT).
                var off = 0
                var readErr = false
                while (off < pcm.size && running.get() && !captureStop) {
                    val n = record.read(pcm, off, pcm.size - off)
                    if (n < 0) { readErr = true; break }
                    if (n == 0) break
                    off += n
                }
                if (readErr) { Log.e(TAG, "AudioRecord.read failed"); clean = false; break }
                if (off < pcm.size) continue

                // Near the 32-bit audio-nonce wrap → ask the rx thread for a fresh-key handshake (the
                // next HELO derives a new session whose seq starts at 0). Until it lands (≤ 1 s) frames
                // are skipped rather than ever reusing a nonce under this key.
                if (cs!!.seqNearWrap()) {
                    requestReset(RESET_REKEY)
                    continue
                }

                // VU spectrum for the UI ring — only while the app UI is in front (the ring isn't
                // visible otherwise): skips ~100 FFTs/s on the realtime thread with the screen off.
                val target = specBufs[specIdx]
                specIdx = (specIdx + 1) % specBufs.size
                if (muted || !uiVisible) java.util.Arrays.fill(target, 0f) else analyzer.compute(pcm, target)
                spectrum = target

                // Mute is synced both ways via the authenticated control channel. While muted we stop
                // sending audio, so the report doubles as the keepalive that tells the client the
                // server has not vanished. When a report is due — on a change, as that keepalive, or
                // in answer to a command — is MuteReporter's decision.
                val commanded = muteCommanded
                if (commanded) muteCommanded = false
                if (muteReporter.due(muted, commanded, now)) {
                    sendCtl(sock, ep!!.addr, ep.port, byteArrayOf(AudioProtocol.CT_MUTE.toByte(), (if (muted) 1 else 0).toByte()))
                }
                if (muted) continue   // stop transmission while muted

                sendAudioFrame(sock, cs, enc, pcm, opusBuf, out, packet, ep!!)
            }
        } finally {
            try { if (recording) record.stop() } catch (_: Exception) {}
            record.release()
            silenceCleanup()
            routeCleanup()
            OpusNative.destroy(enc)
        }
        return clean
    }

    /**
     * Watch for the OS silencing our capture for a higher-priority app (API 29+) and surface it as
     * [ERR_SILENCED] so the notification and the app both say why the line went quiet. Returns the
     * undo; a no-op below API 29, where the platform cannot report it.
     */
    private fun watchSilencing(am: AudioManager, record: AudioRecord): () -> Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return {}
        val sessionId = record.audioSessionId
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val mine = configs?.firstOrNull { it.clientAudioSessionId == sessionId }
                val silenced = mine?.isClientSilenced == true
                val was = lastError == ERR_SILENCED
                if (silenced && !was) { lastError = ERR_SILENCED; refreshNotification() }
                else if (!silenced && was) { lastError = 0; refreshNotification() }
            }
        }
        val undo: () -> Unit = {
            try { am.unregisterAudioRecordingCallback(cb) } catch (_: Exception) {}
            if (lastError == ERR_SILENCED) lastError = 0
        }
        val noop: () -> Unit = {}
        return try {
            am.registerAudioRecordingCallback(cb, null)
            undo
        } catch (_: Exception) {
            noop
        }
    }

    /** Pin the chosen input device; for a Bluetooth SCO mic also bring the SCO link up. Returns the undo. */
    @Suppress("DEPRECATION")
    private fun routeInput(am: AudioManager, record: AudioRecord, wantId: Int): () -> Unit {
        val noop: () -> Unit = {}
        if (wantId == AppSettings.DEVICE_AUTO) return noop
        val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == wantId } ?: return noop
        record.preferredDevice = dev
        if (dev.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return noop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { am.setCommunicationDevice(dev) } catch (e: Exception) { Log.w(TAG, "setCommunicationDevice", e) }
            val undo: () -> Unit = { try { am.clearCommunicationDevice() } catch (_: Exception) {} }
            return undo
        }
        try { am.startBluetoothSco(); am.isBluetoothScoOn = true } catch (e: Exception) { Log.w(TAG, "startBluetoothSco", e) }
        val undo: () -> Unit = { try { am.isBluetoothScoOn = false; am.stopBluetoothSco() } catch (_: Exception) {} }
        return undo
    }

    /**
     * Encode one captured 10 ms frame, encrypt it straight into [out] (type byte + seq prefix +
     * ChaCha20-Poly1305 ciphertext, no intermediate copy) and send it to [tgt]. The seq is drawn from
     * the session it is encrypted with, so the audio nonce (key, seq) can never repeat. Silently skips
     * the frame on an encode/encrypt error or a transient socket failure — UDP audio is best-effort
     * and the next frame is ~10 ms away.
     */
    private fun sendAudioFrame(
        sock: DatagramSocket, cs: CryptoSession, enc: Long,
        pcm: ShortArray, opusBuf: ByteArray, out: ByteArray,
        packet: DatagramPacket, tgt: Endpoint,
    ) {
        val payloadLen = OpusNative.encode(enc, pcm, AudioProtocol.FRAME_SAMPLES, opusBuf, opusBuf.size)
        if (payloadLen <= 0) return

        val seq = cs.nextSeq()
        out[0] = AudioProtocol.PKT_AUDIO.toByte()  // never collides with the ASCII-prefixed control messages
        out[1] = (seq ushr 24).toByte()
        out[2] = (seq ushr 16).toByte()
        out[3] = (seq ushr 8).toByte()
        out[4] = seq.toByte()
        val ctLen = try {
            cs.encryptInto(opusBuf, payloadLen, seq, out, AUDIO_HDR)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt error", e); return
        }

        try {
            packet.setData(out, 0, AUDIO_HDR + ctLen)
            packet.address = tgt.addr
            packet.port = tgt.port
            sock.send(packet)
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "tx error", e)
        }
    }

    // --- Locks / lifecycle plumbing ---------------------------------------------------------------

    /**
     * Streaming: hold a CPU wake lock so the 10 ms capture loop never dozes (a user-started bridge).
     * Idle: release it and instead hold a Wi-Fi multicast lock so `MICRO?` broadcasts still reach us
     * with the screen off (the APF filter drops broadcast frames otherwise); no CPU is kept awake, the
     * radio wakes it per packet.
     */
    @Synchronized
    private fun setStreamingLocks(streaming: Boolean) {
        if (!running.get()) return
        if (streaming) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Micro:mic").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
            multicastLock = null
        } else {
            wakeLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
            wakeLock = null
            if (multicastLock == null) {
                try {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wm?.createMulticastLock("Micro:discovery")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (e: Exception) { Log.w(TAG, "multicast lock", e) }
            }
        }
    }

    @Synchronized
    private fun releaseLocks() {
        wakeLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        wakeLock = null
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        multicastLock = null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PAIR_ID, getString(R.string.notif_pair_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val connected = isClientConnected
        val name = connectedName.ifEmpty { getString(R.string.device_generic) }
        val text = when {
            // Silencing wins over the normal states: the stream looks healthy from both ends, so
            // the notification is the only place the user can find out why it went quiet.
            lastError == ERR_SILENCED -> getString(R.string.err_silenced)
            !connected -> getString(R.string.notif_waiting)
            muted -> getString(R.string.notif_muted_fmt, name)
            else -> getString(R.string.notif_connected_fmt, name)
        }
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(if (connected && muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.notif_stop), stop).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    /** Re-render the foreground notification when its content actually changed (any thread). */
    private fun refreshNotification() {
        if (!running.get()) return
        // lastError belongs in the key: the silencing detector changes nothing else, so without it
        // the dedup below swallowed the one notification that tells the user why the line is quiet.
        val key = "${isClientConnected}|${muted}|${connectedName}|${lastError}"
        if (key == notifKey) return
        notifKey = key
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    /** Heads-up with the pairing code so a locked/backgrounded phone still shows it; tap opens the app. */
    private fun showPairingNotification(req: PendingReq) {
        pairNotifAt = SystemClock.elapsedRealtime()
        val text = if (req.confirmed) getString(R.string.notif_pair_confirmed_fmt, req.name, req.code)
        else getString(R.string.notif_pair_fmt, req.name, req.code)
        val n = Notification.Builder(this, CHANNEL_PAIR_ID)
            .setContentTitle(getString(R.string.new_device))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(AuthStore.PENDING_TTL_MS)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_PAIR_ID, n) } catch (_: Exception) {}
    }

    private fun cancelPairingNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(NOTIF_PAIR_ID) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running.set(false)
        captureStop = true
        instance = null
        isRunning = false
        isClientConnected = false
        muted = false
        spectrum = FloatArray(VU_BANDS)
        connectedName = ""
        val cs = crypto
        resetClient() // the rx loop is winding down: nothing else mutates session state now
        synchronized(pairLock) { clearPairing() }
        AuthStore.clearSession()
        cancelPairingNotification()
        // The marks are written on every accept; this re-write just makes sure the asynchronous
        // apply() queue holds their final values before the process is allowed to go away.
        for ((uuid, ctr) in heloMarks) AuthStore.setHeloCtr(applicationContext, uuid, ctr)
        try { socket?.close() } catch (_: Exception) {}
        releaseLocks()
        // Closing the socket unblocks the receive loop and the flags above stop both loops. Join them
        // off the main thread so onDestroy() returns immediately, and zeroize the session key only
        // AFTER both loops have stopped touching it.
        val ct = captureThread
        val rt = receiverThread
        Thread({
            ct?.join(2000); rt?.join(2000)
            cs?.destroy()
            for ((_, v) in heloKeys) java.util.Arrays.fill(v.second, 0)
            heloKeys.clear()
        }, "mic-shutdown").start()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MicService"
        private const val CHANNEL_ID = "mic_bridge"
        private const val CHANNEL_PAIR_ID = "pairing"
        private const val NOTIF_ID = 1
        private const val NOTIF_PAIR_ID = 2
        const val ACTION_STOP = "ru.metwisom.micro.STOP"
        const val ACTION_RESTART_CAPTURE = "ru.metwisom.micro.RESTART_CAPTURE"

        // Startup / runtime error codes surfaced to the UI (0 = none).
        const val ERR_PORT = 1          // UDP port already in use
        const val ERR_FOREGROUND = 2    // startForeground() refused (background start / permission)
        const val ERR_CAPTURE = 3       // AudioRecord failed (retrying with backoff)
        const val ERR_SILENCED = 4      // the OS silenced our mic for a higher-priority app

        private const val RESET_TIMEOUT = "*"
        private const val RESET_REKEY = "!"
        private const val AUDIO_HDR = 5             // type byte + 4-byte seq
        private const val HELO_CACHE_MAX = 16

        // How long an in-flight SAS pairing keeps the single pairing slot against an unauthenticated PC
        // for a different uuid. Genuine PC/PR/PK steps arrive ~1/s and refresh it; an abandoned
        // pairing frees the slot after this.
        private const val PAIR_INFLIGHT_TTL_MS = 10_000L

        // Absolute lifetime of one pairing slot, refreshes included (see SessionPolicy.pairCommit).
        private const val PAIR_SLOT_MAX_MS = 60_000L

        // Minimum interval between two slot handovers, whoever asks.
        private const val PAIR_SLOT_OPEN_GAP_MS = 10_000L

        // Minimum gap between two pairing heads-up notifications.
        private const val PAIR_NOTIF_MIN_MS = 3_000L

        @Volatile private var instance: MicService? = null

        // --- Service status (service-owned; UI reads these) -----------------------------------
        @Volatile var isRunning = false
            private set
        @Volatile var isClientConnected = false
            private set
        @Volatile var connectedName: String = ""
            private set
        @Volatile var muted = false
            private set
        /** Last startup/runtime error code (ERR_*), 0 when none. */
        @Volatile var lastError: Int = 0
            private set

        // Log-spaced spectrum bands of the last frame (0..1 each), for the equalizer VU ring.
        const val VU_BANDS = 28
        @Volatile var spectrum: FloatArray = FloatArray(VU_BANDS)
            private set

        // True while the app UI is in the foreground — gates the (purely cosmetic) VU-spectrum FFT.
        @Volatile var uiVisible = false
            private set

        // --- UI inputs (the only sanctioned way the UI mutates the service) -------------------
        fun toggleMuted() {
            muted = !muted
            instance?.refreshNotification()
        }
        fun setUiVisible(visible: Boolean) { uiVisible = visible }

        /** The user tapped Allow for the pending pairing request. */
        fun allowPairing(uuid: String) { instance?.allowPairingInternal(uuid) }

        /** The user tapped Deny: reject this uuid for the rest of the run and drop the in-flight pairing. */
        fun denyPairing(uuid: String) {
            AuthStore.deny(uuid)
            instance?.denyPairingInternal(uuid)
        }

        /** Hide the prompt for the current code without deciding. */
        fun dismissPairing() {
            AuthStore.dismissPending()
            instance?.cancelPairingNotification()
        }

        /** Forget a trusted device and, if it is streaming right now, end its session (applied by the rx thread ≤ 1 s). */
        fun forgetDevice(ctx: Context, uuid: String) {
            AuthStore.removeApproved(ctx, uuid)
            instance?.requestReset(uuid)
        }
    }
}
