/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.MixAudioMonitor

class MixAudioMonitorTest {
    @Test fun `bounded history wraps without losing current measurements or reviving old frames`() {
        val m = MixAudioMonitor()
        m.configure(1000, 1)
        feed(m, 20000, .25f, false)
        assertNull(m.at(1000))
        assertEquals(.25f, m.at(19000)!!.rms, .00001f)
        feed(m, 1000, .8f, false)
        assertTrue(m.at(20900)!!.rms > .7f)
        assertNull(m.at(23000))
        m.reset(45000)
        assertNull(m.at(20000))
        feed(m, 1000, .4f, false)
        assertEquals(.4f, m.at(45500)!!.rms, .00001f)
    }
    private fun feed(m: MixAudioMonitor, frames: Int, amplitude: Float, stereo: Boolean = true) {
        repeat(frames) {
            m.sample(amplitude, amplitude * .6f, amplitude * .2f, amplitude * .1f, amplitude * .1f)
            if (stereo) m.sample(-amplitude, -amplitude * .6f, -amplitude * .2f, -amplitude * .1f, -amplitude * .1f)
            m.endFrame()
        }
    }

    @Test fun `opposite phase stereo remains measurable and lookahead follows the playhead`() {
        val m = MixAudioMonitor()
        m.configure(48000, 2)
        feed(m, 48000, .25f)
        feed(m, 48000, .8f)
        assertEquals(.25f, m.at(400)!!.rms, .00001f)
        assertTrue(m.at(1900)!!.rms > .7f)
        assertTrue(m.at(400)!!.positionMs <= 480)
        assertNull(m.at(3000)) // Never reuse stale analysis after an underrun.
    }

    @Test fun `seek reset discards the previous track and anchors sample clock at each rate`() {
        for (rate in listOf(44100, 48000, 96000)) {
            val m = MixAudioMonitor()
            m.configure(rate, 1)
            feed(m, rate / 2, .5f, false)
            m.reset(45000)
            assertNull(m.at(400))
            assertNull(m.at(45000))
            feed(m, rate / 5, .2f, false)
            val frame = m.at(45200)!!
            assertEquals(45200, frame.positionMs)
            assertEquals(.2f, frame.rms, .00001f)
            assertEquals(0f, frame.onset.sub, .00001f)
        }
    }
}
