/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

/** Spend control-loop work near boundaries, without reducing audio processing. */
internal object PlaybackWorkPolicy {
    fun pollMs(playing: Boolean, mixing: Boolean, remainingMs: Long): Long = when {
        mixing -> 250L
        // Paused: nothing is due. Play, pause, seek and track changes wake the engine at once
        // (onIsPlayingChanged → wake), so this only bounds how stale an idle poll can be.
        !playing -> 10_000L
        remainingMs < 0 || remainingMs <= 65_000 -> 250L
        else -> 1000L
    }
}
