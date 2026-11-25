/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

/** Finite, sample-clock ramp: even a requested cut reaches exactly zero without a click. */
internal class BandGainRamp {
    data class Gains(val sub: Float = 1f, val bass: Float = 1f, val mid: Float = 1f, val high: Float = 1f)

    val current = FloatArray(4) { 1f }
    private val step = FloatArray(4)
    private val target = FloatArray(4) { 1f }
    private var remaining = 0
    private var last = Gains()

    fun reset(gains: Gains) {
        last = gains
        copyTarget(gains)
        target.copyInto(current)
        remaining = 0
    }

    fun setTarget(gains: Gains, sampleRate: Int) {
        if (gains == last) return
        last = gains
        copyTarget(gains)
        remaining = (sampleRate * 0.005f).toInt().coerceAtLeast(1)
        for (b in 0..3) step[b] = (target[b] - current[b]) / remaining
    }

    fun advance() {
        if (remaining == 0) return
        if (--remaining == 0) target.copyInto(current)
        else for (b in 0..3) current[b] += step[b]
    }

    private fun copyTarget(g: Gains) {
        target[0] = g.sub.coerceIn(0f, 1f)
        target[1] = g.bass.coerceIn(0f, 1f)
        target[2] = g.mid.coerceIn(0f, 1f)
        target[3] = g.high.coerceIn(0f, 1f)
    }
}
