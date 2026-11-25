/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.rab1d.petal.music.model.Playlist
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key

class QueueManager {
    companion object {
        /** Cost of a pair nobody has measured: between a ridable tempo and a clash. */
        const val NEUTRAL_COST = 1.2
    }

    private val queue = ArrayList<Track>()
    private val originalOrder = ArrayList<Track>()
    private val automix = ArrayList<Track>()
    val onQueueChanged = ArrayList<() -> Unit>()
    var repeatMode: Int = 0 // 0 off, 1 all, 2 one
        set(value) {
            field = value
            onQueueChanged.forEach { it() }
        }
    var shuffle: Boolean = false
        private set
    var currentIndex: Int = -1
        private set

    private fun notifyChanged() {
        onQueueChanged.forEach { it() }
    }

    val current: Track? get() = queue.getOrNull(currentIndex)

    private fun originalIndex(track: Track?): Int {
        if (track == null) return -1
        val same = originalOrder.indexOfFirst { it === track }
        return if (same >= 0) same else originalOrder.indexOfFirst { it.key() == track.key() }
    }

    fun visibleQueue(): List<Track> = queue.toList()

    fun unshuffledQueue(): List<Track> = originalOrder.toList()

    fun upcomingAutomix(): List<Track> = automix.toList()

    fun isAutomixNext(): Boolean =
        currentIndex >= 0 && currentIndex + 1 >= queue.size && automix.isNotEmpty()

    fun set(list: List<Track>, start: Int) {
        originalOrder.clear()
        originalOrder.addAll(list)
        queue.clear()
        val index = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        // tapped song goes first, EVERYTHING else shuffles in behind it. old way only
        // shuffled the stuff after it, so tapping the last song = unshuffled playlist again
        if (shuffle && list.isNotEmpty()) {
            queue.add(list[index])
            queue.addAll(list.filterIndexed { i, _ -> i != index }.shuffled())
        } else {
            queue.addAll(list)
        }
        automix.clear()
        currentIndex = if (queue.isEmpty()) -1 else if (shuffle) 0 else index
        notifyChanged()
    }

    /**
     * Shuffle the whole set immediately and start on the first shuffled song.
     * Used by "shuffle playlist": every press reshuffles (with shuffle mode on)
     * and never toggles the mode off.
     */
    fun playShuffled(list: List<Track>) {
        originalOrder.clear()
        originalOrder.addAll(list)
        queue.clear()
        automix.clear()
        if (list.isEmpty()) {
            currentIndex = -1
            notifyChanged()
            return
        }
        queue.addAll(list.shuffled())
        currentIndex = 0
        notifyChanged()
    }

