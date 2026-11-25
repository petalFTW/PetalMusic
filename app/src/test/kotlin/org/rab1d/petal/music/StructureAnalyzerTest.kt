/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.playback.StructureAnalyzer

class StructureAnalyzerTest {

    private val key = "test/track"

    /** 8-bar phrase at 120 bpm. */
    private val phraseMs = (60000.0 / 120.0 * 4.0 * 8.0).toLong()

    /** Feed [seconds] of a synthetic envelope with the given band RMS values. */
    private fun feed(fromMs: Long, seconds: Int, full: Float, low: Float, high: Float) {
        val hopMs = 23L
        var t = fromMs
        val end = fromMs + seconds * 1000L
        while (t < end) {
            StructureAnalyzer.feed(key, full, low, high, t)
            t += hopMs
        }
    }

    @Test
    fun findsSustainedOutroAfterFullBody() {
        StructureAnalyzer.reset(key)
        // 120 s of full-energy body: strong lows, some tops.
        feed(0, 120, full = 0.5f, low = 0.35f, high = 0.1f)
        // Outro: energy and bass collapse from 120 s on.
        feed(120_000, 40, full = 0.2f, low = 0.02f, high = 0.08f)

        val outro = StructureAnalyzer.detectOutro(key, bpm = 120f, phaseMs = 0f)
        assertTrue("expected an outro, got $outro", outro > 0)
        // Snapped to the nearest 8-bar phrase, so within half a phrase of 120 s.
        assertTrue("outro should be near 120s, got $outro", outro in 104_000..136_000)
        assertEquals(0L, outro % 500L)
    }

    @Test
    fun noFalsePositiveWhenTrackStaysLoudToTheEnd() {
        StructureAnalyzer.reset(key)
        feed(0, 160, full = 0.5f, low = 0.35f, high = 0.1f)
        val outro = StructureAnalyzer.detectOutro(key, bpm = 128f, phaseMs = 0f)
        assertEquals(-1L, outro)
    }

    @Test
    fun keepsOutroCueNearTheMeasuredDropInsteadOfInventingAPhrase() {
        StructureAnalyzer.reset(key)
        feed(0, 120, full = 0.5f, low = 0.35f, high = 0.1f)
        feed(120_000, 40, full = 0.2f, low = 0.02f, high = 0.08f)
        val outro = StructureAnalyzer.detectOutro(key, bpm = 120f, phaseMs = 0f)
        assertTrue(outro in 120_000..120_500)
    }

    @Test
    fun ignoresQuietVersesWithVocalsAndPicksFinalInstrumentalOutro() {
        StructureAnalyzer.reset(key)
        // Quiet verse (still has vocals) at the start.
        feed(0, 40, full = 0.22f, low = 0.05f, high = 0.08f)
        feed(40_000, 60, full = 0.5f, low = 0.35f, high = 0.1f)
        // A second quiet, vocal verse — the old detector latched onto this.
        feed(100_000, 20, full = 0.22f, low = 0.05f, high = 0.08f)
        feed(120_000, 30, full = 0.5f, low = 0.35f, high = 0.1f)
        // The real instrumental outro.
        feed(150_000, 25, full = 0.18f, low = 0.02f, high = 0.07f)

        val outro = StructureAnalyzer.detectOutro(key, bpm = 120f, phaseMs = 0f)
        assertTrue("should pick the final outro, got $outro", outro in 136_000..156_000)
        assertEquals(0L, outro % 500L)
    }

    @Test fun ignoresBreakdownFarFromTheActualEndAndRevokesItWhenTheBodyReturns() {
        StructureAnalyzer.reset(key)
        feed(0, 120, full = .5f, low = .35f, high = .1f)
        feed(120_000, 12, full = .2f, low = .02f, high = .08f)
        assertEquals(-1L, StructureAnalyzer.detectOutro(key, 120f, 0f, 240_000))
        assertTrue(StructureAnalyzer.detectOutro(key, 120f, 0f, 150_000) > 0)
        feed(132_000, 8, full = .5f, low = .35f, high = .1f)
        assertEquals(-1L, StructureAnalyzer.detectOutro(key, 120f, 0f, 150_000))
    }
}
