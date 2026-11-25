/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.playback.DjComposer
import org.rab1d.petal.music.playback.DjComposer.Family
import org.rab1d.petal.music.playback.TrackProfile
import java.util.Random

/**
 * A whole set, the way the engine plays one: songs at the 70 bench tempos (64–128 BPM) with
 * varied arrangements (drumless or full intros and outros, choruses, builds into drops),
 * mixed one after another with the set's memory. Checks the set sounds like a DJ, not one
 * move on repeat: no cuts, several families, nothing dominating.
 */
class DjSetTest {
    private val tempos = doubleArrayOf(123.40,83.00,75.50,120.00,109.00,110.00,98.00,88.00,69.42,117.06,112.00,108.00,106.61,88.00,101.00,65.00,108.00,67.00,110.50,96.67,76.49,91.00,109.00,123.50,98.67,64.00,68.68,88.69,76.50,89.01,93.00,85.00,121.00,74.00,110.65,82.00,124.99,100.00,107.04,117.00,93.33,71.28,98.00,100.00,106.00,69.35,77.00,100.00,91.33,77.00,103.30,80.00,106.67,87.00,96.00,117.01,93.00,71.27,85.00,103.98,121.00,128.00,116.99,85.00,117.33,93.33,92.61,93.00,115.00,86.00)

    private fun song(r: Random): TrackProfile {
        val bpm = tempos[r.nextInt(tempos.size)]
        val bars = 64 + 8 * r.nextInt(5)
        val intro = intArrayOf(0, 4, 8)[r.nextInt(3)]
        val outro = bars - intArrayOf(0, 4, 8, 16)[r.nextInt(4)]
        val drumlessIntro = r.nextBoolean()
        val drumlessOutro = r.nextBoolean()
        val chorusLen = 8
        val verseLen = intArrayOf(8, 16)[r.nextInt(2)]
        val sections = ArrayList<Int>().apply { add(0); if (intro > 0) add(intro) }
        val chorus = BooleanArray(bars)
        var b = intro
        while (b < outro) {
            b += verseLen; if (b < outro) sections += b
            val c0 = b; b += chorusLen
            for (k in c0 until minOf(b, outro)) chorus[k] = true
            if (b < outro) sections += b
        }
        if (outro < bars) sections += outro
        val build = r.nextInt(3) == 0
        fun drums(i: Int) = when {
            i < intro -> if (drumlessIntro) 0f else .4f
            i >= outro -> if (drumlessOutro) 0f else .5f
            build && i < bars && i + 1 < bars && chorus.getOrElse(i + 1) { false } && !chorus[i] && (i + 1) % chorusLen == 0 -> .1f
            else -> .6f
        }
        fun energy(i: Int) = when {
            i < intro || i >= outro -> .55f
            chorus[i] -> 1f
            else -> .82f
        }
        fun vocal(i: Int) = if (i < intro || i >= outro) 0f else if (chorus[i]) 1f else if (r.nextInt(5) == 0) .2f else .8f
        val first = 200.0 + r.nextInt(300)
        return TrackProfile(60000 / bpm, first, .5f + r.nextFloat() * .5f, first, .5f, first,
            FloatArray(bars) { energy(it) }, FloatArray(bars) { if (drums(it) > .2f) 1f else .05f },
            FloatArray(bars) { vocal(it) }, FloatArray(bars) { drums(it) }, sections.distinct().sorted().toIntArray(), 0,
            intro, outro, 1 + r.nextInt(12), r.nextBoolean(), .3f, 0.0, first + bars * 240000 / bpm, true)
    }

    /** Pairs the last [set] left to v1 (no v2 move fit). */
    private var lastV1 = 0

    private fun set(style: DjComposer.MixStyle, stems: Boolean, seed: Long): Map<Family, Int> {
        var v1 = 0
        val r = Random(seed)
        val counts = HashMap<Family, Int>()
        val recent = ArrayList<DjComposer.Played>()
        var prev = song(r)
        repeat(120) { k ->
            val next = song(r)
            val o = DjComposer.Side(prev, null, (prev.firstBarMs + prev.bars * prev.barMs + 1500).toLong(), "s$k", stems)
            val n = DjComposer.Side(next, null, (next.firstBarMs + next.bars * next.barMs + 1500).toLong(), "s${k + 1}", stems)
            val mix = DjComposer.compose(o, n, (o.durationMs * .4).toLong(), recent.toList(), k * 7919,
                options = DjComposer.Options(style = style))
            if (mix == null) { v1++; prev = next; return@repeat }
            counts[mix.family] = (counts[mix.family] ?: 0) + 1
            recent.add(0, DjComposer.Played(mix.family, mix.bars, mix.fill))
            while (recent.size > 8) recent.removeAt(recent.size - 1)
            prev = next
        }
        lastV1 = v1
        return counts
    }

    @Test fun `a set is varied and never cuts`() {
        val cuts = setOf(Family.ECHO_THROW, Family.BACKSPIN, Family.BRAKE, Family.STUTTER, Family.FILTER_FADE)
        for (style in DjComposer.MixStyle.entries) for (stems in listOf(false, true)) {
            val counts = set(style, stems, 42)
            val total = counts.values.sum()
            val line = counts.entries.sortedByDescending { it.value }.joinToString { "${it.key.label} ${it.value * 100 / total}%" }
            println("SET $style stems=$stems: $line")
            assertTrue(line, counts.keys.none { it in cuts })
            assertTrue(line, counts.size >= 4)
            // Shares of the whole set, v1's pairs included.
            assertTrue(line, counts.values.max() <= (total + lastV1) * .5)
            // Pairs v2 has no move for go to v1's blend: a last resort, not a habit.
            println("SET $style stems=$stems: v1 ${lastV1 * 100 / (total + lastV1)}%")
            assertTrue(line, lastV1 <= (total + lastV1) * .15)
        }
    }
}
