/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.*
import kotlin.math.abs

class AdaptiveEqControllerTest {
    @Test fun `strong first note after quiet intro enters gently over a quiet outro`() {
        val controller = AdaptiveEqController(EqTransition.Context(), 8000)
        repeat(50) {
            controller.update((it + 1) / 200f, 40f,
                frame(mid, rms = .001f), frame(BandLevels.ZERO, rms = 0f))
        }
        val first = controller.update(.255f, 40f, frame(mid, rms = .001f), frame(bright, .8f, .8f))
        assertTrue(first.incoming.high < .02f)
        repeat(30) {
            controller.update((it + 52) / 200f, 40f,
                frame(mid, rms = .001f), frame(bright, .8f, .8f))
        }
        assertTrue(controller.snapshot().points.last().incoming.high > .9f)
    }
    private val low = BandLevels(.28f, .22f, .04f, .02f)
    private val mid = BandLevels(.025f, .04f, .25f, .06f)
    private val bright = BandLevels(.02f, .03f, .08f, .26f)
    private fun frame(bands: BandLevels, onset: Float = .02f, rms: Float = .3f) = MixAudioMonitor.Frame(
        1000, 1000, rms, bands, bands, BandLevels(onset, onset, onset, onset),
    )
    private fun run(out: MixAudioMonitor.Frame?, next: MixAudioMonitor.Frame?): EqTransition {
        val controller = AdaptiveEqController(EqTransition.Context(beatMs = 500f), 8000)
        repeat(200) { controller.update((it + 1) / 200f, 40f, out, next) }
        return controller.snapshot()
    }
    private fun distance(a: EqTransition, b: EqTransition): Float = a.points.zip(b.points).sumOf { (x, y) ->
        (0..3).sumOf { (abs(x.incoming[it] - y.incoming[it]) + abs(x.outgoing[it] - y.outgoing[it])).toDouble() }
    }.toFloat()

    @Test fun `both tracks own distinct parts until the closing exchange`() {
        val result = run(frame(low), frame(mid))
        val held = result.points.first { it.progress >= .45f }
        assertTrue(held.outgoing.bass > .9f)
        assertEquals(0f, held.incoming.bass, 0f)
        assertTrue(held.incoming.mid > .9f)
        assertEquals(0f, held.outgoing.mid, 0f)
        assertEquals(BandLevels.ZERO, result.points.last().outgoing)
        assertEquals(BandLevels.ONE, result.points.last().incoming)
    }

    @Test fun `all outgoing parts fade in penultimate bar and stay silent in final bar`() {
        for (bars in listOf(4, 8, 16)) {
            val duration = bars * 2000
            val start = (bars - 2f) / bars
            val end = (bars - 1f) / bars
            for (measured in listOf(true, false)) {
                val controller = AdaptiveEqController(EqTransition.Context(beatMs = 500f), duration)
                var previous = BandLevels.ONE
                var sawFade = false
                for (step in 1..bars * 100) {
                    val t = step.toFloat() / (bars * 100)
                    val point = controller.update(t, 20f,
                        if (measured) frame(low, .4f) else null,
                        if (measured) frame(if (step % 2 == 0) mid else bright, .4f) else null)
                    if (t >= start) for (b in 0..3) {
                        assertTrue("bar $bars band $b revived", point.outgoing[b] <= previous[b] + .000001f)
                        if (t < end && point.outgoing[b] > 0f && point.outgoing[b] < previous[b]) sawFade = true
                    }
                    if (t >= end) {
                        assertEquals(BandLevels.ZERO, point.outgoing)
                        assertEquals(BandLevels.ONE, point.incoming)
                    }
                    previous = point.outgoing
                }
                assertTrue("$bars bars must fade rather than cut", sawFade)
            }
        }
    }

    @Test fun `changing either spectrum changes the transition with identical BPM key and style`() {
        val reference = run(frame(low), frame(mid))
        assertTrue(distance(reference, run(frame(bright), frame(mid))) > 5f)
        assertTrue(distance(reference, run(frame(low), frame(bright))) > 5f)
        // Same audio and same hints produce the same result, not artificial randomness.
        assertEquals(reference, run(frame(low), frame(mid)))
    }

