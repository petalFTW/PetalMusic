/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.playback.*
import org.rab1d.petal.music.playback.DjComposer.Family
import org.rab1d.petal.music.playback.DjTransitionPlan.P
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** AutoMix v3: tempo ride, Context moves (bed, drum tease, acapella out, vocal preview), stems, loops, smooth shuffle. */
class DjContextTest {

    /** A record with explicit per-bar features. */
    private fun record(
        bpm: Double, bars: Int, drums: (Int) -> Float, vocal: (Int) -> Float, sections: IntArray,
        intro: Int, outro: Int, camelot: Int = 8, minor: Boolean = true, energy: (Int) -> Float = { 1f },
        low: (Int) -> Float = { if (drums(it) > .2f) 1f else .05f }, first: Double = 250.0, conf: Float = .9f,
    ) = TrackProfile(60000 / bpm, first, conf, first, .6f, first,
        FloatArray(bars) { energy(it) }, FloatArray(bars) { low(it) }, FloatArray(bars) { vocal(it) },
        FloatArray(bars) { drums(it) }, sections, 0, intro, outro, camelot, minor, .4f, 0.0,
        first + bars * 240000 / bpm, true)

    private fun side(p: TrackProfile, id: String, stems: Boolean = false) =
        DjComposer.Side(p, null, (p.firstBarMs + p.bars * p.barMs + 1500).toLong(), id, stems)

    private val creative = DjComposer.Options(style = DjComposer.MixStyle.CREATIVE)

    private fun compose(out: TrackProfile, inc: TrackProfile, outStems: Boolean = false, inStems: Boolean = false,
                        options: DjComposer.Options = DjComposer.Options(), seed: Int = 1, earliest: Long? = null): DjComposer.Mix? {
        val o = side(out, "out", outStems)
        return DjComposer.compose(o, side(inc, "in", inStems), earliest ?: (o.durationMs - 90_000).coerceAtLeast(0), emptyList(),
            seed, options = options)
    }

    // ---- tempo ride ----------------------------------------------------------------

