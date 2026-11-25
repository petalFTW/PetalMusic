/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.io.IOException
import java.io.InputStream

/** Fixed-size inference with streaming linear overlap-add at segment seams. */
internal object PcmSegmentProcessor {
    fun process(
        input: InputStream,
        frames: Long,
        channels: Int,
        segment: Int,
        overlap: Int,
        scale: Float,
        infer: (Array<FloatArray>) -> Array<FloatArray>,
        write: (Array<FloatArray>, Int) -> Unit,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        require(frames > 0 && channels > 0 && segment > 0)
        require(overlap >= 0 && overlap <= segment / 2)
        val bytes = ByteArray(segment * channels * 2)
        val data = Array(channels) { FloatArray(segment) }
        val inputTail = Array(channels) { FloatArray(overlap) }
        val outputTail = Array(channels) { FloatArray(overlap) }
        var remaining = frames
        var first = true
        while (remaining > 0) {
            if (shouldCancel()) throw UpscaleCancelledException()
            val prefix = if (first) 0 else overlap
            val count = minOf((segment - prefix).toLong(), remaining).toInt()
            Pcm16Io.readChannels(input, bytes, data, count, scale)
            if (prefix > 0) {
                for (c in 0 until channels) {
                    System.arraycopy(data[c], 0, data[c], prefix, count)
                    System.arraycopy(inputTail[c], 0, data[c], 0, prefix)
                }
            }
            val valid = prefix + count
            remaining -= count
            if (remaining > 0) {
                for (c in 0 until channels) {
                    System.arraycopy(data[c], valid - overlap, inputTail[c], 0, overlap)
                }
            }
            val output = infer(data)
            if (output.size != channels || output.any { it.size < segment }) {
                throw IOException("model output does not preserve segment length and channels")
            }
            for (c in 0 until channels) {
                for (i in 0 until prefix) {
                    val weight = (i + 1f) / (prefix + 1f)
                    output[c][i] = outputTail[c][i] * (1f - weight) + output[c][i] * weight
                }
            }
            val emit = if (remaining > 0) valid - overlap else valid
            if (remaining > 0) {
                for (c in 0 until channels) {
                    System.arraycopy(output[c], emit, outputTail[c], 0, overlap)
                }
            }
            write(output, emit)
            first = false
            onProgress(((frames - remaining).toFloat() / frames).coerceIn(0f, 1f))
        }
    }
}
