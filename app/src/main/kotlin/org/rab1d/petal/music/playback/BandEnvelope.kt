/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rolling hop-RMS envelopes on an exact media clock: full band, low (sub+bass,
 * kick and bassline), mid (300 Hz–3 kHz, snare body, vocals), high (>3 kHz,
 * hats and snare crack) and a 1–4 kHz vocal-presence band. ~5.8 ms hops keep
 * beat timing within a few milliseconds. One audio-thread writer; readers copy
 * a window, and a reset (seek, new item) discards a torn copy.
 */
internal class BandEnvelope(private val capacity: Int) {
    companion object {
        const val FULL = 0
        const val LOW = 1
        const val MID = 2
        const val HIGH = 3
        const val VOCAL = 4
        const val BANDS = 5
        private const val HOP_SECONDS = .0058

        fun hopFrames(rate: Int) = (rate * HOP_SECONDS).roundToInt().coerceAtLeast(1)

        /** Hops needed for [ms] of audio at the nominal hop. */
        fun hopsFor(ms: Double) = (ms / (HOP_SECONDS * 1000)).toInt() + 64
    }

    class Snapshot(val originMs: Double, val hopMs: Double, val bands: Array<FloatArray>) {
        val size: Int get() = bands[0].size
        val endMs: Double get() = originMs + size * hopMs
        operator fun get(band: Int): FloatArray = bands[band]
    }

    private val data = Array(BANDS) { FloatArray(capacity) }
    @Volatile private var count = 0L
    @Volatile private var generation = 0
    @Volatile private var originMs = 0.0
    @Volatile private var hopMs = 5.8

    private var hopFrames = 1
    private var n = 0
    private val sums = DoubleArray(BANDS)

    /** Start a new timeline: [originMs] is the media position of the next frame. */
    fun reset(originMs: Double, sampleRate: Int) {
        generation++
        hopFrames = hopFrames(sampleRate)
        hopMs = hopFrames * 1000.0 / sampleRate
        this.originMs = originMs
        n = 0
        sums.fill(0.0)
        count = 0
    }

    /** One mono frame already split into the analysis bands. */
    fun frame(full: Float, low: Float, mid: Float, high: Float, vocal: Float) {
        sums[FULL] += full.toDouble() * full
        sums[LOW] += low.toDouble() * low
        sums[MID] += mid.toDouble() * mid
        sums[HIGH] += high.toDouble() * high
        sums[VOCAL] += vocal.toDouble() * vocal
        if (++n < hopFrames) return
        val i = (count % capacity).toInt()
        for (b in 0 until BANDS) {
            data[b][i] = sqrt(sums[b] / n).toFloat()
            sums[b] = 0.0
        }
        n = 0
        count++
    }

    /** Media position just after the newest complete hop. */
    val endMs: Double get() = originMs + count * hopMs

    /** Media position of the oldest hop still held. */
    val startMs: Double get() = originMs + maxOf(0L, count - (capacity - 64)) * hopMs

    /** The newest [maxMs] of audio ending at or before [untilMs]. */
    fun snapshot(maxMs: Double = Double.MAX_VALUE, untilMs: Double = Double.MAX_VALUE): Snapshot? {
        val gen = generation
        val written = count
        val origin = originMs
        val hop = hopMs
        val end = if (untilMs == Double.MAX_VALUE) written
            else minOf(written, ((untilMs - origin) / hop).toLong().coerceAtLeast(0))
        val limit = if (maxMs == Double.MAX_VALUE) Long.MAX_VALUE else (maxMs / hop).toLong().coerceAtLeast(0)
        // Leave slack so the writer cannot lap the window while it is copied.
        val size = minOf(end, (capacity - 64).toLong(), limit).toInt()
        if (size <= 0) return null
        val start = end - size
        if (written - start > capacity - 64) return null
        val bands = Array(BANDS) { FloatArray(size) }
        for (k in 0 until size) {
            val i = ((start + k) % capacity).toInt()
            for (b in 0 until BANDS) bands[b][k] = data[b][i]
        }
        if (generation != gen || count - start > capacity - 16) return null
        return Snapshot(origin + start * hop, hop, bands)
    }
}

/** What a deck has heard: band envelopes plus chroma on the same media clock. */
internal class Recording(val bands: BandEnvelope.Snapshot, val chroma: ChromaEnvelope.Snapshot?)

/** RBJ band-pass (constant 0 dB peak) for the 1–4 kHz vocal-presence band. */
internal class VocalBand(rate: Int) {
    private val b0: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val omega = 2 * PI * minOf(2000.0, rate * .4) / rate
        val alpha = sin(omega) / (2 * .7)
        val a0 = 1 + alpha
        b0 = alpha / a0
        b2 = -alpha / a0
        a1 = -2 * cos(omega) / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x.toDouble()
        y2 = y1; y1 = y
        return y.toFloat()
    }

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
}
