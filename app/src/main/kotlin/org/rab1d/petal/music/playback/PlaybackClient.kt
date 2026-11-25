/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.sources.spotify.SpotifySessionBridge

// UI buttons live here; the service owns the queue mess.
class PlaybackClient private constructor(
    private val context: Context,
    val controller: MediaController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playJob: Job? = null

    val spotify get() = (context.applicationContext as org.rab1d.petal.music.PetalApp).container.spotify
    private val queue get() = PlaybackBus.queue

    fun playPause() {
        val inProcess = PlaybackBus.playPauseAction
        if (inProcess != null) {
            inProcess()
            return
        }
        if (PlaybackBus.state.value.pumpActive) {
            if (spotify.isPlaying()) spotify.pause() else spotify.resume()
        } else {
            if (controller.isPlaying) controller.pause() else controller.play()
        }
    }

    fun next() {
        val inProcess = PlaybackBus.nextAction
        if (inProcess != null) { inProcess(); return }
        controller.seekToNextMediaItem()
    }

    fun previous() {
        val inProcess = PlaybackBus.previousAction
        if (inProcess != null) { inProcess(); return }
        controller.sendCustomCommand(
            SessionCommand(ACTION_PREVIOUS, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    fun seekTo(ms: Long) {
        val inProcess = PlaybackBus.seekAction
        if (inProcess != null) { inProcess(ms); return }
        val pump = PlaybackBus.state.value.pumpActive
        if (pump) spotify.seek(ms.toInt()) else controller.seekTo(ms)
    }

    fun toggleShuffle() {
        setShuffleEnabled(!queue.shuffle)
    }

    private fun setShuffleEnabled(enabled: Boolean) {
        queue.setShuffle(enabled)
        runCatching { controller.shuffleModeEnabled = queue.shuffle }
        runCatching {
            (context.applicationContext as org.rab1d.petal.music.PetalApp)
                .container.settings.setShuffleEnabled(queue.shuffle)
        }
        PlaybackBus.publishRefresh()
    }

    fun cycleRepeat() = setRepeat((queue.repeatMode + 1) % 3)

    /** 0 = off, 1 = loop list, 2 = loop one. */
    fun setRepeat(mode: Int) {
        queue.repeatMode = mode.coerceIn(0, 2)
        controller.repeatMode = when (queue.repeatMode) {
            1 -> androidx.media3.common.Player.REPEAT_MODE_ALL
            2 -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        runCatching {
            (context.applicationContext as org.rab1d.petal.music.PetalApp)
                .container.settings.setRepeatMode(queue.repeatMode)
        }
        PlaybackBus.publishRefresh()
    }

    // Spotify gets the pump; Free accounts take the YT detour.
    fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        if (shuffle != null) setShuffleEnabled(shuffle)
        playJob?.cancel()
        if (shuffle == true) {
            // Shuffle once, mate, don't flip it back off on another tap.
            queue.playShuffled(tracks)
        } else {
            queue.set(tracks, start)
        }
        val first = queue.current ?: return
        if (container.usesSpotifyPump(first)) {
            if (!spotify.loggedIn) return
            playJob = scope.launch(Dispatchers.IO) {
                spotify.play(first, true)
                PlaybackBus.publishRefresh()
            }
        } else {
            playJob = scope.launch {
                val item = resolveUi(first) ?: run {
                    android.util.Log.w("PetalMusic/Client", "tap dead: no item for ${first.key()}")
                    return@launch
                }
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
        }
        PlaybackBus.publishRefresh()
    }

    private val container get() = (context.applicationContext as org.rab1d.petal.music.PetalApp).container

    fun playFromQueue(index: Int) {
        val track = queue.visibleQueue().getOrNull(index) ?: return
        val currentKey = PlaybackBus.state.value.current?.key()
        if (track.key() == currentKey) {
            playPause()
            return
        }
        playJob?.cancel()
        if (container.usesSpotifyPump(track)) {
            playJob = scope.launch(Dispatchers.IO) {
                spotify.play(track, true)
                queue.jumpTo(index)
                PlaybackBus.publishRefresh()
            }
        } else {
            playJob = scope.launch {
                val item = resolveUi(track) ?: return@launch
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
                queue.jumpTo(index)
                PlaybackBus.publishRefresh()
            }
        }
    }

    private suspend fun resolveUi(track: Track): MediaItem? =
        (context.applicationContext as org.rab1d.petal.music.PetalApp).container.resolveTrack(track)

    fun removeQueueItem(index: Int) {
        queue.removeAt(index)
        PlaybackBus.publishRefresh()
    }

    fun moveQueueItem(from: Int, to: Int) {
        queue.move(from, to)
        PlaybackBus.publishRefresh()
    }

    /** Right after the current song. With nothing playing, it simply plays. */
    fun playNext(tracks: List<Track>) {
        if (queue.current == null) return playTracks(tracks, 0)
        queue.addAfter(tracks)
        PlaybackBus.publishRefresh()
    }

    /** At the end of the queue. With nothing playing, it simply plays. */
    fun addToQueue(tracks: List<Track>) {
        if (queue.current == null) return playTracks(tracks, 0)
        queue.append(tracks)
        PlaybackBus.publishRefresh()
    }

    companion object {
        const val ACTION_PREVIOUS = "org.rab1d.petal.music.PREVIOUS"

        @Volatile private var instance: PlaybackClient? = null
        private var connecting = false
        private val waiting = mutableListOf<(PlaybackClient) -> Unit>()

        fun connect(context: Context, onReady: (PlaybackClient) -> Unit) {
            instance?.let {
                if (it.controller.isConnected) { onReady(it); return }
                it.scope.cancel()
                it.controller.release()
                instance = null
            }
            waiting.add(onReady)
            if (connecting) return
            connecting = true
            val ctx = context.applicationContext
            val token = SessionToken(ctx, ComponentName(ctx, PetalPlaybackService::class.java))
            val future = MediaController.Builder(ctx, token)
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        instance?.takeIf { it.controller === controller }?.let {
                            it.scope.cancel()
                            instance = null
                        }
                        controller.release()
                    }
                }).buildAsync()
            future.addListener({
                connecting = false
                val callbacks = waiting.toList()
                waiting.clear()
                try {
                    val controller = future.get()
                    instance = PlaybackClient(ctx, controller)
                    callbacks.forEach { it(instance!!) }
                } catch (e: Exception) {
                    android.util.Log.w("PetalMusic/Client", "controller connect failed", e)
                    PlaybackBus.publishError("Couldn't connect to playback — try again")
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(ctx))

        }
    }
}
