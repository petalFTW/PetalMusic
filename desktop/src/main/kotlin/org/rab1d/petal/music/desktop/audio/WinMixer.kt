/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Windows port of the BridgeFX delay-line crossfade (BridgeEngine design): a
 * rolling ring retains the outgoing track's tail; at the boundary the ring
 * freezes and incoming PCM is blended over the retained tail with equal-power
 * gains and a style-flavoured envelope. Byte-for-byte the same math as the
 * app's TailMixer, freed from Android so PetalMusic AutoMix works on Windows
 * without the petalOS HAL — the engine is "BridgeFXWin".
 */
class WinMixer(private val sampleRate: Int = 48000, private val channels: Int = 2) {

    @Volatile var fadeMs: Int = 6000
    @Volatile var style: Int = BridgeFXWin.STYLE_CLEAN

    private var ring = FloatArray(0)
    private var ringFrames = 0
    private var write = 0
    private var valid = 0

    var mixing = false
        private set
    private var mixPos = 0L
    private var mixLen = 0L
    private var mixBase = 0
    var mixFadeFrames = 0
        private set
    var validFrames = 0
        private set

    /** Blend [frames] of the incoming deck with the retained tail, or record them. */
    fun process(buf: FloatArray, frames: Int): FloatArray {
        if (sampleRate == 0 || channels == 0) return buf
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

    /** The player moved to the next item: freeze the ring and start the blend. */
    fun markBoundary() {
        val fade = fadeMs * sampleRate / 1000
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

    fun configure(rate: Int, channelCount: Int) {
        check(rate == sampleRate && channelCount == channels) {
            "WinMixer is bound to the engine output format"
        }
        val fade = fadeMs * sampleRate / 1000
        if (fade != ringFrames) {
            ringFrames = fade
            ring = FloatArray((fade * channels).coerceAtLeast(1))
            valid = 0
            write = 0
            endMix()
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
        /** Equal-power outgoing envelope with style flavour (mirrors BridgeEngine). */
        fun gainOut(t: Double, style: Int): Float = when (style) {
            BridgeFXWin.STYLE_REVERB -> cos(t * PI / 2).pow(1.35).toFloat()
            BridgeFXWin.STYLE_STUTTER ->
                (cos(t * PI / 2) * (0.72 + 0.28 * abs(sin(t * PI * 9)))).toFloat()
            BridgeFXWin.STYLE_ADAPTIVE -> cos(t * PI / 2).pow(0.9).toFloat()
            else -> cos(t * PI / 2).toFloat()
        }

        /** Equal-power incoming envelope with style flavour. */
        fun gainIn(t: Double, style: Int): Float = when (style) {
            BridgeFXWin.STYLE_REVERB -> sin(t * PI / 2).pow(1.6).toFloat()
            BridgeFXWin.STYLE_ADAPTIVE -> sin(t * PI / 2).pow(0.9).toFloat()
            else -> sin(t * PI / 2).toFloat()
        }
    }
}
