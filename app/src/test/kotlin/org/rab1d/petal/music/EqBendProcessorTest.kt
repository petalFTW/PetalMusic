/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class EqBendProcessorTest {
    private fun processor() = EqBendProcessor().apply {
        configure(AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        flush()
    }

    private fun process(p: EqBendProcessor, samples: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(samples)
        p.queueInput(input)
        val output = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(output.remaining()).also { output.get(it) }
    }

    @Test fun `unity preserves stereo levels and full kill reaches digital silence`() {
        val p = processor()
        val input = FloatArray(96000) { i -> (.5 * sin(2 * Math.PI * (i / 2) * if (i % 2 == 0) 440.0 / 48000 else 1200.0 / 48000)).toFloat() }
        val unity = process(p, input)
        // Linkwitz–Riley unity reconstruction is all-pass, so compare energy
        // after settling rather than demanding the old zero-phase waveform.
        for (channel in 0..1) {
            val energy = (48000 + channel until input.size step 2).sumOf { unity[it].toDouble() * unity[it] }
            assertEquals(.5 / kotlin.math.sqrt(2.0), kotlin.math.sqrt(energy / 24000), .001)
        }
        p.setGains(0f, 0f, 0f, 0f)
        val muted = process(p, input)
        for (i in 480 until muted.size) assertEquals(0f, muted[i], 0f)
    }

    @Test fun `actual mixed PCM retains outgoing bass and incoming mids while rejecting their conflicts`() {
        val controller = AdaptiveEqController(EqTransition.Context(beatMs = 500f), 8000)
        val low = BandLevels(.28f, .22f, .04f, .02f)
        val mid = BandLevels(.025f, .04f, .25f, .06f)
        fun frame(b: BandLevels) = MixAudioMonitor.Frame(1000, 1000, .3f, b, b, BandLevels.ZERO)
        var point = controller.snapshot().points.last()
        repeat(90) { point = controller.update((it + 1) / 200f, 40f, frame(low), frame(mid)) }
        val a = processor()
        val b = processor()
        fun set(p: EqBendProcessor, g: BandLevels) = p.setGains(g.sub, g.bass, g.mid, g.high)
        set(a, point.outgoing)
        set(b, point.incoming)
        fun tone(hz: Double, n: Int) = sin(2 * Math.PI * hz * n / 48000)
        val out = process(a, FloatArray(96000) { i -> (.2 * (tone(40.0, i / 2) + tone(1000.0, i / 2))).toFloat() })
        val next = process(b, FloatArray(96000) { i -> (.2 * (tone(70.0, i / 2) + tone(1500.0, i / 2))).toFloat() })
        fun amplitude(hz: Double): Double {
            var real = 0.0
            var imaginary = 0.0
            for (n in 24000 until 48000) {
                val value = out[n * 2] + next[n * 2]
                val phase = 2 * Math.PI * hz * n / 48000
                real += value * kotlin.math.cos(phase)
                imaginary += value * sin(phase)
            }
            return 2 * kotlin.math.sqrt(real * real + imaginary * imaginary) / 24000
        }
        assertTrue(amplitude(40.0) > .18)
        assertTrue(amplitude(1500.0) > .18)
        assertTrue(amplitude(70.0) < .003)
        assertTrue(amplitude(1000.0) < .003)
    }

    @Test fun `muted deck still supplies dry audio analysis and seek drops stale history`() {
        val p = processor()
        val input = FloatArray(48000) { i -> if (i % 2 == 0) .25f else -.25f }
        process(p, input)
        assertEquals(.25f, p.featuresAt(400)!!.rms, .00001f)
        p.setGains(0f, 0f, 0f, 0f)
        val muted = process(p, input)
        assertEquals(0f, muted.last(), 0f)
        assertEquals(.25f, p.featuresAt(900)!!.rms, .00001f)
        p.beginMonitoring(45000)
        assertNull(p.featuresAt(900))
        p.flush()
        process(p, input)
        assertEquals(.25f, p.featuresAt(45400)!!.rms, .00001f)
        assertNull(p.featuresAt(900))
    }

    @Test fun `tempo flush preserves waveform continuity and late track analysis`() {
        val reference = processor()
        val changed = processor()
        for (p in listOf(reference, changed)) {
            p.beginMonitoring(180000)
            p.flush()
        }
        fun tone(offset: Int) = FloatArray(48000) { i ->
            (.4 * sin(2 * Math.PI * (offset + i / 2) * 173.0 / 48000)).toFloat()
        }
        val first = tone(0)
        process(reference, first)
        process(changed, first)
        changed.flush()
        val second = tone(24000)
        val expected = process(reference, second)
        val actual = process(changed, second)
        assertArrayEquals(expected, actual, .000001f)
        assertNotNull(changed.featuresAt(180900))
        assertNull(changed.featuresAt(900))
    }

    @Test fun `abrupt gain request is smoothed across multiple buffers`() {
        val p = processor()
        process(p, FloatArray(9600) { .5f })
        p.setGains(0f, 0f, 0f, 0f)
        var previous = .5f
        repeat(5) {
            val result = process(p, FloatArray(128) { .5f })
            for (i in result.indices step 2) {
                assertTrue(abs(result[i] - previous) < .0022f)
                assertEquals(result[i], result[i + 1], 0f)
                previous = result[i]
            }
        }
        assertEquals(0f, previous, 0f)
    }
}
