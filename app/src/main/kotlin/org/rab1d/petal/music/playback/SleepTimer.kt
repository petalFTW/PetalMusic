/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.rab1d.petal.music.model.key

/**
 * Pauses playback after a set time, or when the current song ends. Lives with the process
 * (not a screen), so it keeps counting with the screen off; it does nothing if the app is
 * killed, which also stops the music.
 */
object SleepTimer {
    sealed interface Mode {
        data object Off : Mode
        /** Pause at [endsAt] (elapsedRealtime ms). */
        data class At(val endsAt: Long, val minutes: Int) : Mode
        /** Pause when the song playing now gives way to another. */
        data object EndOfSong : Mode
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _mode = MutableStateFlow<Mode>(Mode.Off)
    val mode: StateFlow<Mode> = _mode

    fun minutes(minutes: Int) {
        job?.cancel()
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _mode.value = Mode.At(endsAt, minutes)
        job = scope.launch {
            delay(minutes * 60_000L)
            fire()
        }
    }

    fun endOfSong() {
        job?.cancel()
        val playing = PlaybackBus.state.value.current?.key() ?: return
        _mode.value = Mode.EndOfSong
        job = scope.launch {
            // A new current song (a skip, an automix handover or the queue ending) is the end.
            PlaybackBus.state.map { it.current?.key() }.distinctUntilChanged().drop(1)
                .first { it != playing }
            fire()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _mode.value = Mode.Off
    }

    /** Milliseconds left for a timed mode, else -1. */
    fun remainingMs(): Long = (_mode.value as? Mode.At)?.let { (it.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: -1

    private fun fire() {
        PlaybackBus.pauseAction?.invoke()
        job = null
        _mode.value = Mode.Off
    }
}
