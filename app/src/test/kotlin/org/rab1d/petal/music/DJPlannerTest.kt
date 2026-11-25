/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.model.TrackAnalysis
import org.rab1d.petal.music.playback.BeatAnalyzer
import org.rab1d.petal.music.playback.DJPlanner

class DJPlannerTest {
    @Test fun `running display follows renderer rebudgeting instead of stale planned bars`() {
        val p = DJPlanner.plan(null, null,
            TrackAnalysis(120f, strength = .8f, camelot = 8),
            TrackAnalysis(120f, strength = .8f, camelot = 8), PlaybackSettings(), 60000)
        val displayed = DJPlanner.renderedPlan(p, true, 27500)!!
        assertEquals(27500, displayed.fadeMs)
        assertEquals(0, displayed.bars)
        assertEquals(p.outgoingSpeed, displayed.outgoingSpeed, 0f)
        assertTrue(DJPlanner.describe(displayed).contains("27.5s"))
        assertEquals(p, DJPlanner.renderedPlan(p, false, 8000))
        assertNull(DJPlanner.renderedPlan(null, false, 8000))
    }
    private fun plan(a: Float, b: Float, remaining: Long = 60000) = DJPlanner.plan(
        null, null, TrackAnalysis(a, strength = .8f), TrackAnalysis(b, strength = .8f),
        PlaybackSettings(), remaining,
    )

    @Test fun `tempo remains matched including imperfect half and double time estimates`() {
        for ((a, b) in listOf(120f to 126f, 62f to 120f, 124f to 60f)) {
            val p = plan(a, b)
            assertEquals(DJPlanner.Kind.BEATMATCH, p.kind)
            val scale = if (a < b * .75f) 2f else if (a > b * 1.5f) .5f else 1f
            assertEquals(b, a * p.outgoingSpeed * scale, .001f)
        }
    }

    @Test fun `overlap has complete bars at incoming tempo and fits outgoing duration`() {
        val p = plan(120f, 125f, 10000)
        assertTrue(p.bars > 0)
        assertEquals(p.bars * 4 * 60000f / 125f, p.fadeMs.toFloat(), 1f)
        assertTrue(p.fadeMs * p.outgoingSpeed <= 9750)
    }

    @Test fun `blend ends on the last beat at or before the outgoing end`() {
        // 120 BPM, first beat at 137ms, track ends at 30120ms.
        val last = BeatAnalyzer.lastBeat(137f, 500f, 30_120L)
        assertEquals(137f + 59 * 500f, last, .001f)
        assertTrue(last <= 30_120L)
        // A track that ends exactly on a beat finishes on that beat.
        assertEquals(29_637f, BeatAnalyzer.lastBeat(137f, 500f, 29_637L), .001f)
        // No grid: the end itself is returned.
        assertEquals(30_120f, BeatAnalyzer.lastBeat(0f, 0f, 30_120L), 0f)
    }

    @Test fun `fractional beat periods do not accumulate phase drift`() {
        val bpm = 127f
        val phase = 137f
        val from = 240123L
        val aligned = BeatAnalyzer.align(from, bpm, phase)
        val period = 60000.0 / bpm
        val cycles = (aligned - phase) / period
        assertTrue(aligned >= from)
        assertTrue(aligned - from < period + 1)
        assertEquals(kotlin.math.round(cycles), cycles, 1.0 / period)
    }

    @Test fun `far tempos keep the playback speed natural`() {
        val p = plan(70f, 180f)
        assertEquals(DJPlanner.Kind.PLAIN, p.kind)
        assertEquals(1f, p.outgoingSpeed, .001f)
    }

    @Test fun `closest multiple keeps the outgoing warp smallest`() {
        // 62 vs 120: the half grid (0.968x) is far kinder than the direct
        // 1.935x, so the half grid must win.
        assertEquals(0.5f * 120f / 62f, plan(62f, 120f).outgoingSpeed, .001f)
        // 174 vs 90: the double grid (1.034x) beats the 0.517x direct match.
        assertEquals(2f * 90f / 174f, plan(174f, 90f).outgoingSpeed, .001f)
    }

    @Test fun `near tempos beatmatch in the right direction`() {
        for ((a, b) in listOf(120f to 126f, 126f to 120f, 174f to 90f, 62f to 120f)) {
            assertEquals("$a->$b", DJPlanner.Kind.BEATMATCH, plan(a, b).kind)
        }
        assertTrue(plan(120f, 126f).outgoingSpeed > 1f)
        assertTrue(plan(126f, 120f).outgoingSpeed < 1f)
        assertEquals(DJPlanner.Kind.PLAIN, plan(90f, 140f).kind)
    }

    @Test fun `disabled alignment leaves cues untouched`() {
        val p = DJPlanner.plan(null, null,
            TrackAnalysis(120f, 130f, .8f), TrackAnalysis(120f, 230f, .8f),
            PlaybackSettings(beatAlign = false), 60000)
        assertEquals(0L, p.alignMs)
        assertNull(p.eq.outgoingPhaseMs)
        assertEquals(0L, p.eq.incomingCueMs)
    }

    @Test fun `metadata tempo without a measured grid is not treated as a phase`() {
        // Source metadata (authoritative) supplies bpm but strength 0 and phase 0,
        // so the planner must not align to a bogus zero phase.
        val meta = TrackAnalysis(120f, strength = 0f, camelot = 8, authoritative = true)
        val p = DJPlanner.plan(null, null, meta, meta.copy(bpm = 122f), PlaybackSettings(), 60000)
        assertEquals(DJPlanner.Kind.BEATMATCH, p.kind)
        assertEquals(0L, p.alignMs)
        assertNull(p.eq.outgoingPhaseMs)
        assertEquals(0L, p.eq.incomingCueMs)
    }

