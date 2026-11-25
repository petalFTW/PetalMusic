/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * App-level port of the BridgeEngine delay-line crossfade: a rolling ring
 * retains the outgoing track's tail; when the player transitions to the next
 * item the ring freezes and incoming PCM is blended over the retained tail
 * with equal-power gains and a style-flavoured envelope. No playback delay,
 * no allocation in the processing loop — the same design as the system
 * effect, in software, so AutoMix also works on ROMs without the BridgeFX HAL.
 */
class TailMixer {

    @Volatile var fadeMs: Int = 6000
    @Volatile var style: Int = BridgeFX.STYLE_CLEAN

    private var rate = 0
    private var channels = 0
    private var ring = FloatArray(0)
    private var ringFrames = 0
    private var write = 0
    private var valid = 0

    // Boundary state
    @Volatile var mixing = false
        private set
    private var mixPos = 0L
    private var mixLen: Long = 0
    private var mixBase = 0
    @Volatile var mixFadeFrames = 0
        private set
    @Volatile var validFrames = 0
        private set

    /** Called for every processed buffer; returns the (possibly blended) buffer. */
    fun process(buf: FloatArray, frames: Int): FloatArray {
        if (rate == 0 || channels == 0) return buf
        if (mixing && mixLen > 0) {
            val ch = channels
            var i = 0
            var f = 0
            while (f < frames) {
                val u = mixPos + f
                if (u >= mixLen) {
                    endMix()
                    break
                }
                val t = u.toDouble() / mixFadeFrames
                val gIn = gainIn(t, style)
                val gOut = gainOut(t, style)
                val tailIdx = (((mixBase + u) % ringFrames).toInt()) * ch
                for (c in 0 until ch) {
                    buf[i + c] = buf[i + c] * gIn + ring[tailIdx + c] * gOut
                }
                i += ch
                f++
            }
            if (mixing) mixPos += frames
            return buf
        }
        record(buf, frames)
        return buf
    }

    /**
     * The player moved to the next item: freeze the ring (it holds the
     * outgoing tail) and start blending incoming audio over it.
     */
    fun markBoundary() {
        val fade = fadeMs * rate / 1000
        if (fade <= 0 || valid <= 0) return
        mixFadeFrames = fade
        mixLen = valid.coerceAtMost(fade).toLong()
        mixBase = (write + ringFrames - valid) % ringFrames
        mixing = true
        mixPos = 0
    }

    fun abort() {
        endMix()
    }

    fun configure(sampleRate: Int, channelCount: Int) {
        if (rate != sampleRate || channels != channelCount) {
            rate = sampleRate
            channels = channelCount
            ringFrames = 0
            valid = 0
            write = 0
            endMix()
        }
        val fade = fadeMs * rate / 1000
        if (fade != ringFrames) {
            ringFrames = fade
            ring = FloatArray((fade * channels).coerceAtLeast(1))
            valid = 0
            write = 0
        }
    }

    private fun endMix() {
        mixing = false
        mixPos = 0
        mixLen = 0
        valid = 0
    }

    private fun record(buf: FloatArray, frames: Int) {
        if (ringFrames <= 0) return
        val ch = channels
        if (valid < ringFrames) valid = (valid + frames).coerceAtMost(ringFrames)
        validFrames = valid
        for (f in 0 until frames) {
            val idx = write * ch
            if (idx + ch <= ring.size) System.arraycopy(buf, f * ch, ring, idx, ch)
            write = (write + 1) % ringFrames
        }
    }

    companion object {
        /** Equal-power outgoing envelope with style flavour. */
        fun gainOut(t: Double, style: Int): Float = when (style) {
            BridgeFX.STYLE_REVERB -> cos(t * Math.PI / 2).pow(1.35).toFloat()
            BridgeFX.STYLE_STUTTER ->
                (cos(t * Math.PI / 2) * (0.72 + 0.28 * kotlin.math.abs(sin(t * Math.PI * 9)))).toFloat()
            BridgeFX.STYLE_ADAPTIVE -> cos(t * Math.PI / 2).pow(0.9).toFloat()
            else -> cos(t * Math.PI / 2).toFloat()
        }

        /** Equal-power incoming envelope with style flavour. */
        fun gainIn(t: Double, style: Int): Float = when (style) {
            BridgeFX.STYLE_REVERB -> sin(t * Math.PI / 2).pow(1.6).toFloat()
            BridgeFX.STYLE_ADAPTIVE -> sin(t * Math.PI / 2).pow(0.9).toFloat()
            else -> sin(t * Math.PI / 2).toFloat()
        }
    }
}
