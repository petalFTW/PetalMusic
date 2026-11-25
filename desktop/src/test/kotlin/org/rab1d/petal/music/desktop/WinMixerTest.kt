/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop

import org.rab1d.petal.music.desktop.audio.BridgeFXWin
import org.rab1d.petal.music.desktop.audio.WinMixer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WinMixerTest {

    @Test
    fun `equal-power envelopes meet the boundaries`() {
        for (style in 0..3) {
            // Stutter intentionally starts at 0.72 (the ROM's gating quirk).
            val startOut = if (style == BridgeFXWin.STYLE_STUTTER) 0.72f else 1f
            assertEquals(startOut, WinMixer.gainOut(0.0, style), 1e-4f, "style $style start")
            assertEquals(0f, WinMixer.gainOut(1.0, style), 1e-4f, "style $style end")
            assertEquals(0f, WinMixer.gainIn(0.0, style), 1e-4f, "style $style start")
            assertEquals(1f, WinMixer.gainIn(1.0, style), 1e-4f, "style $style end")
            for (step in 0..20) {
                val t = step / 20.0
                assertTrue(WinMixer.gainIn(t, style) in 0f..1f, "gIn($t,$style)")
                assertTrue(WinMixer.gainOut(t, style) in 0f..1f, "gOut($t,$style)")
            }
        }
    }

    @Test
    fun `clean style conserves power`() {
        for (step in 0..20) {
            val t = step / 20.0
            val power = WinMixer.gainIn(t, BridgeFXWin.STYLE_CLEAN).toDouble().pow2() +
                WinMixer.gainOut(t, BridgeFXWin.STYLE_CLEAN).toDouble().pow2()
            assertEquals(1.0, power, 1e-6, "t=$t")
        }
    }

    @Test
    fun `stutter dips below the clean envelope mid-fade`() {
        // Compare away from the crossover point where both envelopes vanish.
        val clean = WinMixer.gainOut(0.4, BridgeFXWin.STYLE_CLEAN)
        val stutter = WinMixer.gainOut(0.4, BridgeFXWin.STYLE_STUTTER)
        assertTrue(stutter < clean, "stutter=$stutter clean=$clean")
    }

    @Test
    fun `boundary blends tail then hands over to incoming`() {
        val rate = 48000
        val channels = 2
        val fadeFrames = 4800
        val mixer = WinMixer(rate, channels).apply {
            fadeMs = fadeFrames * 1000 / rate
            style = BridgeFXWin.STYLE_CLEAN
            configure(rate, channels)
        }

        // Record the outgoing "tail": constant 0.5.
        val out = FloatArray(fadeFrames * channels) { 0.5f }
        mixer.process(out, fadeFrames)
        assertFalse(mixer.mixing)

        // Fire the boundary and feed the incoming track: constant 1.
        mixer.markBoundary()
        assertTrue(mixer.mixing)
        val incoming = FloatArray(fadeFrames * channels) { 1f }

        val head = incoming.copyOf()
        mixer.process(head, fadeFrames / 2)
        // A quarter in (t≈0.5) both envelopes are live: the sample must sit
        // strictly above the outgoing-only level (0.5) and the blend must be
        // visible (equal-power sums above unity for correlated signals).
        val midFrame = fadeFrames / 4
        val midSample = head[midFrame * channels]
        assertTrue(midSample > 0.6f && midSample < 1.4f, "mid=$midSample")

        // Fade exhausted: ring drained, output is purely incoming.
        val tail = incoming.copyOf()
        mixer.process(tail, fadeFrames / 2 + 16)
        assertFalse(mixer.mixing)
        assertEquals(1f, tail.last(), 1e-6f)
    }

    @Test
    fun `abort ends a mix immediately`() {
        val rate = 48000
        val mixer = WinMixer(rate, 2).apply {
            fadeMs = 100
            configure(rate, 2)
        }
        mixer.process(FloatArray(rate * 2) { 0.25f }, rate)
        mixer.markBoundary()
        assertTrue(mixer.mixing)
        mixer.abort()
        assertFalse(mixer.mixing)
    }

    private fun Double.pow2() = this * this
}
