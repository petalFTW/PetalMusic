/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class TrackAnalyzerTest {
    private val rate = 22050

    /**
     * A synthetic hip-hop record at [bpm]: 0.37 s of silence, a hats-only
     * 8-bar intro, then kick on 1 and 3 (plus the "and" of 3), snare on 2 and 4,
     * 8th hats, and a sustained vocal-like tone in bars [vocalBars].
     */
    private fun record(bpm: Double, bars: Int, vocalBars: IntRange, offsetMs: Double = 370.0): Pair<FloatArray, Double> {
        val beat = 60.0 / bpm
        val n = ((offsetMs / 1000 + bars * 4 * beat + 1) * rate).toInt()
        val pcm = FloatArray(n)
        val random = java.util.Random(3)
        fun hit(at: Double, length: Double, voice: (Double) -> Double) {
            val start = (at * rate).toInt()
            for (i in 0 until (length * rate).toInt()) if (start + i in pcm.indices) {
                pcm[start + i] += voice(i.toDouble() / rate).toFloat()
            }
        }
        val origin = offsetMs / 1000
        for (bar in 0 until bars) {
            val t0 = origin + bar * 4 * beat
            for (e in 0 until 8) hit(t0 + e * beat / 2, .03) { t -> .12 * (random.nextGaussian()) * exp(-t / .008) * (if (e % 2 == 0) 1.0 else .6) }
            if (bar < 8) continue
            for (k in listOf(0.0, 2.0, 2.5)) hit(t0 + k * beat, .18) { t -> .8 * sin(2 * PI * (55 + 60 * exp(-t / .02)) * t) * exp(-t / .07) }
            for (k in listOf(1.0, 3.0)) hit(t0 + k * beat, .12) { t -> .5 * random.nextGaussian() * exp(-t / .03) + .3 * sin(2 * PI * 190 * t) * exp(-t / .04) }
            if (bar in vocalBars) hit(t0, 4 * beat) { t ->
                val f = 220 * (1 + .01 * sin(2 * PI * 5 * t))
                (1..12).sumOf { h -> sin(2 * PI * f * h * t) * (if (h * 220 in 1500..3500) .05 else .015) } *
                    (.6 + .4 * sin(2 * PI * t / (4 * beat)).let { it * it })
            }
        }
        return pcm to offsetMs
    }

    private fun envelope(pcm: FloatArray): BandEnvelope.Snapshot {
        val env = BandEnvelope(BandEnvelope.hopsFor(pcm.size * 1000.0 / rate + 2000))
        env.reset(0.0, rate)
        val crossover = DjCrossover(rate)
        val vocal = VocalBand(rate)
        val bands = FloatArray(4)
        for (x in pcm) {
            crossover.split(x, bands)
            env.frame(x, bands[0] + bands[1], bands[2], bands[3], vocal.process(x))
        }
        return env.snapshot()!!
    }

    @Test fun `hip-hop record gets an exact grid, its bar phase, vocals and sections`() {
        val bpm = 92.0
        val (pcm, offset) = record(bpm, 64, 16 until 40)
        val p = TrackAnalyzer.analyse(envelope(pcm), complete = true)!!
        val beatMs = 60000 / bpm
        assertEquals(bpm, p.bpm.toDouble(), .15)
        assertTrue("grid confidence ${p.gridConfidence}", p.gridConfidence >= .5f)
        // Within a few ms of the real beat after more than a minute and a half.
        val phase = (p.phaseMs - offset).mod(beatMs).let { if (it > beatMs / 2) it - beatMs else it }
        assertTrue("phase error $phase ms", abs(phase) < 8)
        val late = (p.phaseMs + 200 * p.beatMs - offset).mod(beatMs).let { if (it > beatMs / 2) it - beatMs else it }
        assertTrue("drift after 200 beats $late ms", abs(late) < 8)
        // Kick on 1, snare on 2 and 4: bar lines on the first kick.
        val bar = (p.downbeatMs!! - offset).mod(beatMs * 4)
        assertTrue("downbeat $bar", bar < 12 || bar > beatMs * 4 - 12)
        val vocalIn = (18 until 38).map { p.vocal(it) }.average()
        val vocalOut = ((8 until 15) + (42 until 62)).map { p.vocal(it) }.average()
        assertTrue("vocal $vocalIn vs $vocalOut", vocalIn > vocalOut + .3)
        // Drums arrive at bar 8, the full song (vocal) at 16; either is the intro's end.
        assertTrue("intro ${p.introEndBar}", p.introEndBar in 7..9 || p.introEndBar in 15..17)
        assertTrue("sections ${p.sections.toList()}", p.sections.any { it in 15..17 } && p.sections.any { it in 39..41 })
    }

    @Test fun `a file that starts mid-bar still finds beat one`() {
        val bpm = 96.0
        val beat = 60000 / bpm
        val (full, offset) = record(bpm, 48, 12 until 30)
        // Drop the first 370 ms + 2 beats: the file now opens on beat 3.
        val cut = ((offset + 2 * beat) / 1000 * rate).toInt()
        val p = TrackAnalyzer.analyse(envelope(full.copyOfRange(cut, full.size)))!!
        val expected = (4 * beat - (cut * 1000.0 / rate - offset)).mod(4 * beat)
        val bar = (p.downbeatMs!! - expected).mod(4 * beat)
        assertTrue("downbeat ${p.downbeatMs} expected $expected", bar < 12 || bar > 4 * beat - 12)
    }

    @Test fun `half-time ambiguity resolves the way hip-hop and R&B count`() {
        val (pcm, _) = record(72.0, 40, 12 until 30)
        val p = TrackAnalyzer.analyse(envelope(pcm))!!
        assertEquals(72.0, p.bpm.toDouble(), .2)
    }

    @Test fun `noise has no confident grid`() {
        val random = java.util.Random(1)
        val pcm = FloatArray(rate * 40) { (random.nextGaussian() * .2).toFloat() }
        val p = TrackAnalyzer.analyse(envelope(pcm))
        assertTrue(p == null || p.gridConfidence < .3f)
    }

    @Test fun `chord progression gives its key`() {
        val key = KeyDetector(rate)
        // A minor: Am, F, C, G — all inside the relative major/minor pair 8A/8B.
        val chords = listOf(listOf(57, 60, 64), listOf(53, 57, 60), listOf(48, 52, 55), listOf(55, 59, 62))
        for (rep in 0 until 6) for (chord in chords) for (i in 0 until rate * 2) {
            val t = i.toDouble() / rate
            val x = chord.sumOf { m -> sin(2 * PI * 440 * Math.pow(2.0, (m - 69) / 12.0) * t) } / 4
            key.sample(x.toFloat())
        }
        val k = key.result()!!
        assertEquals(8, k.camelot)
    }

    @Test fun `profiles survive a round trip to disk`() {
        val (pcm, _) = record(92.0, 24, 10 until 20)
        val p = TrackAnalyzer.analyse(envelope(pcm), complete = true)!!
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> p.write(out) } }.toByteArray()
        val q = TrackProfile.read(DataInputStream(ByteArrayInputStream(bytes)))!!
        assertEquals(p.beatMs, q.beatMs, 0.0)
        assertEquals(p.downbeatMs, q.downbeatMs)
        assertArrayEquals(p.barVocal, q.barVocal, 0f)
        assertArrayEquals(p.sections, q.sections)
        assertTrue(q.complete)
    }

    @Test fun `envelope clock follows media time and survives a reset`() {
        val e = BandEnvelope(BandEnvelope.hopsFor(30_000.0))
        e.reset(5000.0, 44100)
        repeat(44100 * 20) { e.frame(.1f, .1f, .1f, .1f, .1f) }
        val s = e.snapshot(15_000.0)!!
        assertEquals(25_000.0, s.endMs, 10.0)
        assertEquals(10_000.0, s.originMs, 10.0)
        e.reset(0.0, 44100)
        assertNull(e.snapshot())
    }
}
