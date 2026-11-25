/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.rab1d.petal.music.data.AutomixStyle
import org.rab1d.petal.music.model.Track

/**
 * Single-process state bus between the playback service and the UI.
 * The QueueManager singleton lives here — PetalMusic owns the queue.
 */
object PlaybackBus {
    data class State(
        val current: Track? = null,
        val playing: Boolean = false,
        val queueSnapshot: List<Track> = emptyList(),
        val automixSnapshot: List<Track> = emptyList(),
        val bridgeAttached: Boolean = false,
        val fadeMs: Int = 6000,
        val automixEnabled: Boolean = true,
        val style: AutomixStyle = AutomixStyle.CLEAN,
        val remainingMs: Long = -1,
        /** Wall-clock ms until the next transition's fade starts, -1 when idle. */
        val transitionEtaMs: Long = -1,
        val pumpActive: Boolean = false,
        val error: String? = null,
        val errorId: Long = 0,
        val dj: DjInfo? = null,
        val audio: AudioInfo? = null,
        /** The track AutoMix is queued to blend into (for the dual-art view). */
        val next: Track? = null,
        /** 0..1 while a crossfade overlap is running, 0 otherwise. */
        val transitionProgress: Float = 0f,
        /** True while the crossfade overlap is running. */
        val transitionActive: Boolean = false,
        /** True when the running blend is seaming a detected outro. */
        val transitionSeam: Boolean = false,
        /** Position in the outgoing track where the selected blend begins (-1 = none). */
        val transitionStartMs: Long = -1,
        /** Human summary of the selected EQ blend. */
        val eqSummary: String = "",
        val transitionEq: EqTransition = EqTransition.WAITING,
        val transitionDurationMs: Int = 0,
        /** Queue flags live here too so a repeat/shuffle tap always recomposes. */
        val repeatMode: Int = 0,
        val shuffle: Boolean = false,
        /** Monotonic tick, bumped on every publish so StateFlow never conflates. */
        val uiTick: Long = 0,
    )

    /** Decoded audio format (shown under the artwork). */
    /** Smoothed dry levels at the playhead: full-band RMS, four bands, mean band onset. */
    data class Level(val rms: Float, val sub: Float, val bass: Float, val mid: Float, val high: Float, val onset: Float)

    data class AudioInfo(
        val label: String = "",
    )

    /** DJ panel data for the now-playing screen. */
    data class DjInfo(
        val kind: String = "",
        val bpmOut: Float? = null,
        val bpmIn: Float? = null,
        val fadeMs: Int = 0,
        val engineName: String = "",
        val alignMs: Long = 0,
        val speed: Float = 1f,
        /** Camelot wheel code of the playing track, when the HAL has it. */
        val camelotOut: Int? = null,
    )

    val queue = QueueManager()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Live position source for PCM-pump playback (Spotify). */
    @Volatile var pumpPositionProvider: (() -> Long)? = null

    // In-process control surface. UI and service share one process, so the UI
    // drives playback through these directly instead of the MediaSession —
    // transport commands through the session desynced after a manual skip.
    @Volatile var positionProvider: (() -> Long)? = null
    @Volatile var durationProvider: (() -> Long)? = null
    /**
     * What the audible deck sounds like right now, for audio-reactive UI. Read-only and
     * main thread, like [positionProvider]. Null when nothing is measured (the Spotify
     * pump, or the first ~40 ms after a seek).
     */
    @Volatile var levelProvider: (() -> Level?)? = null
    @Volatile var nextAction: (() -> Unit)? = null
    @Volatile var previousAction: (() -> Unit)? = null
    @Volatile var playPauseAction: (() -> Unit)? = null
    /** Pause only (never resumes): the sleep timer. */
    @Volatile var pauseAction: (() -> Unit)? = null
    @Volatile var seekAction: ((Long) -> Unit)? = null

    private var last: State? = null

    /** Re-publish the last state (for queue mutations from the UI). */
    fun publishRefresh() {
        // Rebuild from the queue itself so a shuffle/repeat change is never
        // swallowed by StateFlow equality, even before the service has
        // published a full state (the "shuffle/loop lost on relaunch" bug).
        val s = _state.value
        last = s
        _state.value = s.copy(
            queueSnapshot = queue.visibleQueue(),
            automixSnapshot = queue.upcomingAutomix(),
            repeatMode = queue.repeatMode,
            shuffle = queue.shuffle,
            uiTick = s.uiTick + 1,
        )
    }

    /** Surface a playback problem (snackbar in the UI), never crash. */
    fun publishError(message: String) {
        _state.value = _state.value.copy(error = message, errorId = System.nanoTime())
        last = _state.value
    }

    fun publish(
        current: Track?,
        playing: Boolean,
        queueSnapshot: List<Track>,
        automixSnapshot: List<Track>,
        bridgeAttached: Boolean,
        fadeMs: Int,
        automixEnabled: Boolean,
        style: AutomixStyle,
        remainingMs: Long,
        pumpActive: Boolean = false,
        dj: DjInfo? = null,
        audio: AudioInfo? = null,
        next: Track? = null,
        transitionProgress: Float = 0f,
        transitionActive: Boolean = false,
        transitionSeam: Boolean = false,
        transitionStartMs: Long = -1,
        eqSummary: String = "",
        transitionEq: EqTransition = EqTransition.WAITING,
        transitionDurationMs: Int = 0,
        positionMs: Long = -1,
        playbackSpeed: Float = 1f,
    ) {
        val next = State(
            current = current,
            playing = playing,
            queueSnapshot = queueSnapshot,
            automixSnapshot = automixSnapshot,
            bridgeAttached = bridgeAttached,
            fadeMs = fadeMs,
            automixEnabled = automixEnabled,
            style = style,
            remainingMs = remainingMs,
            transitionEtaMs = when {
                !automixEnabled || transitionActive || fadeMs <= 0 -> -1
                transitionStartMs >= 0 && positionMs >= 0 ->
                    ((transitionStartMs - positionMs).coerceAtLeast(0) /
                        playbackSpeed.coerceAtLeast(.01f)).toLong()
                remainingMs > fadeMs -> remainingMs - fadeMs
                else -> -1
            },
            pumpActive = pumpActive,
            dj = dj,
            audio = audio,
            next = next,
            transitionProgress = transitionProgress,
            transitionActive = transitionActive,
            transitionSeam = transitionSeam,
            transitionStartMs = transitionStartMs,
            eqSummary = eqSummary,
            transitionEq = transitionEq,
            transitionDurationMs = transitionDurationMs,
            repeatMode = queue.repeatMode,
            shuffle = queue.shuffle,
            uiTick = _state.value.uiTick + 1,
        )
        // The engine publishes on every poll (4×/s near a mix), but between real changes only
        // the countdowns move, and nothing on screen reads them. Emitting anyway recomposed
        // the whole UI several times a second while music played.
        val prev = _state.value
        if (next.copy(remainingMs = prev.remainingMs, transitionEtaMs = prev.transitionEtaMs, uiTick = prev.uiTick) == prev) {
            return
        }
        _state.value = next
        last = _state.value
    }
}
