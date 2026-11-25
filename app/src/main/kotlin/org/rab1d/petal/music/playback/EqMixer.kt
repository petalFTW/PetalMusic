/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * In-app DJ crossmixer owned entirely by the app.
 *
 * A ring records the outgoing track; at the boundary the player switches to the
 * next item and this plays the **real** retained tail once while the incoming
 * track starts, trading sub / low / mid / high bands on independent equal-power
 * curves (sub last, so the kick swaps rather than doubles). The result is a true
 * EQ crossfade with the next song playing early — not the ROM BridgeFX one-bar
 * loop, and not a plain volume fade.
 *
 * Band scheme and crossover points are ported from the BridgeFX planner so the
 * character matches, but nothing here depends on the HAL.
 */
class EqMixer {

    @Volatile var fadeMs: Int = 6000
    @Volatile var style: Int = BridgeFX.STYLE_CLEAN

    private class X {
        var l1a = 0f; var l1b = 0f
        var l2a = 0f; var l2b = 0f
        var l3a = 0f; var l3b = 0f
        fun reset() { l1a = 0f; l1b = 0f; l2a = 0f; l2b = 0f; l3a = 0f; l3b = 0f }
    }

    private var rate = 0
    private var channels = 0
    private var ring = FloatArray(0)
    private var ringFrames = 0
    private var write = 0
    private var valid = 0

    @Volatile private var mixing = false
    private var mixPos = 0L
    private var mixLen = 0L
    private var mixBase = 0
    private var mixFade = 0
    private var mixStartMs = 0L

    private var k1 = 0f; private var k2 = 0f; private var k3 = 0f
    private var tailX = ArrayList<X>()
    private var liveX = ArrayList<X>()
    private val tb = FloatArray(4)
    private val lb = FloatArray(4)

    // Per band: aStart, aEnd, bStart, bEnd (fractions of the blend).
    private var curves = BASE

    /** True while the outgoing tail is being blended over the incoming. */
    val isMixing: Boolean get() = mixing

    fun configure(sampleRate: Int, channelCount: Int) {
        if (rate == sampleRate && channels == channelCount) return
        rate = sampleRate.coerceAtLeast(8000)
        channels = channelCount.coerceIn(1, 8)
        val nyq = rate * 0.45f
        fun coeff(hz: Float) = 1f - exp(-2f * PI.toFloat() * minOf(hz, nyq) / rate)
        k1 = coeff(90f)      // sub
        k2 = coeff(250f)     // low
        k3 = coeff(2000f)    // mid
        resizeRing()
        tailX = ArrayList(List(channels) { X() })
        liveX = ArrayList(List(channels) { X() })
    }

    fun reset() {
        valid = 0; write = 0
        endMix()
    }

    /**
     * The sink was flushed (seek / track change). Stop any in-flight mix but
     * keep the recorded ring: the upcoming transition still needs that tail.
     */
    fun flush() {
        if (mixing) android.util.Log.i("PetalEqMix", "flush during mix frame=$mixPos/$mixLen")
        endMix()
    }

    private fun resizeRing() {
        // Sized for the longest supported transition so it never has to be
        // rebuilt at a boundary (which would discard the captured tail).
        ringFrames = (MAX_FADE_MS * rate / 1000).coerceAtLeast(1)
        ring = FloatArray(ringFrames * channels)
        valid = 0; write = 0
    }

    /** The player moved to the next item: freeze the tail and start blending. */
    fun markBoundary() {
        val fade = (fadeMs.coerceIn(250, MAX_FADE_MS) * rate / 1000)
        if (rate == 0 || valid <= 0 || fade <= 0) return
        mixFade = fade
        mixLen = valid.coerceAtMost(fade).toLong()
        mixBase = (write + ringFrames - valid) % ringFrames
        curves = curvesFor(style)
        for (x in tailX) x.reset()
        for (x in liveX) x.reset()
        mixPos = 0
        mixing = true
        mixStartMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i(
            "PetalEqMix",
            "boundary fade=${fade} frames tail=${mixLen} frames style=$style",
        )
    }

    fun abort() {
        if (mixing) android.util.Log.i("PetalEqMix", "abort at frame=$mixPos/$mixLen")
        endMix()
    }

    private fun endMix() {
        mixing = false
        mixPos = 0; mixLen = 0
    }