    @Test fun `key compatibility preserves tempo but limits a clashing overlap`() {
        fun plan(outKey: Int, inKey: Int) = DJPlanner.plan(
            null, null,
            TrackAnalysis(124f, phaseMs = 180f, strength = .8f, camelot = outKey),
            TrackAnalysis(120f, phaseMs = 90f, strength = .8f, camelot = inKey),
             PlaybackSettings(fadeMs = 30000), 60000,
        )
        val neighbour = plan(1, 2)
        val distant = plan(1, 8)
        assertEquals(neighbour.outgoingSpeed, distant.outgoingSpeed, 0f)
        assertTrue(distant.fadeMs < neighbour.fadeMs)
        assertEquals(neighbour.alignMs, distant.alignMs)
        assertFalse(neighbour.eq.tonalClash)
        assertTrue(distant.eq.tonalClash)
    }

    @Test fun `fade setting and incoming duration bound the overlap`() {
        val settings = PlaybackSettings(fadeMs = 3500, autoFade = false)
        val a = TrackAnalysis(120f, strength = .8f)
        val p = DJPlanner.plan(null, null, a, a, settings, 60000)
        assertTrue(p.fadeMs <= 3500)
        val incoming = org.rab1d.petal.music.model.Track("short", org.rab1d.petal.music.model.Source.LOCAL,
            "short", durationMs = 3000)
        assertTrue(DJPlanner.plan(null, incoming, a, a, settings, 60000).fadeMs <= 1000)
    }

    @Test fun `unreliable rhythm never bends a song and incompatible tempos still allow an intro`() {
        val p = DJPlanner.plan(null, null, TrackAnalysis(120f, strength = .1f),
            TrackAnalysis(124f, strength = .8f), PlaybackSettings(), 60000)
        assertEquals(DJPlanner.Kind.PLAIN, p.kind)
        assertEquals(1f, p.outgoingSpeed, 0f)
        assertEquals(0f, p.eq.beatMs, 0f)
        assertEquals(8000, plan(90f, 140f).fadeMs)
    }

    @Test fun `late arrivals never invent a minimum overlap longer than the tail`() {
        for (remaining in listOf(0L, 100L, 250L, 300L, 700L)) {
            val p = plan(120f, 125f, remaining)
            assertTrue(p.fadeMs * p.outgoingSpeed + p.alignMs <= remaining)
        }
    }

    @Test fun `relative keys remain compatible but opposite mode neighbours need room`() {
        fun p(number: Int, minor: Boolean) = DJPlanner.plan(null, null,
            TrackAnalysis(120f, strength = .8f, camelot = 8, minor = true),
            TrackAnalysis(120f, strength = .8f, camelot = number, minor = minor),
            PlaybackSettings(), 60000)
        assertFalse(p(8, false).eq.tonalClash)
        assertFalse(p(9, true).eq.tonalClash)
        assertTrue(p(9, false).eq.tonalClash)
    }

    @Test fun `automatic duration uses musical evidence instead of the saved manual fade`() {
        val a = TrackAnalysis(120f, strength = .8f, camelot = 8)
        fun p(b: TrackAnalysis, saved: Int = 6000) = DJPlanner.plan(null, null, a, b,
            PlaybackSettings(fadeMs = saved), 60000)
        assertEquals(32000, p(a).fadeMs)
        assertEquals(p(a).fadeMs, p(a, 0).fadeMs)
        assertEquals(16000, p(a.copy(camelot = null)).fadeMs)
        assertEquals(16000, p(a.copy(camelot = 3)).fadeMs)
        assertEquals(16000, p(a.copy(strength = 0f, authoritative = true)).fadeMs)
        assertTrue(p(a.copy(bpm = 126f)).fadeMs < p(a).fadeMs)
        val short = org.rab1d.petal.music.model.Track("short", org.rab1d.petal.music.model.Source.LOCAL,
            "short", durationMs = 3000)
        assertTrue(DJPlanner.plan(null, short, a, a, PlaybackSettings(), 60000).fadeMs <= 1000)
        assertEquals(0, DJPlanner.plan(null, null, a, a,
            PlaybackSettings(fadeMs = 0, autoFade = false), 60000).fadeMs)
    }

    @Test fun `detected ending owns duration even without compatible tempo or key`() {
        val a = TrackAnalysis(120f, strength = .8f, camelot = 8)
        for (b in listOf(null, a, a.copy(bpm = 140f), a.copy(camelot = 3))) {
            val p = DJPlanner.plan(null, null, a, b, PlaybackSettings(), 27500, outro = true)
            assertEquals(27250, p.fadeMs)
            assertEquals(0, p.bars)
        }
        val unknown = DJPlanner.plan(null, null, null, null, PlaybackSettings(), 37500, outro = true)
        assertEquals(37250, unknown.fadeMs)
        val manual = DJPlanner.plan(null, null, a, a,
            PlaybackSettings(fadeMs = 6000, autoFade = false), 27500, outro = true)
        assertTrue(manual.fadeMs <= 6000)
        val short = org.rab1d.petal.music.model.Track("short", org.rab1d.petal.music.model.Source.LOCAL,
            "short", durationMs = 9000)
        assertTrue(DJPlanner.plan(null, short, a, a, PlaybackSettings(), 27500, outro = true).fadeMs <= 3000)
    }
}
