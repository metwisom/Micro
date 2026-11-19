package ru.metwisom.micro

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cheap real-input spectrum analyzer for the VU ring. Hann-windows a 480-sample (10 ms @ 48 kHz)
 * frame, runs a 512-point radix-2 FFT, and groups the magnitude bins into log-spaced bands across
 * the speech range. All buffers are preallocated and reused — no per-frame allocation, safe to call
 * on the capture thread. Not thread-safe; one instance per capture loop.
 */
class Spectrum(private val bands: Int = MicService.VU_BANDS) {

    private val n = 512
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val frame = AudioProtocol.FRAME_SAMPLES // 480
    private val win = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / (frame - 1))).toFloat() }
    private val rev = IntArray(n)            // bit-reversal permutation
    private val edges = IntArray(bands + 1)  // band → FFT bin boundaries

    // Full-scale reference: a 0 dBFS sine peaks at ~A·N/4 in a Hann-windowed bin.
    private val fullScale = (n / 4f) * 32768f
    private val gain = 1.8f // perceptual boost so normal speech fills the ring; tune to taste.

    init {
        var j = 0
        for (i in 0 until n) {
            rev[i] = j
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
        }
        // Log-spaced band edges from ~120 Hz to ~7.5 kHz.
        val binHz = AudioProtocol.SAMPLE_RATE.toFloat() / n
        val fLow = 120f; val fHigh = 7500f
        val logLo = ln(fLow); val logHi = ln(fHigh)
        for (b in 0..bands) {
            val f = exp(logLo + (logHi - logLo) * b / bands).toFloat()
            edges[b] = (f / binHz).toInt().coerceIn(1, n / 2)
        }
    }

    /** Compute [bands] magnitudes (0..1, perceptual) from [pcm] into [out] (size == bands). */
    fun compute(pcm: ShortArray, out: FloatArray) {
        val m = minOf(frame, pcm.size)
        for (i in 0 until m) re[i] = pcm[i].toFloat() * win[i]
        for (i in m until n) re[i] = 0f
        java.util.Arrays.fill(im, 0f)
        fft()
        for (b in 0 until bands) {
            var lo = edges[b]; val hi = maxOf(edges[b + 1], lo + 1)
            var peak = 0f
            var k = lo
            while (k < hi && k <= n / 2) {
                val p = re[k] * re[k] + im[k] * im[k]
                if (p > peak) peak = p
                k++
            }
            val level = sqrt(sqrt(peak) / fullScale) * gain
            out[b] = level.coerceIn(0f, 1f)
        }
    }

    // In-place iterative radix-2 Cooley-Tukey (decimation-in-time).
    private fun fft() {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k; val bIdx = a + half
                    val tRe = curRe * re[bIdx] - curIm * im[bIdx]
                    val tIm = curRe * im[bIdx] + curIm * re[bIdx]
                    re[bIdx] = re[a] - tRe; im[bIdx] = im[a] - tIm
                    re[a] += tRe; im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
