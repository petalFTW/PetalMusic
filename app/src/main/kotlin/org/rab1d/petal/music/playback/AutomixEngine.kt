/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.rab1d.petal.music.data.AutomixStyle
import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.TrackAnalysis
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.sources.SourceProvider
import org.rab1d.petal.music.sources.spotify.LibrespotBridge

/**
 * The AutoMix brain — the reason PetalMusic exists. Because the app owns the
 * queue, transitions are exact, for every source:
 *
 *  - exo→exo: system BridgeFX (arm op 2, boundary op 12, profile op 15) or the
 *    internal TailMixer retained-tail blend.
 *  - pump→pump (Spotify): librespot's internal crossfade mixing line.
 *  - pump↔exo: HAL boundary at the switch, or volume-ramp fallback.
 *
 * When the explicit queue drains, providers' automix suggestions keep the
 * music going — universally, across every logged-in source.
 */
class AutomixEngine(
    private val context: Context,
    private var player: Player,
    private val queue: QueueManager,
    private val bridge: BridgeFXLink,
    /** App-owned EQ crossfade; when present the HAL is only an analyser. */
    private val appMixer: EqMixer?,
    private val providers: Map<Source, SourceProvider>,
    private val pump: LibrespotBridge?,
    private val resolveTrack: suspend (Track) -> MediaItem?,
    private val switcher: Switcher,
    private val onEvent: () -> Unit,
) {
    companion object {
        const val TAG = "PetalMusic/Automix"
        private const val ARM_MARGIN_MS = 500
        private const val SWITCH_LEAD_MS = 350L
        /** Unplanned (manual skip, no incoming analysis) overlap cap. */
        private const val SKIP_FADE_MS = 2500
        /** Poll latency budget for the early-exit switch point. */
        private const val EARLY_LEAD_MS = 300
        /** Upper bound on an outro-length seam, so it can't become a whole song. */
        private const val AUTOMIX_MAX_MS = 32_000
        /** Opening media measured by a silent prelisten (~20 s at 3x). */
        private const val PRELISTEN_MS = 60_000L
        /** Hand a composed mix to the renderer this long before its bar line. */
        private const val DJ_FIRE_LEAD_MS = 1500L
        /** How close to the end a pair v2 has no move for is committed to v1. */
        private const val V1_HANDOFF_MS = 60_000L
        /** A stem move whose separation is not done this long before its bar line plays its fallback. */
        private const val STEM_DEADLINE_MS = 8000L
        /** Smooth shuffle reorders this many songs after the next one. */
        private const val SMOOTH_WINDOW = 6
    }

    /** Cross-engine switch operations owned by the playback service. */
    interface Switcher {
        /** Pump (Spotify) is playing, hand over to an ExoPlayer track. */
        suspend fun pumpToExo(track: Track, useHal: Boolean)

        /** ExoPlayer is playing, hand over to a pump (Spotify) track. */
        suspend fun exoToPump(track: Track, useHal: Boolean)

        /** Spotify→Spotify: librespot's internal crossfade. */
        fun pumpToPump(track: Track): Boolean

        /**
         * True crossfade into [track]; [outgoingSpeed] bends the current track
         * to the incoming tempo, and [startDelayMs] waits for the beat.
         */
        fun crossfade(track: Track, fadeMs: Int, outgoingSpeed: Float, startDelayMs: Long, seam: Boolean, eq: EqTransition.Context)
    }

    private val handler = Handler(Looper.getMainLooper())

    init {
        AnalysisCache.attach(context.filesDir)
        AutomixLog.attach(context)
    }

    @Volatile var settings: PlaybackSettings = PlaybackSettings()
        set(value) {
            field = value
        }

    /** Set by the service once the HAL probe result is known. */
    @Volatile var bridgeAvailable: Boolean = false
    private val useSystemBridge: Boolean get() = bridgeAvailable && settings.preferSystemBridge

    /** True when the app itself performs the transition (not BridgeFX). */
    private val appMixerActive: Boolean get() = settings.inAppMixer && appMixer != null

    private var armedKey: String? = null
    private var transitionId = 0
    private var nextResolved = false
    private var resolvedNextKey: String? = null
    private var resolving = false
    private var running = false
    /** Last repeat mode we acted on, so repeat-one can kill a live blend once. */
    private var lastRepeatMode = -1

    /** The plan currently being executed (UI shows it on the DJ panel). */
    @Volatile var activePlan: DJPlanner.Plan? = null
        private set

    fun onNowAnalyzing(key: String) {
        // retained for future pump-track analysis
    }

    private val analyzedKeys = HashSet<String>()

    /**
     * Fill the analysis cache for [track]: seed instantly from source metadata
     * when present (TIDAL ships BPM/key), then enrich in the background from
     * the provider so the planner works with real numbers.
     */
    private fun ensureAnalysis(track: Track) {
        if (AnalysisCache.get(track.key())?.authoritative == true) return
        if ((track.bpm != null && track.bpm > 0f) || track.camelot != null) {
            // Tempo is authoritative, but no beat grid has been measured yet:
            // strength 0 keeps the planner from trusting the placeholder phase.
            AnalysisCache.put(
                track.key(),
                TrackAnalysis(track.bpm ?: 0f, 0f, 0f, track.camelot, authoritative = true),
            )
        }
        if (track.source == Source.LOCAL || !analyzedKeys.add(track.key())) return
        Thread {
            val facts = runCatching {
                kotlinx.coroutines.runBlocking { providers[track.source]?.analyze(track) }
            }.getOrNull()
            if (facts != null) {
                AnalysisCache.put(track.key(), facts)
                Log.i(TAG, "metadata bpm=${facts.bpm} camelot=${facts.camelot} for ${track.title}")
                handler.post { onEvent() }
            }
        }.start()
    }

    // Pump state
    private var pumpTransitionFired = false
    private var pumpTrackKey: String? = null

    val pumpActive: Boolean
        get() {
            val cur = queue.current ?: return false
            return usesSpotifyPump(cur) && pump?.loggedIn == true
        }

    private fun usesSpotifyPump(track: Track): Boolean =
        (context.applicationContext as org.rab1d.petal.music.PetalApp).container.usesSpotifyPump(track)

    internal fun halStyle(): Int = when (settings.style) {
        AutomixStyle.CLEAN -> BridgeFX.STYLE_CLEAN
        AutomixStyle.REVERB -> BridgeFX.STYLE_REVERB
        AutomixStyle.STUTTER -> BridgeFX.STYLE_STUTTER
        AutomixStyle.ADAPTIVE -> BridgeFX.STYLE_ADAPTIVE
    }

    private val poller = object : Runnable {
        override fun run() {
            safePoll()
            if (running) {
                val pumpPlaying = pumpActive
                val playing = if (pumpPlaying) pump?.isPlaying() == true else player.isPlaying
                val duration = if (pumpPlaying) pump?.duration() ?: -1 else player.duration
                val position = if (pumpPlaying) pump?.position() ?: 0 else player.currentPosition
                handler.postDelayed(this, PlaybackWorkPolicy.pollMs(playing,
                    (player as? CrossfadePlayer)?.transitionActive == true,
                    if (duration > 0) duration - position else -1))
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poller)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    /** User/player events bypass the relaxed idle cadence. */
    fun wake() {
        if (!running) return
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    /** The dual-player crossmixer swapped which player is live. */
    fun setPlayer(p: Player) {
        player = p
        armedKey = null
        earlyKey = null
        nextResolved = false
        restoreVolume()
        onEvent()
    }

    /**
     * Settings changed under us. Old per-track latches were computed from the
     * previous values, so bin them and let the next poll rebuild.
     */
    fun onSettingsChanged() {
        wake()
        if ((player as? CrossfadePlayer)?.transitionActive != true) {
            activePlan = null
            selectedTransitionMs = -1
            selectedEqSummary = ""
        }
        armedKey = null
        earlyKey = null
        lockedMix = null
        nextResolved = false
        suggestionsKey = null
        volumeFadeArmed = false
        volumeFadeStep = 0
        if (!settings.automixEnabled && bridge.attached) bridge.abort()
    }

    /** Kill whatever blend is running right now (user flicked AutoMix off). */
    fun abortTransition() {
        (player as? CrossfadePlayer)?.abortTransition()
        if (bridge.attached) bridge.abort()
        restoreVolume()
        armedKey = null
        earlyKey = null
        activePlan = null
        onEvent()
    }

    private fun safePoll() {
        try { poll() } catch (e: Exception) { Log.w(TAG, "poll", e) }
    }

    private fun poll() {
        // Repeat-one must never blend with itself; if a blend is already running
        // when the user flips it on, kill it and leave the replay alone.
        val rm = queue.repeatMode
        if (rm != lastRepeatMode) {
            if (rm == 2) abortTransition()
            lastRepeatMode = rm
        }
        val current = queue.current ?: return
        ensureAnalysis(current)
        queue.peekNext()?.let { ensureAnalysis(it) }
        if (usesSpotifyPump(current)) {
            pollPump(current)
        } else {
            pollExo(current)
        }
        onEvent()
    }

    // ---- ExoPlayer path ----------------------------------------------------------------

    private fun pollExo(current: Track) {
        // The renderer owns a committed blend. Do not re-arm/replan over it.
        if ((player as? CrossfadePlayer)?.transitionActive == true) return
        if (!player.isPlaying) return
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        val remaining = duration - player.currentPosition
        if (remaining <= 0) return
        val fade = if (settings.autoFade) 45_000 else settings.fadeMs

        if (volumeFadeArmed && remaining <= (activePlan?.fadeMs ?: fade).toLong().coerceAtMost(2000)) {
            maybeVolumeRamp(remaining)
        }

        pollHalAnalysis(current)

        // Structure pass: find where the outgoing's outro actually begins and
        // start the mix there rather than at an arbitrary tail offset.
        val outro = updateStructure(current)

        // Prefetch the continuation early so the transition can be planned and
        // the HAL armed BEFORE the track ends (end-of-track only would leave no
        // room for a real crossfade).
        if (settings.automixEnabled && remaining <= (if (v2Active) 150_000 else 45_000) && queue.peekNext() == null &&
            queue.upcomingAutomix().isEmpty() && suggestionsKey != current.key()
        ) {
            suggestionsKey = current.key()
            requestAutomixSuggestions(current)
        }

        val v2 = v2Active
        trackProfiles(current, remaining)
        // Leave time to measure the next record (lookahead or silent prelisten);
        // a v2 exit can sit a few phrases before the end.
        if (remaining <= if (v2) 150_000 else 100_000) resolveNextAhead()
        measureNext(remaining)
        // v2 plays every pair it has a move for; the rest get v1's smooth blend.
        if (v2 && pollDj(current)) return

        refreshPreview(current, remaining, outro)
        if (maybeOutroExit(current, remaining, outro)) return

        // Spotify-style beat-matched early exit: advance into the next track
        // while the outgoing tail is still retained, so the HAL crossfades a
        // real overlap instead of waiting for the physical end. Off by default.
        if (maybeEarlyExit(current, remaining, outro)) return

        if (settings.automixEnabled && (activePlan?.fadeMs ?: 0) >= 250 &&
            remaining <= (fade + ARM_MARGIN_MS) * 2
        ) {
            val key = current.key()
            if (armedKey != key) {
                val pending = pendingNext()
                if (pending == null) {
                    Log.i(TAG, "arm skipped: no pending next (queue=${queue.visibleQueue().size} automix=${queue.upcomingAutomix().size})")
                }
                if (pending != null) {
                    val plan = activePlan ?: buildPlan(current, pending, remaining)
                    activePlan = plan
                    if (appMixerActive) {
                        // Nothing to arm: the EqCrossfadeProcessor mixes at the
                        // transition itself, using the retained outgoing tail.
                        armedKey = key
                        Log.i(TAG, "app-mix ready ${plan.kind} fade=${plan.fadeMs}")
                    } else if (useSystemBridge && bridge.attached) {
                        // Override the HAL with the DJ plan before arming.
                        bridge.setFade(plan.fadeMs)
                        bridge.setStyle(halStyle())
                        if (bridge.arm((remaining - plan.alignMs).toInt().coerceAtLeast(0))) {
                            armedKey = key
                            Log.i(TAG, "arm ${plan.kind} fade=${plan.fadeMs} speed=${plan.outgoingSpeed} align=${plan.alignMs}")
                        }
                    } else {
                        // No HAL: plan a volume-ramp fade around the switch.
                        armedKey = key
                        volumeFadeArmed = true
                    }
                    onEvent()
                }
            }
        }
    }

    private var earlyKey: String? = null

    /** Reconcile the displayed estimate with the same evidence used to fire the
     * transition. No old song/queue plan may survive while waiting for analysis. */
    private fun refreshPreview(current: Track, remainingMs: Long, outro: Long) {
        val next = pendingNext()
        val preserve = next != null && settings.preserveAlbum && current.albumId != null &&
            current.albumId == next.albumId && next.trackNumber == current.trackNumber + 1 &&
            current.source == next.source
        if (!settings.automixEnabled || queue.repeatMode == 2 || next == null || preserve) {
            activePlan = null
            selectedTransitionMs = -1
            selectedEqSummary = ""
            return
        }
        val seam = outro > 0 && appMixerActive && !seekInvalidated
        val plan = buildPlan(current, next, remainingMs, seam)
        activePlan = plan
        if (plan.fadeMs < 250) {
            selectedTransitionMs = -1
            selectedEqSummary = ""
            return
        }
        val start = maxOf(player.currentPosition,
            player.duration - (plan.fadeMs * plan.outgoingSpeed).toLong())
        selectPoint(start, plan, seam)
    }

    /**
     * Start the incoming track [fade] before the outgoing one ends. With the
     * dual-player mixer this is the true crossfade (both tracks audible); with
     * BridgeFX it hands the switch to ExoPlayer and lets the HAL bridge it.
     */
    private fun maybeEarlyExit(current: Track, remainingMs: Long, outro: Long): Boolean {
        if (queue.repeatMode == 2) return false
        if (!settings.automixEnabled) return false
        val appCrossfade = appMixerActive
        val halPath = useSystemBridge && bridge.attached
        if (!appCrossfade && !halPath) return false
        if (!appCrossfade && !settings.earlyExit) return false
        val key = current.key()
        if (earlyKey == key) return false
        val next = pendingNext() ?: return false
        if (!appCrossfade && !nextResolved) return false
        val preserve = settings.preserveAlbum && current.albumId != null &&
            current.albumId == next.albumId &&
            next.trackNumber == current.trackNumber + 1 &&
            current.source == next.source
        if (preserve) return false

        val plan = buildPlan(current, next, remainingMs)
        val fade = plan.fadeMs
        if (fade < 250) return false
        // A viable outro cue owns the start — never chop the tail short with
        // the fallback once we know where the record actually ends.
        if (outro > 0 && nextResolved) return false
        // Wait until the outgoing is on a real downbeat before starting the
        // incoming, so both grids land on the bar (tempo alone isn't enough).
        val align = if (appCrossfade && settings.beatAlign) {
            val dn = nextDownbeatMs()
            if (dn in 1..8000) dn else plan.alignMs
        } else {
            0L
        }
        // The planner budgets whole bars in wall time. Allow a bar of lead so
        // polling cannot repeatedly miss a narrow window as that budget drops
        // by a bar; account for the outgoing deck's matched playback speed.
        val lead = if (appCrossfade && plan.bars > 0)
            maxOf(EARLY_LEAD_MS.toLong(), (plan.eq.beatMs * 4).toLong()) else EARLY_LEAD_MS.toLong()
        val outgoingSpan = if (appCrossfade) fade * plan.outgoingSpeed else fade.toFloat()
        if (remainingMs > outgoingSpan + lead + align) return false

        earlyKey = key
        activePlan = plan
        selectPoint((player.duration - remainingMs + align).coerceAtLeast(0), plan, false)
        if (appCrossfade) {
            // Outgoing keeps playing to its real end while the standby player
            // starts the incoming early; the EQ bands trade, then they swap.
            // The OUTGOING bends to the incoming's native tempo for the overlap,
            // so the new track carries on unwarped after the handover.
            val fade = plan.fadeMs
                .coerceAtMost(((remainingMs - align) / plan.outgoingSpeed).toInt().coerceAtLeast(500))
            Log.i(
                TAG,
                "crossfade remaining=${remainingMs}ms fade=$fade outSpeed=${plan.outgoingSpeed} " +
                    "align=${align}ms",
            )
            switcher.crossfade(next, fade, plan.outgoingSpeed, align, false, plan.eq)
        } else {
            if (halPath) {
                bridge.setFade(plan.fadeMs)
                bridge.setStyle(halStyle())
                bridge.pushCachedProfile(next)
                bridge.arm(0)
            }
            Log.i(TAG, "early exit remaining=${remainingMs}ms ${plan.kind} fade=${plan.fadeMs}")
            player.seekToNextMediaItem()
        }
        onEvent()
        return true
    }

    private var lastHalAnalysisAt = 0L
    /** Last downbeat phase (ms), bar length and capture time from the HAL grid. */
    @Volatile private var halDownbeatPhaseMs: Long = -1
    @Volatile private var halBarMs: Long = -1
    @Volatile private var halDownbeatAt: Long = 0L

    /** Projected ms until the outgoing's next downbeat (-1 unknown). Only when nothing measured it. */
    private fun nextDownbeatMs(): Long {
        if (queue.current?.let { AnalysisCache.get(it.key())?.gridded } == true) return -1
        val phase = halDownbeatPhaseMs
        val bar = halBarMs
        if (phase < 0 || bar <= 0) return -1
        val elapsed = android.os.SystemClock.elapsedRealtime() - halDownbeatAt
        if (elapsed > 6000) return -1
        var d = (phase - elapsed) % bar
        if (d < 0) d += bar
        return d
    }

    /**
     * The HAL analyses the stream it is processing (BridgeEngine::getAnalysis):
     *   [0]=bpm [1]=loudness [2]=style [3]=samplesToNextBeat
     *   [4]=tempoConfidence×1000 [5]=keyPitchClass [6]=keyConfidence×1000
     *   [7]=camelot [8]=camelotMinor [9]=samplesToNextDownbeat
     *   [10]=beatPeriodSamples [11]=beatConfidence×1000
     * Pull it while a track plays so the next transition can be beatmatched.
     */
    private fun pollHalAnalysis(current: Track) {
        if (!bridge.attached) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHalAnalysisAt < 2000) return
        lastHalAnalysisAt = now
        val a = bridge.analysis() ?: return
        val bpm = a.getOrNull(0) ?: 0
        val tempoConf = a.getOrNull(4) ?: 0
        val beatPeriod = a.getOrNull(10) ?: 0
        val toNextBeat = a.getOrNull(3) ?: 0
        val toNextDownbeat = a.getOrNull(9) ?: 0
        val keyReliable = (a.getOrNull(6) ?: 0) >= 600
        val halCamelot = a.getOrNull(7)?.takeIf { keyReliable && it in 1..12 }
        val halMinor = a.getOrNull(8)?.takeIf { halCamelot != null && it in 0..1 }?.let { it == 1 }
        // Time to the next real downbeat, used to start the mix on a bar.
        halDownbeatPhaseMs = if (bpm in 40..220 && beatPeriod > 0 && toNextDownbeat > 0) {
            (toNextDownbeat.toFloat() / beatPeriod * (60000f / bpm)).toLong().coerceIn(1, 8000)
        } else {
            -1
        }
        halBarMs = if (bpm in 40..220) {
            (4 * (60000.0 / bpm)).toLong().coerceIn(500, 6000)
        } else {
            -1
        }
        halDownbeatAt = now
        // The HAL's tempo jumps between octaves and unrelated values at "full"
        // confidence and its key wanders every poll. Measured profiles own the
        // grid; the HAL only fills a tempo hint for a record nothing else knows.
        val prior = AnalysisCache.get(current.key())
        if (prior == null && bpm in 40..220 && tempoConf >= 900 && beatPeriod > 0) {
            AnalysisCache.put(current.key(), TrackAnalysis(bpm.toFloat(), 0f, .2f))
        }
    }

    // ---- structure / outro detection ---------------------------------------------------

    private var structureKey: String? = null

    /** Outro start for the current track (-1 until enough of the tail is heard). */
    @Volatile private var outroStartMs: Long = -1

    /** Where the selected blend begins in the outgoing track (-1 = none). */
    @Volatile var selectedTransitionMs: Long = -1
        private set

    /** Human summary of the selected EQ blend. */
    @Volatile var selectedEqSummary: String = ""
        private set

    private fun selectPoint(ms: Long, plan: DJPlanner.Plan?, seam: Boolean) {
        selectedTransitionMs = ms
        if (plan != null) selectedEqSummary = DJPlanner.eqSummary(plan, seam)
    }

    /**
     * Set when the user seeks mid-track: the outro we were reading is no longer
     * where the record ends, so drop the structure path and fall back to a
     * plain switch for this track.
     */
    @Volatile private var seekInvalidated = false

    fun onUserSeek() {
        wake()
        halDownbeatPhaseMs = -1
        halDownbeatAt = 0L
        seekInvalidated = true
        outroStartMs = -1
        // The seek aborted whatever the HAL had armed, so bin the per-track
        // latches too. Without this the engine thinks it is still armed and
        // just sits out the rest of the song, which is the whole "toggle
        // AutoMix off/on to wake it up" thing.
        armedKey = null
        earlyKey = null
        activePlan = null
        // The v2 plan survives a seek: if its mix point is still ahead it plays as planned
        // (seeking up to the transition to hear it used to recompose from there, find no
        // room, and hand the pair to v1). pollDj's "missed" check recomposes if it is behind.
        djFiredKey = null
        volumeFadeArmed = false
        volumeFadeStep = 0
        (player as? CrossfadePlayer)?.resetStructure()
        queue.current?.let { StructureAnalyzer.reset(it.key()) }
        Log.i(TAG, "user seek -> outro invalidated, re-arm allowed")
    }

    /**
     * Pump the outgoing's band envelope through [StructureAnalyzer] and return
     * the detected outro start once we're inside the tail. Cheap: it only runs
     * close to the end, and the audio thread does the actual capturing.
     */
    private fun updateStructure(current: Track): Long {
        val cp = player as? CrossfadePlayer ?: return -1
        if (structureKey != current.key()) {
            halDownbeatPhaseMs = -1
            halDownbeatAt = 0L
            structureKey = current.key()
            outroStartMs = -1
            seekInvalidated = false
            selectedTransitionMs = -1
            selectedEqSummary = ""
            cp.resetStructure()
            StructureAnalyzer.reset(current.key())
            cp.setStructureSink { f, l, h, ms -> StructureAnalyzer.feed(current.key(), f, l, h, ms) }
        }
        if (seekInvalidated) return -1
        cp.noteStructurePosition(player.currentPosition)
        val remaining = player.duration.takeIf { it > 0 }?.let { it - player.currentPosition } ?: return outroStartMs
        // Detection rejects anything outside this horizon anyway. Keep feeding
        // the body profile, but avoid copying/sorting it for the whole song.
        if (remaining > 45_000 || remaining <= 0) return outroStartMs
        val a = AnalysisCache.get(current.key())
        val grid = a?.takeIf { it.strength >= .3f }
        val found = StructureAnalyzer.detectOutro(current.key(), grid?.bpm ?: 0f,
            grid?.phaseMs ?: 0f, player.duration)
        val duration = player.duration
        // Energy can return after a quiet passage. Do not keep an obsolete cue
        // latched for the rest of the song or make it progressively earlier.
        if (found <= 0 || found >= duration) {
            outroStartMs = -1
            return -1
        }
        if (found > 0 && duration > 0 && found < duration) {
            if (outroStartMs != found) {
                outroStartMs = found
                selectedTransitionMs = found
                Log.i(TAG, "outro detected at ${found}ms of ${duration}ms (${current.title})")
            }
        }
        return outroStartMs
    }

    /**
     * Fire the true crossfade at the detected outro: once playback reaches the
     * outro start, blend into the incoming track (aligned to the next
     * downbeat) so the outgoing plays its own ending under the mix.
     */
    private fun maybeOutroExit(current: Track, remainingMs: Long, outro: Long): Boolean {
        if (!settings.automixEnabled || (!settings.autoFade && settings.fadeMs < 250) || queue.repeatMode == 2 ||
            outro <= 0 || seekInvalidated) return false
        if (!appMixerActive) return false
        val key = current.key()
        if (earlyKey == key) return false
        val pos = player.currentPosition
        if (pos < outro) return false
        val next = pendingNext() ?: return false
        if (!nextResolved) return false
        val preserve = settings.preserveAlbum && current.albumId != null &&
            current.albumId == next.albumId &&
            next.trackNumber == current.trackNumber + 1 &&
            current.source == next.source
        if (preserve) return false

        val plan = buildPlan(current, next, remainingMs, outro = true)
        val align = nextAlign(current, pos)
        if (plan.fadeMs < 250) return false
        // The detected ending owns the automatic overlap, including pairings
        // without tempo/key metadata. Convert its media span to wall time.
        val span = ((remainingMs - align) / plan.outgoingSpeed).toInt() - 250
        // Still bound the overlap for very short incoming tracks/manual budgets.
        val mix = minOf(span, plan.fadeMs).coerceAtLeast(1)
        if (remainingMs > plan.fadeMs * plan.outgoingSpeed + 1000 + align) return false
        earlyKey = key
        activePlan = plan
        selectPoint((pos + align).coerceAtLeast(0), plan, true)
        Log.i(
            TAG,
            "OUTRO crossfade pos=$pos outro=$outro remaining=${remainingMs}ms mix=${mix}ms " +
                "outSpeed=${plan.outgoingSpeed} align=${align}ms",
        )
        switcher.crossfade(next, mix, plan.outgoingSpeed, align, true, plan.eq.forOutro())
        onEvent()
        return true
    }

    /** ms until the outgoing's next downbeat (HAL grid when present). */
    private fun nextAlign(current: Track, pos: Long): Long {
        if (!settings.beatAlign) return 0
        val dn = nextDownbeatMs()
        if (dn in 1..8000) return dn
        val a = AnalysisCache.get(current.key()) ?: return 0
        if (a.bpm <= 0f) return 0
        // BPM phase only supplies a beat, not the first beat of a phrase.
        if (a.strength < .3f) return 0
        return BeatAnalyzer.align(pos, a.bpm, a.phaseMs) - pos
    }

    /** DJ plan for the switch out of [current] into [incoming]. */
    private fun buildPlan(current: Track, incoming: Track?, remainingMs: Long, outro: Boolean = false): DJPlanner.Plan {
        val out = AnalysisCache.get(current.key())
        val inA = incoming?.let { AnalysisCache.get(it.key()) }
        val plan = DJPlanner.plan(current, incoming, out, inA, settings, remainingMs, outro)
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(
            TAG,
            "plan ${plan.kind} fade=${plan.fadeMs} bars=${plan.bars} speed=${plan.outgoingSpeed} " +
                "bpm=${plan.bpmOut}->${plan.bpmIn} key=${out?.camelot}->${inA?.camelot} " +
                "align=${plan.alignMs} rem=${remainingMs} dur=${player.duration} pos=${player.currentPosition}",
        )
        return plan
    }

    /**
     * ExoPlayer moved to another item (auto, manual skip, or playlist change).
     * [incoming] is the item ExoPlayer now points at.
     */
    fun onTransition(reason: Int, incoming: Track?) {
        // Repeat-one replays the same record; there is nothing to mix into.
        if (queue.repeatMode == 2) {
            abortTransition()
            return
        }
        armedKey = null
        earlyKey = null
        // With the dual-player mixer the service advances the queue and swaps
        // players; the HAL/volume decision path no longer applies.
        if (!appMixerActive) mixerDecision(reason, incoming)
        if ((player as? CrossfadePlayer)?.transitionActive != true) {
            activePlan = null
            selectedTransitionMs = -1
            selectedEqSummary = ""
        }
        nextResolved = false
        onEvent()
    }

    private fun mixerDecision(reason: Int, incoming: Track?) {
        // Repeat-one restarts the same record; no self-mix, ever.
        if (queue.repeatMode == 2 ||
            (incoming != null && incoming.key() == queue.current?.key())
        ) {
            restoreVolume()
            return
        }
        val transition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            if (useSystemBridge && bridge.attached) bridge.abort()
            restoreVolume()
            return
        }
        if (!transition || incoming == null || !settings.automixEnabled ||
            (!settings.autoFade && settings.fadeMs < 250)) return

        val current = queue.current
        val preserveAlbum = settings.preserveAlbum && current != null &&
            current.albumId != null && current.albumId == incoming.albumId &&
            incoming.trackNumber == current.trackNumber + 1 && current.source == incoming.source
        if (preserveAlbum) {
            if (useSystemBridge && bridge.attached) bridge.abort()
            restoreVolume()
            return
        }
        restoreVolume()
        // A manual skip lands here with no armed plan. Keep it musical: a DJ
        // does not crossfade for twelve seconds on a skip, so cap the unplanned
        // (PLAIN) overlap to a short bridge while beatmatched transitions keep
        // their computed length.
        val planned = activePlan ?: DJPlanner.plan(
            queue.current, incoming, AnalysisCache.get(queue.current?.key() ?: ""),
            // A skip still has a short retained tail; zero remaining always meant a cut.
            incoming?.let { AnalysisCache.get(it.key()) }, settings, SKIP_FADE_MS + 250L,
        )
        val plan = if (activePlan == null && planned.kind == DJPlanner.Kind.PLAIN) {
            planned.copy(fadeMs = planned.fadeMs.coerceAtMost(SKIP_FADE_MS))
        } else {
            planned
        }
        if (appMixerActive) {
            // The app owns this transition: mix the retained tail under the
            // incoming track with the 4-band EQ crossfade.
            appMixer!!.fadeMs = plan.fadeMs
            appMixer.style = halStyle()
            appMixer.markBoundary()
            BridgeFXLink.announce(context, plan.fadeMs)
            Log.i(TAG, "app boundary ${plan.kind} fade=${plan.fadeMs}")
        } else if (useSystemBridge && bridge.attached) {
            transitionId++
            bridge.setFade(plan.fadeMs)
            bridge.setStyle(halStyle())
            bridge.pushCachedProfile(incoming)
            if (bridge.boundary(0, transitionId)) {
                BridgeFXLink.announce(context, plan.fadeMs)
                Log.i(TAG, "hal boundary id=$transitionId ${plan.kind} fade=${plan.fadeMs}")
                logRender(transitionId)
            }
        } else {
            // Internal fallback: the volume ramp covers the switch.
            BridgeFXLink.announce(context, plan.fadeMs)
            Log.i(TAG, "internal boundary ${plan.kind} fade=${plan.fadeMs}")
        }
        // This callback runs after the boundary: the player now owns the
        // incoming track. A tail renderer may stretch its retained outgoing
        // audio, but changing player speed here would warp the new song.

    }

    /** Non-HAL fallback: fade out towards the switch, fade the new one in. */
    private var suggestionsKey: String? = null
    private var volumeFadeArmed = false
    private var volumeFadeStep = 0

    private fun maybeVolumeRamp(remainingMs: Long) {
        if (!settings.automixEnabled || (!settings.autoFade && settings.fadeMs < 250) || useSystemBridge || appMixerActive) return
        if (!volumeFadeArmed) return
        val fade = (activePlan?.fadeMs ?: settings.fadeMs).toLong().coerceAtMost(2000)
        if (remainingMs <= fade) {
            volumeFadeStep++
            val f = volumeFadeStep.toFloat() / 10f
            player.volume = (1f - f).coerceIn(0.05f, 1f)
            if (volumeFadeStep >= 10) {
                volumeFadeArmed = false
                volumeFadeStep = 0
            }
        }
    }

    private fun restoreVolume() {
        volumeFadeArmed = false
        volumeFadeStep = 0
        player.volume = 1f
    }

    /**
     * Op 17: the HAL's own view of the transition it executed —
     * [0]=max callback µs [1]=prep drops [2]=character [3]=speedA×1000.
     */
    private fun logRender(transitionId: Int) {
        handler.postDelayed({
            bridge.renderStats()?.let { r ->
                Log.i(
                    TAG,
                    "hal render id=$transitionId maxUs=${r.getOrNull(0)} " +
                        "drops=${r.getOrNull(1)} char=${r.getOrNull(2)} " +
                        "speedA=${(r.getOrNull(3) ?: 1000) / 1000f}",
                )
            }
        }, 800)
    }

    // ---- Pump (Spotify) path -----------------------------------------------------------

    private fun pollPump(current: Track) {
        val p = pump ?: return
        val key = current.key()
        if (pumpTrackKey != key) {
            pumpTrackKey = key
            pumpTransitionFired = false
            armedKey = null
            activePlan = null
        }
        val duration = p.duration()
        if (duration <= 0) return
        val remaining = duration - p.position()
        if (remaining <= 0) return

        if (!pumpTransitionFired && settings.automixEnabled &&
            (settings.autoFade || settings.fadeMs >= 250)
        ) {
            val next = pendingNext() ?: return
            val plan = buildPlan(current, next, remaining)
            if (plan.fadeMs < 250 || remaining > plan.fadeMs + EARLY_LEAD_MS) return
            activePlan = plan
            pumpTransitionFired = true
            if (usesSpotifyPump(next)) {
                // librespot's own mixing line crossfades within the pump.
                if (p.crossfadeTo(next, plan.fadeMs)) {
                    BridgeFXLink.announce(context, plan.fadeMs)
                    Log.i(TAG, "pump→pump crossfade fade=${plan.fadeMs}")
                }
            } else {
                handler.post { runPumpToExo(next) }
            }
        } else if (!settings.automixEnabled && remaining <= 2000) {
            // No automix: natural stop, like a plain player.
        }
    }

    private fun runPumpToExo(next: Track) {
        pumpTrackKey = null
        CoroutineScope(Dispatchers.Main).launch {
            val useHal = useSystemBridge && bridge.attached
            switcher.pumpToExo(next, useHal)
            if (useHal) {
                transitionId++
                bridge.pushCachedProfile(next)
                bridge.boundary(0, transitionId)
                BridgeFXLink.announce(context, settings.fadeMs)
                logRender(transitionId)
            }
            pumpTransitionFired = false
            onEvent()
        }
    }

    /** Service tells the engine a manual exo→pump handoff is starting. */
    fun onManualExoToPump(track: Track) {
        if (useSystemBridge && bridge.attached && settings.automixEnabled &&
            (settings.autoFade || settings.fadeMs >= 250)) {
            transitionId++
            bridge.pushCachedProfile(track)
            bridge.boundary(0, transitionId)
            BridgeFXLink.announce(context, settings.fadeMs)
            logRender(transitionId)
        }
        pumpTrackKey = track.key()
        pumpTransitionFired = false
    }

    /** Pump finished a track naturally (no automix queued): stop cleanly. */
    fun onPumpEnded() {
        pumpTrackKey = null
        pumpTransitionFired = false
        onEvent()
    }

    // ---- Next resolution ----------------------------------------------------------------

    /** The next track, resolving automix lazily if the queue is dry. */
    private fun pendingNext(): Track? {
        // Repeat-one means the next song is this song. Never mix a track into
        // itself, that just fights the replay.
        if (queue.repeatMode == 2) return null
        val next = (if (settings.automixEnabled) queue.peekNext() else queue.peekQueueNext())
            ?: return null
        return next.takeIf { it.key() != queue.current?.key() }
    }

    fun hasNextResolved(): Boolean = nextResolved

    // ---- profiles and the v2 composer ---------------------------------------------------

    private var resolvedNextMediaId: String? = null
    private val profiles = ProfileTracker(context) { handler.post { onEvent() } }
    /** Next-track keys already prelistened; never repeated. */
    private val prelistenedKeys = HashSet<String>()
    private var lookaheadCurrentKey: String? = null
    /** Recent v2 transitions, newest first, so pacing and families vary. */
    private val recentMixes = ArrayList<DjComposer.Played>()
    private var lockedMix: DjComposer.Mix? = null
    private val stemWorker by lazy { StemWorker(context) }
    private var resolvedNextUri: android.net.Uri? = null
    /** The mix whose tempo ride has started (its plan is then held). */
    private var ridingMix: DjComposer.Mix? = null
    private var lockedPair: String? = null
    private var lockedVersion = -1
    private var djFiredKey: String? = null
    private var lockedSpeed = 1f
    /** The pair v2 handed to v1 (no move fit); v1 plays it to the end. */
    private var v1Pair: String? = null

    private val v2Active: Boolean
        get() = appMixerActive && settings.algorithm == org.rab1d.petal.music.data.AutomixAlgorithm.V2_DJ

    /**
     * The playing record: decode its file once, otherwise analyse what its deck
     * has recorded so far (the whole track when it played from the start).
     */
    private fun trackProfiles(current: Track, remaining: Long) {
        val cp = player as? CrossfadePlayer ?: return
        if (!appMixerActive) return
        val key = current.key()
        if (lookaheadCurrentKey != key) {
            lookaheadCurrentKey = key
            profiles.lookahead(key, player.currentMediaItem?.localConfiguration?.uri)
        }
        if (profiles.isDecoding(key) || cp.transitionActive) return
        profiles.live(key) { cp.activeRecording() }
    }

    /**
     * Measure the next record before its mix: decode a file whole, otherwise
     * cue the preloaded stream silently on the standby deck.
     */
    private fun measureNext(remaining: Long) {
        if (!appMixerActive || !settings.automixEnabled || !nextResolved) return
        val next = pendingNext() ?: return
        val key = next.key()
        if (key != resolvedNextKey || profiles.hasComplete(key) || profiles.isDecoding(key)) return
        if (key in prelistenedKeys || profiles.get(key) != null) return
        // Never run into the mix itself: a prelisten takes ~20 s of wall time.
        val start = lockedMix?.outStartMs?.let { it - player.currentPosition } ?: remaining
        if (start < 25_000) return
        val mediaId = resolvedNextMediaId ?: return
        val cp = player as? CrossfadePlayer ?: return
        prelistenedKeys += key
        cp.prelisten(mediaId, PRELISTEN_MS) { snapshot ->
            AutomixLog.i(TAG, "prelisten ${next.title}: ${snapshot?.let { "%.0f".format(it.bands.endMs) } ?: "none"} ms heard")
            profiles.prelistened(key, snapshot)
        }
    }

    private fun lookahead(key: String, item: MediaItem) {
        if (appMixerActive) profiles.lookahead(key, item.localConfiguration?.uri)
    }

    /**
     * AutoMix v2: compose the mix for the coming pair (locked once chosen,
     * recomposed when better analysis arrives early enough), then fire it so
     * the renderer starts on the chosen bar line. False when v2 has no move for
     * the pair: the caller plays it with v1's blend.
     */
    private var lastPollSpeed = 1f
    private var lockedOut: TrackProfile? = null
    private var lockedIn: TrackProfile? = null

    private fun pollDj(current: Track): Boolean {
        val cp = player as? CrossfadePlayer ?: return true
        if (!settings.automixEnabled || queue.repeatMode == 2) return true
        val key = current.key()
        smoothShuffle(key)
        if (djFiredKey == key) return true
        val next = pendingNext() ?: run { activePlan = null; return true }
        val preserve = settings.preserveAlbum && current.albumId != null && current.albumId == next.albumId &&
            next.trackNumber == current.trackNumber + 1 && current.source == next.source
        if (preserve) { activePlan = null; return true }
        val pos = player.currentPosition
        val duration = player.duration
        val ready = nextResolved && cp.hasPreloaded(next.key())
        val pair = "$key→${next.key()}"
        // Handed to v1 once: it stays there, so the two engines never both arm one pair.
        if (v1Pair == pair) return false
        // Nothing fit with this analysis; try again only when the analysis changes.
        if (lockedMix == null && lockedPair == pair && lockedVersion == ProfileStore.version) {
            return handToV1(pair, pos, duration, current)
        }
        // A plan composed at another tempo (a glide home since) is stale unless its ride has begun.
        val speedNow = cp.deckSpeed
        // While the deck's tempo is still gliding, a speed change is not a reason to recompose:
        // every poll would (each one re-running stem separation, ~2 s of CPU) until it settles.
        val gliding = kotlin.math.abs(speedNow - lastPollSpeed) > .0005f
        lastPollSpeed = speedNow
        var mix = lockedMix?.takeIf { lockedPair == pair }
        // Only this pair's own analysis counts: lookaheads of later songs bump the global version too.
        val profOut = profiles.get(key)
        val profIn = profiles.get(next.key())
        val improved = mix != null && (profOut !== lockedOut || profIn !== lockedIn ||
            (!gliding && kotlin.math.abs(speedNow - lockedSpeed) > .002f)) &&
            mix.outStartMs - pos > 20_000 && ridingMix !== mix
        val missed = mix != null && mix.outStartMs - pos < (if (ready) 300 else 1500)
        if (mix == null || improved || missed) {
            // A recompose may only improve the plan: if better analysis finds nothing, the
            // plan already made still plays (device log: every "no move" came seconds after
            // a real plan, and the pair then went to v1's blend).
            val previous = mix.takeIf { improved && !missed }
            val earliest = pos + if (ready) DJ_FIRE_LEAD_MS + 800 else 6000
            val outUri = player.currentMediaItem?.localConfiguration?.uri
            val inUri = resolvedNextUri.takeIf { resolvedNextKey == next.key() }
            // Stems need the whole file readable ahead of time and a complete profile.
            fun stems(uri: android.net.Uri?, k: String) =
                settings.stemMoves && stemWorker.supports(uri) && profiles.hasComplete(k)
            mix = DjComposer.compose(
                DjComposer.Side(profiles.get(key), AnalysisCache.get(key), duration, key, stems(outUri, key)),
                DjComposer.Side(profiles.get(next.key()), AnalysisCache.get(next.key()),
                    next.durationMs.takeIf { it > 0 } ?: 240_000, next.key(), stems(inUri, next.key())),
                earliest, recentMixes, pair.hashCode(), settings.beatAlign,
                DjComposer.Options(
                    style = if (settings.cleanMix) DjComposer.MixStyle.CLEAN else DjComposer.MixStyle.CREATIVE,
                    conformIncoming = settings.conformIncoming,
                    glideBack = settings.glideBack,
                    letOutrosPlay = settings.letOutrosPlay,
                    // A song held at the last mix's tempo is mixed from that tempo.
                    currentSpeed = speedNow,
                    tempoRide = settings.tempoRide,
                    djPacing = settings.djPacing,
                ),
            )
            if (mix == null && previous == null) {
                // Say why, once per analysis change: a pair waiting on analysis looks the same
                // as one that has nothing in the log otherwise.
                val po = profiles.get(key)
                val pi = profiles.get(next.key())
                AutomixLog.i(TAG, "dj no move yet ${current.title} → ${next.title}: " +
                    "out=${po?.let { "bpm=%.1f grid=%.2f complete=${it.complete}".format(it.bpm, it.gridConfidence) } ?: "no profile"} " +
                    "in=${pi?.let { "bpm=%.1f grid=%.2f complete=${it.complete}".format(it.bpm, it.gridConfidence) } ?: "no profile"} " +
                    "ready=$ready left=${(duration - pos) / 1000}s")
            }
            if (mix == null && previous != null) {
                AutomixLog.i(TAG, "dj recompose for ${current.title} found no move; keeping ${previous.label}")
                mix = previous
            }
            if (mix?.needsStems == true && mix !== previous) stemWorker.prepare(mix, outUri, inUri) { onEvent() }
            lockedMix = mix
            lockedPair = pair
            lockedVersion = ProfileStore.version
            lockedOut = profOut
            lockedIn = profIn
            lockedSpeed = speedNow
            if (mix != null && mix !== previous) AutomixLog.i(TAG, "dj plan ${current.title} → ${next.title}: ${mix.label} at " +
                "${mix.outStartMs}ms (in ${mix.outStartMs - pos}ms) cue=${mix.incomingCueMs} | ${mix.notes} | " +
                DjComposer.metrics(mix, profiles.get(key), profiles.get(next.key()), speedNow))
        }
        if (mix == null) return handToV1(pair, pos, duration, current)
        // Stems not separated in time: play the same pair without them.
        if (mix.needsStems && !mix.stemsReady && mix.outStartMs - pos < STEM_DEADLINE_MS) {
            val fallback = mix.fallback
            AutomixLog.i(TAG, "dj stems not ready for ${mix.label}; " +
                (fallback?.let { "playing ${it.label} at ${it.outStartMs}ms instead" } ?: "no stem-free move"))
            if (fallback != null) {
                mix = fallback
                lockedMix = fallback
            } else {
                // A stem move without its stems is not the move: v1's blend plays the pair.
                v1Pair = pair
                activePlan = null
                AutomixLog.i(TAG, "dj no stem-free move for ${current.title}; v1 blend")
                return false
            }
        }
        // Ride: ease the outgoing towards the incoming's tempo over the bars before the mix.
        mix.rideSpeed?.let { target ->
            val deckSpeed = cp.deckSpeed.coerceAtLeast(.5f)
            val untilStart = ((mix.outStartMs - pos) / deckSpeed).toLong()
            val window = (mix.rideBars * mix.outBarMs / deckSpeed).toLong()
            if (ridingMix !== mix && untilStart in 6_000..window + 2_000) {
                ridingMix = mix
                AutomixLog.i(TAG, "dj ride ${current.title}: $deckSpeed → $target over ${untilStart - DJ_FIRE_LEAD_MS}ms")
                cp.rideActive(target, untilStart - DJ_FIRE_LEAD_MS)
            }
        }
        val plan = DJPlanner.Plan(
            if (mix.sync) DJPlanner.Kind.BEATMATCH else DJPlanner.Kind.PLAIN, mix.durationMs, mix.speed,
            (mix.outStartMs - pos).coerceAtLeast(0), mix.bpmOut, mix.bpmIn, mix.bars,
            // The deck is cued a pre-roll early; the renderer unmutes it on the real cue.
            EqTransition.Context(algorithm = settings.algorithm, dj = mix,
                incomingCueMs = mix.incomingCueMs - mix.prerollMs,
                beatMs = (mix.wallBarMs / 4).toFloat(), outgoingBeatMs = (mix.outBarMs / 4).toFloat()),
        )
        activePlan = plan
        selectPoint(mix.outStartMs, plan, false)
        // An unprepared deck needs a few seconds before it can start on the bar line.
        if (mix.outStartMs - pos > (if (ready) DJ_FIRE_LEAD_MS else 5000L) || !nextResolved) return true
        djFiredKey = key
        recentMixes.add(0, DjComposer.Played(mix.family, mix.bars, mix.fill))
        while (recentMixes.size > 8) recentMixes.removeAt(recentMixes.size - 1)
        earlyKey = key
        AutomixLog.i(TAG, "dj fire ${mix.label} ready=$ready pos=$pos start=${mix.outStartMs}")
        switcher.crossfade(next, mix.durationMs, mix.speed, 0, false, plan.eq)
        onEvent()
        return true
    }

    /**
     * v2 found no move for [pair]. Until the last stretch it may still find one as analysis
     * improves (or after a seek or skip), and meanwhile v1 must not arm: true keeps v2 in
     * charge. Inside the last [V1_HANDOFF_MS] the pair is committed to v1's smooth blend.
     * (It used to commit as soon as v1 had armed, but v1 armed on any poll where v2 briefly
     * had no plan, so one gap sent the pair to v1 minutes early.)
     */
    private fun handToV1(pair: String, pos: Long, duration: Long, current: Track): Boolean {
        if (duration <= 0 || duration - pos > V1_HANDOFF_MS) return true
        if (v1Pair != pair) {
            v1Pair = pair
            activePlan = null
            armedKey = null
            AutomixLog.i(TAG, "dj no move for ${current.title}; v1 blend")
        }
        return false
    }

    private var smoothedFor: String? = null
    private var smoothedVersion = -1
    private var smoothedAt = 0L

    /**
     * Smooth shuffle: order the songs after the next one by how well each follows the one
     * before (tempo within riding distance, compatible keys), and decode local files in
     * that window ahead so their tempo becomes known. Re-run when new profiles arrive.
     */
    private fun smoothShuffle(currentKey: String) {
        if (!settings.smoothShuffle || !queue.shuffle) return
        val now = android.os.SystemClock.elapsedRealtime()
        val fresh = smoothedFor != currentKey
        if (!fresh && (ProfileStore.version == smoothedVersion || now - smoothedAt < 20_000)) return
        smoothedFor = currentKey
        smoothedVersion = ProfileStore.version
        smoothedAt = now
        val upcoming = queue.visibleQueue().drop(queue.currentIndex + 1).take(SMOOTH_WINDOW + 1)
        for (t in upcoming) t.localPath?.let { path ->
            profiles.lookahead(t.key(), android.net.Uri.fromFile(java.io.File(path)))
        }
        if (queue.smoothUpcoming(SMOOTH_WINDOW, ::followCost)) {
            AutomixLog.i(TAG, "smooth shuffle after ${queue.current?.title}: " +
                queue.visibleQueue().drop(queue.currentIndex + 1).take(SMOOTH_WINDOW + 1).joinToString(" → ") { it.title })
            onEvent()
        }
    }

    private fun tempoOf(t: Track): Float? {
        profiles.get(t.key())?.takeIf { it.gridConfidence >= .3f }?.let { return it.bpm }
        AnalysisCache.get(t.key())?.takeIf { it.bpm > 0f }?.let { return it.bpm }
        return t.bpm?.takeIf { it > 0f }
    }

    private fun keyOf(t: Track): Pair<Int, Boolean?>? {
        profiles.get(t.key())?.takeIf { it.keyConfidence >= .08f }?.camelot?.let { return it to profiles.get(t.key())?.minor }
        return t.camelot?.takeIf { it in 1..12 }?.let { it to null }
    }

    /** How well [b] follows [a]: 0 for a straight blend, rising with the tempo gap; key clashes cost more. */
    private fun followCost(a: Track, b: Track): Double? {
        val ta = tempoOf(a) ?: return null
        val tb = tempoOf(b) ?: return null
        val r = (tb / ta).toDouble()
        val d = listOf(1.0, 2.0, .5).minOf { kotlin.math.abs(r / it - 1) }
        var cost = when {
            d <= .04 -> 0.0
            d <= .08 -> .4
            else -> 1.0 + d * 4
        }
        if (DjComposer.clash(keyOf(a), keyOf(b))) cost += .6
        return cost
    }

    fun resolveNextAhead() {
        val next = pendingNext()
        if (resolvedNextKey != next?.key()) {
            nextResolved = false
            resolvedNextKey = null
            // A queue edit must not leave the old next song on either deck.
            if ((player as? CrossfadePlayer)?.transitionActive != true) {
                (player as? CrossfadePlayer)?.cancelPreload()
                while (player.mediaItemCount > player.currentMediaItemIndex + 1) {
                    player.removeMediaItem(player.mediaItemCount - 1)
                }
            }
        }
        if (resolving || nextResolved) return
        if (next == null) return
        if (pumpActive) return
        if (usesSpotifyPump(next)) return // handled by exo→pump switch, never enqueued
        resolving = true
        val key = next.key()
        val currentKey = queue.current?.key()
        Thread {
            try {
                val item = kotlinx.coroutines.runBlocking { resolveTrack(next) }
                Log.i(TAG, "resolved next ${next.key()} -> ${item != null}")
                if (item == null) {
                    handler.post {
                        if (queue.current?.key() != currentKey || pendingNext()?.key() != key) return@post
                        queue.dropUpcoming(key)
                        onEvent()
                    }
                    return@Thread
                }
                handler.post {
                    if (queue.current?.key() != currentKey || pendingNext()?.key() != key) return@post
                    val lastIdx = player.mediaItemCount - 1
                    val lastTag = if (lastIdx >= 0) {
                        player.getMediaItemAt(lastIdx).localConfiguration?.tag as? Track
                    } else null
                    if (lastTag?.key() != key) {
                        player.addMediaItem(item)
                    }
                    // Buffer it on the standby deck now so the handover at the
                    // end of the current track is instant (no resolve/decode gap).
                    (player as? CrossfadePlayer)?.preload(item)
                    nextResolved = true
                    resolvedNextKey = key
                    resolvedNextMediaId = item.mediaId
                    resolvedNextUri = item.localConfiguration?.uri
                    lookahead(key, item)
                    onEvent()
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolve $key failed: ${e.message}")
            } finally {
                handler.post { resolving = false }
            }
        }.start()
    }

    fun requestAutomixSuggestions(seed: Track, onReady: (List<Track>) -> Unit = {}) {
        if (resolving) return
        resolving = true
        Thread {
            val suggestions = collectSuggestions(seed)
            Log.i(TAG, "automix suggestions for ${seed.title}: ${suggestions.size} (${suggestions.take(2).joinToString { it.title }})")
            handler.post {
                queue.replaceAutomix(suggestions)
                nextResolved = false
                if (suggestions.isNotEmpty() && !pumpActive) resolveNextAhead()
                onEvent()
                onReady(suggestions)
            }
            resolving = false
        }.start()
    }

    /** Blocking variant used for immediate user skips. */
    fun suggestionsBlocking(seed: Track): List<Track> {
        val out = collectSuggestions(seed)
        handler.post {
            queue.replaceAutomix(out)
            onEvent()
        }
        return out
    }

    private fun collectSuggestions(seed: Track): List<Track> {
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        fun addAll(list: List<Track>) {
            for (t in list) {
                val k = t.key()
                if (seen.add(k) && k != seed.key()) out.add(t)
                if (out.size >= 10) return
            }
        }
        // Same source first (real recommendations), then any other logged-in
        // provider — universal automix across services.
        val preferred = providers[seed.source]
        if (preferred != null && preferred.isLoggedIn()) {
            runCatching { kotlinx.coroutines.runBlocking { preferred.automixNext(seed) } }
                .onFailure { Log.w(TAG, "automix ${seed.source} failed: ${it.message}") }
                .getOrNull()?.let { addAll(it) }
        }
        for ((source, provider) in providers) {
            if (out.size >= 10) break
            if (source == seed.source || !provider.isLoggedIn()) continue
            runCatching { kotlinx.coroutines.runBlocking { provider.automixNext(seed) } }
                .onFailure { Log.w(TAG, "automix $source failed: ${it.message}") }
                .getOrNull()?.let { addAll(it) }
        }
        return out
    }

    /** Record the outgoing track's musical profile for future HAL intros. */
    fun recordProfile() {
        if (!bridge.attached) return
        val a = bridge.analysis() ?: return
        if (a.size >= 14 && a[0] > 0 && a[4] >= 550) {
            val key = queue.current?.key() ?: return
            val duration = player.duration
            val pos = player.currentPosition
            val section = when {
                pos <= 15000 -> "intro"
                duration > 0 && duration - pos <= 15000 -> "outro"
                else -> return
            }
            ProfileCache.put(
                context, "$key:$section",
                intArrayOf(a[0], a[1], a[4], a[5], a[6], a[7], a[8], a[13]),
            )
        }
    }

    /** Push a cached intro profile for [incoming] so the HAL blends it well. */
    private fun BridgeFXLink.pushCachedProfile(incoming: Track?) {
        val profile = ProfileCache.intro(context, incoming?.key()) ?: return
        pushProfile(profile)
    }
}
