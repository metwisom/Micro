package ru.metwisom.micro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.metwisom.micro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private var authDialog: AlertDialog? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startBridge()
        else binding.status.text = getString(R.string.need_mic_permission)
    }

    private val poller = object : Runnable {
        override fun run() {
            refreshState()
            maybeShowAuthDialog()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ip = AudioProtocol.localIpv4() ?: "—"
        binding.address.text = getString(R.string.address_fmt, ip, AudioProtocol.PORT)

        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.toggle.setOnClickListener {
            if (MicService.isRunning) stopBridge() else requestAndStart()
        }
        binding.mute.setOnClickListener {
            MicService.muted = !MicService.muted
            refreshState()
        }

        // Auto-start the server on launch.
        if (!MicService.isRunning) requestAndStart()
    }

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

    private fun maybeShowAuthDialog() {
        val req = AuthStore.pending
        if (req == null) {
            // Approved (code accepted) → close the prompt.
            authDialog?.dismiss()
            authDialog = null
            return
        }
        if (authDialog?.isShowing == true) return

        authDialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_device)
            .setMessage(getString(R.string.code_prompt_fmt, req.name, req.ip, req.code))
            .setCancelable(false)
            .setNegativeButton(R.string.deny) { _, _ -> AuthStore.deny(req.uuid) }
            .create()
        authDialog!!.show()
    }

    private fun refreshState() {
        binding.toggle.setText(if (MicService.isRunning) R.string.stop else R.string.start)
        binding.status.text = when {
            !MicService.isRunning -> getString(R.string.stopped)
            MicService.isClientConnected ->
                getString(R.string.connected_fmt, MicService.connectedName.ifEmpty { getString(R.string.device_generic) })
            else -> getString(R.string.waiting)
        }
        binding.status.setTextColor(
            when {
                !MicService.isRunning -> 0xFF9AA0A6.toInt()          // grey
                MicService.isClientConnected -> 0xFF3ECF8E.toInt()   // green
                else -> 0xFFE0A030.toInt()                           // amber (waiting)
            }
        )

        val connected = MicService.isRunning && MicService.isClientConnected
        binding.mute.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
        if (connected) {
            binding.mute.setText(if (MicService.muted) R.string.btn_unmute else R.string.btn_mute)
            binding.mute.setIconResource(if (MicService.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            binding.mute.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (MicService.muted) 0xFFC42B1C.toInt() else 0xFF2E9E5B.toInt()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(poller)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poller)
        authDialog?.dismiss()
        authDialog = null
    }
}
