/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.MixTiming
import kotlin.math.roundToInt

class MixTimingTest {
    @Test fun `entrance protects buffered first notes and completes before handover`() {
        for (duration in listOf(100, 500, 2500, 16000)) for (beat in listOf(0f, 500f, 800f)) {
            assertEquals(0f, MixTiming.entryGain(0f, duration, beat), 0f)
            assertTrue(MixTiming.entryGain(40f, duration, beat) < 1f)
            var previous = 0f
            for (ms in 0..duration step 10) {
                val gain = MixTiming.entryGain(ms.toFloat(), duration, beat)
                assertTrue(gain in previous..1f)
                previous = gain
            }
            assertEquals(1f, MixTiming.entryGain(duration / 2f, duration, beat), 0f)
        }
    }
    @Test fun `closing fade spans fourteen to fifteen or two to three bars`() {
        val long = MixTiming.closingWindow(32000f, 500f)
        assertEquals(14f / 16, long.start, .000001f)
        assertEquals(15f / 16, long.end, .000001f)
        val short = MixTiming.closingWindow(8000f, 500f)
        assertEquals(2f / 4, short.start, .000001f)
        assertEquals(3f / 4, short.end, .000001f)
        assertEquals(.5f, short.gain(.625f), .000001f)
    }

    @Test fun `runtime overlap cannot exceed sixteen incoming bars`() {
        assertEquals(32000, MixTiming.durationMs(90000, null, 0, 0, 500f))
    }

    @Test fun `outro overlap keeps the ending rather than shortening it to bar groups`() {
        assertEquals(27000, MixTiming.durationMs(27250, 27500.0, 137, 137, 500f, outro = true))
        assertEquals(44000, MixTiming.durationMs(44500, 44250.0, 137, 137, 500f, outro = true))
        assertEquals(27250, MixTiming.durationMs(27250, 27500.0, 0, 0, 0f, outro = true))
    }

    @Test fun `late preparation cannot leave seven or thirty one beats`() {
        assertEquals(2000, MixTiming.durationMs(4000, 3750.0, 137, 137, 500f))
        assertEquals(12000, MixTiming.durationMs(16000, 15750.0, 137, 137, 500f))
    }

    @Test fun `complete eight beat group survives when there is room`() {
        assertEquals(4000, MixTiming.durationMs(4000, 4500.0, 137, 137, 500f))
    }

    @Test fun `fractional tempos do not drop a whole phrase because of millisecond rounding`() {
        for (bpm in listOf(87f, 123f, 127f, 174f)) {
            val beat = 60000f / bpm
            val requested = (beat * 32).roundToInt()
            val duration = MixTiming.durationMs(requested, null, 231, 231, beat)
            assertEquals(requested, duration)
        }
    }

    @Test fun `playhead offset does not move the incoming phrase endpoint`() {
        assertEquals(3960, MixTiming.durationMs(4000, 5000.0, 177, 137, 500f))
    }

    @Test fun `half and double time use incoming beats after outgoing wall time conversion`() {
        for (speed in listOf(.8, 1.0, 1.2)) {
            val remainingMedia = 7750.0 * speed
            assertEquals(4000, MixTiming.durationMs(8000, remainingMedia / speed, 0, 0, 500f))
        }
    }

    @Test fun `short or unknown grids keep bounded fallback without inventing beats`() {
        assertEquals(750, MixTiming.durationMs(8000, 1000.0, 0, 0, 500f))
        assertEquals(1, MixTiming.durationMs(8000, 100.0, 0, 0, 500f))
        for (beat in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(7250, MixTiming.durationMs(8000, 7500.0, 0, 0, beat))
        }
    }
}
