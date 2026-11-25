/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.data.AutomixStyle
import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.model.TrackAnalysis
import org.rab1d.petal.music.playback.*

class EqTransitionTest {
    @Test fun `metadata matches tempo without inventing a phase or pitching the musical key`() {
        fun plan(style: AutomixStyle, key: Int?) = DJPlanner.plan(
            null, null, TrackAnalysis(120f, camelot = 1, authoritative = true),
            TrackAnalysis(124f, camelot = key, authoritative = true),
            PlaybackSettings(style = style), 60000,
        )
        val compatible = plan(AutomixStyle.CLEAN, 1)
        val distant = plan(AutomixStyle.ADAPTIVE, 5)
        assertEquals(0f, compatible.eq.beatMs, 0f)
        assertEquals(DJPlanner.Kind.BEATMATCH, compatible.kind)
        assertEquals(AutomixStyle.ADAPTIVE, distant.eq.style)
        assertTrue(distant.eq.forOutro().seam)
        // Compatible and distant keys produce the identical tempo decision.
        assertEquals(compatible.kind, distant.kind)
        assertEquals(compatible.outgoingSpeed, distant.outgoingSpeed, 0f)
        assertEquals(compatible.fadeMs, distant.fadeMs)
    }

    @Test fun `kill ramp is continuous finite and sample rate independent`() {
        for (rate in listOf(44100, 48000, 96000)) {
            val ramp = BandGainRamp()
            ramp.setTarget(BandGainRamp.Gains(0f, 0f, 0f, 0f), rate)
            val frames = (rate * .005f).toInt()
            var previous = 1f
            repeat(frames) {
                ramp.advance()
                val next = ramp.current[0]
                assertTrue(next <= previous)
                assertTrue(previous - next < 1.01f / frames)
                previous = next
            }
            assertEquals(0f, ramp.current[0], 0f)
            repeat(100) { ramp.advance() }
            assertEquals(0f, ramp.current[0], 0f)
        }
    }

    @Test fun `retarget starts from current gain and reset clears an old ramp`() {
        val ramp = BandGainRamp()
        ramp.setTarget(BandGainRamp.Gains(0f), 48000)
        repeat(120) { ramp.advance() }
        val before = ramp.current[0]
        ramp.setTarget(BandGainRamp.Gains(), 48000)
        assertEquals(before, ramp.current[0], 0f)
        repeat(240) { ramp.advance() }
        assertEquals(1f, ramp.current[0], 0f)
        ramp.reset(BandGainRamp.Gains(0f, 0f, 0f, 0f))
        ramp.advance()
        assertEquals(0f, ramp.current[0], 0f)
    }
}
