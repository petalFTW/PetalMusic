/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.data.AutomixStyle
import org.rab1d.petal.music.playback.PlaybackBus

class PlaybackBusTest {
    private fun publish(active: Boolean = false, fade: Int = 32000, enabled: Boolean = true) {
        PlaybackBus.publish(null, true, emptyList(), emptyList(), false, fade, enabled,
            AutomixStyle.CLEAN, 40000, transitionActive = active,
            transitionStartMs = 240000, positionMs = 230000, playbackSpeed = 1.25f)
    }

    @Test fun `countdown follows selected cue and stops during the committed blend`() {
        publish()
        assertEquals(8000L, PlaybackBus.state.value.transitionEtaMs)
        assertEquals(32000, PlaybackBus.state.value.fadeMs)
        publish(active = true)
        assertEquals(-1L, PlaybackBus.state.value.transitionEtaMs)
        publish(fade = 0)
        assertEquals(-1L, PlaybackBus.state.value.transitionEtaMs)
        publish(enabled = false)
        assertEquals(-1L, PlaybackBus.state.value.transitionEtaMs)
    }
}
