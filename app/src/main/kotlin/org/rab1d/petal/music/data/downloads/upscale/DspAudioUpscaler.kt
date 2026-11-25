/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.io.BufferedInputStream
import java.io.File

/**
 * DSP-only finishing for genuine full-band audio. No model is loaded: this is
 * the Enhance path, and it is intentionally conservative enough to avoid the
 * generative artifacts of an all-in-one restoration model.
 */
internal object DspAudioUpscaler {
    private const val BLOCK = 8192

    fun process(
        pcm: File,
        frames: Long,
        output: File,
        profile: AudioProfile,
        width: Float,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Long {
        val channels = profile.channels
        val mastering = HifiMastering(profile.sampleRate, channels, width, profile)
        val data = Array(channels) { FloatArray(BLOCK) }
        val bytes = ByteArray(BLOCK * channels * 2)
        try {
            BufferedInputStream(pcm.inputStream(), 1 shl 16).use { input ->
                WavWriter(output, channels, profile.sampleRate).use { writer ->
                    var remaining = frames
                    while (remaining > 0) {
                        if (shouldCancel()) throw UpscaleCancelledException()
                        val count = minOf(BLOCK.toLong(), remaining).toInt()
                        Pcm16Io.readChannels(input, bytes, data, count, 1f)
                        mastering.process(data, count)
                        writer.writeChannels(data, count)
                        remaining -= count
                        onProgress(1f - remaining.toFloat() / frames)
                    }
                }
            }
            val expected = WavWriter.HEADER_BYTES + frames * channels * 2L
            if (output.length() != expected) {
                throw java.io.IOException("DSP output length does not match decoded audio")
            }
            return output.length()
        } catch (e: Throwable) {
            runCatching { output.delete() }
            throw e
        }
    }
}
