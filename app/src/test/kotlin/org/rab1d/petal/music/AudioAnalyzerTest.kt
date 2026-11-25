/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.data.downloads.upscale.AudioAnalyzer
import org.rab1d.petal.music.data.downloads.upscale.AudioCharacter
import org.rab1d.petal.music.data.downloads.upscale.Pcm16Io
import org.rab1d.petal.music.data.downloads.upscale.Radix2Fft
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.random.Random

class AudioAnalyzerTest {
    private val rate = 44_100

    @Test
    fun `fft finds a one kilohertz tone`() {
        val n = 4096
        val re = FloatArray(n) { i -> (0.5 * sin(2.0 * PI * 1_000 * i / rate)).toFloat() }
        val im = FloatArray(n)
        Radix2Fft(n).transform(re, im)
        var peak = 1
        for (i in 1..n / 2) {
            if (re[i] * re[i] + im[i] * im[i] > re[peak] * re[peak] + im[peak] * im[peak]) peak = i
        }
        val hz = peak.toDouble() * rate / n
        assertTrue("peak=$hz", abs(hz - 1_000) < 15)
    }

    @Test
    fun `cutoff estimator sees a codec cliff but not a gentle rolloff`() {
        val bins = 2049
        val cliff = DoubleArray(bins) { i ->
            val hz = i.toDouble() * rate / 4096
            if (hz < 16_000) 1.0 else 1e-8
        }
        val cutoff = AudioAnalyzer.estimateCutoff(cliff, rate, 4096)
        assertTrue("cutoff=$cutoff", abs(cutoff - 16_000) < 800)

        val gentle = DoubleArray(bins) { i ->
            val hz = i.toDouble() * rate / 4096
            10.0.pow(-hz / 20_000.0)
        }
        assertEquals(rate / 2.0, AudioAnalyzer.estimateCutoff(gentle, rate, 4096), 1.0)
    }

    @Test
    fun `full band and band limited signals route differently`() {
        val duration = 2
        val full = stereoPcm(duration) { i ->
            val x = (Random(i).nextDouble() * 2 - 1) * 0.1
            x.toFloat()
        }
        val profileFull = analyze(full)
        assertEquals(AudioCharacter.FULL_BAND, profileFull.character)
        assertEquals(1.0, profileFull.lrCorrelation, 0.01)

        val limited = stereoPcm(duration) { i ->
            val t = i.toDouble() / rate
            (0.1 * sin(2 * PI * 440 * t) + 0.08 * sin(2 * PI * 1_000 * t) +
                0.06 * sin(2 * PI * 2_000 * t) + 0.05 * sin(2 * PI * 4_000 * t)).toFloat()
        }
        val profileLimited = analyze(limited)
        assertTrue(profileLimited.cutoffHz < 8_000)
        assertTrue(profileLimited.character != AudioCharacter.FULL_BAND)
    }

    @Test
    fun `stereo correlation and clipping are measured`() {
        val pcm = stereoPcm(1) { i ->
            val x = (0.2 * sin(2.0 * PI * 1_000 * i / rate)).toFloat()
            x
        }
        val profile = analyze(pcm)
        assertEquals(1.0, profile.lrCorrelation, 0.01)
        assertTrue(profile.sideMidDb < -60)
        assertTrue(profile.peak > 0.15)
    }

    private fun analyze(bytes: ByteArray): org.rab1d.petal.music.data.downloads.upscale.AudioProfile {
        val file = File.createTempFile("petal-profile", ".pcm")
        try {
            file.writeBytes(bytes)
            return AudioAnalyzer.analyze(file, bytes.size / 4L, rate, 2)
        } finally {
            file.delete()
        }
    }

    private fun stereoPcm(seconds: Int, sample: (Int) -> Float): ByteArray {
        val frames = rate * seconds
        val interleaved = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val x = sample(i)
            interleaved[i * 2] = x
            interleaved[i * 2 + 1] = x
        }
        val out = java.io.ByteArrayOutputStream()
        Pcm16Io.write(out, interleaved, frames, 2)
        return out.toByteArray()
    }
}
