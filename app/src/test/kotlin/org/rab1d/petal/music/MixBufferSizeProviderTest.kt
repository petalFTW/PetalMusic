/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.MixBufferSizeProvider

class MixBufferSizeProviderTest {
    @Test fun `PCM buffer preserves platform speed reserve and whole frames`() {
        for (rate in listOf(44100, 48000, 96000)) for (frame in listOf(2, 4, 6, 8)) {
            for (minimum in listOf(513, 3840, 32768)) for (speed in listOf(.5, 1.0, 1.12, 8.0)) {
                val bytes = MixBufferSizeProvider.getBufferSizeInBytes(minimum, C.ENCODING_PCM_16BIT,
                    DefaultAudioSink.OUTPUT_MODE_PCM, frame, rate, -1, speed)
                assertEquals(0, bytes % frame)
                assertTrue(bytes >= minimum)
                assertTrue(bytes / maxOf(1.0, speed) >= minimum * 2)
                assertTrue(bytes >= rate * frame * .180)
            }
        }
    }

    @Test fun `typical stereo device does not get seconds of queued EQ`() {
        val bytes = MixBufferSizeProvider.getBufferSizeInBytes(3840, C.ENCODING_PCM_16BIT,
            DefaultAudioSink.OUTPUT_MODE_PCM, 4, 48000, -1, 8.0)
        assertTrue(bytes / (48000.0 * 4) <= .35)
    }
}