    fun restore(list: List<Track>, start: Int, original: List<Track>?) {
        val playing = list.getOrNull(start.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
        queue.clear()
        originalOrder.clear()
        originalOrder.addAll(original ?: list)
        queue.addAll(if (shuffle || original == null) list else originalOrder)
        currentIndex = if (queue.isEmpty()) -1 else if (shuffle || original == null) {
            start.coerceIn(0, queue.lastIndex)
        } else {
            queue.indexOfFirst { it.key() == playing?.key() }.coerceAtLeast(0)
        }
        automix.clear()
        notifyChanged()
    }

    fun jumpTo(index: Int) {
        if (index !in queue.indices || index == currentIndex) return
        currentIndex = index
        notifyChanged()
    }

    fun addAfter(list: List<Track>) {
        val at = currentIndex + 1
        queue.addAll(at.coerceAtLeast(0), list)
        val originalAt = originalIndex(current)
        originalOrder.addAll((originalAt + 1).coerceAtLeast(0), list)
        notifyChanged()
    }

    fun append(list: List<Track>) {
        queue.addAll(list)
        originalOrder.addAll(list)
        notifyChanged()
    }

    fun replaceAutomix(list: List<Track>) {
        automix.clear()
        automix.addAll(list)
    }

    /** Swap one entry in place (e.g. a Spotify song replaced by its YT Music match). */
    fun replaceAt(index: Int, track: Track) {
        if (index !in queue.indices) return
        queue[index] = track
        val originalIdx = originalOrder.indexOfFirst { it.key() == queue[index].key() && it.source == track.originSource }
        if (originalIdx >= 0) originalOrder[originalIdx] = track
        notifyChanged()
    }

    /** Pull the next automix suggestion into the real queue (returns null if none). */
    fun promoteAutomix(): Track? {
        val t = automix.removeFirstOrNull() ?: return null
        queue.add(t)
        originalOrder.add(t)
        notifyChanged()
        return t
    }

    fun peekNext(): Track? {
        if (repeatMode == 2) return current
        return queue.getOrNull(currentIndex + 1)
            ?: if (repeatMode == 1 && queue.isNotEmpty()) queue.first() else automix.firstOrNull()
    }

    /**
     * Next real queue entry, repeat respected, no AutoMix fallback. Use this
     * when AutoMix is off so plain playback just walks the list like a normal
     * player instead of sucking in suggestions.
     */
    fun peekQueueNext(): Track? {
        if (repeatMode == 2) return current
        return queue.getOrNull(currentIndex + 1)
            ?: if (repeatMode == 1 && queue.isNotEmpty()) queue.first() else null
    }

    fun advance(): Track? {
        if (repeatMode == 2) return current
        val next = queue.getOrNull(currentIndex + 1)
        if (next != null) {
            currentIndex++
            notifyChanged()
            return next
        }
        if (repeatMode == 1 && queue.isNotEmpty()) {
            currentIndex = 0
            notifyChanged()
            return current
        }
        val promoted = promoteAutomix()
        if (promoted != null) {
            currentIndex = queue.size - 1
            notifyChanged()
            return promoted
        }
        return null
    }

    fun goBack(): Track? {
        if (repeatMode == 2) return current
        if (currentIndex > 0) {
            currentIndex--
            notifyChanged()
            return current
        }
        if (repeatMode == 1 && queue.isNotEmpty()) {
            currentIndex = queue.lastIndex
            notifyChanged()
        }
        return current
    }

    fun moveTo(index: Int) {
        if (index in queue.indices && index != currentIndex) {
            val cur = current
            val moving = queue.removeAt(index)
            queue.add(currentIndex.coerceAtMost(queue.size), moving)
            val old = originalIndex(moving)
            if (old >= 0) originalOrder.removeAt(old)
            val originalAt = originalIndex(cur)
            originalOrder.add((originalAt + 1).coerceAtLeast(0), moving)
            val newIdx = queue.indexOfFirst { it === moving }
            currentIndex = newIdx
            notifyChanged()
        }
    }

    /**
     * Smooth shuffle: reorder up to [window] songs after the next one so each follows the
     * one before at the lowest [cost] (null: unknown, neutral), greedily from the next song.
     * The next song itself stays: it is already resolved and buffered. Shuffle mode only;
     * the unshuffled order is untouched. True when the order changed.
     */
    fun smoothUpcoming(window: Int, cost: (Track, Track) -> Double?): Boolean {
        if (!shuffle) return false
        val from = currentIndex + 2
        val to = minOf(queue.size, from + window)
        if (currentIndex < 0 || to - from < 2) return false
        val pool = queue.subList(from, to).toMutableList()
        val ordered = ArrayList<Track>(pool.size)
        var prev = queue[from - 1]
        while (pool.isNotEmpty()) {
            val pick = pool.indices.minBy { cost(prev, pool[it]) ?: NEUTRAL_COST }
            prev = pool.removeAt(pick)
            ordered += prev
        }
        if ((from until to).all { queue[it] === ordered[it - from] }) return false
        for (i in ordered.indices) queue[from + i] = ordered[i]
        notifyChanged()
        return true
    }

    /** Arbitrary reorder: move the track at [from] to position [to]. */
    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val moving = queue.removeAt(from)
        queue.add(to, moving)

        val old = originalIndex(moving)
        if (old >= 0) {
            originalOrder.removeAt(old)
            val before = queue.getOrNull(to - 1)
            val target = if (before == null) 0 else originalIndex(before).let { if (it >= 0) it + 1 else to }
            originalOrder.add(target.coerceIn(0, originalOrder.size), moving)
        }

        currentIndex = when {
            from == currentIndex -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        notifyChanged()
    }

    fun removeAt(index: Int) {
        if (index in queue.indices) {
            val removed = queue.removeAt(index)
            val old = originalIndex(removed)
            if (old >= 0) originalOrder.removeAt(old)
            if (index < currentIndex) currentIndex--
            if (currentIndex > queue.lastIndex) currentIndex = queue.lastIndex
            notifyChanged()
        }
    }

    /** Remove an upcoming track that failed to resolve (skip unplayable). */
    fun dropUpcoming(key: String) {
        if (queue.getOrNull(currentIndex + 1)?.key() == key) {
            val removed = queue.removeAt(currentIndex + 1)
            val old = originalIndex(removed)
            if (old >= 0) originalOrder.removeAt(old)
            notifyChanged()
            return
        }
        val idx = automix.indexOfFirst { it.key() == key }
        if (idx >= 0) {
            automix.removeAt(idx)
            notifyChanged()
        }
    }

    fun clear() {
        queue.clear()
        originalOrder.clear()
        automix.clear()
        currentIndex = -1
        notifyChanged()
    }

    fun exportPlaylist(name: String): Playlist =
        Playlist(
            id = "pl_${System.currentTimeMillis()}",
            name = name,
            trackKeys = queue.map { it.key() }.toMutableList(),
            tracks = queue.toMutableList(),
        )

    fun setShuffle(on: Boolean) {
        if (shuffle == on) return
        shuffle = on
        if (on && queue.size > 1) {
            val upcoming = queue.drop(currentIndex + 1).shuffled()
            queue.subList(currentIndex + 1, queue.size).clear()
            queue.addAll(upcoming)
        } else if (!on && queue.isNotEmpty()) {
            val playing = current
            queue.clear()
            queue.addAll(originalOrder)
            currentIndex = originalIndex(playing).coerceAtLeast(0)
        }
        notifyChanged()
    }
}
