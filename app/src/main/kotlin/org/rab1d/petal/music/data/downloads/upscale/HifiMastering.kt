/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative finishing stage for both Apollo (Restore) and DSP-only (Enhance).
 *
 * It deliberately avoids generative reconstruction: a multiband mid/side width
 * curve, a correlation guard, a quiet high-band exciter, gentle presence and
 * transient lift, and a soft-knee limiter. Width is never pushed into a
 * correlation hole, and the exciter is mixed far below the dry signal.
 */
internal class HifiMastering(
    sampleRate: Int,
    private val channels: Int,
    width: Float,
    profile: AudioProfile? = null,
) {
    private val widthAmount = width.coerceIn(0f, 1.5f)
    private val correlationGuard = if (profile == null) 1f else
        ((profile.lrCorrelation - 0.05) / 0.35).coerceIn(0.0, 1.0).toFloat()
    private val widthScale = (widthAmount / 0.5f).coerceIn(0f, 2f) * correlationGuard

    // 120 / 500 / 2000 / 8000 Hz multiband anchors for the side component.
    private val band1 = onePole(120.0, sampleRate)
    private val band2 = onePole(500.0, sampleRate)
    private val band3 = onePole(2_000.0, sampleRate)
    private val band4 = onePole(8_000.0, sampleRate)
    private val sideLow = FloatArray(4)

    // Presence split at 3 kHz; exciter split at 6 kHz.
    private val presenceCoef = onePole(3_000.0, sampleRate)
    private val exciterCoef = onePole(6_000.0, sampleRate)
    private val presenceLp = FloatArray(channels)
    private val exciterLp = FloatArray(channels)
    private val exciterAmount = if (profile?.isFullBand == true) 0.02f else 0.05f

    // Envelope followers: fast catches transients, slow follows the body.
    private val fastAttack = 1f - exp(-1.0 / (0.002 * sampleRate)).toFloat()
    private val fastRelease = 1f - exp(-1.0 / (0.080 * sampleRate)).toFloat()
    private val slowAttack = 1f - exp(-1.0 / (0.030 * sampleRate)).toFloat()
    private val slowRelease = 1f - exp(-1.0 / (0.400 * sampleRate)).toFloat()
    private val limiterAttack = 1f - exp(-1.0 / (0.002 * sampleRate)).toFloat()
    private val limiterRelease = 1f - exp(-1.0 / (0.120 * sampleRate)).toFloat()
    private val widthSmooth = 1f - exp(-1.0 / (0.050 * sampleRate)).toFloat()

    private var fastEnv = 0f
    private var slowEnv = 0f
    private var limiterGain = 1f
    private var highBandDeficit = if (profile == null || profile.isFullBand) 0f else 0.5f
    private var presenceTarget = 0.08f * highBandDeficit
    private var presenceAmount = 0f

    private val ceiling = 0.97f
    private val knee = 0.86f
    private var limitedLeft = 0f
    private var limitedRight = 0f

    fun process(data: Array<FloatArray>, frames: Int) {
        if (frames <= 0 || data.size < channels) return
        var highEnergy = 0.0
        var totalEnergy = 0.0

        for (i in 0 until frames) {
            presenceAmount += (presenceTarget - presenceAmount) * widthSmooth
            if (channels == 1) {
                var x = data[0][i]
                val low = presenceLp[0] + presenceCoef * (x - presenceLp[0])
                val high = x - low
                presenceLp[0] = low
                highEnergy += high.toDouble() * high
                x = excite(0, x + presenceAmount * high)
                val gain = dynamicsGain(abs(x))
                x = limitMono(x * gain)
                data[0][i] = x
                totalEnergy += x.toDouble() * x
                continue
            }

            var left = data[0][i]
            var right = data[1][i]

            // Presence: only the high-passed part the source is short of.
            for (c in 0 until 2) {
                val x = if (c == 0) left else right
                val low = presenceLp[c] + presenceCoef * (x - presenceLp[c])
                val high = x - low
                presenceLp[c] = low
                highEnergy += high.toDouble() * high
                if (c == 0) left += presenceAmount * high else right += presenceAmount * high
            }

            // Multiband M/S width. Bass stays at 1.00x; the side rises gently
            // through the mids and highs, then the correlation guard scales it.
            var mid = (left + right) * 0.5f
            var side = (left - right) * 0.5f
            val low1 = sideLow[0] + band1 * (side - sideLow[0]); sideLow[0] = low1
            val low2 = sideLow[1] + band2 * (side - sideLow[1]); sideLow[1] = low2
            val low3 = sideLow[2] + band3 * (side - sideLow[2]); sideLow[2] = low3
            val low4 = sideLow[3] + band4 * (side - sideLow[3]); sideLow[3] = low4
            val widened = (low1) * 1.00f +
                (low2 - low1) * 1.035f +
                (low3 - low2) * 1.075f +
                (low4 - low3) * 1.14f +
                (side - low4) * 1.17f
            side += (widened - side) * widthScale
            left = mid + side
            right = mid - side

            // Punch and quiet-passage lift, applied equally to both channels.
            mid = (left + right) * 0.5f
            val gain = dynamicsGain(abs(mid))
            left = excite(0, left * gain)
            right = excite(1, right * gain)
            limitFrame(left, right)
            left = limitedLeft
            right = limitedRight

            data[0][i] = left
            data[1][i] = right
            totalEnergy += left.toDouble() * left + right.toDouble() * right
        }

        val n = frames.toDouble()
        if (totalEnergy > 1e-12) {
            val ratio = sqrt(highEnergy / totalEnergy).toFloat()
            val deficit = ((0.10f - ratio) / 0.10f).coerceIn(0f, 1f)
            highBandDeficit += (deficit - highBandDeficit) * 0.25f
            presenceTarget = 0.08f * highBandDeficit
        }
    }

    /** Very quiet per-channel harmonic lift; high-passed above 6 kHz. */
    private fun excite(channel: Int, value: Float): Float {
        val low = exciterLp[channel] + exciterCoef * (value - exciterLp[channel])
        exciterLp[channel] = low
        val high = (value - low).coerceIn(-0.5f, 0.5f)
        return value + exciterAmount * high * abs(high)
    }

    private fun dynamicsGain(level: Float): Float {
        fastEnv = if (level > fastEnv) {
            fastEnv + (level - fastEnv) * fastAttack
        } else {
            fastEnv + (level - fastEnv) * fastRelease
        }
        slowEnv = if (level > slowEnv) {
            slowEnv + (level - slowEnv) * slowAttack
        } else {
            slowEnv + (level - slowEnv) * slowRelease
        }
        val transient = (fastEnv - slowEnv).coerceAtLeast(0f)
        val punch = 1f + 0.20f * (transient / (slowEnv + 0.05f)).coerceIn(0f, 1f)
        val quiet = ((0.04f - slowEnv) / 0.04f).coerceIn(0f, 1f)
        return (punch + 0.08f * quiet).coerceIn(1f, 1.35f)
    }

    private fun limitFrame(left: Float, right: Float) {
        val peak = max(abs(left), abs(right))
        advanceLimiter(peak)
        limitedLeft = softClip(left * limiterGain)
        limitedRight = softClip(right * limiterGain)
    }

    private fun limitMono(x: Float): Float {
        advanceLimiter(abs(x))
        return softClip(x * limiterGain)
    }

    private fun advanceLimiter(peak: Float) {
        val target = if (peak > ceiling) ceiling / peak else 1f
        limiterGain += if (target < limiterGain) {
            (target - limiterGain) * limiterAttack
        } else {
            (target - limiterGain) * limiterRelease
        }
    }

    private fun softClip(x: Float): Float {
        val ax = abs(x)
        if (ax <= knee) return x
        val sign = if (x < 0f) -1f else 1f
        val range = ceiling - knee
        val t = (ax - knee) / range
        return sign * (knee + range * (1f - exp(-t.toDouble())).toFloat())
    }

    private fun onePole(hz: Double, sampleRate: Int): Float =
        (1.0 - exp(-2.0 * PI * hz / sampleRate)).toFloat()
}
