/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.playback.TempoStretchProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TempoStretchTest {
    private val rate = 48_000
    private val fmt = AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    private fun tone(frames: Int, from: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (12_000 * sin(2 * PI * 220 * (from + i) / rate)).toInt().toShort()
            b.putShort(v); b.putShort(v)
        }
        b.flip()
        return b
    }

    private fun drainInto(p: AudioProcessor, out: ArrayList<Short>) {
        while (true) {
            val o = p.getOutput()
            if (!o.hasRemaining()) return
            val s = o.asShortBuffer()
            while (s.hasRemaining()) out += s.get()
            o.position(o.limit())
        }
    }

    /** What the sink does on a speed change: drain to end of stream, apply, flush. */
    private fun ride(speeds: List<Float>, buffersPerStep: Int): Pair<List<Short>, Int> {
        val p = TempoStretchProcessor()
        p.configure(fmt)
        p.setSpeed(speeds[0])
        p.flush()
        val out = ArrayList<Short>()
        var frame = 0
        for (s in speeds) {
            if (s != speeds[0] || frame > 0) {
                p.queueEndOfStream()
                drainInto(p, out)
                assertTrue(p.isEnded)
                p.setSpeed(s)
                p.flush()
            }
            repeat(buffersPerStep) {
                p.queueInput(tone(1024, frame))
                frame += 1024
                drainInto(p, out)
            }
        }
        p.queueEndOfStream()
        drainInto(p, out)
        return out to frame
    }

    @Test fun `speed changes join with no gap or click`() {
        // A loop-ride-like ramp: 1.0 → 1.2 in 0.25 % steps, a step every ~85 ms.
        val speeds = (0..80).map { 1.0025f + it * .0025f }
        val (out, inFrames) = ride(speeds, 4)
        val left = out.filterIndexed { i, _ -> i % 2 == 0 }
        // A 220 Hz tone at 12000 moves at most ~350 per sample; a gap or a hard join jumps far more.
        var worst = 0
        for (i in 1 until left.size) worst = maxOf(worst, abs(left[i] - left[i - 1]))
        assertTrue("worst sample step $worst", worst < 900)
        // No silent stretch (Sonic's drain padding): every 2 ms window still carries the tone.
        val win = rate / 500
        for (w in 0 until left.size / win - 1) {
            val peak = (w * win until (w + 1) * win).maxOf { abs(left[it].toInt()) }
            assertTrue("dip at ${w * 2} ms: $peak", peak > 3000)
        }
        // The tempo is right: the output is the input over the mean speed (the remainders of each
        // drain play unstretched, a few percent at this step rate).
        val expected = inFrames / speeds.average()
        assertEquals(expected, left.size.toDouble(), expected * .05)
    }

    @Test fun `a steady stretch is the requested speed`() {
        val (out, inFrames) = ride(listOf(1.04f), 400)
        assertEquals(inFrames / 1.04, out.size / 2.0, inFrames * .005)
    }
}
