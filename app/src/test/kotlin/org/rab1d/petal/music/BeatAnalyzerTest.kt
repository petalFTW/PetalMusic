/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.BeatAnalyzer
import kotlin.math.PI
import kotlin.math.sin

class BeatAnalyzerTest {
    @Test fun steadyPulseHasTrustworthyTempoAndBeatPhase() {
        val rate = BeatAnalyzer.ANALYSIS_RATE
        val pcm = FloatArray(rate * 30)
        for (beat in 0 until 60) {
            val start = ((137 + beat * 500) * rate / 1000.0).toInt()
            for (i in 0 until rate / 25) if (start + i < pcm.size) {
                pcm[start + i] = (sin(2 * PI * 180 * i / rate) * (1f - i.toFloat() / (rate / 25))).toFloat()
            }
        }
        val result = BeatAnalyzer.analyze(pcm)!!
        assertEquals(120f, result.bpm, 1f)
        assertTrue("A regular pulse must supply a usable grid: $result", result.strength >= .3f)
        assertEquals(137f, result.phaseMs, 40f)
    }

    @Test fun silenceDoesNotSupplyABeatGrid() {
        assertNull(BeatAnalyzer.analyze(FloatArray(BeatAnalyzer.ANALYSIS_RATE * 10)))
    }
}
