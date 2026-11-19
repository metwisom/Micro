package ru.metwisom.micro

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import ru.metwisom.micro.databinding.SettingsActivityBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupLanguageSpinner()
        setupSourceSpinner()
        setupInputDeviceSpinner()
        setupFecSwitch()
        setupLowLatencySwitch()
        ready = true
    }

    // "" = follow system, otherwise a BCP-47 language tag.
    private val langTags = listOf("", "en", "ru")

    private fun setupLanguageSpinner() {
        val labels = listOf(getString(R.string.lang_system), "English", "Русский")
        binding.langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        val cur = AppCompatDelegate.getApplicationLocales()
        val curTag = if (cur.isEmpty) "" else (cur[0]?.language ?: "")
        binding.langSpinner.setSelection(langTags.indexOf(curTag).coerceAtLeast(0), false)
        binding.langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!ready) return
                val tag = langTags[pos]
                val now = AppCompatDelegate.getApplicationLocales()
                val nowTag = if (now.isEmpty) "" else (now[0]?.language ?: "")
                if (tag == nowTag) return
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        renderTrusted()
    }

    private fun setupSourceSpinner() {
        val labels = AppSettings.SOURCES.map { it.first }
        binding.source.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.source.setSelection(AppSettings.indexOfSource(AppSettings.audioSource(this)), false)
        binding.source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val value = AppSettings.SOURCES[pos].second
                if (!ready || value == AppSettings.audioSource(this@SettingsActivity)) return
                AppSettings.setAudioSource(this@SettingsActivity, value)
                restartIfRunning()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupInputDeviceSpinner() {
        val labels = mutableListOf(getString(R.string.device_auto))
        val ids = mutableListOf(AppSettings.DEVICE_AUTO)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            labels += "${d.productName} · ${deviceTypeName(d.type)}"
            ids += d.id
        }
        // Capture the fully-built id list as an immutable snapshot the listener closes over, so a
        // spinner callback can never read a half-populated/replaced field (the previous mutable field
        // was written here and read in the callback — a latent ordering hazard).
        val deviceIds = ids.toList()
        binding.inputDevice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.inputDevice.setSelection(deviceIds.indexOf(AppSettings.inputDeviceId(this)).coerceAtLeast(0), false)
        binding.inputDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val value = deviceIds[pos]
                if (!ready || value == AppSettings.inputDeviceId(this@SettingsActivity)) return
                AppSettings.setInputDeviceId(this@SettingsActivity, value)
                restartIfRunning()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupFecSwitch() {
        binding.fec.isChecked = AppSettings.fecEnabled(this)
        binding.fec.setOnCheckedChangeListener { _, checked ->
            if (!ready || checked == AppSettings.fecEnabled(this)) return@setOnCheckedChangeListener
            AppSettings.setFecEnabled(this, checked)
            restartIfRunning()
        }
    }

    private fun setupLowLatencySwitch() {
        binding.lowLatency.isChecked = AppSettings.lowLatency(this)
        binding.fec.isEnabled = !AppSettings.lowLatency(this) // FEC is unavailable in CELT-only mode
        binding.lowLatency.setOnCheckedChangeListener { _, checked ->
            if (!ready || checked == AppSettings.lowLatency(this)) return@setOnCheckedChangeListener
            AppSettings.setLowLatency(this, checked)
            binding.fec.isEnabled = !checked
            restartIfRunning()
        }
    }

    private fun renderTrusted() {
        val list = AuthStore.trustedDevices(this)
        binding.trustedList.removeAllViews()
        binding.trustedEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val dp = resources.displayMetrics.density
        for (dev in list) {
            val card = MaterialCardView(this).apply {
                radius = 16 * dp
                cardElevation = 0f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * dp).toInt() }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (8 * dp).toInt()
                setPadding((16 * dp).toInt(), p, p, p)
            }
            val name = TextView(this).apply {
                text = dev.name
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.delete)
                setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle(R.string.delete_device_title)
                        .setMessage(getString(R.string.delete_device_msg, dev.name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            MicService.forgetDevice(this@SettingsActivity, dev.uuid)
                            renderTrusted()
                        }
                        .show()
                }
            }
            row.addView(name)
            row.addView(del)
            card.addView(row)
            binding.trustedList.addView(card)
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> getString(R.string.dev_builtin)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> getString(R.string.dev_bt)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> getString(R.string.dev_wired)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> getString(R.string.dev_usb)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> getString(R.string.dev_speaker)
        else -> getString(R.string.dev_other, type)
    }

    private fun restartIfRunning() {
        if (!MicService.isRunning) return
        startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_RESTART_CAPTURE))
    }
}