    /** Called for every processed buffer; returns the (possibly blended) buffer. */
    fun process(buf: FloatArray, frames: Int) {
        if (rate == 0 || channels == 0) return
        val ch = channels
        var f = 0
        while (f < frames) {
            if (mixing && mixPos < mixLen) {
                val t = (mixPos.toDouble() / mixFade).coerceIn(0.0, 1.0)
                val tailIdx = ((mixBase + mixPos) % ringFrames).toInt() * ch
                for (c in 0 until ch) {
                    val live = buf[f * ch + c]
                    val tail = ring[tailIdx + c]
                    var y = 0f
                    split4(tailX[c], tail, tb)
                    split4(liveX[c], live, lb)
                    for (band in 0 until 4) {
                        val ci = band * 4
                        y += tb[band] * bandA(curves, ci, t) + lb[band] * bandB(curves, ci, t)
                    }
                    buf[f * ch + c] = y
                }
                mixPos++
                f++
                if (mixPos >= mixLen) {
                    android.util.Log.i(
                        "PetalEqMix",
                        "mix complete frames=$mixPos elapsed=${
                            android.os.SystemClock.elapsedRealtime() - mixStartMs
                        }ms",
                    )
                    endMix()
                }
            } else {
                record(buf, f, frames - f)
                break
            }
        }
    }

    private fun record(buf: FloatArray, from: Int, count: Int) {
        if (ringFrames <= 0) return
        val ch = channels
        for (i in 0 until count) {
            val base = (from + i) * ch
            val idx = write * ch
            for (c in 0 until ch) ring[idx + c] = buf[base + c]
            write = (write + 1) % ringFrames
            if (valid < ringFrames) valid++
        }
    }

    private fun split4(s: X, x: Float, out: FloatArray) {
        s.l1a += k1 * (x - s.l1a); s.l1b += k1 * (s.l1a - s.l1b)
        s.l2a += k2 * (x - s.l2a); s.l2b += k2 * (s.l2a - s.l2b)
        s.l3a += k3 * (x - s.l3a); s.l3b += k3 * (s.l3a - s.l3b)
        out[0] = s.l1b
        out[1] = s.l2b - s.l1b
        out[2] = s.l3b - s.l2b
        out[3] = x - s.l3b
    }

    companion object {
        private const val MAX_FADE_MS = 12000
        // sub, low, mid, high — aStart, aEnd, bStart, bEnd.
        private val BASE = floatArrayOf(
            0.42f, 0.58f, 0.44f, 0.60f,
            0.30f, 0.70f, 0.10f, 0.78f,
            0.18f, 0.80f, 0.00f, 0.85f,
            0.04f, 0.50f, 0.00f, 0.45f,
        )
        private val BASS_SWAP = floatArrayOf(
            0.42f, 0.54f, 0.46f, 0.58f,
            0.30f, 0.64f, 0.20f, 0.72f,
            0.18f, 0.80f, 0.00f, 0.85f,
            0.04f, 0.50f, 0.00f, 0.45f,
        )
        private val FILTER = floatArrayOf(
            0.20f, 0.80f, 0.15f, 0.85f,
            0.15f, 0.85f, 0.05f, 0.90f,
            0.10f, 0.90f, 0.00f, 0.90f,
            0.05f, 0.90f, 0.00f, 0.90f,
        )

        fun curvesFor(style: Int): FloatArray = when (style) {
            BridgeFX.STYLE_STUTTER -> BASS_SWAP
            BridgeFX.STYLE_ADAPTIVE -> BASE
            BridgeFX.STYLE_REVERB -> FILTER
            else -> BASE
        }

        /** Outgoing band envelope: cos over [start,end], step outside. */
        private fun bandA(curves: FloatArray, ci: Int, t: Double): Float {
            val s = curves[ci].toDouble(); val e = curves[ci + 1].toDouble()
            if (e <= s) return if (t < s) 1f else 0f
            val u = ((t - s) / (e - s)).coerceIn(0.0, 1.0)
            return cos(u * PI / 2).toFloat()
        }

        /** Incoming band envelope: sin over [start,end], step outside. */
        private fun bandB(curves: FloatArray, ci: Int, t: Double): Float {
            val s = curves[ci + 2].toDouble(); val e = curves[ci + 3].toDouble()
            if (e <= s) return if (t < s) 0f else 1f
            val u = ((t - s) / (e - s)).coerceIn(0.0, 1.0)
            return sin(u * PI / 2).toFloat()
        }
    }
}
