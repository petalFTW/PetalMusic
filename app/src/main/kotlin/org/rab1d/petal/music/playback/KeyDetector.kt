/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming key estimate: decimated mono → 4096-point FFT chroma (65 Hz–2 kHz)
 * → Krumhansl–Kessler profile correlation over 24 keys. Constant memory.
 * A global key per track; modulations and modal ambiguity lower confidence.
 */
internal class KeyDetector(sampleRate: Int) {
    data class Key(val camelot: Int, val minor: Boolean, val confidence: Float)

    private val factor = (sampleRate / 11025.0).roundToInt().coerceAtLeast(1)
    private val rate = sampleRate.toDouble() / factor
    private val size = 4096
    private val frame = DoubleArray(size)
    private var filled = 0
    private var acc = 0.0
    private var accN = 0
    private val chroma = DoubleArray(12)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
    private val pitchClass = IntArray(size / 2) { k ->
        val f = k * rate / size
        if (f < 65 || f > 2000) -1 else ((12 * ln(f / 440.0) / ln(2.0)).roundToInt() + 69).mod(12)
    }

    fun sample(x: Float) {
        acc += x
        if (++accN < factor) return
        frame[filled++] = acc / accN
        acc = 0.0
        accN = 0
        if (filled == size) {
            analyseFrame()
            filled = 0
        }
    }

    private fun analyseFrame() {
        var energy = 0.0
        for (i in 0 until size) {
            re[i] = frame[i] * window[i]
            im[i] = 0.0
            energy += frame[i] * frame[i]
        }
        if (energy < 1e-6) return
        fft(re, im)
        val local = DoubleArray(12)
        for (k in 1 until size / 2) {
            val pc = pitchClass[k]
            if (pc >= 0) local[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        val total = local.sum().coerceAtLeast(1e-9)
        // Normalised per frame so loud passages do not dominate the key.
        for (p in 0..11) chroma[p] += local[p] / total
    }

    fun result(): Key? {
        if (chroma.sum() <= 0.0) return null
        val scores = ArrayList<Triple<Int, Boolean, Double>>()
        for (tonic in 0..11) {
            scores += Triple(tonic, false, correlate(MAJOR, tonic))
            scores += Triple(tonic, true, correlate(MINOR, tonic))
        }
        scores.sortByDescending { it.third }
        val (tonic, minor, best) = scores[0]
        // Relative major/minor share a Camelot number, so only a different
        // number counts as a competing key.
        val camelot = camelot(tonic, minor)
        val rival = scores.drop(1).firstOrNull { camelot(it.first, it.second) != camelot }?.third ?: 0.0
        return Key(camelot, minor, (best - rival).toFloat().coerceIn(0f, 1f))
    }

    private fun correlate(profile: DoubleArray, tonic: Int): Double {
        val meanC = chroma.average()
        val meanP = profile.average()
        var num = 0.0; var dc = 0.0; var dp = 0.0
        for (i in 0..11) {
            val c = chroma[(i + tonic) % 12] - meanC
            val p = profile[i] - meanP
            num += c * p; dc += c * c; dp += p * p
        }
        return num / sqrt(dc * dp).coerceAtLeast(1e-12)
    }

    companion object {
        /** Key from a summed chroma vector (C = bin 0). */
        fun fromChroma(chroma: DoubleArray): Key? {
            if (chroma.sum() <= 0.0) return null
            val d = KeyDetector(44100)
            chroma.copyInto(d.chroma)
            return d.result()
        }

        private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** Camelot number for a tonic pitch class (C = 0); minor keys share their relative major's. */
        fun camelot(tonic: Int, minor: Boolean): Int {
            val major = if (minor) (tonic + 3) % 12 else tonic
            return (major * 7 + 7) % 12 + 1
        }

        /** In-place iterative radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle); val wi = sin(angle)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
