/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.link

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative finishing stage for the desktop Device Link DSP path: multiband
 * M/S width (bass unchanged), gentle presence and transient lift, and a
 * soft-knee limiter. Harmonics are supplied by [BandwidthExtender], so this
 * class deliberately does not add a second exciter.
 */
internal class HifiMastering(
    sampleRate: Int,
    private val channels: Int,
    width: Float,
) {
    private val widthAmount = width.coerceIn(0f, 1.5f)
    private val widthScale = (widthAmount / 0.5f).coerceIn(0f, 2f)

    private val band1 = onePole(120.0, sampleRate)
    private val band2 = onePole(500.0, sampleRate)
    private val band3 = onePole(2_000.0, sampleRate)
    private val band4 = onePole(8_000.0, sampleRate)
    private val sideLow = FloatArray(4)

    private val presenceCoef = onePole(3_000.0, sampleRate)
    private val presenceLp = FloatArray(channels)

    private val fastAttack = 1f - exp(-1.0 / (0.002 * sampleRate)).toFloat()
    private val fastRelease = 1f - exp(-1.0 / (0.080 * sampleRate)).toFloat()
    private val slowAttack = 1f - exp(-1.0 / (0.030 * sampleRate)).toFloat()
    private val slowRelease = 1f - exp(-1.0 / (0.400 * sampleRate)).toFloat()
    private val limiterAttack = 1f - exp(-1.0 / (0.002 * sampleRate)).toFloat()
    private val limiterRelease = 1f - exp(-1.0 / (0.120 * sampleRate)).toFloat()
    private val presenceSmooth = 1f - exp(-1.0 / (0.100 * sampleRate)).toFloat()

    private var fastEnv = 0f
    private var slowEnv = 0f
    private var limiterGain = 1f
    private var highBandDeficit = 0f
    private var presenceTarget = 0f
    private var presenceAmount = 0f
    private val ceiling = 0.97f
    private val knee = 0.86f
    private var limitedLeft = 0f
    private var limitedRight = 0f

    fun process(samples: FloatArray, frames: Int) {
        if (frames <= 0 || channels <= 0) return
        // The desktop receives the whole track in one call, so measure the
        // actual correlation once and use it to guard the width curve.
        var sl = 0.0
        var sr = 0.0
        var slr = 0.0
        if (channels > 1) {
            for (i in 0 until frames) {
                val base = i * channels
                if (base + 1 >= samples.size) break
                val l = samples[base].toDouble()
                val r = samples[base + 1].toDouble()
                sl += l * l
                sr += r * r
                slr += l * r
            }
        }
        val corr = if (sl > 1e-12 && sr > 1e-12) (slr / sqrt(sl * sr)).coerceIn(-1.0, 1.0) else 1.0
        val guard = ((corr - 0.05) / 0.35).coerceIn(0.0, 1.0).toFloat()
        val activeWidth = widthScale * guard
        var highEnergy = 0.0
        var totalEnergy = 0.0
        for (i in 0 until frames) {
            presenceAmount += (presenceTarget - presenceAmount) * presenceSmooth
            val base = i * channels
            if (base + channels > samples.size) break
            if (channels == 1) {
                var x = samples[base]
                val low = presenceLp[0] + presenceCoef * (x - presenceLp[0])
                val high = x - low
                presenceLp[0] = low
                highEnergy += high.toDouble() * high
                x += presenceAmount * high
                x = limitMono(x * dynamicsGain(abs(x)))
                samples[base] = x
                totalEnergy += x.toDouble() * x
                continue
            }

            var left = samples[base]
            var right = samples[base + 1]
            for (c in 0 until 2) {
                val x = if (c == 0) left else right
                val low = presenceLp[c] + presenceCoef * (x - presenceLp[c])
                val high = x - low
                presenceLp[c] = low
                highEnergy += high.toDouble() * high
                if (c == 0) left += presenceAmount * high else right += presenceAmount * high
            }

            var mid = (left + right) * 0.5f
            var side = (left - right) * 0.5f
            val low1 = sideLow[0] + band1 * (side - sideLow[0]); sideLow[0] = low1
            val low2 = sideLow[1] + band2 * (side - sideLow[1]); sideLow[1] = low2
            val low3 = sideLow[2] + band3 * (side - sideLow[2]); sideLow[2] = low3
            val low4 = sideLow[3] + band4 * (side - sideLow[3]); sideLow[3] = low4
            val widened = low1 * 1.00f +
                (low2 - low1) * 1.035f +
                (low3 - low2) * 1.075f +
                (low4 - low3) * 1.14f +
                (side - low4) * 1.17f
            side += (widened - side) * activeWidth
            left = mid + side
            right = mid - side

            mid = (left + right) * 0.5f
            val gain = dynamicsGain(abs(mid))
            limitFrame(left * gain, right * gain)
            left = limitedLeft
            right = limitedRight
            samples[base] = left
            samples[base + 1] = right
            totalEnergy += left.toDouble() * left + right.toDouble() * right
        }

        if (totalEnergy > 1e-12) {
            val ratio = sqrt(highEnergy / totalEnergy).toFloat()
            val deficit = ((0.10f - ratio) / 0.10f).coerceIn(0f, 1f)
            highBandDeficit += (deficit - highBandDeficit) * 0.25f
            presenceTarget = 0.08f * highBandDeficit
        }
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
