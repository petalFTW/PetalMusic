/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import org.rab1d.petal.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rab1d.petal.music.PetalApp
import org.rab1d.petal.music.data.PlaybackSession
import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.sources.PetalDataSource
import org.rab1d.petal.music.sources.spotify.SpotifySessionBridge

// Playback lives here; the mixer does the fancy shit.
class PetalPlaybackService : MediaSessionService() {

    companion object {
        const val TAG = "PetalMusic/Playback"

        const val ACTION_SHUFFLE = "org.rab1d.petal.music.TOGGLE_SHUFFLE"
        const val ACTION_FAVORITE = "org.rab1d.petal.music.TOGGLE_FAVORITE"
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var bridge: BridgeFXLink
    private lateinit var player: CrossfadePlayer
    private lateinit var session: MediaSession
    private var sessionReleased = false
    private var favorites = emptySet<String>()
    private var sessionButtonsState: Triple<Boolean, Boolean, Boolean>? = null
    private lateinit var engine: AutomixEngine
    private lateinit var eqMixer: EqMixer
    private var settings: PlaybackSettings = PlaybackSettings()
    private var previousRestartKey: String? = null
    private var previousRestartAt = 0L

    /** Playhead to resume from after a restore, until the first play. */
    @Volatile private var resumePositionMs: Long = 0L

    private val container get() = (application as PetalApp).container
    private val queue: QueueManager get() = PlaybackBus.queue
    // A queue edit is saved straight away (it's only re-encoded when it really changed).
    private val queueChanged: () -> Unit = { publishState(); saveSession() }
    private val spotify get() = container.spotify

    override fun onCreate() {
        super.onCreate()
        PlaybackBus.pumpPositionProvider = { spotify.position() }
        bridge = BridgeFXLink()

        // Restore shuffle / repeat from the last session.
        runCatching {
            queue.setShuffle(container.settings.shuffleEnabled())
            queue.repeatMode = container.settings.repeatMode()
        }

        // Two decks under one player, so the UI doesn't lose its shit on swaps.
        eqMixer = EqMixer()
        player = CrossfadePlayer(
            this,
            onSwapped = { publishState() },
            onOverlapStart = { cover, ms -> announceArtFade(cover, ms) },
            onOverlapEnd = { announceArtFadeEnd() },
        )

        engine = AutomixEngine(
            context = this,
            player = player,
            queue = queue,
            bridge = bridge,
            appMixer = eqMixer,
            providers = container.providers,
            pump = spotify,
            resolveTrack = { container.resolveTrack(it) },
            switcher = switcher,
            onEvent = { publishState() },
        )
        engine.start()

        // Direct UI controls keep the controller from drifting off.
        PlaybackBus.positionProvider = {
            if (container.usesSpotifyPump(queue.current) && spotify.loggedIn) {
                spotify.position()
            } else {
                player.currentPosition
            }
        }
        PlaybackBus.durationProvider = {
            if (container.usesSpotifyPump(queue.current) && spotify.loggedIn) {
                spotify.duration().toLong()
            } else {
                player.duration.takeIf { it > 0 } ?: 0L
            }
        }
        PlaybackBus.levelProvider = {
            if (container.usesSpotifyPump(queue.current) && spotify.loggedIn) null
            else player.liveLevel()?.let { f ->
                PlaybackBus.Level(
                    f.rms, f.bands.sub, f.bands.bass, f.bands.mid, f.bands.high,
                    (f.onset.sub + f.onset.bass + f.onset.mid + f.onset.high) / 4f,
                )
            }
        }
        PlaybackBus.nextAction = { scope.launch { skipToNext() } }
        PlaybackBus.previousAction = { scope.launch { previousTrack() } }
        PlaybackBus.playPauseAction = {
            val pump = container.usesSpotifyPump(queue.current) && spotify.loggedIn
            if (pump) {
                if (spotify.isPlaying()) spotify.pause() else spotify.resume()
            } else when {
                // Empty deck after restore? Load the saved tune first.
                player.mediaItemCount == 0 -> {
                    val from = resumePositionMs
                    resumePositionMs = 0L
                    scope.launch {
                        val item = queue.current?.let {
                            runCatching { container.resolveTrack(it) }.getOrNull()
                        }
                        if (item != null) {
                            withContext(Dispatchers.Main) {
                                player.setMediaItems(listOf(item), 0, from)
                                player.prepare()
                                player.play()
                            }
                        }
                        publishState()
                    }
                }
                player.playbackState == Player.STATE_IDLE -> {
                    val from = resumePositionMs
                    resumePositionMs = 0L
                    player.prepare()
                    if (from > 0) player.seekTo(from)
                    player.play()
                }
                player.isPlaying -> player.pause()
                else -> player.play()
            }
            engine.wake()
            publishState()
        }
        PlaybackBus.pauseAction = {
            if (container.usesSpotifyPump(queue.current) && spotify.loggedIn) {
                if (spotify.isPlaying()) spotify.pause()
            } else if (player.isPlaying) player.pause()
            publishState()
        }
        PlaybackBus.seekAction = { ms ->
            previousRestartKey = null
            val pump = container.usesSpotifyPump(queue.current) && spotify.loggedIn
            if (pump) spotify.seek(ms.toInt()) else player.seekTo(ms)
        }

        player.addListener(listener)

        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.i("PetalRend", "decoder=$decoderName")
            }

            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                Log.i(
                    "PetalRend",
                    "format mime=${format.sampleMimeType} rate=${format.sampleRate} ch=${format.channelCount} pcm=${format.pcmEncoding}",
                )
                audioFormatLabel = buildAudioLabel(format)
                publishState()
            }

            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                Log.w("PetalRend", "underrun buf=$bufferSize ms=$bufferSizeMs since=$elapsedSinceLastFeedMs")
            }

            override fun onAudioSinkError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioSinkError: Exception,
            ) {
                Log.e("PetalRend", "sink error", audioSinkError)
            }

            override fun onAudioDisabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderCounters: androidx.media3.exoplayer.DecoderCounters,
            ) {
                Log.i("PetalRend", "audio disabled")
            }

            override fun onAudioEnabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderCounters: androidx.media3.exoplayer.DecoderCounters,
            ) {
                Log.i("PetalRend", "audio enabled")
            }


        })

        session = MediaSession.Builder(this, player).setCallback(sessionCallback).build()
        updateSessionButtons()
        scope.launch {
            container.settings.favorites.collect {
                favorites = it
                updateSessionButtons()
            }
        }

        scope.launch {
            container.settings.discordToken.collect { discordToken = it }
        }
        scope.launch {
            container.settings.discordEnabled.collect {
                discordRpcOn = it
                if (!it) discord?.setActivity(null)
            }
        }
        scope.launch {
            container.settings.settings.collectLatest {
                val wasAutomix = settings.automixEnabled
                settings = it
                engine.settings = it
                eqMixer.fadeMs = it.fadeMs
                eqMixer.style = engine.halStyle()
                if (bridge.attached) {
                    bridge.setFade(it.fadeMs)
                    bridge.setStyle(engine.halStyle())
                }
                engine.onSettingsChanged()
                // Flicked off mid-blend: stop mixing right now.
                if (wasAutomix && !it.automixEnabled) engine.abortTransition()
                publishState()
            }
        }
        queue.onQueueChanged.add(queueChanged)
        restoreSession()
        // Get shuffle and loop back in sync before anyone hits play.
        applyQueueModes()
        // Save as we go; Android can kill this thing whenever it likes. Pauses and queue
        // changes save at once, so this only bounds what an unexpected kill can lose.
        scope.launch {
            while (true) {
                delay(15_000)
                if (player.isPlaying || spotify.isPlaying()) saveSession()
            }
        }
        publishState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveSession()
        // Swiped away means closed, ditch the island card too.
        engine.abortTransition()
        player.pause()
        spotify.pause()
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun releaseSession() {
        if (!sessionReleased) {
            sessionReleased = true
            session.release()
        }
    }

    /** Persist the queue and playhead so a relaunch resumes the last song. */
    // What was last written, so an unchanged queue is never re-encoded.
    private var savedQueueSignature: Int? = null

    private fun saveSession() {
        runCatching {
            val list = queue.visibleQueue()
            if (list.isEmpty() || queue.currentIndex !in list.indices) {
                container.settings.setPlaybackSession(null)
                savedQueueSignature = null
                return
            }
            val pump = container.usesSpotifyPump(queue.current) && spotify.loggedIn
            // An empty deck (mid-restore, or parked waiting for play) has no real position:
            // saving now would overwrite the resume point with 0.
            if (!pump && player.mediaItemCount == 0) return
            val pos = if (pump) spotify.position() else player.currentPosition.coerceAtLeast(0)
            val unshuffled = queue.unshuffledQueue()
            val signature = arrayOf(list.map { it.key() }, queue.currentIndex, unshuffled.map { it.key() }).contentDeepHashCode()
            if (signature == savedQueueSignature) {
                // Only the playhead moved: write the few bytes, not the whole queue.
                container.settings.setPlaybackPosition(queue.currentIndex, pos)
            } else {
                container.settings.setPlaybackSession(PlaybackSession(list, queue.currentIndex, pos, unshuffled))
                savedQueueSignature = signature
            }
        }
    }

    // Restore the queue and park its current tune on the deck, paused.
    private fun restoreSession() {
        if (player.mediaItemCount > 0) return
        val saved = container.settings.playbackSession()
        if (queue.visibleQueue().isEmpty()) {
            if (saved == null || saved.queue.isEmpty()) return
            queue.restore(saved.queue, saved.index, saved.unshuffledQueue)
        }
        val current = queue.current ?: return
        val savedForCurrent = saved?.takeIf {
            it.queue.getOrNull(it.index)?.key() == current.key()
        }
        resumePositionMs = savedForCurrent?.positionMs ?: 0L
        Log.i(
            TAG,
            "restore: ${queue.visibleQueue().size} tracks, current=${current.title}, at ${resumePositionMs}ms",
        )
        publishState()
        val from = resumePositionMs
        scope.launch {
            val item = runCatching { container.resolveTrack(current) }.getOrNull()
            if (item == null) {
                // Leave the queue visible; the first Play will resolve it again.
                Log.w(TAG, "restore: could not preload ${current.title}")
                return@launch
            }
            withContext(Dispatchers.Main) {
                // A newer tap wins; don't stomp it with this slow-ass restore.
                if (player.mediaItemCount > 0 || queue.current?.key() != current.key()) return@withContext
                player.setMediaItems(listOf(item), 0, from)
                player.pause()
                resumePositionMs = 0L
                publishState()
            }
        }
    }

    /** Mirror the queue's shuffle/repeat onto the player after a restore. */
    private fun applyQueueModes() {
        player.repeatMode = when (queue.repeatMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.shuffleModeEnabled = queue.shuffle
        publishState()
    }

    override fun onDestroy() {
        saveSession()
        queue.onQueueChanged.remove(queueChanged)
        discord?.close()
        discord = null
        clearAutomixOwnership()
        PlaybackBus.positionProvider = null
        PlaybackBus.durationProvider = null
        PlaybackBus.levelProvider = null
        PlaybackBus.nextAction = null
        PlaybackBus.previousAction = null
        PlaybackBus.playPauseAction = null
        PlaybackBus.pauseAction = null
        PlaybackBus.seekAction = null
        releaseSession()
        player.release()
        bridge.detach()
        engine.stop()
        scope.cancel()
        super.onDestroy()
    }

    // Deck and Spotify handoffs.

    private val switcher = object : AutomixEngine.Switcher {
        override suspend fun pumpToExo(track: Track, useHal: Boolean) {
            spotify.pause()
            val item = container.resolveTrack(track) ?: run {
                publishState()
                return
            }
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(item))
                player.prepare()
                player.volume = if (useHal) 1f else 0f
                player.play()
            }
            if (!useHal) rampPlayerVolume(settings.fadeMs)
            queue.advanceIfMatches(track)
        }

        override suspend fun exoToPump(track: Track, useHal: Boolean) {
            if (useHal) {
                player.stop()
                withContext(Dispatchers.IO) { spotify.play(track, true) }
            } else {
                val fadeMs = settings.fadeMs.coerceAtLeast(250)
                rampPlayerVolumeDown(fadeMs)
                withContext(Dispatchers.Main) { player.stop() }
                withContext(Dispatchers.IO) { spotify.play(track, true) }
                SpotifySessionBridge.activeSink?.setVolume(0f)
                rampPumpVolumeUp(fadeMs)
            }
            queue.advanceIfMatches(track)
        }

        override fun pumpToPump(track: Track): Boolean {
            val ok = spotify.crossfadeTo(track, settings.fadeMs)
            if (ok) queue.advanceIfMatches(track)
            return ok
        }

        override fun crossfade(track: Track, fadeMs: Int, speed: Float, startDelayMs: Long, seam: Boolean, eq: EqTransition.Context) =
            startCrossfade(track, fadeMs, speed, startDelayMs, seam, eq)
    }

    private fun announceInAppTransition(durationMs: Int) = RomHooks.announceInAppTransition(this, durationMs)

    private fun announceArtFade(cover: String?, durationMs: Int) = RomHooks.announceArtFade(this, cover, durationMs)

    private fun announceArtFadeEnd() = RomHooks.announceArtFadeEnd(this)

    private fun startCrossfade(next: Track, fadeMs: Int, speed: Float, startDelayMs: Long, seam: Boolean, eq: EqTransition.Context) {
        announceInAppTransition(fadeMs)
        scope.launch {
            val item = withContext(Dispatchers.IO) { container.resolveTrack(next) }
            if (item == null) return@launch
            withContext(Dispatchers.Main) {
                player.crossfadeTo(item, fadeMs, speed, startDelayMs, seam, eq) {
                    queue.advanceIfMatches(next)
                    lastSessionTried = -1
                    publishState()
                }
            }
        }
    }

    private suspend fun rampPlayerVolume(fadeMs: Int) {
        val steps = 24
        repeat(steps) { i ->
            delay(fadeMs.toLong() / steps)
            player.volume = (i + 1).toFloat() / steps
        }
    }

    private suspend fun rampPlayerVolumeDown(fadeMs: Int) {
        val steps = 24
        repeat(steps) { i ->
            delay(fadeMs.toLong() / steps)
            player.volume = 1f - (i + 1).toFloat() / steps
        }
        player.volume = 1f
    }

    private suspend fun rampPumpVolumeUp(fadeMs: Int) {
        val steps = 24
        repeat(steps) { i ->
            delay(fadeMs.toLong() / steps)
            SpotifySessionBridge.activeSink?.setVolume((i + 1).toFloat() / steps)
        }
    }

    // Player callbacks, here we go.

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val tagged = mediaItem?.localConfiguration?.tag as? Track
            val mediaKey = mediaItem?.mediaId
            // Binder ate the tag? Fish the track out by media id.
            val incoming = tagged ?: mediaKey?.let { key ->
                queue.visibleQueue().firstOrNull { it.key() == key }
                    ?: queue.upcomingAutomix().firstOrNull { it.key() == key }
                    ?: queue.current?.takeIf { it.key() == key }
            }
            Log.i(
                "PetalMusic/Playback",
                "transition reason=$reason incoming=${incoming?.key()} tagged=${tagged != null} " +
                    "qCurrent=${queue.current?.key()} qNext=${queue.peekNext()?.key()}",
            )
            if (incoming != null) {
                engine.onNowAnalyzing(incoming.key())
                // The mixer moves its own queue; don't advance the damn thing twice.
                if (!settings.inAppMixer) {
                    when {
                        queue.current == null -> queue.set(listOf(incoming), 0)
                        queue.peekNext()?.key() == incoming.key() -> queue.advance()
                    }
                }
                scope.launch { container.settings.addRecent(incoming.key()) }
            }
            engine.onTransition(reason, incoming)
            engine.recordProfile()
            // Keep a tight window: everything before the current item goes.
            while (player.currentMediaItemIndex > 0) player.removeMediaItem(0)
            engine.resolveNextAhead()
            publishState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Ended without a mix? Just get the next tune going.
                scope.launch { continueAfterEnd() }
            }
            publishState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            engine.wake()
            if (!isPlaying) saveSession()
            publishState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error: ${error.errorCodeName} track=${queue.current?.key()} pos=${player.currentPosition}", error)
            // Silence looks like a dead tap, so at least show what broke.
            PlaybackBus.publishError("Playback failed: ${error.errorCodeName}")
            // Use our queue, not the mixer's tiny playlist.
            val next = if (settings.automixEnabled) queue.peekNext() else queue.peekQueueNext()
            if (next != null && next.key() != queue.current?.key()) {
                scope.launch { skipToNext() }
            } else player.pause()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) {
                if (bridge.attached) bridge.abort()
                engine.onUserSeek()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            Log.i("PetalMusic/BridgeFX", "session id changed -> $audioSessionId, attaching")
            SpotifySessionBridge.audioSessionId = audioSessionId
            engine.bridgeAvailable = bridge.attach(audioSessionId)
            if (bridge.attached) {
                bridge.setFade(settings.fadeMs.coerceAtLeast(250))
                bridge.setStyle(engine.halStyle())
                declareAutomixOwnership()
            }
            publishState()
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(result.availableSessionCommands.buildUpon()
                    .add(SessionCommand(ACTION_SHUFFLE, android.os.Bundle.EMPTY))
                    .add(SessionCommand(ACTION_FAVORITE, android.os.Bundle.EMPTY))
                    .add(SessionCommand(PlaybackClient.ACTION_PREVIOUS, android.os.Bundle.EMPTY))
                    .build())
                .setAvailablePlayerCommands(result.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            if (customCommand.customAction == ACTION_SHUFFLE) {
                queue.setShuffle(!queue.shuffle)
                player.shuffleModeEnabled = queue.shuffle
                container.settings.setShuffleEnabled(queue.shuffle)
                publishState()
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_FAVORITE) {
                val key = queue.current?.key()
                    ?: return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE))
                scope.launch { container.settings.toggleFavorite(key) }
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == PlaybackClient.ACTION_PREVIOUS) {
                scope.launch { previousTrack() }
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(
                        androidx.media3.session.SessionResult.RESULT_SUCCESS,
                    ),
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            command: Int,
        ): Int {
            // The mixer only holds one song; its indexes aren't our queue.
            if (command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            ) {
                scope.launch { skipToNext() }
                return androidx.media3.session.SessionResult.RESULT_SUCCESS
            }
            if (command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ) {
                // Grab this before Media3 runs its own bloody seek.
                val positionMs = PlaybackBus.positionProvider?.invoke() ?: player.currentPosition
                scope.launch { previousTrack(positionMs) }
                return androidx.media3.session.SessionResult.RESULT_SUCCESS
            }
            if (command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_DEFAULT_POSITION ||
                command == Player.COMMAND_SEEK_BACK || command == Player.COMMAND_SEEK_FORWARD
            ) {
                previousRestartKey = null
            }
            return super.onPlayerCommandRequest(session, controller, command)
        }
    }

    /** Track ran to the end: queue → automix suggestions → start the next one. */
    private suspend fun continueAfterEnd() {
        val current = queue.current ?: return
        if (player.playbackState != Player.STATE_ENDED) return
        // Repeat-one: nothing to mix into, just run it back.
        if (queue.repeatMode == 2) {
            restartCurrent(current)
            return
        }
        // Standby already buffered it, skip the awkward silent gap.
        if (settings.inAppMixer) {
            val cf = player
            val pending = if (settings.automixEnabled) queue.peekNext() else queue.peekQueueNext()
            if (pending != null && !container.usesSpotifyPump(pending) &&
                cf.hasPreloaded(pending.key()) && cf.promotePreloaded()
            ) {
                queue.advanceIfMatches(pending)
                // Clear the old latches so the next preload isn't stuck.
                engine.onTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, pending)
                engine.onNowAnalyzing(pending.key())
                publishState()
                return
            }
        }
        // AutoMix is off, keep suggestions out of this queue.
        var next = if (settings.automixEnabled) queue.peekNext() else queue.peekQueueNext()
        if (next != null && next.key() == current.key()) next = null
        if (next == null && settings.automixEnabled) {
            next = withContext(Dispatchers.IO) {
                engine.suggestionsBlocking(current).firstOrNull()
            }
            next?.let { queue.replaceAutomix(listOf(it)) }
        }
        if (next == null) {
            player.pause()
            return
        }
        if (container.usesSpotifyPump(next)) {
            engine.onManualExoToPump(next)
            withContext(Dispatchers.IO) { spotify.play(next, true) }
            queue.advanceIfMatches(next)
            publishState()
            return
        }
        val item = container.resolveTrack(next) ?: run {
            queue.dropUpcoming(next.key())
            continueAfterEnd()
            return
        }
        player.addMediaItem(item)
        player.seekTo(player.mediaItemCount - 1, 0)
        player.prepare()
        player.play()
        queue.advanceIfMatches(next)
        publishState()
    }

    /** Play [current] again from the top; used by repeat-one. */
    private suspend fun restartCurrent(current: Track) {
        if (container.usesSpotifyPump(current)) {
            if (!spotify.loggedIn) return
            engine.onManualExoToPump(current)
            withContext(Dispatchers.IO) { spotify.play(current, true) }
            publishState()
            return
        }
        val item = container.resolveTrack(current)
        if (item == null) {
            publishState()
            return
        }
        withContext(Dispatchers.Main) {
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
        publishState()
    }

    /** Skip forward: advance the queue and hard-cut to the next track. */
    private suspend fun skipToNext() {
        val current = queue.current ?: return
        var next = if (settings.automixEnabled) queue.peekNext() else queue.peekQueueNext()
        if (next == null && settings.automixEnabled) {
            next = withContext(Dispatchers.IO) {
                engine.suggestionsBlocking(current).firstOrNull()
            }
            next?.let { queue.replaceAutomix(listOf(it)) }
        }
        val target = next ?: return
        queue.advanceIfMatches(target)
        loadTrack(target)
    }

    // Back once rewinds; back near the start grabs the last tune.
    private suspend fun previousTrack(
        positionMs: Long = PlaybackBus.positionProvider?.invoke() ?: player.currentPosition,
    ) {
        val current = queue.current ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        // Spotify can report the old playhead while its seek catches up.
        val justRestarted = container.usesSpotifyPump(current) && spotify.loggedIn &&
            previousRestartKey == current.key() &&
            now - previousRestartAt < PREVIOUS_RESTART_THRESHOLD_MS
        if (positionMs >= PREVIOUS_RESTART_THRESHOLD_MS && !justRestarted) {
            val seek = PlaybackBus.seekAction
            if (seek != null) seek(0) else withContext(Dispatchers.Main) { player.seekTo(0) }
            previousRestartKey = current.key()
            previousRestartAt = now
            return
        }
        previousRestartKey = null
        val target = queue.goBack() ?: return
        loadTrack(target)
    }

    /** Hard-cut the player onto [target] and keep the queue aligned. */
    private suspend fun loadTrack(target: Track) {
        if (container.usesSpotifyPump(target)) {
            engine.onManualExoToPump(target)
            withContext(Dispatchers.IO) { spotify.play(target, true) }
            publishState()
            return
        }
        val item = container.resolveTrack(target)
        if (item == null) {
            publishState()
            return
        }
        withContext(Dispatchers.Main) {
            player.setMediaItem(item)
            player.prepare()
            player.play()
        }
        publishState()
    }

    /** Resolve whatever is next (queue, automix, or pump switch) and perform the skip. */
    private suspend fun ensureNextThen(then: suspend () -> Unit) {
        val current = queue.current ?: return
        var next = queue.peekNext()
        if (next == null && settings.automixEnabled) {
            next = withContext(Dispatchers.IO) {
                engine.suggestionsBlocking(current).firstOrNull()
            }
            next?.let { queue.replaceAutomix(listOf(it)) }
        }
        var attempts = 0
        while (next != null && attempts < 3) {
            if (container.usesSpotifyPump(next)) {
                engine.onManualExoToPump(next)
                withContext(Dispatchers.IO) { spotify.play(next, true) }
                queue.advanceIfMatches(next)
                publishState()
                return
            }
            val item = container.resolveTrack(next)
            if (item != null) {
                player.addMediaItem(item)
                player.seekToNextMediaItem()
                then()
                return
            }
            // Unresolvable: drop it and try the next candidate.
            queue.dropUpcoming(next.key())
            next = queue.peekNext()
            attempts++
        }
    }

    private var lastVolLog = 0L
    private var lastSessionTried = -1
    private var lastAttachTryAt = 0L

    @Volatile private var audioFormatLabel: String = ""
    @Volatile private var discordToken: String? = null
    @Volatile private var discordRpcOn = false
    private var discord: org.rab1d.petal.music.data.discord.DiscordRpc? = null
    private var discordKey: String? = null
    private var discordPlaying: Boolean? = null
    private var lastDiscordPush = 0L

    /** BitChord-style stats line: codec · sample rate · channels · bitrate. */
    private fun buildAudioLabel(format: androidx.media3.common.Format): String {
        val parts = mutableListOf<String>()
        val codec = when (format.sampleMimeType) {
            androidx.media3.common.MimeTypes.AUDIO_FLAC -> "FLAC"
            androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
            androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
            androidx.media3.common.MimeTypes.AUDIO_VORBIS -> "Vorbis"
            androidx.media3.common.MimeTypes.AUDIO_MPEG -> "MP3"
            else -> format.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: "PCM"
        }
        parts += codec
        if (format.bitrate > 0) parts += "${format.bitrate / 1000} kbps"
        if (format.sampleRate > 0) parts += "%.1f kHz".format(format.sampleRate / 1000f)
        if (format.channelCount > 0) {
            parts += when (format.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                else -> "${format.channelCount} ch"
            }
        }
        return parts.joinToString(" · ")
    }
    private var bridgeAvailableLogged = false
    private var bridgeRetryMs = 2000L

    // Poll for BridgeFX; the session-id callback flakes out sometimes.
    private fun maybeAttachBridge() {
        val sid = player.audioSessionId
        if (sid <= 0) return
        if (!bridge.attached && !player.isPlaying && !spotify.isPlaying()) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Retry after boot, but don't churn AudioTracks every bloody poll.
        if (sid == lastSessionTried && (bridge.attached || now - lastAttachTryAt < bridgeRetryMs)) return
        if (sid != lastSessionTried) bridgeRetryMs = 2000L
        lastSessionTried = sid
        lastAttachTryAt = now
        SpotifySessionBridge.audioSessionId = sid
        engine.bridgeAvailable = bridge.attach(sid)
        bridgeRetryMs = if (bridge.attached) 2000L else (bridgeRetryMs * 2).coerceAtMost(30_000L)
        if (bridge.attached) {
            bridge.setFade(settings.fadeMs.coerceAtLeast(250))
            bridge.setStyle(engine.halStyle())
            declareAutomixOwnership()
        }
    }

    private fun declareAutomixOwnership() = RomHooks.declareAutomixOwnership(this)

    private fun clearAutomixOwnership() = RomHooks.clearAutomixOwnership(this)

    /** Discord Rich Presence — push on change, refresh timestamps each minute. */
    private fun updateDiscordPresence() {
        val token = discordToken ?: return
        if (!discordRpcOn) {
            discord?.setActivity(null)
            return
        }
        val cur = queue.current ?: run {
            discord?.setActivity(null)
            discordKey = null
            return
        }
        val pumpActive = container.usesSpotifyPump(cur) && spotify.loggedIn
        val pos = if (pumpActive) spotify.position() else player.currentPosition
        val playing = if (pumpActive) spotify.isPlaying() else player.isPlaying
        val now = android.os.SystemClock.elapsedRealtime()
        val changed = cur.key() != discordKey || playing != discordPlaying
        if (!changed && now - lastDiscordPush < 60_000) return
        discordKey = cur.key()
        discordPlaying = playing
        lastDiscordPush = now
        val rpc = discord ?: org.rab1d.petal.music.data.discord.DiscordRpc(token)
            .also { d -> discord = d; d.connect() }
        rpc.updateSong(cur, pos, cur.durationMs, playing)
    }

    private fun publishState() {
        updateSessionButtons()
        maybeAttachBridge()
        bridge.setPlaybackActive(player.isPlaying || spotify.isPlaying())
        runCatching { updateDiscordPresence() }
        if (bridge.attached && !bridgeAvailableLogged) {
            bridgeAvailableLogged = true
            Log.i("PetalMusic/BridgeFX", "BridgeFX owned by PetalMusic for DJ automix")
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (Log.isLoggable("PetalMix", Log.DEBUG) && now - lastVolLog > 2000) {
            lastVolLog = now
            android.util.Log.i(
                "PetalMix",
                "player.volume=${player.volume} speed=${player.playbackParameters.speed} " +
                    "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                    "pos=${player.currentPosition} real=${player.isPlaying} " +
                    "sessionId=${player.audioSessionId} bridge=${bridge.attached}",
            )
        }
        val pumpActive = container.usesSpotifyPump(queue.current) && spotify.loggedIn
        val plan = DJPlanner.renderedPlan(engine.activePlan, player.transitionActive, player.transitionDurationMs)
        val analysis = queue.current?.let { AnalysisCache.get(it.key()) }
        val dj = plan?.let {
            PlaybackBus.DjInfo(
                kind = DJPlanner.describe(it),
                bpmOut = it.bpmOut ?: analysis?.bpm,
                bpmIn = it.bpmIn,
                fadeMs = it.fadeMs,
                engineName = if (bridge.attached && settings.preferSystemBridge) "BridgeFX HAL" else "Internal DSP",
                alignMs = it.alignMs,
                speed = it.outgoingSpeed,
                camelotOut = analysis?.camelot,
            )
        }
        PlaybackBus.publish(
            current = queue.current,
            playing = if (pumpActive) spotify.isPlaying() else player.isPlaying,
            queueSnapshot = queue.visibleQueue(),
            automixSnapshot = queue.upcomingAutomix(),
            bridgeAttached = bridge.attached,
            fadeMs = plan?.fadeMs ?: if (settings.autoFade) 0 else settings.fadeMs,
            automixEnabled = settings.automixEnabled,
            style = settings.style,
            remainingMs = if (pumpActive) {
                spotify.duration() - spotify.position()
            } else if (player.duration > 0) {
                player.duration - player.currentPosition
            } else -1,
            pumpActive = pumpActive,
            dj = dj,
            audio = if (audioFormatLabel.isNotEmpty()) {
                PlaybackBus.AudioInfo(audioFormatLabel)
            } else null,
            next = queue.peekNext(),
            transitionProgress = player.crossfadeProgress,
            transitionActive = player.transitionActive,
            transitionSeam = player.transitionSeam,
            transitionStartMs = engine.selectedTransitionMs,
            eqSummary = if (player.transitionActive) player.transitionEq.label else engine.selectedEqSummary,
            transitionEq = player.transitionEq,
            transitionDurationMs = if (player.transitionActive) player.transitionDurationMs else 0,
            positionMs = if (pumpActive) spotify.position() else player.currentPosition,
            playbackSpeed = if (pumpActive) 1f else player.playbackParameters.speed,
        )
    }

    private fun updateSessionButtons() {
        if (!::session.isInitialized || sessionReleased) return
        val key = queue.current?.key()
        val state = Triple(queue.shuffle, key != null && key in favorites, key != null)
        if (state == sessionButtonsState) return
        sessionButtonsState = state
        // These go through MediaSession, no private SystemUI handshake crap.
        session.setCustomLayout(listOf(
            CommandButton.Builder()
                .setDisplayName(getString(if (state.first) R.string.media_shuffle_off else R.string.media_shuffle_on))
                .setIconResId(if (state.first) R.drawable.ic_media_shuffle_on else R.drawable.ic_media_shuffle)
                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, android.os.Bundle.EMPTY))
                .setEnabled(state.third)
                .build(),
            CommandButton.Builder()
                .setDisplayName(getString(if (state.second) R.string.media_remove_favorite else R.string.media_add_favorite))
                .setIconResId(if (state.second) R.drawable.ic_media_favorite_on else R.drawable.ic_media_favorite)
                .setSessionCommand(SessionCommand(ACTION_FAVORITE, android.os.Bundle.EMPTY))
                .setEnabled(state.third)
                .build(),
        ))
    }
}

internal fun QueueManager.advanceIfMatches(track: Track) {
    if (current?.key() == track.key()) return
    if (peekNext()?.key() == track.key() || upcomingAutomix().firstOrNull()?.key() == track.key()) {
        advance()
    } else {
        // Not adjacent (e.g. automix not yet promoted): force into position.
        promoteTo(track)
    }
}

private fun QueueManager.promoteTo(track: Track) {
    val upcoming = upcomingAutomix().firstOrNull { it.key() == track.key() }
    if (upcoming != null) {
        replaceAutomix(upcomingAutomix().filterNot { it.key() == upcoming.key() })
        append(listOf(track))
        advance()
    }
}
