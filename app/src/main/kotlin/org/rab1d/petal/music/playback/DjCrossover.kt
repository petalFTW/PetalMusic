/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Fourth-order Linkwitz–Riley splits with phase compensation between branches.
 * Unity sum has flat magnitude (all-pass phase), not sample-identical waveform.
 * Biquads use the W3C/RBJ Audio EQ Cookbook coefficients. No per-sample allocation.
 */
internal class DjCrossover(rate: Int) {
    private class Biquad(rate: Int, hz: Double, kind: Int) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var z1 = 0.0
        private var z2 = 0.0
        init {
            val omega = 2 * PI * minOf(hz, rate * .4) / rate
            val c = cos(omega)
            val alpha = sin(omega) / sqrt(2.0)
            val a0 = 1 + alpha
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
            when (kind) {
                0 -> { b0 = (1 - c) / 2 / a0; b1 = 2 * b0; b2 = b0 }
                1 -> { b0 = (1 + c) / 2 / a0; b1 = -2 * b0; b2 = b0 }
                else -> { b0 = a2; b1 = a1; b2 = 1.0 }
            }
        }
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        fun reset() { z1 = 0.0; z2 = 0.0 }
    }
    private class Split(rate: Int, hz: Double) {
        val filters = arrayOf(Biquad(rate, hz, 0), Biquad(rate, hz, 0),
            Biquad(rate, hz, 1), Biquad(rate, hz, 1))
        fun low(x: Double) = filters[1].process(filters[0].process(x))
        fun high(x: Double) = filters[3].process(filters[2].process(x))
        fun reset() = filters.forEach { it.reset() }
    }
    private val body = Split(rate, 300.0)
    private val bottom = Split(rate, 90.0)
    private val top = Split(rate, 3000.0)
    private val lowPhase = Biquad(rate, 3000.0, 2)
    private val highPhase = Biquad(rate, 90.0, 2)

    fun split(x: Float, bands: FloatArray) {
        val low = lowPhase.process(body.low(x.toDouble()))
        val high = highPhase.process(body.high(x.toDouble()))
        bands[0] = bottom.low(low).toFloat()
        bands[1] = bottom.high(low).toFloat()
        bands[2] = top.low(high).toFloat()
        bands[3] = top.high(high).toFloat()
    }
    fun reset() {
        body.reset(); bottom.reset(); top.reset(); lowPhase.reset(); highPhase.reset()
    }
}
