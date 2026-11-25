/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.DjCrossover
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DjCrossoverTest {
    private fun response(rate: Int, hz: Double): FloatArray {
        val split = DjCrossover(rate)
        val bands = FloatArray(4)
        val energy = DoubleArray(5)
        repeat(rate) { frame ->
            val x = (.25 * sin(2 * PI * hz * frame / rate)).toFloat()
            split.split(x, bands)
            if (frame >= rate / 2) {
                for (b in 0..3) energy[b] += bands[b].toDouble() * bands[b]
                val sum = bands.sum().toDouble()
                energy[4] += sum * sum
            }
        }
        return FloatArray(5) { sqrt(energy[it] / (rate / 2)) .toFloat() / (.25f / sqrt(2f)) }
    }

    @Test fun `unity magnitude stays flat across bands and sample rates`() {
        for (rate in listOf(8000, 44100, 48000, 96000)) {
            for (hz in listOf(30.0, 90.0, 170.0, 300.0, 900.0, 3000.0)) {
                assertEquals("$rate Hz $hz", 1f, response(rate, hz)[4], .006f)
            }
        }
    }

    @Test fun `held parts reject conflicting frequencies rather than leaking whole songs`() {
        val sub = response(48000, 40.0)
        assertTrue(sub[2] + sub[3] < .001f) // >60 dB rejection in upper parts
        val mid = response(48000, 1000.0)
        assertTrue(mid[0] + mid[1] < .01f) // >40 dB rejection in bass parts
        val high = response(48000, 9000.0)
        assertTrue(high[0] + high[1] + high[2] < .01f)
    }

    @Test fun `reset clears filter history`() {
        val split = DjCrossover(48000)
        val bands = FloatArray(4)
        repeat(500) { split.split(.5f, bands) }
        split.reset()
        repeat(500) { split.split(0f, bands); bands.forEach { assertEquals(0f, it, 0f) } }
    }
}
