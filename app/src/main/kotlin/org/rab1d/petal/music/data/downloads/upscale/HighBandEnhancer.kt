/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Adds only the model's high-band residual, retaining the original below the
 * detected cutoff. This is the deficit mask: a full-band input keeps its real
 * band, and a codec cliff gets the reconstructed top above that cliff.
 */
internal class HighBandEnhancer(
    sampleRate: Int,
    cutoffHz: Double = 8_000.0,
    private val amount: Float = 0.5f,
) {
    private val half = 64
    private val lowPass = DoubleArray(half * 2 + 1)

    init {
        require(sampleRate > 0 && cutoffHz > 0 && cutoffHz < sampleRate / 2.0)
        val cutoff = cutoffHz / sampleRate
        for (i in lowPass.indices) {
            val offset = i - half
            val sinc = if (offset == 0) 2 * cutoff else
                sin(2 * PI * cutoff * offset) / (PI * offset)
            val window = 0.42 + 0.5 * cos(PI * offset / half) + 0.08 * cos(2 * PI * offset / half)
            lowPass[i] = sinc * window
        }
        val norm = lowPass.sum()
        for (i in lowPass.indices) lowPass[i] /= norm
    }

    /** Symmetric filtering introduces no delay; neither input array is modified. */
    fun enhance(original: Array<FloatArray>, reconstructed: Array<FloatArray>): Array<FloatArray> {
        require(original.size == reconstructed.size)
        return Array(original.size) { c ->
            val source = original[c]
            val prediction = reconstructed[c]
            require(source.size == prediction.size)
            val residual = FloatArray(source.size) { prediction[it] - source[it] }
            FloatArray(source.size) { i ->
                var low = 0.0
                for (tap in lowPass.indices) {
                    val index = (i + tap - half).coerceIn(0, source.lastIndex)
                    low += residual[index] * lowPass[tap]
                }
                val high = (residual[i] - low).toFloat()
                // Leave the source unchanged below the crossover and avoid
                // letting hallucinated high-band peaks overload the PCM writer.
                val correction = (amount * high).coerceIn(-0.35f, 0.35f)
                val headroom = if (correction >= 0f) (1f - source[i]).coerceAtLeast(0f)
                    else (1f + source[i]).coerceAtLeast(0f)
                source[i] + correction.coerceIn(-headroom, headroom)
            }
        }
    }
}
