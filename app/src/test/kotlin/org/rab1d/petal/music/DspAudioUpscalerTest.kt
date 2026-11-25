/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.data.downloads.upscale.AudioCharacter
import org.rab1d.petal.music.data.downloads.upscale.AudioProfile
import org.rab1d.petal.music.data.downloads.upscale.DspAudioUpscaler
import org.rab1d.petal.music.data.downloads.upscale.Pcm16Io
import org.rab1d.petal.music.data.downloads.upscale.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspAudioUpscalerTest {
    private val rate = 44_100

    @Test
    fun `dsp path preserves duration and never clips`() {
        val frames = rate
        val pcm = File.createTempFile("petal-dsp", ".pcm")
        val out = File.createTempFile("petal-dsp", ".wav")
        try {
            val interleaved = FloatArray(frames * 2)
            for (i in 0 until frames) {
                val x = (0.8 * sin(2.0 * PI * 1_000 * i / rate)).toFloat()
                interleaved[i * 2] = x
                interleaved[i * 2 + 1] = x
            }
            pcm.outputStream().use { Pcm16Io.write(it, interleaved, frames, 2) }
            val profile = AudioProfile(
                sampleRate = rate, channels = 2, frames = frames.toLong(),
                cutoffHz = 21_000.0, hf16Db = -20.0, hf18Db = -30.0, hf20Db = -40.0,
                lrCorrelation = 0.8, sideMidDb = -8.0, rmsDb = -6.0, crestDb = 4.0,
                peak = 0.8, clippedSamples = 0, character = AudioCharacter.FULL_BAND,
            )
            DspAudioUpscaler.process(pcm, frames.toLong(), out, profile, 0.5f, { false }, {})
            val bytes = out.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(
                WavWriter.HEADER_BYTES + frames * 2 * 2L,
                out.length(),
            )
            var peak = 0f
            for (i in WavWriter.HEADER_BYTES until bytes.size step 2) {
                val v = (((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                peak = maxOf(peak, abs(v / 32768f))
            }
            assertTrue("peak=$peak", peak <= 0.9701f)
        } finally {
            pcm.delete()
            out.delete()
        }
    }
}
