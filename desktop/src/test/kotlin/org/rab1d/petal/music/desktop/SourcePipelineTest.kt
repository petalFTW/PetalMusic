/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop

import org.rab1d.petal.music.desktop.audio.PcmIO
import org.rab1d.petal.music.desktop.audio.PcmRawSource
import org.rab1d.petal.music.desktop.audio.SourcePipeline
import org.rab1d.petal.music.desktop.audio.WavRawSource
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourcePipelineTest {

    private class SineSource(
        override val sampleRate: Int,
        override val channels: Int,
        private val frames: Long,
    ) : PcmRawSource {
        private var produced = 0L
        private val buf = FloatArray(2048 * channels)

        override fun read(out: FloatArray, maxFrames: Int): Int {
            if (produced >= frames) return -1
            val n = minOf(maxFrames, buf.size / channels, (frames - produced).toInt())
            for (f in 0 until n) {
                val v = sin(2.0 * PI * 440.0 * (produced + f) / sampleRate).toFloat() * 0.8f
                for (c in 0 until channels) out[f * channels + c] = v
            }
            produced += n
            return n
        }

        override fun close() = Unit
    }

    @Test
    fun `44100 stereo resamples to 48000 without drift`() {
        SourcePipeline(SineSource(44100, 2, 44100), 48000, 2).use { pipeline ->
            val out = FloatArray(4800 * 2)
            var total = 0
            while (true) {
                val n = pipeline.read(out, 4800)
                if (n < 0) break
                total += n
            }
            assertEquals(48000f, total.toFloat(), 240f, "one second in, one second out")
        }
    }

    @Test
    fun `mono sources upmix to stereo`() {
        SourcePipeline(SineSource(48000, 1, 4800), 48000, 2).use { pipeline ->
            val out = FloatArray(4800 * 2)
            var total = 0
            while (true) {
                val n = pipeline.read(out, 4800)
                if (n < 0) break
                total += n
            }
            assertEquals(4800, total)
            assertEquals(out[0], out[1], 1e-6f, "channels identical")
        }
    }

    @Test
    fun `wav files round-trip through the decoder`() {
        val rate = 48000
        val frames = 9600
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        val pcm = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * 220.0 * f / rate) * 20000).toInt()
            pcm[f * 4] = v.toByte()
            pcm[f * 4 + 1] = (v shr 8).toByte()
            pcm[f * 4 + 2] = pcm[f * 4]
            pcm[f * 4 + 3] = pcm[f * 4 + 1]
        }
        val file = Files.createTempFile("petalwin", ".wav")
        Files.delete(file)
        AudioSystem.write(
            AudioInputStream(pcm.inputStream(), format, frames.toLong()),
            AudioFileFormat.Type.WAVE,
            file.toFile(),
        )
        try {
            WavRawSource(file).use { raw ->
                assertEquals(rate, raw.sampleRate)
                assertEquals(2, raw.channels)
                val out = FloatArray(4800 * 2)
                var total = 0
                var peak = 0f
                while (true) {
                    val n = raw.read(out, 4800)
                    if (n < 0) break
                    total += n
                    for (i in 0 until n * 2) peak = maxOf(peak, kotlin.math.abs(out[i]))
                }
                assertEquals(frames, total)
                assertTrue(peak in 0.5f..0.7f, "peak=$peak")
            }
            assertTrue(PcmIO.estimateDurationMs(file) in 190..210, "duration=${PcmIO.estimateDurationMs(file)}")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
