/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.model.TrackAnalysis
import org.rab1d.petal.music.playback.*
import org.rab1d.petal.music.playback.DjComposer.Family
import org.rab1d.petal.music.playback.DjTransitionPlan.P
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class DjComposerTest {
    private fun profile(
        bpm: Double, bars: Int, first: Double = 250.0, vocal: (Int) -> Float = { 0f },
        sections: IntArray = intArrayOf(0, 8, 16, 32), intro: Int = 0, outro: Int = bars, conf: Float = .9f,
    ) = TrackProfile(60000 / bpm, first, conf, first, .5f, first, FloatArray(bars) { 1f }, FloatArray(bars) { 1f },
        FloatArray(bars) { vocal(it) }, FloatArray(bars) { .5f }, sections, 0, intro, outro, 8, true, .3f,
        0.0, first + bars * 240000 / bpm, true)

    private fun side(p: TrackProfile?, analysis: TrackAnalysis? = null, id: String = "a") =
        DjComposer.Side(p, analysis, (p?.let { it.firstBarMs + it.bars * it.barMs + 1500 } ?: 200_000.0).toLong(), id)

    private val creative = DjComposer.Options(style = DjComposer.MixStyle.CREATIVE)

    private fun compose(out: TrackProfile?, inc: TrackProfile?, seed: Int = 1, previous: Family? = null,
                        earliest: Long? = null, recent: List<DjComposer.Played>? = null,
                        options: DjComposer.Options = DjComposer.Options()): DjComposer.Mix? {
        val o = side(out, id = "out")
        return DjComposer.compose(o, side(inc, id = "in"),
            earliest ?: (o.durationMs - 90_000).coerceAtLeast(0),
            recent ?: listOfNotNull(previous?.let { DjComposer.Played(it, 8) }), seed, options = options)
    }

    // ---- choices ------------------------------------------------------------------

    @Test fun `matched pairs blend on bar lines and the incoming cues on its own bar`() {
        val out = profile(92.0, 72)
        val inc = profile(94.0, 72, first = 410.0)
        val mix = compose(out, inc, options = DjComposer.Options(conformIncoming = false))!!
        assertTrue(mix.family.blend)
        assertTrue(mix.sync)
        assertEquals(94f / 92f, mix.speed, 1e-4f)
        val bar = out.barMs
        assertEquals(0.0, ((mix.outStartMs - out.firstBarMs) / bar).let { it - Math.round(it) } * bar, 1.0)
        assertEquals(0.0, ((mix.incomingCueMs - inc.firstBarMs) / inc.barMs).let { it - Math.round(it) } * inc.barMs, 1.0)
        assertEquals(inc.barMs, mix.wallBarMs, 1e-6)
    }

    // ---- clean mixes: the two reference transitions --------------------------------

    /** A record with explicit per-bar features; [beatless] bars have no drums or low end. */
    private fun record(
        bpm: Double, bars: Int, beatless: (Int) -> Boolean, vocal: (Int) -> Float, sections: IntArray,
        intro: Int, outro: Int, camelot: Int, minor: Boolean, first: Double = 250.0,
    ) = TrackProfile(60000 / bpm, first, .9f, first, .6f, first,
        FloatArray(bars) { if (beatless(it)) .4f else 1f }, FloatArray(bars) { if (beatless(it)) .05f else 1f },
        FloatArray(bars) { vocal(it) }, FloatArray(bars) { if (beatless(it)) 0f else .6f }, sections, 0, intro, outro,
        camelot, minor, .4f, 0.0, first + bars * 240000 / bpm, true)

    @Test fun `outro over a beatless intro is an overlay that lands on the outgoing's last bar`() {
        // The Hills to I KNOW?: 113 to 118 BPM (4.4 %), 5A to 7B, beatless outro and intro.
        val out = record(113.0, 72, { it >= 64 }, { if (it < 64) 1f else 0f }, intArrayOf(0, 16, 32, 48, 64),
            intro = 0, outro = 64, camelot = 5, minor = true)
        val inc = record(118.0, 72, { it < 8 }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40),
            intro = 8, outro = 72, camelot = 7, minor = false)
        for (seed in 0 until 20) {
            // The reference transitions let outros play: pacing off.
            val mix = compose(out, inc, seed, options = DjComposer.Options(djPacing = false))!!
            assertEquals(Family.OVERLAY, mix.family)
            assertEquals(8, mix.bars)
            // It ends where the outgoing does, and the incoming's beat arrives on that bar.
            assertEquals(64, mix.exitBar)
            assertEquals(0, mix.incomingBar)
            assertEquals(DjComposer.Fill.NONE, mix.fill)
            // 4.4 % apart: the outgoing rides halfway before the mix, the incoming is stretched the rest.
            assertTrue(mix.stretchIncoming && mix.holdOutgoing && mix.glideBack)
            val half = kotlin.math.sqrt(118f / 113f)
            assertEquals(half, mix.speed, 1e-4f)
            assertEquals(half, mix.rideSpeed!!, 1e-4f)
            assertEquals(16, mix.rideBars)
            assertEquals(inc.barMs, mix.incomingBarMs, 1e-6)
            assertEquals(out.barMs / half, mix.wallBarMs, 1e-3)
            // Both at full volume; the outgoing's highs dip on bar 0 and are back by bar 4.
            val o = mix.outgoing
            val n = mix.incoming
            assertEquals(0f, o.at(P.HIGH, .02f), .01f)
            assertEquals(.5f, o.at(P.HIGH, 2f), .02f)
            assertEquals(1f, o.at(P.HIGH, 4.5f), 0f)
            for (p in listOf(P.SUB, P.BASS, P.MID, P.HIGH)) assertEquals(1f, n.at(p, .5f), 0f)
            assertEquals(1f, o.at(P.DRY, 7.9f), 0f)
            assertTrue(mix.hardEntry)
            assertEquals(0f, (0..800).maxOf { o.at(P.SEND, it / 100f) }, 0f)
            check(mix)
            val m = DjComposer.metrics(mix, out, inc)
            assertEquals("$m", DjComposer.Metrics(0, 0, 0, 0.0), m)
        }
    }

    @Test fun `a beat-matched pair hands off by the key bar with one low end at a time`() {
        // Starships to One More Time: 125 to 126 BPM, 10A to 10B, the incoming full from its first bar.
        val out = record(125.0, 72, { false }, { if (it < 56) 1f else .2f }, intArrayOf(0, 8, 24, 40, 56),
            intro = 0, outro = 64, camelot = 10, minor = true)
        val inc = record(126.0, 72, { false }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40),
            intro = 0, outro = 72, camelot = 10, minor = false)
        for (seed in 0 until 20) {
            val mix = compose(out, inc, seed)!!
            assertEquals(Family.HANDOFF, mix.family)
            assertEquals(4, mix.bars)
            assertTrue(mix.sync && mix.stretchIncoming)
            assertEquals(1f, mix.speed, 0f)
            assertEquals(inc.barMs, mix.incomingBarMs, 1e-6)
            val o = mix.outgoing
            val n = mix.incoming
            // Bar 0: outgoing low and highs gone, incoming at full volume with its highs open.
            for (p in listOf(P.SUB, P.BASS, P.HIGH)) assertEquals(0f, o.at(p, .1f), 0f)
            assertEquals(1f, n.at(P.HIGH, .1f), 0f)
            // The incoming's low end ramps in a straight line.
            assertEquals(.5f, n.at(P.BASS, 2f), .01f)
            assertEquals(.25f, n.at(P.BASS, 1f), .01f)
            // The outgoing fades over the half bar before the key bar.
            assertEquals(1f, o.at(P.DRY, 3.4f), 0f)
            assertEquals(0f, o.at(P.DRY, 4f), 0f)
            assertEquals(0f, (0..400).maxOf { o.at(P.SEND, it / 100f) }, 0f)
            check(mix)
            val m = DjComposer.metrics(mix, out, inc)
            assertEquals("$m", DjComposer.Metrics(0, 0, 0, 0.0), m)
        }
    }

    @Test fun `clean mixes never add effects and do not vary for variety's sake`() {
        val families = HashSet<Family>()
        for (seed in 0 until 200) {
            val out = profile(90.0 + seed % 5, 72, vocal = { if (it < 60) 1f else 0f })
            val inc = profile(92.0, 72, vocal = { if (it >= 8) 1f else 0f }, intro = 8)
            for (pair in listOf(out to inc, out to profile(120.0 + seed % 7, 72))) {
                // No move (v1 plays it) is fine; a move must be clean.
                val mix = compose(pair.first, pair.second, seed * 7919,
                    previous = Family.entries[seed % Family.entries.size]) ?: continue
                families += mix.family
                assertEquals(DjComposer.Fill.NONE, mix.fill)
                assertTrue(mix.outgoing.rolls.isEmpty() && mix.outgoing.spins.isEmpty() && mix.incoming.spins.isEmpty())
                var bar = 0f
                while (bar <= mix.bars) {
                    assertEquals("${mix.label} echo at $bar", 0f, mix.outgoing.at(P.SEND, bar), 0f)
                    bar += .05f
                }
                check(mix)
            }
        }
        // Clean blends plainly and mixes unmatched pairs by context; nothing ever cuts.
        assertTrue("clean families $families", families.none { it in CUTS })
        assertTrue("clean families $families", families.all { it in setOf(Family.OVERLAY, Family.HANDOFF,
            Family.BASS_SWAP, Family.DRUM_TEASE, Family.BED_UNDER, Family.ACAPELLA_OUT, Family.VOCAL_PREVIEW,
            Family.LOOP_RIDE) })
    }

    @Test fun `a song held at the last mix's tempo is mixed from that tempo`() {
        val out = profile(92.0, 72)
        val inc = profile(96.0, 72)
        // Native 92 held at 1.03x = 94.8 BPM; 96 is now only 1.3 % away.
        val mix = compose(out, inc, options = DjComposer.Options(currentSpeed = 1.03f))!!
        assertTrue(mix.family.blend && mix.stretchIncoming)
        assertEquals(1.03f, mix.speed, 1e-6f)
        assertEquals(out.barMs / 1.03f, mix.wallBarMs, 1e-6)
        // The renderer's incoming speed: its media bar over the wall bar.
        assertEquals(92.0 * 1.03f / 96.0, mix.incomingBarMs / mix.wallBarMs, 1e-6)
    }

    @Test fun `distant tempos with no move are left to v1, never stretched`() {
        // 92 to 128 BPM: too far to ride, no beatless bed or lone vocal; there is no tail over any more.
        val mix = compose(profile(92.0, 72), profile(128.0, 72))
        if (mix != null) {
            assertFalse(mix.notes, mix.stretchIncoming)
            check(mix)
        }
    }

    @Test fun `no outgoing grid is left to v1`() {
        for (options in listOf(DjComposer.Options(), creative))
            assertNull(DjComposer.compose(DjComposer.Side(null, null, 200_000), side(null), 150_000, emptyList(), 1,
                options = options))
    }

    @Test fun `mixes vary across pairs and every family obeys the bass rules`() {
        val matched = HashSet<Family>()
        val cuts = HashSet<Family>()
        val fills = HashSet<DjComposer.Fill>()
        // Cuts vary across a set because the last few are remembered, not by a dice roll.
        val recent = ArrayList<DjComposer.Played>()
        val set = ArrayList<DjComposer.Played>()
        for (seed in 0 until 300) {
            val vocalEnd = 56 + seed % 9
            val out = profile(90.0 + seed % 5, 72, vocal = { if (it < vocalEnd) 1f else 0f },
                sections = intArrayOf(0, 8, 24, vocalEnd), outro = if (seed % 3 == 0) vocalEnd else 72)
            val inc = profile(92.0, 72, vocal = { if (it >= 8) 1f else 0f }, intro = 8)
            val a = compose(out, inc, seed * 7919, recent = set.toList(), options = creative)!!
            matched += a.family
            fills += a.fill
            set.add(0, DjComposer.Played(a.family, a.bars, a.fill))
            while (set.size > 4) set.removeAt(set.size - 1)
            check(a)
            // Unmatched: a context move, or none (v1's blend plays it).
            val b = compose(out, profile(120.0 + seed % 7, 72), seed * 31, recent = recent.toList(), options = creative) ?: continue
            cuts += b.family
            assertTrue(b.label, overlaps(b))
            recent.add(0, DjComposer.Played(b.family, b.bars))
            while (recent.size > 4) recent.removeAt(recent.size - 1)
            check(b)
        }
        assertTrue("blend families $matched", matched.count { it.blend } >= 3)
        // Unmatched pairs are mixed, never cut, and always overlap.
        assertTrue("unmatched families $cuts", cuts.none { it in CUTS })
        assertTrue("fills $fills", fills.size >= 3)
    }

    private val CUTS = setOf(Family.ECHO_THROW, Family.BACKSPIN, Family.BRAKE, Family.STUTTER, Family.FILTER_FADE)

    /** Both records are heard together for at least a quarter bar. */
    private fun overlaps(mix: DjComposer.Mix): Boolean {
        val bands = listOf(P.SUB, P.BASS, P.MID, P.HIGH)
        val o = mix.outgoing
        val n = mix.incoming
        return (0..mix.bars * 40).count { k ->
            val b = k / 40f
            o.at(P.DRY, b) * bands.maxOf { o.at(it, b) } > .2f && b >= mix.entryBar && bands.maxOf { n.at(it, b) } > .2f
        } >= 10
    }

    private fun check(mix: DjComposer.Mix) {
        val o = mix.outgoing
        val n = mix.incoming
        val s = mix.swapBar.toFloat()
        var bar = 0f
        while (bar <= mix.bars) {
            val near = abs(bar - s) < .02f || abs(bar - mix.entryBar) < .02f
            val inAudible = bar >= mix.entryBar
            val outBass = o.at(P.SUB, bar) * o.at(P.DRY, bar)
            val inBass = if (inAudible) n.at(P.SUB, bar) else 0f
            if (!near) assertTrue("${mix.label} doubles the bass at bar $bar ($outBass/$inBass)", outBass * inBass < .05f)
            // Past the key bar a muted outgoing must not keep feeding the echo (it would play on through the wet path).
            if (bar > s + .02f && o.at(P.DRY, bar) < .05f)
                assertTrue("${mix.label} feeds the echo with the dry gone at bar $bar", o.at(P.SEND, bar) < .02f)
            if (!inAudible) {
                // Not yet entered: the deck is silent even if it starts a latency early.
                for (p in listOf(P.SUB, P.BASS, P.MID, P.HIGH)) assertEquals(0f, n.at(p, bar), 0f)
            }
            bar += .01f
        }
        val end = mix.bars.toFloat()
        for (p in listOf(P.SUB, P.BASS, P.MID, P.HIGH, P.DRY)) assertEquals("${mix.label} $p", 1f, n.at(p, end), 1e-4f)
        assertEquals(0f, n.at(P.HPF, end), 0f)
        assertEquals(0f, n.at(P.LPF, end), 0f)
        assertEquals("${mix.label} wet", 0f, o.at(P.WET, end), 0f)
        val outAudible = o.at(P.DRY, end) * maxOf(o.at(P.SUB, end), o.at(P.MID, end), o.at(P.HIGH, end))
        assertEquals("${mix.label} outgoing at end", 0f, outAudible, 1e-4f)
    }

    @Test fun `an unmatched pair never cuts and never drops into a quiet intro`() {
        // Incoming: 8 quiet bars of pads, then the song.
        val quiet = TrackProfile(60000 / 128.0, 250.0, .9f, 250.0, .5f, 250.0,
            FloatArray(72) { if (it < 8) .25f else 1f }, FloatArray(72) { if (it < 8) .05f else 1f },
            FloatArray(72) { if (it < 8) 0f else 1f }, FloatArray(72) { if (it < 8) 0f else .6f },
            intArrayOf(0, 8, 24, 40), 0, 8, 72, 8, true, .3f, 0.0, 250 + 72 * 1875.0, true)
        var under = 0
        for (options in listOf(DjComposer.Options(), creative)) for (seed in 0 until 40) {
            val mix = compose(profile(92.0, 72), quiet, seed * 977, options = options)!!
            assertFalse(mix.family.blend)
            assertFalse(mix.label, mix.family in CUTS)
            assertTrue(mix.label, overlaps(mix))
            val cue = Math.round((mix.incomingCueMs - quiet.firstBarMs) / quiet.barMs).toInt()
            when (mix.family) {
                // The quiet intro washes in under the build; the song arrives on the drop.
                Family.INTRO_WASH -> {
                    under++
                    assertTrue("${mix.label} opens at bar ${cue + mix.swapBar}", cue + mix.swapBar >= 8)
                }
                // Or it plays under the outgoing as a bed and the song arrives as the outgoing ends.
                Family.BED_UNDER -> {
                    under++
                    val atEnd = mix.incomingCueMs + (mix.bars - mix.entryBar) * mix.wallBarMs
                    assertEquals(quiet.barStartMs(8), atEnd, 1.0)
                }
                // Or it lands on its full bar with the outgoing's tail over it.
                else -> assertTrue("${mix.label} drops in at bar $cue", cue >= 8)
            }
        }
        assertTrue("intro washes or beds $under/80", under > 0)
    }

    @Test fun `an unmatched pair overlaps on the incoming's instrumental intro`() {
        // 20 MIN to INTENTIONS: 123 to 74 BPM, the incoming opens on four bars of music without vocals.
        val out = record(123.0, 96, { false }, { if (it < 88) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 72, 88),
            intro = 0, outro = 88, camelot = 4, minor = true)
        val inc = record(74.0, 72, { it < 4 }, { if (it >= 6) 1f else 0f }, intArrayOf(0, 4, 20, 36),
            intro = 4, outro = 72, camelot = 9, minor = false)
        for (options in listOf(DjComposer.Options(), creative)) for (seed in 0 until 10) {
            val mix = compose(out, inc, seed, options = options)!!
            assertEquals(mix.notes, Family.BED_UNDER, mix.family)
            // Nothing is stretched or beat-synced; each deck plays at its own tempo.
            assertFalse(mix.sync || mix.stretchIncoming)
            assertEquals(1f, mix.speed, 0f)
            assertTrue(mix.hardEntry)
            // The overlap is the incoming's whole 4-bar bed, ~6.5 outgoing bars.
            assertEquals(7, mix.bars)
            // The incoming's first full bar lands exactly where the outgoing ends, on a phrase line.
            val atEnd = mix.incomingCueMs + (mix.bars - mix.entryBar) * mix.wallBarMs
            assertEquals(inc.barStartMs(4), atEnd, 1.0)
            assertEquals(0, (mix.exitBar + mix.bars) % 4)
            // Heard from its first bar, while the outgoing steps back.
            val n = mix.incoming
            val o = mix.outgoing
            assertTrue(n.at(P.MID, mix.entryBar + .05f) >= .8f)
            assertTrue(o.at(P.MID, mix.bars / 2f) < .8f)
            // No effects, and the outgoing plays to the bar line.
            assertEquals(0f, (0..700).maxOf { o.at(P.SEND, it / 100f) }, 0f)
            assertEquals(1f, o.at(P.DRY, mix.bars - .1f), 0f)
            check(mix)
        }
        // A beat-matched pair still blends; an intro with vocals is not a bed.
        val matchedInc = record(124.0, 72, { it < 4 }, { if (it >= 6) 1f else 0f }, intArrayOf(0, 4, 20, 36),
            intro = 4, outro = 72, camelot = 4, minor = true)
        assertTrue(compose(out, matchedInc)!!.family != Family.BED_UNDER)
        val sung = record(74.0, 72, { it < 4 }, { 1f }, intArrayOf(0, 4, 20, 36), intro = 4, outro = 72,
            camelot = 9, minor = false)
        assertTrue(compose(out, sung, options = creative)!!.family != Family.BED_UNDER)
    }

    @Test fun `no pair is ever a cut, at any tempo or in either style`() {
        for (options in listOf(DjComposer.Options(), creative)) for (seed in 0 until 200) {
            val bpm = listOf(60.0, 74.0, 101.0, 110.0, 128.0, 140.0, 184.0)[seed % 7]
            // No move at all goes to v1's blend, which overlaps too.
            val mix = compose(profile(92.0, 72), profile(bpm, 72), seed * 131, options = options) ?: continue
            assertFalse("${mix.label} at $bpm", mix.family in CUTS)
            assertTrue("${mix.label} at $bpm", overlaps(mix))
            assertTrue(mix.outgoing.spins.isEmpty() && mix.incoming.spins.isEmpty())
            check(mix)
        }
    }

    @Test fun `incoming vocals are held back while the outgoing still sings`() {
        val out = profile(92.0, 72, vocal = { 1f })
        val inc = profile(92.0, 72, vocal = { 1f })
        for (seed in 0 until 60) {
            val mix = compose(out, inc, seed)!!
            if (mix.family != Family.BASS_SWAP && mix.family != Family.FILTER_BLEND) continue
            for (b in 0 until mix.swapBar) assertEquals("bar $b", 0f, mix.incoming.at(P.MID, b + .5f), 0f)
        }
    }

    @Test fun `the outgoing leaves after its vocal line, not in the middle of the song`() {
        // Vocals end at bar 56 of 72, where the outro begins.
        val out = profile(92.0, 72, vocal = { if (it < 56) 1f else 0f }, sections = intArrayOf(0, 8, 24, 40, 56), outro = 56)
        val inc = profile(92.0, 72)
        for (seed in 0 until 40) {
            // Full-song mode (DJ pacing off): play to the line's end, skip at most 16 bars.
            val mix = compose(out, inc, seed, options = DjComposer.Options(djPacing = false))!!
            val exitBar = Math.round((mix.outStartMs - out.firstBarMs) / out.barMs).toInt()
            // The outgoing sings until its line ends; a new beat may ride under it first.
            assertTrue("${mix.label} exits at $exitBar", exitBar + mix.bars - 1 >= 56 && exitBar >= 44)
            val mixEnd = mix.outStartMs + mix.bars * mix.outBarMs
            assertTrue("skips ${(out.firstBarMs + 72 * out.barMs - mixEnd) / 1000}s", out.firstBarMs + 72 * out.barMs - mixEnd <= 16 * out.barMs + 1)
            if (mix.family.blend) assertTrue("hip-hop blends stay short: ${mix.bars}", mix.bars <= 9)
        }
    }

    @Test fun `the plan respects preparation time and is deterministic`() {
        val out = profile(92.0, 72)
        val inc = profile(93.0, 72)
        val earliest = (out.firstBarMs + 60 * out.barMs).toLong()
        val a = compose(out, inc, seed = 5, earliest = earliest)!!
        val b = compose(out, inc, seed = 5, earliest = earliest)!!
        assertTrue(a.outStartMs >= earliest)
        assertEquals(a.family, b.family)
        assertEquals(a.outStartMs, b.outStartMs)
        assertEquals(a.incomingCueMs, b.incomingCueMs)
    }

    @Test fun `v1 still cues the incoming on its measured beat one`() {
        val grid = TrackAnalysis(120f, phaseMs = 0f, strength = .8f, camelot = 8, downbeatMs = 500f)
        val p = DJPlanner.plan(null, null, grid, grid.copy(downbeatMs = 1500f), PlaybackSettings(), 60_000)
        assertEquals(1500L, p.eq.incomingCueMs)
        assertNull(p.eq.dj)
    }

    // ---- beat sync ------------------------------------------------------------------

    @Test fun `beat sync measures the audible offset and closes it`() {
        // Outgoing 20 ms ahead of the incoming at a 2.6 s bar.
        val err = BeatSync.errorMs(10_000 + 20, 10_000.0, 2600.0, 5_000, 5_000.0, 2600.0)
        assertEquals(20.0, err, 1e-6)
        // Whole beats are a different grid, not drift: wrapped.
        assertEquals(20.0, BeatSync.errorMs(10_000 + 650 + 20, 10_000.0, 2600.0, 5_000, 5_000.0, 2600.0), 1e-6)
        val nudge = BeatSync.nudge(err, 1.02f)!!
        assertTrue(nudge.speed < 1.02f)
        // Speed deficit × duration removes the lead.
        assertEquals(err, (1.02 - nudge.speed) / 1.02 * nudge.durationMs, 1.0)
        assertNull(BeatSync.nudge(3.0, 1f))
        // A stretched incoming: 2100 ms media bars heard as 2000 ms wall bars. Two bars in on
        // both decks, 10 ms of wall time apart.
        assertEquals(10.0, BeatSync.errorMs(10_000 + 4000 + 10, 10_000.0, 2000.0, 5_000 + 4200, 5_000.0, 2100.0, 2000.0), 1e-6)
    }

    @Test fun `linear lanes ramp evenly`() {
        val lane = DjTransitionPlan.Lane(0f).ramp(0f, 4f, 1f, linear = true)
        for (k in 0..8) assertEquals(k / 8f, lane.at(k / 2f), 1e-6f)
    }

    // ---- DSP ------------------------------------------------------------------------

    private fun run(fx: DjFx, a: DeckAutomation, input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (f in input.indices) {
            if (f % DjFx.BLOCK == 0) fx.beginBlock(a, f.toLong(), 0.0)
            out[f] = fx.process(0, input[f], f.toLong())
            fx.endFrame(f.toLong())
        }
        return out
    }

    @Test fun `loop roll repeats the captured beat sample for sample`() {
        val beat = 4800
        val deck = DjTransitionPlan.Deck.of(1f)
        deck.rolls += DjTransitionPlan.RollStep(1f, 1)
        val input = FloatArray(48000) { sin(it * .0137).toFloat() * (1 + it % 7) / 8f }
        val out = run(DjFx(48000, 1), DeckAutomation(0.0, 400.0, deck), input)
        val anchor = 19200
        for (f in 0 until anchor + beat) assertEquals(input[f], out[f], 1e-6f)
        for (f in anchor + beat until input.size) {
            val i = (f - anchor) % beat
            if (i < 48 || i > beat - 49) continue
            assertEquals("frame $f", input[anchor + i], out[f], 1e-6f)
        }
    }

    @Test fun `backspin plays the deck backwards and brake slows it to a stop`() {
        val ramp = FloatArray(48000 * 2) { it / 96000f }
        val spin = DjTransitionPlan.Deck.of(1f)
        spin.spins += DjTransitionPlan.Spin(1f, 1f, DjTransitionPlan.SpinKind.BACKSPIN)
        // Bar = 1000 ms, so the spin covers frames 48000..60000.
        val back = run(DjFx(48000, 1), DeckAutomation(0.0, 1000.0, spin), ramp)
        val gain = { f: Int -> (1f - (f - 48000) / 12000f).let { it * kotlin.math.sqrt(it) } }
        val early = back[49000] / gain(49000)
        val later = back[54000] / gain(54000)
        assertTrue("reverse: $early then $later", later < early && early < ramp[49000])
        val brake = DjTransitionPlan.Deck.of(1f)
        brake.spins += DjTransitionPlan.Spin(1f, 2f, DjTransitionPlan.SpinKind.BRAKE)
        val slow = run(DjFx(48000, 1), DeckAutomation(0.0, 1000.0, brake), ramp)
        // Reading falls behind the live input as the platter slows.
        assertTrue(slow[70000] / (1 - ((70000 - 48000) / 24000f).let { it * it }) < ramp[70000] - .05f)
        assertTrue(back.all { it.isFinite() } && slow.all { it.isFinite() })
    }

    @Test fun `spin-up starts from silence and lands exactly on the incoming's own audio`() {
        val deck = DjTransitionPlan.Deck.of(1f)
        deck.spins += DjTransitionPlan.Spin(1f, .5f, DjTransitionPlan.SpinKind.SPINUP)
        val input = FloatArray(48000 * 2) { sin(it * .01).toFloat() * .5f }
        // Bar = 1000 ms: the spin covers frames 48000..54000.
        val out = run(DjFx(48000, 1), DeckAutomation(0.0, 1000.0, deck), input)
        assertTrue(abs(out[48010]) < .05f)
        for (f in 54100 until 60000) assertEquals("frame $f", input[f], out[f], 1e-3f)
    }

    @Test fun `echo stays bounded at maximum feedback and rings out after the dry cut`() {
        val deck = DjTransitionPlan.Deck.of(1f)
        deck.lane(P.FEEDBACK).ramp(0f, .001f, .9f)
        deck.lane(P.SEND).ramp(0f, .001f, 1f).ramp(1f, 1.003f, 0f)
        deck.lane(P.DRY).ramp(1f, 1.003f, 0f)
        val random = java.util.Random(7)
        val input = FloatArray(48000 * 6) { (random.nextFloat() * 2 - 1) * .9f }
        val out = run(DjFx(48000, 1), DeckAutomation(0.0, 1000.0, deck), input)
        assertTrue(out.all { it.isFinite() && abs(it) < 25f })
        fun peak(from: Int, to: Int) = (from until to).maxOf { abs(out[it]) }
        assertTrue(peak(48000 + 2000, 48000 + 14000) > .05f)
        assertTrue(peak(48000 * 5, 48000 * 6) < peak(48000 + 2000, 48000 + 14000) * .5f)
    }

    @Test fun `resonant high-pass sweep removes the low end and idle lanes are transparent`() {
        val deck = DjTransitionPlan.Deck.of(1f)
        deck.lane(P.HPF).ramp(0f, .001f, .6f)
        val input = FloatArray(48000) { sin(2 * Math.PI * 50 * it / 48000).toFloat() * .5f }
        val out = run(DjFx(48000, 1), DeckAutomation(0.0, 1000.0, deck), input)
        assertTrue((24000 until 48000).maxOf { abs(out[it]) } < .02f)
        val bypass = run(DjFx(48000, 1), DeckAutomation(0.0, 1000.0, DjTransitionPlan.Deck.of(1f)), input)
        for (f in input.indices) assertEquals(input[f], bypass[f], 1e-6f)
    }

    @Test fun `processor applies the bass swap on the downbeat sample of its media clock`() {
        val p = EqBendProcessor().apply {
            configure(AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT))
            flush()
        }
        val deck = DjTransitionPlan.Deck.of(0f)
        deck.lane(P.SUB).ramp(4f, 4.012f, 1f)
        deck.lane(P.BASS).ramp(4f, 4.012f, 1f)
        p.automation = DeckAutomation(0.0, 500.0, deck)
        val input = FloatArray(48000 * 3) { sin(2 * Math.PI * 45 * it / 48000).toFloat() * .5f }
        val output = FloatArray(input.size)
        var at = 0
        for (chunk in input.toList().chunked(1024)) {
            val buffer = ByteBuffer.allocateDirect(chunk.size * 4).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(chunk.toFloatArray())
            p.queueInput(buffer)
            val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            out.get(output, at, out.remaining())
            at += chunk.size
        }
        val drop = 96000
        assertTrue((drop - 30000 until drop - 64).maxOf { abs(output[it]) } < 1e-3f)
        assertTrue((drop + 1200 until drop + 4800).maxOf { abs(output[it]) } > .3f)
    }
}
