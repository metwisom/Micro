package ru.metwisom.micro

import android.content.Context
import android.media.MediaRecorder
import android.os.Build

/** Persisted user settings (currently just the capture audio source). */
object AppSettings {
    private const val PREFS = "micro_settings"
    private const val KEY_SOURCE = "audio_source"
    private const val KEY_INPUT_DEVICE = "input_device_id"

    const val DEVICE_AUTO = -1   // let the system pick the input device

    // VOICE_RECOGNITION = low processing (no AGC/NS/AEC), good latency. Default.
    const val DEFAULT_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** Selectable capture sources: label shown in the UI -> AudioSource constant. */
    val SOURCES: List<Pair<String, Int>> = buildList {
        add("DEFAULT" to MediaRecorder.AudioSource.DEFAULT)
        add("MIC" to MediaRecorder.AudioSource.MIC)
        add("CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER)
        add("VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION)
        add("VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        add("UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED) // API 24
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add("VOICE_PERFORMANCE" to MediaRecorder.AudioSource.VOICE_PERFORMANCE) // API 29
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun audioSource(ctx: Context): Int = prefs(ctx).getInt(KEY_SOURCE, DEFAULT_SOURCE)

    fun setAudioSource(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_SOURCE, value).apply()

    fun indexOfSource(value: Int): Int =
        SOURCES.indexOfFirst { it.second == value }.coerceAtLeast(0)

    fun inputDeviceId(ctx: Context): Int = prefs(ctx).getInt(KEY_INPUT_DEVICE, DEVICE_AUTO)

    fun setInputDeviceId(ctx: Context, id: Int) =
        prefs(ctx).edit().putInt(KEY_INPUT_DEVICE, id).apply()
}

