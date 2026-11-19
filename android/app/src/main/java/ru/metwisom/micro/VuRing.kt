package ru.metwisom.micro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equalizer-style ring drawn around the mic mute button. Radiating bars whose lengths follow the
 * live audio spectrum (low freq at the bottom, high at the top, mirrored left/right), so the user
 * sees their voice is being heard. Self-animates via [Choreographer] while attached; pull the current
 * spectrum through [spectrumProvider]. Non-clickable — it sits under the button so taps reach it.
 */
class VuRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Returns the current spectrum (0..1 per band, any length), or null when idle. */
    var spectrumProvider: (() -> FloatArray?)? = null

    /** Bar / ring color (the host sets it from the theme; defaults to the status-ok resource). */
    var color: Int = androidx.core.content.ContextCompat.getColor(context, R.color.status_ok)

    private val density = resources.displayMetrics.density
    private val bars = 56
    private val levels = FloatArray(bars) // smoothed per-bar length
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }

    private val buttonRadiusPx = 100f * density // mute button is 200dp → radius 100dp
    private val gapPx = 7f * density
    private val minLenPx = 3f * density          // thin idle ring, always visible
    private val maxLenPx = 38f * density         // full deflection at level 1

    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun step() {
        val spec = spectrumProvider?.invoke()
        for (i in 0 until bars) {
            // Map by vertical position (screen y is down): bottom of the ring = low freq, top =
            // high freq. sin(angle) is +1 at the bottom, -1 at the top → vfrac 0..1 bottom→top.
            // Left and right at the same height land on the same band, so the ring is symmetric.
            val ang = (i.toFloat() / bars) * (2f * Math.PI.toFloat())
            val target = if (spec != null && spec.isNotEmpty()) {
                val vfrac = (1f - sin(ang)) / 2f
                val idx = (vfrac * (spec.size - 1)).toInt().coerceIn(0, spec.size - 1)
                spec[idx].coerceIn(0f, 1f)
            } else 0f
            // Fast attack, slow release — reads like a real analyzer.
            levels[i] += (target - levels[i]) * if (target > levels[i]) 0.6f else 0.18f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r0 = buttonRadiusPx + gapPx
        val base = color and 0x00FFFFFF

        for (i in 0 until bars) {
            val lv = levels[i]
            val ang = (i.toFloat() / bars) * (2f * Math.PI.toFloat())
            val len = minLenPx + lv * maxLenPx
            val alpha = (70 + 185 * lv).toInt().coerceIn(0, 255)
            paint.color = base or (alpha shl 24)
            val c = cos(ang); val s = sin(ang)
            canvas.drawLine(
                cx + c * r0, cy + s * r0,
                cx + c * (r0 + len), cy + s * (r0 + len),
                paint,
            )
        }
    }
}
