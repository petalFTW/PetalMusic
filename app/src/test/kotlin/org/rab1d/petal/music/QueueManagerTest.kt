/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.playback.QueueManager

class QueueManagerTest {
    private val tracks = (1..6).map { Track("$it", Source.LOCAL, "Song $it") }

    @Test fun `shuffle state and original order stay together`() {
        val queue = QueueManager()
        queue.setShuffle(true)
        queue.set(tracks, 0)

        assertTrue(queue.shuffle)
        assertEquals(tracks.first(), queue.current)
        assertEquals(tracks.toSet(), queue.visibleQueue().toSet())

        val chosen = queue.visibleQueue()[3]
        queue.jumpTo(3)
        queue.setShuffle(false)

        assertFalse(queue.shuffle)
        assertEquals(tracks, queue.visibleQueue())
        assertEquals(chosen, queue.current)
    }

    @Test fun `tapping the last song with shuffle on still shuffles the rest in after it`() {
        val queue = QueueManager()
        queue.setShuffle(true)
        queue.set(tracks, tracks.lastIndex)

        assertEquals(tracks.last(), queue.current)
        assertEquals(0, queue.currentIndex)
        assertEquals(tracks.size, queue.visibleQueue().size)
        assertEquals(tracks.toSet(), queue.visibleQueue().toSet())
        // the rest gets played next, not dumped back at the front in order
        assertEquals(tracks.dropLast(1).toSet(), queue.visibleQueue().drop(1).toSet())
        val second = queue.visibleQueue()[1]
        assertEquals(second, queue.advance())

        queue.setShuffle(false)
        assertEquals(tracks, queue.visibleQueue())
        assertEquals(second, queue.current)
    }

    @Test fun `playShuffled fills the queue and starts at the top`() {
        val queue = QueueManager()
        queue.setShuffle(true)
        queue.playShuffled(tracks)

        assertTrue(queue.shuffle)
        assertEquals(0, queue.currentIndex)
        assertEquals(queue.visibleQueue().first(), queue.current)
        assertEquals(tracks.toSet(), queue.visibleQueue().toSet())
        assertEquals(tracks, queue.unshuffledQueue())

        // Un-shuffling returns the playlist to its stored order.
        queue.setShuffle(false)
        assertEquals(tracks, queue.visibleQueue())
    }

    @Test fun `previous follows the queue after jumping ahead`() {
        val queue = QueueManager()
        queue.set(tracks, 0)
        queue.jumpTo(4)

        assertEquals(tracks[3], queue.goBack())
        assertEquals(3, queue.currentIndex)
    }

    @Test fun `restored shuffle can return to playlist order`() {
        val queue = QueueManager()
        queue.setShuffle(true)
        queue.set(tracks, 0)
        val savedQueue = queue.visibleQueue().map { it.copy() }
        val original = queue.unshuffledQueue().map { it.copy() }

        val restored = QueueManager()
        restored.setShuffle(true)
        restored.restore(savedQueue, 2, original)
        val playing = restored.current
        restored.setShuffle(false)

        assertEquals(original, restored.visibleQueue())
        assertEquals(playing, restored.current)
    }

    @Test fun `shuffle off restores the playlist after a quick process restart`() {
        val queue = QueueManager()
        queue.setShuffle(true)
        queue.set(tracks, 0)
        val savedQueue = queue.visibleQueue().map { it.copy() }

        val restored = QueueManager()
        restored.restore(savedQueue, 2, tracks.map { it.copy() })

        assertEquals(tracks, restored.visibleQueue())
        assertEquals(savedQueue[2], restored.current)
    }
}