    @Test fun `pairs up to 8 percent apart blend by riding half the gap on each deck`() {
        val out = record(92.0, 72, { .6f }, { if (it < 60) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 64), 0, 64)
        val inc = record(98.0, 72, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 8, 72)
        val mix = compose(out, inc)!!
        assertTrue(mix.notes, mix.family.blend && mix.sync)
        val r = 98f / 92f
        assertEquals(sqrt(r), mix.rideSpeed!!, 1e-4f)
        assertEquals(16, mix.rideBars)
        // Each deck moves ~3.2 %, not 6.5 %, and the two tempos meet.
        val inSpeed = (mix.incomingBarMs * mix.speed / mix.outBarMs).toFloat()
        assertEquals(92f * mix.speed, 98f * inSpeed, 1e-3f)
        assertTrue(abs(mix.speed - 1) < .035f && abs(inSpeed - 1) < .035f)
        // Without the ride the same pair cannot blend.
        val strict = compose(out, inc, options = DjComposer.Options(tempoRide = false))
        assertFalse(strict?.family?.blend == true)
        assertNull(strict?.rideSpeed)
        // 8-12 % apart: a 32-bar ride, up to 6 % on the outgoing, before any context move.
        val far = compose(out, record(102.0, 72, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24), 8, 72))!!
        assertTrue(far.notes, far.family.blend && far.sync)
        assertEquals(32, far.rideBars)
        assertEquals(sqrt(102f / 92f), far.rideSpeed!!, 1e-4f)
        // Beyond 12 % no pre-mix ride: the loop ride takes it, beat-matched after an audible ride.
        val beyond = compose(out, record(106.0, 72, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24), 8, 72))!!
        assertFalse(beyond.family.blend)
        assertNull(beyond.rideSpeed)
        assertEquals(beyond.notes, Family.LOOP_RIDE, beyond.family)
    }

    // ---- bed under -----------------------------------------------------------------

    @Test fun `a bed with drums at an unrelated tempo needs the incoming's stems`() {
        // 123 to 74 BPM; the incoming's 4-bar intro has drums but no vocal.
        val out = record(123.0, 96, { .6f }, { if (it < 88) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 72, 88), 0, 88, 4)
        val inc = record(74.0, 72, { if (it < 4) .5f else .6f }, { if (it >= 6) 1f else 0f }, intArrayOf(0, 4, 20, 36),
            4, 72, 9, false, energy = { if (it < 4) .7f else 1f })
        for (options in listOf(DjComposer.Options(), creative)) {
            // Beds only (no loop ride). No stems: two grooves would flam, so no bed.
            val beds = options.copy(tempoRide = false)
            val plain = compose(out, inc, options = beds)
            assertNotEquals(plain?.notes, Family.BED_UNDER, plain?.family)
            // With stems the bed plays without its drums, which arrive on the key bar.
            val mix = compose(out, inc, inStems = true, options = beds)!!
            assertEquals(mix.notes, Family.BED_UNDER, mix.family)
            assertNotNull(mix.inStemsMs)
            val n = mix.incoming
            val key = mix.bars.toFloat()
            for (k in 0 until (key * 10).toInt() - 1) assertEquals(0f, n.at(P.DRUMS, k / 10f), 1e-3f)
            assertEquals(1f, n.at(P.DRUMS, key), 0f)
            // And a stem-free version is ready if separation is late.
            val fallback = mix.fallback
            assertFalse(fallback?.needsStems == true)
            assertNotEquals(Family.BED_UNDER, fallback?.family)
        }
    }

    @Test fun `a matched pair beats a bed - the overlay is no longer pre-empted`() {
        val out = record(113.0, 72, { if (it >= 64) 0f else .6f }, { if (it < 64) 1f else 0f }, intArrayOf(0, 16, 32, 48, 64),
            0, 64, 5, true, energy = { if (it >= 64) .4f else 1f })
        val inc = record(118.0, 72, { if (it < 8) 0f else .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40),
            8, 72, 7, false, energy = { if (it < 8) .4f else 1f })
        val outros = DjComposer.Options(djPacing = false)
        assertEquals(Family.OVERLAY, compose(out, inc, options = outros)!!.family)
        assertEquals(Family.OVERLAY, compose(out, inc, options = outros.copy(tempoRide = false))!!.family)
    }

    // ---- drum tease ----------------------------------------------------------------

    @Test fun `drum tease brings in only the incoming's drums, then its bass, then the rest`() {
        val out = record(92.0, 72, { .6f }, { if (it < 64) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 64), 0, 64)
        val inc = record(93.0, 72, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 8, 72)
        for (options in listOf(DjComposer.Options(), creative)) for (outStems in listOf(false, true)) {
            val mix = compose(out, inc, outStems = outStems, inStems = true, options = options)!!
            assertEquals(mix.notes, Family.DRUM_TEASE, mix.family)
            assertTrue(mix.sync)
            val l = mix.bars.toFloat()
            val h = l / 2
            val n = mix.incoming
            val o = mix.outgoing
            // First half: incoming drums only, no kick.
            assertEquals(1f, n.at(P.DRUMS, .5f), 0f)
            for (p in listOf(P.VOX, P.OTHER, P.BASSLINE)) assertEquals(0f, n.at(p, .5f), 0f)
            assertEquals(0f, n.at(P.SUB, h - .1f), 0f)
            assertEquals(1f, n.at(P.HIGH, .5f), 0f)
            // Halfway: its kick replaces the outgoing's.
            assertEquals(1f, n.at(P.SUB, h + .1f), 0f)
            if (outStems) assertEquals(0f, o.at(P.DRUMS, h + .1f), 0f) else assertEquals(0f, o.at(P.SUB, h + .1f), 0f)
            // Its vocal waits for the key bar, so it never sings over the outgoing.
            assertEquals(0f, n.at(P.VOX, l - .2f), 0f)
            for (p in listOf(P.VOX, P.OTHER, P.BASSLINE, P.DRUMS)) assertEquals(1f, n.at(p, l), 0f)
            assertEquals(0f, o.at(P.DRY, l), 0f)
            assertNotNull(mix.inStemsMs)
            assertEquals(outStems, mix.outStemsMs != null)
            assertEquals(0f, (0..800).maxOf { o.at(P.SEND, it / 100f) }, 0f)
            val fallback = mix.fallback!!
            assertTrue(fallback.family.blend && !fallback.needsStems)
        }
        // Not without the incoming's stems.
        assertNotEquals(Family.DRUM_TEASE, compose(out, inc, outStems = true)!!.family)
    }

    // ---- acapella out --------------------------------------------------------------

    @Test fun `acapella out carries the outgoing's last line over the incoming's opening, then its beat lands`() {
        // 90 to 120 BPM (33 %, too far even for a half-time loop ride), keys compatible; the
        // incoming opens on its full beat (no drumless intro).
        val out = record(90.0, 72, { .6f }, { if (it in 40 until 64) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 64), 0, 64, 8)
        val inc = record(120.0, 96, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 0, 96, 9)
        // Without the incoming's stems there is nothing beatless to put under the line: not offered.
        assertNotEquals(Family.ACAPELLA_OUT, compose(out, inc, outStems = true)?.family)
        val mix = compose(out, inc, outStems = true, inStems = true)!!
        assertEquals(mix.notes, Family.ACAPELLA_OUT, mix.family)
        assertFalse(mix.sync || mix.stretchIncoming)
        val a = mix.swapBar
        assertTrue(a in 1..2)
        // The line ends exactly on the key bar.
        assertEquals(64, mix.exitBar + a)
        val o = mix.outgoing
        val n = mix.incoming
        for (p in listOf(P.DRUMS, P.BASSLINE, P.OTHER)) assertEquals(0f, o.at(p, .2f), 0f)
        assertEquals(1f, o.at(P.VOX, .2f), 0f)
        assertEquals(1f, o.at(P.DRY, a - .1f), 0f)
        assertEquals(0f, o.at(P.DRY, a.toFloat()), 0f)
        // The incoming plays under the line from bar 0, its drums and bass line held back,
        // and lands complete as the line ends: the two overlap the whole time.
        assertEquals(0, mix.entryBar)
        assertTrue(n.at(P.MID, .2f) >= .7f)
        assertEquals(0f, n.at(P.DRUMS, a - .1f), 0f)
        assertEquals(0f, n.at(P.SUB, a - .1f), 0f)
        for (p in listOf(P.DRUMS, P.BASSLINE, P.SUB, P.MID)) assertEquals(1f, n.at(p, a.toFloat()), 0f)
        assertNotNull(mix.outStemsMs)
        assertNotNull(mix.inStemsMs)
        // Late stems: a stem-free move, or none (v1's blend).
        assertFalse(mix.fallback?.needsStems == true)
        // Clean: no echo on the last word; Creative throws it.
        assertEquals(0f, (0..400).maxOf { o.at(P.SEND, it / 100f) }, 0f)
        val thrown = compose(out, inc, outStems = true, inStems = true, options = creative)!!
        assertEquals(Family.ACAPELLA_OUT, thrown.family)
        assertTrue((0..400).maxOf { thrown.outgoing.at(P.SEND, it / 100f) } > .5f)
        // A drumless incoming intro goes under the line without stems.
        val padded = record(120.0, 96, { if (it < 4) 0f else .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 4, 24, 40),
            4, 96, 9, energy = { if (it < 4) .6f else 1f })
        val plain = compose(out, padded, outStems = true)!!
        assertTrue(plain.notes, plain.family == Family.ACAPELLA_OUT || plain.family == Family.BED_UNDER)
        // A key clash keeps the vocal off the switch.
        val clash = record(120.0, 96, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 0, 96, 2)
        assertNotEquals(Family.ACAPELLA_OUT, compose(out, clash, outStems = true, inStems = true)!!.family)
    }

    // ---- no tail over ---------------------------------------------------------------

    @Test fun `an unmatched pair with no context move is left to v1, never a tail over`() {
        // 90 to 120 BPM (no loop ride fits), drums to the end and no stems: nothing keeps a beat.
        val drummy = record(90.0, 72, { .6f }, { if (it < 64) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 64), 0, 64, 8)
        val inc = record(120.0, 96, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 0, 96, 9)
        val mix = compose(drummy, inc)
        assertTrue(mix?.notes ?: "", mix == null || mix.family != Family.BED_UNDER || mix.hardEntry)
        assertTrue(Family.entries.none { it.label == "Tail over" })
    }

    private fun overlaps(mix: DjComposer.Mix): Boolean {
        val bands = listOf(P.SUB, P.BASS, P.MID, P.HIGH)
        return (0..mix.bars * 40).count { k ->
            val b = k / 40f
            mix.outgoing.at(P.DRY, b) * bands.maxOf { mix.outgoing.at(it, b) } > .2f && b >= mix.entryBar &&
                bands.maxOf { mix.incoming.at(it, b) } > .2f
        } >= 10
    }

    // ---- loop ride -----------------------------------------------------------------

    @Test fun `distant tempos loop a clean bar, ride it to the incoming's tempo, then blend beat-matched`() {
        // 92 to 112 BPM (21.7 %): too far for any stretch; the outgoing's outro is instrumental with drums.
        val out = record(92.0, 80, { .6f }, { if (it < 56) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56), 0, 56, 8)
        val inc = record(112.0, 96, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 8, 96, 9,
            energy = { if (it < 8) .8f else 1f })
        for (options in listOf(DjComposer.Options(), creative)) {
            val mix = compose(out, inc, options = options)!!
            assertEquals(mix.notes, Family.LOOP_RIDE, mix.family)
            // The ride ends exactly at the incoming's tempo, so the incoming plays native and is synced.
            assertEquals(112f / 92f, mix.rampSpeed!!, 1e-4f)
            assertFalse(mix.stretchIncoming)
            assertTrue(mix.sync && mix.holdOutgoing)
            assertEquals(inc.barMs, mix.wallBarMs, 1e-3)
            // Loop first, ride after it plays once, a bar at the new tempo before the incoming enters.
            val loop = mix.outgoing.loops.single()
            assertEquals(0f, loop.at, 0f)
            assertEquals(mix.bars.toFloat(), loop.until, 0f)
            assertTrue(mix.rampFromBar >= loop.beats / 4f)
            assertEquals(mix.entryBar - 1f, mix.rampToBar, 0f)
            assertEquals(12, (mix.rampToBar - mix.rampFromBar).toInt())
            // The loop is a clean bar: no vocal, with drums.
            for (b in mix.exitBar until mix.exitBar + (loop.beats / 4).toInt()) assertTrue(out.vocal(b) < .3f)
            // The incoming enters after the ride with its bass killed and swaps it halfway.
            val n = mix.incoming
            assertEquals(0f, n.at(P.MID, mix.entryBar - .5f), 0f)
            assertEquals(0f, n.at(P.SUB, mix.entryBar + .5f), 0f)
            assertEquals(1f, n.at(P.SUB, mix.swapBar + .1f), 0f)
            assertEquals(0f, mix.outgoing.at(P.SUB, mix.swapBar + .1f), 0f)
            // The media under the loop never runs out.
            assertTrue(mix.exitBar + mix.bars <= out.bars)
            assertTrue(overlaps(mix))
        }
        // Half-time: 70 into 128 rides the loop down to 64 (two incoming bars per outgoing bar).
        val slow = record(70.0, 72, { .6f }, { if (it < 48) 1f else 0f }, intArrayOf(0, 8, 24, 40, 48), 0, 48, 8)
        val half = compose(slow, record(128.0, 96, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 8, 96, 9))!!
        assertEquals(half.notes, Family.LOOP_RIDE, half.family)
        assertEquals(64f / 70f, half.rampSpeed!!, 1e-4f)
        assertEquals(2 * 240000 / 128.0, half.wallBarMs, 1e-3)
        assertTrue(half.notes, half.notes.contains("half-time"))
        // A sung loop is allowed but steps back once the incoming is in.
        val sung = compose(record(92.0, 80, { .6f }, { 1f }, intArrayOf(0, 8, 24, 40, 56), 0, 80, 8), inc)!!
        assertEquals(sung.notes, Family.LOOP_RIDE, sung.family)
        assertTrue(sung.outgoing.at(P.MID, sung.entryBar + 1f) < .5f)
        // Beyond the ride (and half/double) range: a context move or v1's blend, never a cut.
        val far = compose(out, record(128.0, 96, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24), 8, 96))
        if (far != null) {
            assertNotEquals(Family.LOOP_RIDE, far.family)
            assertTrue(overlaps(far))
        }
    }

    // ---- mashup and double drop ----------------------------------------------------

    @Test fun `creative mashups put the incoming's acapella over the outgoing's beat, then drop its own`() {
        val out = record(92.0, 72, { .6f }, { if (it < 52) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56), 0, 64, 8)
        // The incoming starts singing on bar 4; its chorus is a section at bar 12.
        val inc = record(93.0, 72, { .6f }, { if (it >= 4) 1f else 0f }, intArrayOf(0, 4, 12, 28), 4, 72, 9)
        val seen = HashSet<Family>()
        for (seed in 0 until 40) {
            val mix = compose(out, inc, inStems = true, options = creative, seed = seed)!!
            seen += mix.family
            if (mix.family != Family.MASHUP) continue
            val l = mix.swapBar.toFloat()
            val n = mix.incoming
            // Only its vocal over the old beat, none of its own instrumental, one low end.
            for (p in listOf(P.DRUMS, P.BASSLINE, P.OTHER)) assertEquals(0f, n.at(p, l - .2f), 0f)
            assertEquals(1f, n.at(P.VOX, .5f), 0f)
            assertEquals(1f, n.at(P.MID, .5f), 0f)
            assertEquals(0f, n.at(P.SUB, l - .2f), 0f)
            // Its beat drops on the swap bar while the outgoing hands over its low end and leaves.
            for (p in listOf(P.DRUMS, P.BASSLINE, P.SUB)) assertEquals(1f, n.at(p, l), 0f)
            assertEquals(0f, mix.outgoing.at(P.SUB, l + .1f), 0f)
            assertEquals(0f, mix.outgoing.at(P.DRY, mix.bars.toFloat()), 0f)
            // It entered where its vocal starts.
            assertTrue(inc.vocal(mix.incomingBar) >= .5f)
            assertNotNull(mix.inStemsMs)
        }
        assertTrue("families $seen", Family.MASHUP in seen)
        // Clean never mashes up, and nothing mashes up without the incoming's stems.
        assertNotEquals(Family.MASHUP, compose(out, inc, inStems = true)!!.family)
        assertNotEquals(Family.MASHUP, compose(out, inc, options = creative)!!.family)
    }

    @Test fun `a double drop lands both records' drops on the same bar with one low end`() {
        // Both have a quiet build into a drop: outgoing at bar 56, incoming at bar 8.
        val out = record(92.0, 80, { if (it in 52 until 56) .1f else .6f }, { 0f }, intArrayOf(0, 8, 24, 40, 52, 56, 72),
            0, 72, 8, energy = { if (it in 52 until 56) .5f else 1f })
        val inc = record(92.5, 72, { if (it < 8) .1f else .6f }, { 0f }, intArrayOf(0, 8, 24, 40), 8, 72, 9,
            energy = { if (it < 8) .5f else 1f })
        val mix = compose(out, inc, options = creative)!!
        assertEquals(mix.notes, Family.DOUBLE_DROP, mix.family)
        val d = mix.swapBar
        assertTrue(DjComposer.isDrop(out, mix.exitBar + d))
        assertTrue(DjComposer.isDrop(inc, mix.incomingBar + d))
        val o = mix.outgoing
        val n = mix.incoming
        // Before the drop the incoming is filtered and without lows; on it both play, one bass.
        assertTrue(n.at(P.HPF, d - 2f) > .1f)
        assertEquals(0f, n.at(P.SUB, d - .1f), 0f)
        assertEquals(1f, n.at(P.SUB, d + .1f), 0f)
        assertEquals(0f, o.at(P.SUB, d + .1f), 0f)
        assertTrue(o.at(P.MID, d + 2f) > .5f && o.at(P.DRY, d + 2f) == 1f)
        assertEquals(0f, o.at(P.DRY, mix.bars.toFloat()), 0f)
    }

    // ---- DJ pacing -----------------------------------------------------------------

    @Test fun `dj pacing mixes out after the second chorus, not at the outro`() {
        // Choruses (sung, full) at 16-24 and 40-48; verses quieter; a long outro to bar 96.
        val out = record(92.0, 96, { .6f }, { if (it in 8 until 48) 1f else 0f }, intArrayOf(0, 8, 16, 24, 40, 48, 64, 80),
            0, 80, 8, energy = { if (it in 16 until 24 || it in 40 until 48) 1f else .85f })
        val inc = record(93.0, 72, { .6f }, { if (it >= 8) 1f else 0f }, intArrayOf(0, 8, 24, 40), 8, 72, 9)
        assertEquals(48, DjComposer.payoffBar(out))
        val paced = compose(out, inc, seed = 3, earliest = 60_000)!!
        assertTrue("exit ${paced.exitBar}", paced.exitBar + paced.bars in 48..68)
        // Off: it plays on towards the outro.
        val full = compose(out, inc, seed = 3, options = DjComposer.Options(djPacing = false), earliest = 60_000)!!
        assertTrue("exit ${full.exitBar}", full.exitBar >= 72)
        // A partial (stream) profile has no payoff.
        val partial = TrackProfile(out.beatMs, out.phaseMs, out.gridConfidence, out.downbeatMs, out.downbeatConfidence,
            out.firstBarMs, out.barEnergy, out.barLow, out.barVocal, out.barDrums, out.sections, out.phraseOffset,
            out.introEndBar, out.outroStartBar, out.camelot, out.minor, out.keyConfidence, out.analysedFromMs,
            out.analysedToMs, false)
        assertNull(DjComposer.payoffBar(partial))
    }

    // ---- vocal preview -------------------------------------------------------------

    @Test fun `a big drop previews the incoming's hook vocal over the build, then starts it from the top`() {
        val out = record(124.0, 96, { .6f }, { if (it < 80) 1f else 0f }, intArrayOf(0, 8, 24, 40, 56, 72, 88), 0, 88,
            energy = { 1f })
        // Much quieter and slower; the hook (sung, full) starts at bar 8.
        val inc = record(80.0, 72, { .5f }, { if (it in 8 until 16) 1f else .2f }, intArrayOf(0, 8, 16, 32), 0, 72,
            energy = { if (it < 8) .25f else .3f })
        val mix = compose(out, inc, inStems = true, options = creative)!!
        assertEquals(mix.notes, Family.VOCAL_PREVIEW, mix.family)
        val inc0 = inc.barStartMs(8).toLong()
        assertEquals(inc0, mix.clipMs!!.first)
        assertEquals(inc.barStartMs(10).toLong(), mix.clipMs!!.last)
        val o = mix.outgoing
        assertTrue(o.sampleAt in 0f..mix.swapBar.toFloat())
        assertTrue(o.at(P.SAMPLE, o.sampleAt + .1f) > .8f)
        assertEquals(0f, o.at(P.SAMPLE, mix.swapBar.toFloat()), 0f)
        // The clip ends on the cut, where the incoming starts from its first bar.
        val clipBars = (2 * inc.barMs / mix.wallBarMs).toFloat()
        assertEquals(mix.swapBar.toFloat(), o.sampleAt + clipBars, 1e-3f)
        assertEquals(0, mix.incomingBar)
        assertEquals(mix.swapBar, mix.entryBar)
        assertNotNull(mix.fallback)
    }

    // ---- DSP: stems, clip and loops ------------------------------------------------

    private fun process(p: EqBendProcessor, input: FloatArray, channels: Int): FloatArray {
        val output = FloatArray(input.size)
        var at = 0
        for (chunk in input.toList().chunked(1024 * channels)) {
            val buffer = ByteBuffer.allocateDirect(chunk.size * 4).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(chunk.toFloatArray())
            p.queueInput(buffer)
            val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            out.get(output, at, out.remaining())
            at += chunk.size
        }
        return output
    }

    private fun processor(rate: Int = 48000, channels: Int = 1) = EqBendProcessor().apply {
        configure(AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        flush()
    }

    @Test fun `stem lanes remove one stem and unity gains leave the deck untouched`() {
        val rate = 48000
        val n = rate * 2
        val drums = FloatArray(n) { if (it % 12000 < 200) .4f * sin(it * .3).toFloat() else 0f }
        val tone = FloatArray(n) { .3f * sin(2 * PI * 440 * it / rate).toFloat() }
        val input = FloatArray(n) { drums[it] + tone[it] }
        val zero = ShortArray(n)
        val window = StemWindow(0.0, rate, 1, n, arrayOf(toShorts(drums), zero, zero, toShorts(tone)), "test")
        // Unity: identical to a deck with no stems at all.
        val plain = process(processor().apply { automation = DeckAutomation(0.0, 1000.0, DjTransitionPlan.Deck.of(1f)) }, input, 1)
        val unity = process(processor().apply { automation = DeckAutomation(0.0, 1000.0, DjTransitionPlan.Deck.of(1f), window) }, input, 1)
        for (i in input.indices) assertEquals(plain[i], unity[i], 1e-6f)
        // Drums out from bar 0: only the tone is left (through the flat crossover).
        val deck = DjTransitionPlan.Deck.of(1f)
        deck.lane(P.DRUMS).ramp(0f, .001f, 0f)
        val out = process(processor().apply { automation = DeckAutomation(0.0, 1000.0, deck, window) }, input, 1)
        val toneOnly = process(processor().apply { automation = DeckAutomation(0.0, 1000.0, DjTransitionPlan.Deck.of(1f)) }, tone, 1)
        for (i in 4800 until n) assertEquals("frame $i", toneOnly[i], out[i], 2e-4f)
    }

    @Test fun `a clip is voiced on the deck from its bar, at its gain`() {
        val rate = 48000
        val clipPcm = FloatArray(rate / 2) { .5f * sin(2 * PI * 660 * it / rate).toFloat() }
        val deck = DjTransitionPlan.Deck.of(1f)
        deck.sampleAt = 1f
        deck.lane(P.SAMPLE).ramp(1f, 1.0001f, 1f)
        val p = processor().apply {
            automation = DeckAutomation(0.0, 500.0, deck, clip = StemClip(rate, 1, clipPcm.size, toShorts(clipPcm), "test"))
        }
        val out = process(p, FloatArray(rate), 1)
        // Silent before bar 1 (frame 24000), the clip after it.
        assertTrue((0 until 23900).maxOf { abs(out[it]) } < 1e-6f)
        assertTrue((24500 until 47000).maxOf { abs(out[it]) } > .3f)
    }

    @Test fun `a bar loop repeats bar 0 seamlessly and hands back to the live audio`() {
        val rate = 48000
        val deck = DjTransitionPlan.Deck.of(1f)
        // Bar = 1000 ms; loop beat 0..2 (half a bar, 24000 frames) from bar 0 until bar 2.
        deck.loops += DjTransitionPlan.Loop(0f, 2f, 2f)
        val input = FloatArray(rate * 3) { sin(it * .0137).toFloat() * (1 + it % 5) / 6f }
        val fx = DjFx(rate, 1)
        val a = DeckAutomation(0.0, 1000.0, deck)
        val out = FloatArray(input.size)
        for (f in input.indices) {
            if (f % DjFx.BLOCK == 0) fx.beginBlock(a, f.toLong(), 0.0)
            out[f] = fx.process(0, input[f], f.toLong())
            fx.endFrame(f.toLong())
        }
        val len = 24000
        for (f in 0 until len) assertEquals(input[f], out[f], 1e-6f)
        for (f in len until 2 * rate - 48) {
            if ((f - len) % len < 48) continue // the seam crossfade
            assertEquals("frame $f", input[(f - len) % len], out[f], 1e-6f)
        }
        // On a smooth signal the seam crossfades into what originally followed the loop: no jump.
        val smooth = FloatArray(rate * 3) { sin(it * .0137).toFloat() * .8f }
        val sfx = DjFx(rate, 1)
        val sout = FloatArray(smooth.size)
        for (f in smooth.indices) {
            if (f % DjFx.BLOCK == 0) sfx.beginBlock(a, f.toLong(), 0.0)
            sout[f] = sfx.process(0, smooth[f], f.toLong())
            sfx.endFrame(f.toLong())
        }
        for (f in len - 2 until 2 * rate + 2) assertTrue("jump at $f", abs(sout[f + 1] - sout[f]) < .03f)
        for (f in 2 * rate until input.size) assertEquals(input[f], out[f], 1e-6f)
    }

    @Test fun `hpss stems sum back to the input and split clicks from a tone`() {
        val rate = 44100
        val n = rate * 3
        val clicks = FloatArray(n) { if (it % 11025 < 64) (if (it % 2 == 0) .6f else -.6f) else 0f }
        val tone = FloatArray(n) { .3f * sin(2 * PI * 330 * it / rate).toFloat() }
        val pcm = FloatArray(n * 2) { val f = it / 2; clicks[f] + tone[f] }
        val stems = HpssStemSeparator().separate(pcm, n, rate, 2)!!
        // The masks partition every bin, so the stems add back up (away from the edges).
        var err = 0.0
        var ref = 0.0
        for (i in rate * 2 until (n - rate) * 2) {
            val sum = stems.sumOf { it[i].toDouble() }
            err += (sum - pcm[i]) * (sum - pcm[i])
            ref += pcm[i].toDouble() * pcm[i]
        }
        assertTrue("reconstruction error ${err / ref}", err / ref < 1e-3)
        fun energy(x: FloatArray, from: Int, to: Int) = (from until to).sumOf { x[it * 2].toDouble() * x[it * 2] }
        // Around a click the drum stem holds most of the transient; between clicks the tone is not drums.
        val click = 11025 * 4
        assertTrue(energy(stems[StemWindow.DRUMS], click, click + 64) > energy(stems[StemWindow.OTHER], click, click + 64) +
            energy(stems[StemWindow.VOCALS], click, click + 64))
        val gap = click + 4000
        assertTrue(energy(stems[StemWindow.DRUMS], gap, gap + 2000) < .1 * (energy(stems[StemWindow.VOCALS], gap, gap + 2000) +
            energy(stems[StemWindow.OTHER], gap, gap + 2000)))
        // A centred 330 Hz tone is sustained and in the voice range: the vocal stem.
        assertTrue(energy(stems[StemWindow.VOCALS], gap, gap + 2000) > energy(stems[StemWindow.OTHER], gap, gap + 2000))
    }

    // ---- smooth shuffle ------------------------------------------------------------

    @Test fun `smooth shuffle orders songs after the next one by tempo and leaves the next alone`() {
        val bpm = mapOf("a" to 90f, "b" to 128f, "c" to 70f, "d" to 92f, "e" to 127f, "f" to 71f, "g" to 93f)
        val tracks = bpm.keys.map { Track(it, Source.LOCAL, it.uppercase()) }
        val q = QueueManager()
        q.setShuffle(true)
        q.set(tracks, 0)
        val shuffled = q.visibleQueue()
        val next = shuffled[1]
        val cost = { x: Track, y: Track ->
            val r = bpm.getValue(y.id) / bpm.getValue(x.id)
            listOf(1f, 2f, .5f).minOf { abs(r / it - 1) }.toDouble()
        }
        q.smoothUpcoming(6, cost)
        val after = q.visibleQueue()
        assertEquals(shuffled[0], after[0])
        assertEquals(next, after[1])
        assertEquals(shuffled.toSet(), after.toSet())
        // Each step is the closest tempo left.
        for (i in 2 until after.size) {
            val rest = after.drop(i)
            assertEquals(rest.minOf { cost(after[i - 1], it) }, cost(after[i - 1], after[i]), 1e-9)
        }
        // Off without shuffle.
        q.setShuffle(false)
        assertFalse(q.smoothUpcoming(6, cost))
    }
}
