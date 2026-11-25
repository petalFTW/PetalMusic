/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rolling 12-bin chroma (pitch-class energy, 60 Hz–2 kHz) on a deck's media
 * clock: mono decimated to ~5.5 kHz, a 1024-point Hann FFT every 512 samples
 * (~93 ms). Chords and 808 notes change on bar lines, which settles the bar
 * phase when kick and snare cannot; the summed chroma gives the key. One writer
 * (audio thread or decoder), allocation-free; readers copy a window.
 */
internal class ChromaEnvelope(private val capacity: Int) {
    companion object {
        const val SIZE = 1024
        const val HOP = 512
        const val BINS = 12

        /** Frames needed for [ms] of audio. */
        fun framesFor(ms: Double) = (ms / 92.0).toInt() + 16
    }

    class Snapshot(val originMs: Double, val hopMs: Double, val frames: Int, val data: FloatArray) {
        /** Media position at the centre of frame [k]. */
        fun centreMs(k: Int) = originMs + (k + .5) * hopMs
        fun get(k: Int, bin: Int) = data[k * BINS + bin]
    }

    private val data = FloatArray(capacity * BINS)
    @Volatile private var count = 0L
    @Volatile private var generation = 0
    @Volatile private var originMs = 0.0
    @Volatile private var hopMs = 92.9

    private var factor = 8
    private var acc = 0f
    private var accN = 0
    private val ring = FloatArray(SIZE)
    private var write = 0
    private var filled = 0
    private var sinceHop = 0
    private val re = DoubleArray(SIZE)
    private val im = DoubleArray(SIZE)
    private val window = DoubleArray(SIZE) { .5 - .5 * cos(2 * PI * it / (SIZE - 1)) }
    private var pitchClass = IntArray(SIZE / 2)
    private val local = DoubleArray(BINS)

    fun reset(originMs: Double, rate: Int) {
        generation++
        factor = (rate / 5512.5).roundToInt().coerceAtLeast(1)
        val decimated = rate.toDouble() / factor
        hopMs = HOP * 1000.0 / decimated
        // Frame k's window ends SIZE + k·HOP decimated samples in, so it is centred
        // at k·HOP + SIZE/2. Shift the origin so centreMs(k) = origin + (k+½)·hop.
        this.originMs = originMs + (SIZE / 2 - HOP / 2) * 1000.0 / decimated
        antiAlias.forEach { it.set(rate, 2200.0) }
        pitchClass = IntArray(SIZE / 2) { k ->
            val f = k * decimated / SIZE
            if (f < 60 || f > 2000) -1 else ((12 * ln(f / 440.0) / ln(2.0)).roundToInt() + 69).mod(12)
        }
        acc = 0f; accN = 0; write = 0; filled = 0; sinceHop = 0
        ring.fill(0f)
        count = 0
    }

    /** Two RBJ low-pass stages so 3–5 kHz harmonics do not alias into the chroma range. */
    private class LowPass {
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun set(rate: Int, hz: Double) {
            val w = 2 * PI * minOf(hz, rate * .45) / rate
            val c = cos(w); val alpha = kotlin.math.sin(w) / (2 * .7071)
            val a0 = 1 + alpha
            b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0
            a1 = -2 * c / a0; a2 = (1 - alpha) / a0
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }
    private val antiAlias = arrayOf(LowPass(), LowPass())

    fun sample(x: Float) {
        acc += antiAlias[1].process(antiAlias[0].process(x.toDouble())).toFloat()
        if (++accN < factor) return
        ring[write] = acc / accN
        write = (write + 1) % SIZE
        acc = 0f
        accN = 0
        if (filled < SIZE) filled++
        if (++sinceHop < HOP || filled < SIZE) return
        sinceHop = 0
        for (i in 0 until SIZE) {
            re[i] = ring[(write + i) % SIZE] * window[i]
            im[i] = 0.0
        }
        KeyDetector.fft(re, im)
        local.fill(0.0)
        for (k in 1 until SIZE / 2) {
            val pc = pitchClass[k]
            if (pc >= 0) local[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        val base = (count % capacity).toInt() * BINS
        for (b in 0 until BINS) data[base + b] = local[b].toFloat()
        count++
    }

    fun snapshot(): Snapshot? {
        val gen = generation
        val written = count
        val size = minOf(written, (capacity - 8).toLong()).toInt()
        if (size <= 0) return null
        val start = written - size
        val out = FloatArray(size * BINS)
        for (k in 0 until size) {
            val src = ((start + k) % capacity).toInt() * BINS
            System.arraycopy(data, src, out, k * BINS, BINS)
        }
        if (generation != gen || count - start > capacity - 4) return null
        return Snapshot(originMs + start * hopMs, hopMs, size, out)
    }
}