    @Test fun `every measured and unmeasured blend stays bounded and finishes at unity`() {
        for (a in listOf(null, frame(low), frame(mid), frame(bright))) {
            for (b in listOf(null, frame(low, .4f), frame(mid), frame(bright))) {
                val result = run(a, b)
                assertEquals(BandLevels.ONE, result.points.first().outgoing)
                assertEquals(BandLevels.ZERO, result.points.first().incoming)
                assertEquals(BandLevels.ZERO, result.points.last().outgoing)
                assertEquals(BandLevels.ONE, result.points.last().incoming)
                for (point in result.points) for (band in 0..3) {
                    assertTrue(point.outgoing[band] in 0f..1f)
                    assertTrue(point.incoming[band] in 0f..1f)
                    assertTrue(point.outgoing[band] + point.incoming[band] <= 1.000001f)
                }
            }
        }
    }

    @Test fun `a band trades level instead of ducking through the middle of the blend`() {
        // Low-heavy outgoing into a mid-heavy incoming: the outgoing must not
        // give a band up before the incoming has taken it, or the mix loses its
        // bottom for seconds at a time.
        for (result in listOf(run(frame(low), frame(mid)), run(frame(mid), frame(low)))) {
            for (point in result.points) for (band in 0..3) {
                val sum = point.outgoing[band] + point.incoming[band]
                assertTrue(
                    "band $band dips to $sum at progress ${point.progress}",
                    sum >= .85f,
                )
            }
        }
    }

    @Test fun `live changes affect committed blends without changing their past`() {
        val adaptive = AdaptiveEqController(EqTransition.Context(), 8000)
        val steady = AdaptiveEqController(EqTransition.Context(), 8000)
        repeat(100) {
            adaptive.update((it + 1) / 200f, 40f, frame(mid), frame(mid))
            steady.update((it + 1) / 200f, 40f, frame(mid), frame(mid))
        }
        val past = adaptive.snapshot()
        repeat(40) {
            adaptive.update((it + 101) / 200f, 40f, frame(mid),
                frame(BandLevels(.05f, .08f, .5f, .12f), rms = .6f))
            steady.update((it + 101) / 200f, 40f, frame(mid), frame(mid))
        }
        assertEquals(past.points, adaptive.snapshot().points.take(past.points.size))
        assertTrue(distance(adaptive.snapshot(), steady.snapshot()) > .2f)
    }

    @Test fun `silent incoming holds outgoing and pause does not advance controller`() {
        val controller = AdaptiveEqController(EqTransition.Context(), 8000)
        repeat(140) { controller.update((it + 1) / 200f, 40f, frame(mid), frame(BandLevels.ZERO, rms = 0f)) }
        assertEquals(BandLevels.ONE, controller.snapshot().points.last().outgoing)
        assertEquals(BandLevels.ZERO, controller.snapshot().points.last().incoming)
        val paused = controller.snapshot()
        repeat(10) { controller.update(.7f, 0f, frame(mid), frame(low)) }
        assertEquals(paused, controller.snapshot())
    }

    @Test fun `missing measurements do not invent a bass kill and stale data is labelled`() {
        val result = run(null, null)
        assertTrue(result.label.contains("Listening"))
        for (p in result.points) assertEquals(1f, p.outgoing.bass + p.incoming.bass, .000001f)
        val controller = AdaptiveEqController(EqTransition.Context(), 8000)
        controller.update(.07f, 560f, frame(low), frame(mid))
        controller.update(.09f, 160f, null, null)
        assertTrue(controller.snapshot().label.contains("holding measured plan"))
    }

    @Test fun `quiet intro survives the scheduled closing cue until an audible handoff`() {
        val controller = AdaptiveEqController(EqTransition.Context(), 8000)
        repeat(180) {
            controller.update((it + 1) / 200f, 40f, frame(mid), frame(BandLevels.ZERO, rms = 0f))
        }
        assertEquals(BandLevels.ONE, controller.snapshot().points.last().outgoing)
        repeat(20) { controller.update((it + 181) / 200f, 40f, frame(mid), frame(low)) }
        assertEquals(BandLevels.ZERO, controller.snapshot().points.last().outgoing)
        assertEquals(BandLevels.ONE, controller.snapshot().points.last().incoming)
    }

    @Test fun `an inaudible outgoing carrier cannot hollow out the new record`() {
        val controller = AdaptiveEqController(EqTransition.Context(), 8000)
        repeat(80) { controller.update((it + 1) / 200f, 40f, frame(low), frame(mid)) }
        repeat(20) {
            controller.update((it + 81) / 200f, 40f, frame(BandLevels.ZERO, rms = 0f), frame(mid))
        }
        assertEquals(BandLevels.ZERO, controller.snapshot().points.last().outgoing)
        assertEquals(BandLevels.ONE, controller.snapshot().points.last().incoming)
    }
}
