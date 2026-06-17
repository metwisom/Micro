package ru.metwisom.micro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that captures the mic and streams Opus frames to the Windows client
 * over UDP. Lives independently of the UI so the bridge keeps running with the screen off.
 */
class MicService : Service() {

    private val running = AtomicBoolean(false)
    @Volatile private var captureStop = false
    private var socket: DatagramSocket? = null
    private var captureThread: Thread? = null
    private var receiverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var clientAddr: InetAddress? = null
    @Volatile private var clientPort: Int = 0
    @Volatile private var lastClientSeen: Long = 0L
    @Volatile private var crypto: CryptoSession? = null
    @Volatile private var cryptoUuid: String = ""
    @Volatile private var cryptoClientEph: String = ""
    @Volatile private var keysReply: String? = null
    @Volatile private var seqCounter: Int = 0      // persists across capture restarts
    @Volatile private var targetLossPct: Int = 0   // reported by the client
    // In-flight SAS pairing (one client at a time).
    @Volatile private var pairUuid: String? = null
    private var pairCommit: ByteArray? = null
    private var pairClientPub: ByteArray? = null
    private var pairName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_CAPTURE -> {
                // Apply new capture settings (source / input device) without rebinding the
                // socket — a full restart would race the port bind and kill the server.
                if (running.get()) restartCapture()
                return START_STICKY
            }
        }
        if (running.compareAndSet(false, true)) {
            startForegroundNotification()
            acquireWakeLock()
            start()
            isRunning = true
        }
        return START_STICKY
    }

    private fun start() {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 1000
            bind(java.net.InetSocketAddress(AudioProtocol.PORT))
        }
        socket = sock

        receiverThread = Thread({ receiveLoop(sock) }, "mic-rx").apply { start() }
        startCapture(sock)
    }

    private fun startCapture(sock: DatagramSocket) {
        captureStop = false
        captureThread = Thread({ captureLoop(sock) }, "mic-tx").apply { start() }
    }

    private fun restartCapture() {
        val sock = socket ?: return
        captureStop = true
        captureThread?.join(700)
        startCapture(sock)
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(512)
        val pkt = DatagramPacket(buf, buf.size)
        val ctx = applicationContext
        while (running.get()) {
            try {
                sock.receive(pkt)
                val text = String(buf, 0, pkt.length, Charsets.UTF_8)
                when {
                    text.startsWith("PC|") -> handlePairCommit(sock, pkt, text, ctx)
                    text.startsWith("PR|") -> handlePairReveal(pkt, text, ctx)
                    text.startsWith("PK|") -> handlePairConfirm(pkt, text, ctx)
                    text.startsWith("HELO|") -> handleSession(sock, pkt, text, ctx)
                    text == "BYE!" -> resetClient()
                    text == "MICRO?" -> {
                        val reply = "${AudioProtocol.ANNOUNCE_PREFIX}${AudioProtocol.PORT}|${Build.MODEL}"
                            .toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                    }
                    text.startsWith("PING|") ->
                        reply(sock, ("PONG|" + text.substring(5)).toByteArray(Charsets.US_ASCII), pkt)
                    text.startsWith("MUTE|") -> muted = text.getOrNull(5) == '1'
                    text.startsWith("LOSS|") ->
                        text.substringAfter('|', "").trim().toIntOrNull()
                            ?.let { targetLossPct = it.coerceIn(0, 100) }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // loop and re-check running flag
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "rx error", e)
            }
        }
    }

    // PC|uuid|name|H(clientPub) — client commits, server reveals its long-term pub.
    private fun handlePairCommit(sock: DatagramSocket, pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        val uuid = p.getOrNull(1)?.trim().orEmpty()
        if (uuid.isEmpty()) return
        val name = p.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() } ?: (pkt.address.hostAddress ?: "?")
        if (AuthStore.isDenied(uuid)) { reply(sock, AudioProtocol.AUTH_DENIED, pkt); return }
        val commit = try { Base64.decode(p.getOrNull(3)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        if (commit == null || commit.size != 32) return

        pairUuid = uuid
        pairCommit = commit
        pairClientPub = null
        pairName = name
        AuthStore.clearPending()
        val sp = Base64.encodeToString(Identity.pub(ctx), Base64.NO_WRAP)
        reply(sock, "PS|$sp".toByteArray(Charsets.US_ASCII), pkt)
    }

    // PR|uuid|clientPub — client reveals; verify against commit, then show SAS on the phone.
    private fun handlePairReveal(pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        if (p.getOrNull(1)?.trim() != pairUuid) return
        val commit = pairCommit ?: return
        val clientPub = try { Base64.decode(p.getOrNull(2)?.trim().orEmpty(), Base64.DEFAULT) } catch (_: Exception) { null }
        if (clientPub == null || clientPub.size != 32) return
        if (!sha256(clientPub).contentEquals(commit)) return  // commitment violation

        pairClientPub = clientPub
        val code = sas(clientPub, Identity.pub(ctx))
        val ip = pkt.address.hostAddress ?: "?"
        AuthStore.requestPending(PendingReq(pairUuid!!, ip, pairName, code))
    }

    // PK|uuid — client confirmed the SAS matched. Pin + approve; the session opens on the next
    // HELO (which carries the client's ephemeral key → forward secrecy).
    private fun handlePairConfirm(pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        if (p.getOrNull(1)?.trim() != pairUuid) return
        val clientPub = pairClientPub ?: return
        AuthStore.pin(ctx, pairUuid!!, clientPub)
        AuthStore.approve(ctx, pairUuid!!, pairName, always = true)
        AuthStore.clearPending()
        pairUuid = null; pairCommit = null; pairClientPub = null
    }

    // HELO|uuid|name|<clientEphPub> — paired device opens/keeps a session.
    private fun handleSession(sock: DatagramSocket, pkt: DatagramPacket, text: String, ctx: Context) {
        val p = text.split("|")
        val uuid = p.getOrNull(1)?.trim().orEmpty()
        if (uuid.isEmpty()) return
        val name = p.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() } ?: (pkt.address.hostAddress ?: "?")
        val ephB64 = p.getOrNull(3)?.trim().orEmpty()
        val clientEph = try { Base64.decode(ephB64, Base64.DEFAULT) } catch (_: Exception) { null }
        if (clientEph == null || clientEph.size != 32) return

        val pinned = AuthStore.pinnedPub(ctx, uuid)
        if (pinned == null || !AuthStore.isApproved(ctx, uuid)) {
            reply(sock, "REPAIR".toByteArray(Charsets.US_ASCII), pkt)
            return
        }
        // (Re)derive when there's no session yet, the client changed, or its ephemeral changed.
        if (crypto == null || cryptoUuid != uuid || cryptoClientEph != ephB64) {
            val setup = CryptoSession.forServer(Identity.secret(ctx), pinned, clientEph)
            crypto = setup.session
            cryptoUuid = uuid
            cryptoClientEph = ephB64
            keysReply = "KEYS|${setup.saltB64}|${setup.serverEphB64}"
        }
        accept(pkt, name)
        keysReply?.let { reply(sock, it.toByteArray(Charsets.US_ASCII), pkt) }
    }

    private fun resetClient() {
        clientAddr = null
        crypto = null
        cryptoUuid = ""
        cryptoClientEph = ""
        keysReply = null
    }

    private fun accept(pkt: DatagramPacket, name: String) {
        clientAddr = pkt.address
        clientPort = pkt.port
        lastClientSeen = System.currentTimeMillis()
        connectedName = name
    }

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun sas(clientPub: ByteArray, serverPub: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("MICRO-SAS-v1".toByteArray())
        md.update(clientPub)
        md.update(serverPub)
        val d = md.digest()
        val v = (((d[0].toInt() and 0xff) shl 8) or (d[1].toInt() and 0xff)) % 10000
        return "%04d".format(v)
    }

    private fun reply(sock: DatagramSocket, bytes: ByteArray, pkt: DatagramPacket) {
        try { sock.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port)) } catch (_: Exception) {}
    }

    private fun captureLoop(sock: DatagramSocket) {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Small buffer keeps capture latency low; we do our own noise suppression on the
        // Windows side, so a low-processing source (e.g. VOICE_RECOGNITION) is preferred.
        val recBuf = maxOf(minBuf, AudioProtocol.FRAME_SAMPLES * 2 * 2)

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(AppSettings.audioSource(applicationContext))
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
            // Unsupported source/device combo — keep the service (and socket) alive so the
            // user can pick another in the UI.
            Log.e(TAG, "AudioRecord build failed", e)
            isClientConnected = false
            return
        }

        // Optionally pin a specific input device (mic).
        val wantId = AppSettings.inputDeviceId(applicationContext)
        if (wantId != AppSettings.DEVICE_AUTO) {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == wantId }
                ?.let { record.preferredDevice = it }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            record.release()
            isClientConnected = false
            return
        }

        // Native libopus encoder (in-band FEC on, DTX off, complexity 5 — set in create()).
        val enc = OpusNative.create(
            AudioProtocol.SAMPLE_RATE, AudioProtocol.CHANNELS,
            OpusNative.APPLICATION_VOIP, AudioProtocol.BITRATE
        )
        if (enc == 0L) {
            Log.e(TAG, "Opus encoder create failed")
            record.release()
            isClientConnected = false
            return
        }
        var appliedLoss = 5
        var lastSentMute = false
        var lastMuteKeep = 0L
        val muteOn = "MUTE|1".toByteArray(Charsets.US_ASCII)
        val muteOff = "MUTE|0".toByteArray(Charsets.US_ASCII)

        val pcm = ShortArray(AudioProtocol.FRAME_SAMPLES)
        val opusBuf = ByteArray(4000)   // raw Opus payload
        val out = ByteArray(4 + 4000 + 16)   // seq + encrypted payload (+ AEAD tag)
        val packet = DatagramPacket(out, out.size)   // reused per send

        record.startRecording()
        try {
            while (running.get() && !captureStop) {
                // Adapt FEC strength + bitrate to the client-reported loss.
                val want = targetLossPct
                if (want != appliedLoss) {
                    appliedLoss = want
                    OpusNative.setPacketLoss(enc, want.coerceIn(0, 30))
                    OpusNative.setBitrate(enc, when {
                        want > 15 -> 24000
                        want > 5 -> 28000
                        else -> AudioProtocol.BITRATE
                    })
                }

                // Fill a full 10 ms frame (AudioRecord may return partial reads).
                var off = 0
                while (off < pcm.size && running.get() && !captureStop) {
                    val n = record.read(pcm, off, pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < pcm.size) continue

                val addr = clientAddr
                val cs = crypto
                val active = addr != null && cs != null &&
                    System.currentTimeMillis() - lastClientSeen < AudioProtocol.CLIENT_TIMEOUT_MS
                isClientConnected = active
                if (!active) {
                    // Client timed out → drop the session so a reconnect re-keys (new salt).
                    if (addr != null && System.currentTimeMillis() - lastClientSeen >= AudioProtocol.CLIENT_TIMEOUT_MS) {
                        clientAddr = null
                        crypto = null
                        cryptoUuid = ""
                        cryptoClientEph = ""
                        keysReply = null
                    }
                    continue
                }

                // Mute synced both ways. While muted we stop sending audio but keep pinging the
                // client every 500 ms (MUTE|1) so it doesn't think the server vanished, and the
                // change reaches it instantly on toggle.
                val nowMs = System.currentTimeMillis()
                if (muted != lastSentMute || (muted && nowMs - lastMuteKeep >= 500)) {
                    lastSentMute = muted
                    lastMuteKeep = nowMs
                    val m = if (muted) muteOn else muteOff
                    try {
                        packet.setData(m, 0, m.size)
                        packet.address = addr
                        packet.port = clientPort
                        sock.send(packet)
                    } catch (_: Exception) {}
                }
                if (muted) continue   // stop transmission while muted

                val payloadLen = OpusNative.encode(enc, pcm, AudioProtocol.FRAME_SAMPLES, opusBuf, opusBuf.size)
                if (payloadLen <= 0) continue

                val seq = seqCounter
                out[0] = (seq ushr 24).toByte()
                out[1] = (seq ushr 16).toByte()
                out[2] = (seq ushr 8).toByte()
                out[3] = seq.toByte()
                // Encrypt straight into out[4..] (no intermediate buffer / copy).
                val ctLen = try {
                    cs!!.encryptInto(opusBuf, payloadLen, seq, out, 4)
                } catch (e: Exception) {
                    Log.w(TAG, "encrypt error", e); continue
                }
                seqCounter = seq + 1

                try {
                    packet.setData(out, 0, 4 + ctLen)
                    packet.address = addr
                    packet.port = clientPort
                    sock.send(packet)
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "tx error", e)
                }
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            OpusNative.destroy(enc)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Micro:mic").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Micro")
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        running.set(false)
        captureStop = true
        isRunning = false
        isClientConnected = false
        muted = false
        crypto = null
        cryptoUuid = ""
        pairUuid = null
        try { socket?.close() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        captureThread?.join(500)
        receiverThread?.join(500)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MicService"
        private const val CHANNEL_ID = "mic_bridge"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "ru.metwisom.micro.STOP"
        const val ACTION_RESTART_CAPTURE = "ru.metwisom.micro.RESTART_CAPTURE"

        @Volatile var isRunning = false
            private set
        @Volatile var isClientConnected = false
            private set
        @Volatile var connectedName: String = ""
        @Volatile var muted = false
    }
}
