/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.playback.PlaybackWorkPolicy

class PlaybackWorkPolicyTest {
    @Test fun `save wakeups while idle and in song body but retain boundary cadence`() {
        assertEquals(10_000L, PlaybackWorkPolicy.pollMs(false, false, 1000))
        assertEquals(1000L, PlaybackWorkPolicy.pollMs(true, false, 180000))
        for (remaining in listOf(-1L, 0L, 1000L, 45000L, 60000L, 65000L))
            assertEquals(250L, PlaybackWorkPolicy.pollMs(true, false, remaining))
        assertEquals(250L, PlaybackWorkPolicy.pollMs(true, true, 180000))
        assertEquals(250L, PlaybackWorkPolicy.pollMs(false, true, 180000))
    }
}
