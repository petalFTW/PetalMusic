/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.playback.BridgeFX
import org.rab1d.petal.music.playback.TailMixer
import kotlin.math.abs

/**
 * Retained-tail crossfade behaviour — the software twin of the BridgeEngine
 * delay-line design: the outgoing tail replays under the incoming track with
 * equal-power gains.
 */
class TailMixerTest {

    private fun mixer(rate: Int, fadeMs: Int, style: Int = BridgeFX.STYLE_CLEAN): TailMixer {
        val m = TailMixer()
        m.configure(rate, 1)
        m.fadeMs = fadeMs
        m.style = style
        return m
    }

    private fun tone(freq: Double, frames: Int, rate: Int, amp: Double = 1.0) =
        FloatArray(frames) { i -> (amp * kotlin.math.sin(2 * Math.PI * freq * i / rate)).toFloat() }

    @Test
    fun `passes through when idle and records tail`() {
        val m = mixer(rate = 1000, fadeMs = 100)
        val buf = tone(10.0, 1000, 1000)
        val out = m.process(buf.copyOf(), 1000)
        assertEquals(buf.size, out.size)
        for (i in buf.indices) assertEquals(buf[i], out[i], 1e-6f)
    }

    @Test
    fun `boundary blends incoming over retained tail`() {
        val rate = 1000
        val fadeFrames = 100
        val m = mixer(rate, fadeFrames)
        // Outgoing: 500 frames of 7.3 Hz → ring holds the last 100 frames.
        m.process(tone(7.3, 500, rate), 500)
        m.markBoundary()
        // Incoming: silence; the mix must replay the retained tail fading out.
        val silence = FloatArray(200)
        val mixed = m.process(silence, 200)
        // Early in the blend the tail is loud.
        val headRms = kotlin.math.sqrt(mixed.take(40).map { it * it }.average().coerceAtLeast(0.0))
        assertTrue("expected audible tail, got $headRms", headRms > 0.2)
        // After the fade window the outgoing tail is gone.
        assertTrue(abs(mixed[fadeFrames + 20]) < 0.01f)
    }

    @Test
    fun `equal power curves sum close to unity`() {
        val t = 0.25
        val gIn = TailMixer.gainIn(t, BridgeFX.STYLE_CLEAN).toDouble()
        val gOut = TailMixer.gainOut(t, BridgeFX.STYLE_CLEAN).toDouble()
        assertEquals(1.0, gIn * gIn + gOut * gOut, 1e-6)
    }

    @Test
    fun `stutter gates the outgoing envelope`() {
        val base = TailMixer.gainOut(0.5, BridgeFX.STYLE_CLEAN)
        val stutter = TailMixer.gainOut(0.5, BridgeFX.STYLE_STUTTER)
        assertTrue(stutter <= base + 1e-6f)
        assertTrue(stutter > 0f)
    }

    @Test
    fun `abort stops blending`() {
        val m = mixer(rate = 1000, fadeMs = 100)
        m.process(tone(10.0, 500, 1000), 500)
        m.markBoundary()
        m.abort()
        val silence = FloatArray(50)
        val out = m.process(silence, 50)
        for (v in out) assertEquals(0f, v, 1e-7f)
    }

    @Test
    fun `zero fade disables mixing`() {
        val m = mixer(rate = 1000, fadeMs = 0)
        m.process(tone(10.0, 500, 1000), 500)
        m.markBoundary()
        val buf = tone(20.0, 50, 1000)
        val out = m.process(buf.copyOf(), 50)
        for (i in buf.indices) assertEquals(buf[i], out[i], 1e-6f)
    }
}
