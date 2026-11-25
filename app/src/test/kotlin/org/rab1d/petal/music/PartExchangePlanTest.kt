/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.*

class PartExchangePlanTest {
    private fun frame(b: BandLevels, onset: Float = .02f) = MixAudioMonitor.Frame(
        1000, 1000, .3f, b, b, BandLevels(onset, onset, onset, onset))
    private val low = frame(BandLevels(.28f, .22f, .04f, .02f))
    private val mid = frame(BandLevels(.025f, .04f, .25f, .06f))
    private val high = frame(BandLevels(.02f, .03f, .08f, .26f))
    private fun plan(a: MixAudioMonitor.Frame, b: MixAudioMonitor.Frame) =
        PartExchangePlan.analyse(a, b, EqTransition.Context(beatMs = 500f), 8000f)

    @Test fun `both songs choose the retained and entering parts`() {
        assertEquals(0, plan(low, mid).carrier)
        assertEquals(1, plan(low, mid).lead)
        assertEquals(2, plan(high, mid).carrier)
        assertEquals(2, plan(low, high).lead)
        assertNotEquals(plan(low, mid).exchanges, plan(high, mid).exchanges)
        assertNotEquals(plan(low, mid).exchanges, plan(low, high).exchanges)
        assertEquals(plan(low, mid).exchanges, plan(low, mid).exchanges)
    }

    @Test fun `held combination has one song bass and the other song melody before the closing fade`() {
        val p = plan(low, mid)
        assertEquals(0f, p.incoming(0, .45f), 0f)
        assertEquals(0f, p.incoming(1, .45f), 0f)
        assertEquals(1f, p.incoming(2, .45f), 0f)
        for (band in 0..3) assertEquals(1f, p.incoming(band, .75f), 0f)
    }

    @Test fun `rhythmic shared part can leave return and leave again`() {
        val rhythmic = frame(BandLevels(.21f, .15f, .12f, .20f), .4f)
        val p = PartExchangePlan.analyse(rhythmic, high,
            EqTransition.Context(beatMs = 500f, style = org.rab1d.petal.music.data.AutomixStyle.STUTTER), 8000f)
        val band = if (p.lead == 0) 1 else p.lead + 1
        val sequence = (0..1000).map { p.incoming(band, it / 1000f) }
        assertTrue(sequence.zipWithNext().any { (a, b) -> b < a })
        assertEquals(1f, sequence.last(), 0f)
        assertFalse(plan(low, mid).exchanges.any { it.incoming == 0f })
    }

    @Test fun `unmatched rhythms never manufacture a percussion return`() {
        val rhythmic = frame(BandLevels(.21f, .15f, .12f, .20f), .4f)
        val p = PartExchangePlan.analyse(rhythmic, high, EqTransition.Context(), 8000f)
        assertFalse(p.exchanges.any { it.incoming == 0f })
        assertFalse(plan(rhythmic, high).exchanges.any { it.incoming == 0f })
    }

    @Test fun `fitting exchanges into the closing window preserves measured beat cues`() {
        val p = plan(low, mid)
        for (e in p.exchanges) {
            val beats = e.start * 8000 / 500
            assertEquals(kotlin.math.round(beats), beats, .00001f)
        }
    }

    @Test fun `outro part entrances take more time while preserving complementary ownership`() {
        val normal = PartExchangePlan.analyse(low, mid, EqTransition.Context(beatMs = 500f), 28000f)
        val outro = PartExchangePlan.analyse(low, mid, EqTransition.Context(beatMs = 500f, seam = true), 28000f)
        val a = normal.exchanges.first { it.group == normal.lead }
        val b = outro.exchanges.first { it.group == outro.lead }
        assertEquals(normal.carrier, outro.carrier)
        assertEquals(normal.lead, outro.lead)
        assertTrue(b.end - b.start > (a.end - a.start) * 2)
        for (band in 0..3) assertEquals(1f, outro.incoming(band, 1f), 0f)
    }

    @Test fun `all exchanges are valid and low bands stay together across diverse profiles`() {
        val random = java.util.Random(7)
        repeat(300) {
            fun f() = frame(BandLevels(random.nextFloat(), random.nextFloat(),
                random.nextFloat(), random.nextFloat()), random.nextFloat())
            val p = PartExchangePlan.analyse(f(), f(),
                EqTransition.Context(beatMs = if (it % 2 == 0) 430f else 0f),
                listOf(500f, 4000f, 8000f, 32000f)[it % 4])
            for (e in p.exchanges) assertTrue("$e", e.start >= 0 && e.end > e.start && e.end <= 1)
            for (step in 0..100) {
                val t = step / 100f
                assertEquals(p.incoming(0, t), p.incoming(1, t), 0f)
                for (b in 0..3) assertTrue(p.incoming(b, t) in 0f..1f)
            }
        }
    }
}
