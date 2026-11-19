package ru.metwisom.micro

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.metwisom.micro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private var authDialog: AlertDialog? = null
    private var authDialogKey: String? = null   // uuid + code + confirmed flag currently shown
    private var lastUi: UiSnapshot? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Everything the main screen renders; compared per tick so views only change when state does. */
    private data class UiSnapshot(
        val running: Boolean, val connected: Boolean, val name: String, val muted: Boolean, val error: Int,
        /** Set when the user denied the microphone permission; owned by the snapshot so the 500 ms
         *  poller re-renders it instead of overwriting it with "Stopped" on its next tick. */
        val micDenied: Boolean = false,
    )

    /** Sticky until the permission is actually granted (see [refreshState]). */
    private var micDenied = false

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Decide on the actual grant state, not on the result map: only the *missing* permissions are
        // requested, so RECORD_AUDIO is absent from the map whenever it was already granted.
        if (hasMicPermission()) {
            micDenied = false
            startBridge()
        } else {
            micDenied = true
            lastUi = null // force the next tick to render it rather than diff it away
            refreshState()
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            refreshState()
            maybeShowAuthDialog()
            ui.postDelayed(this, 500)
        }
    }

    private val colorIdle by lazy { MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant) }
    private val colorPrimary by lazy { MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary) }
    private val colorOk by lazy { ContextCompat.getColor(this, R.color.status_ok) }
    private val colorWarn by lazy { ContextCompat.getColor(this, R.color.status_warn) }
    private val colorErr by lazy { ContextCompat.getColor(this, R.color.status_err) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.address.text = getString(R.string.address_fmt, "—", AudioProtocol.PORT)
        refreshAddress()

        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.toggle.setOnClickListener {
            if (MicService.isRunning) stopBridge() else requestAndStart()
        }
        binding.mute.setOnClickListener {
            MicService.toggleMuted()
            refreshState()
        }

        // VU ring reads the live capture spectrum; it collapses to a thin ring while muted/idle.
        binding.vuRing.color = colorOk
        binding.vuRing.spectrumProvider = {
            if (MicService.isRunning && MicService.isClientConnected && !MicService.muted)
                MicService.spectrum else null
        }

        // Auto-start the server on a *fresh* launch only. A non-null savedInstanceState means the
        // Activity is being recreated (rotation, theme or locale change, process death), and the
        // user may well have pressed Stop before that — turning the microphone back on behind their
        // back is the one thing this app must never do.
        if (savedInstanceState == null && !MicService.isRunning) requestAndStart()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBridge() else permissions.launch(missing.toTypedArray())
    }

    private fun startBridge() {
        ContextCompat.startForegroundService(this, Intent(this, MicService::class.java))
    }

    private fun stopBridge() {
        startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
    }

    /** Local IP for the "Address" line, computed off-main (interface enumeration can block). */
    private fun refreshAddress() {
        Thread({
            val ip = AudioProtocol.localIpv4() ?: "—"
            ui.post {
                if (!isFinishing && !isDestroyed) binding.address.text = getString(R.string.address_fmt, ip, AudioProtocol.PORT)
            }
        }, "ip-lookup").start()
    }

    private fun maybeShowAuthDialog() {
        val req = AuthStore.pendingFresh()
        if (req == null) {
            // Decided, stale or dismissed → close the prompt.
            authDialog?.dismiss()
            authDialog = null
            authDialogKey = null
            return
        }
        // Rebuild only when the code rotated (client retried after a wrong entry) or the desktop just
        // confirmed the code (Allow becomes available).
        val key = "${req.uuid}:${req.code}:${req.confirmed}"
        if (authDialog?.isShowing == true) {
            if (authDialogKey == key) return
            authDialog?.dismiss()
            authDialog = null
        }

        val message = getString(R.string.code_prompt_fmt, req.name, req.ip, req.code) + "\n\n" +
            getString(if (req.confirmed) R.string.pair_confirmed else R.string.pair_wait_confirm)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_device)
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton(R.string.deny) { _, _ -> MicService.denyPairing(req.uuid) }
            .setNeutralButton(R.string.dismiss) { _, _ -> MicService.dismissPairing() }
            .setPositiveButton(R.string.allow) { _, _ -> MicService.allowPairing(req.uuid) }
            .create()
        // Allow only after the computer verified the code: the human compares first, then authorizes.
        dialog.setOnShowListener { dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = req.confirmed }
        authDialog = dialog
        authDialogKey = key
        dialog.show()
    }

    private fun refreshState() {
        val snap = UiSnapshot(
            running = MicService.isRunning,
            connected = MicService.isRunning && MicService.isClientConnected,
            name = MicService.connectedName,
            muted = MicService.muted,
            error = MicService.lastError,
            micDenied = micDenied && !hasMicPermission(),
        )
        if (snap == lastUi) return
        lastUi = snap

        binding.toggle.setText(if (snap.running) R.string.stop else R.string.start)
        binding.toggle.backgroundTintList = ColorStateList.valueOf(if (snap.running) colorErr else colorPrimary)

        val errorText = when (snap.error) {
            MicService.ERR_PORT -> getString(R.string.err_port_busy)
            MicService.ERR_FOREGROUND -> getString(R.string.err_foreground)
            MicService.ERR_CAPTURE -> getString(R.string.err_capture)
            MicService.ERR_SILENCED -> getString(R.string.err_silenced)
            else -> null
        }
        val showError = errorText != null &&
            (!snap.running || snap.error == MicService.ERR_CAPTURE || snap.error == MicService.ERR_SILENCED)
        binding.status.text = when {
            snap.micDenied -> getString(R.string.need_mic_permission)
            showError -> errorText
            !snap.running -> getString(R.string.stopped)
            snap.connected -> getString(R.string.connected_fmt, snap.name.ifEmpty { getString(R.string.device_generic) })
            else -> getString(R.string.waiting)
        }
        binding.status.setTextColor(
            when {
                snap.micDenied -> colorErr
                showError -> colorErr
                !snap.running -> colorIdle
                snap.connected -> colorOk
                else -> colorWarn
            }
        )

        binding.mute.visibility = if (snap.connected) View.VISIBLE else View.GONE
        binding.vuRing.visibility = if (snap.connected) View.VISIBLE else View.GONE
        if (snap.connected) binding.vuRing.start() else binding.vuRing.stop()
        if (snap.connected) {
            binding.mute.setText(if (snap.muted) R.string.btn_unmute else R.string.btn_mute)
            binding.mute.setIconResource(if (snap.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            binding.mute.backgroundTintList = ColorStateList.valueOf(if (snap.muted) colorErr else colorOk)
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep the shown address current across Wi-Fi changes (needs ACCESS_NETWORK_STATE).
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshAddress()
            override fun onLost(network: Network) = refreshAddress()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshAddress()
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        netCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        netCallback = null
    }

    override fun onResume() {
        super.onResume()
        MicService.setUiVisible(true) // let the service compute the VU spectrum (the ring is visible)
        lastUi = null                 // force a full render on return
        ui.post(poller)
    }

    override fun onPause() {
        super.onPause()
        MicService.setUiVisible(false) // ring hidden → service can skip the per-frame FFT
        ui.removeCallbacks(poller)
        binding.vuRing.stop()
        authDialog?.dismiss()
        authDialog = null
        authDialogKey = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(poller)
        authDialog?.dismiss()
        authDialog = null
        authDialogKey = null
    }
}
