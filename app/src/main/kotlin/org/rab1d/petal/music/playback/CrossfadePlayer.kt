/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt
import org.rab1d.petal.music.sources.PetalDataSource

// Two decks, one session player; swapping the session itself broke the UI.
class CrossfadePlayer(
    private val context: Context,
    private val onSwapped: (() -> Unit)? = null,
    /** blend just went audible: incoming cover uri + how long it runs */
    private val onOverlapStart: ((String?, Int) -> Unit)? = null,
    /** blend done or killed, stop drawing the incoming cover */
    private val onOverlapEnd: (() -> Unit)? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val bendA = EqBendProcessor()
    private val bendB = EqBendProcessor()
    // Each deck's stretcher, kept so a glide can move its speed without draining the sink.
    private val tempoA = TempoStretchProcessor()
    private val tempoB = TempoStretchProcessor()
    private val slotA = buildExo(bendA, tempoA)
    private val slotB = buildExo(bendB, tempoB)
    private var active: ExoPlayer = slotA
    private var standby: ExoPlayer = slotB

    private val playlist = ArrayList<MediaItem>()
    private var index = 0
    private val playbackRequested = MutableStateFlow(false)
    @set:JvmName("updatePlayRequest")
    private var playWhenReady = false
        set(value) {
            field = value
            playbackRequested.value = value
        }
    private var volume = 1f
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffleModeEnabled = false
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var released = false
    private var crossfading = false
    private var overlapStarted = false
    private var incomingAttackGain = 0f
    private var crossfadeJob: kotlinx.coroutines.Job? = null

    /** Next track sitting ready on the standby deck for a gapless handover. */
    private var preloadedKey: String? = null
    private var preloadedIndex: Int = -1

    /** Exact plan currently rendered, also published to the UI. */
    var transitionEq: EqTransition = EqTransition.WAITING
        private set
    var transitionDurationMs: Int = 0
        private set

    /** 0..1 while the outgoing is fading into the incoming, 0 when idle. */
    @Volatile var crossfadeProgress: Float = 0f
        private set

    /** True while the crossfade overlap is running. */
    val transitionActive: Boolean get() = crossfading

    /** True when the running blend is seaming a detected outro. */
    @Volatile var transitionSeam: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val relay = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!released) invalidateState()
        }
    }

    init {
        slotA.addListener(relay)
        slotB.addListener(relay)
    }

    private fun bendFor(player: ExoPlayer): EqBendProcessor =
        if (player === slotA) bendA else bendB

    private fun tempoFor(player: ExoPlayer): TempoStretchProcessor =
        if (player === slotA) tempoA else tempoB

    private fun buildExo(bend: EqBendProcessor, tempo: TempoStretchProcessor): ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(BendRenderersFactory(context, bend, tempo))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context, PetalDataSource.Factory()),
            ),
        )
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            // Let the service own focus; the second deck used to pause the first.
            false,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        // Dynamic scheduling (experimentalSetDynamicSchedulingEnabled) is left off: with the deck
        // chain's variable DSP per buffer, late wake-ups are the suspected cause of clicks/skips heard on device.
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    // ---- extras used by the service -------------------------------------------------------

    /** Live audio session of whichever player is active (for BridgeFX analysis). */
    val audioSessionId: Int get() = active.audioSessionId

    fun addAnalyticsListener(listener: AnalyticsListener) {
        slotA.addAnalyticsListener(listener)
        slotB.addAnalyticsListener(listener)
    }

    // Analyze the live deck only, otherwise the incoming track muddies the outro.
    fun setStructureSink(sink: ((Float, Float, Float, Long) -> Unit)?) {
        bendA.sink = if (active === slotA) sink else null
        bendB.sink = if (active === slotB) sink else null
    }

    /** Re-anchor the active player's hop clock after a seek. */
    fun noteStructurePosition(ms: Long) {
        if (active === slotA) bendA.notePosition(ms) else bendB.notePosition(ms)
    }

    fun resetStructure() {
        bendA.resetStructure()
        bendB.resetStructure()
    }

    /** The audible deck's measured level at its playhead, for the UI. Application thread. */
    internal fun liveLevel(): MixAudioMonitor.Frame? = bendFor(active).featuresAt(active.currentPosition)

    /** Newest [maxMs] of the live deck's dry envelope, on its media clock. */
    internal fun activeRecording(): Recording? = bendFor(active).let { b ->
        b.envelope.snapshot()?.let { Recording(it, b.chroma.snapshot()) }
    }

    private var prelistenJob: kotlinx.coroutines.Job? = null

    /** Learned time from play() to the incoming being heard; refined by every v2 mix and kept. */
    private val latencyStore = context.getSharedPreferences("petal_automix", Context.MODE_PRIVATE)
    private var startLatencyMs = latencyStore.getFloat("start_latency_ms", 100f).toDouble()
    /** How long a seek on a playing deck freezes it before audio resumes; learned. */
    private var seekLatencyMs = latencyStore.getFloat("seek_latency_ms", 160f).toDouble()

    /**
     * Cue the preloaded next track "in the headphones": play it silently and
     * fast (time-stretched; the processor still sees media-time PCM) so its
     * beat grid, bar phase and intro can be measured before the mix. Streams
     * cannot be decoded ahead any other way. The deck returns to its start.
     */
    internal fun prelisten(key: String, mediaMs: Long, onDone: (Recording?) -> Unit) {
        if (released || crossfading || preloadedKey != key || standby.playerError != null ||
            standby.playbackState != Player.STATE_READY || prelistenJob?.isActive == true) return
        val deck = standby
        val bend = bendFor(deck)
        bend.setGains(0f, 0f, 0f, 0f)
        deck.volume = 0f
        bend.beginMonitoring(0)
        deck.seekTo(0)
        deck.playbackParameters = PlaybackParameters(PRELISTEN_SPEED, 1f)
        deck.play()
        android.util.Log.i("PetalXF", "prelisten $key for ${mediaMs}ms")
        prelistenJob = scope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + mediaMs / PRELISTEN_SPEED.toLong() + 15_000
            while (deck === standby && preloadedKey == key && !crossfading &&
                deck.playerError == null && deck.playbackState != Player.STATE_ENDED &&
                bend.envelope.endMs < mediaMs && android.os.SystemClock.elapsedRealtime() < deadline) delay(100)
            val snapshot = bend.envelope.snapshot()?.let { Recording(it, bend.chroma.snapshot()) }
            if (deck === standby && !crossfading) settlePrelisten(deck)
            onDone(snapshot)
        }
    }

    /** Return a prelistened standby deck to its silent, native-speed start. */
    private fun settlePrelisten(deck: ExoPlayer) {
        prelistenJob?.cancel()
        prelistenJob = null
        deck.pause()
        deck.volume = 0f
        deck.playbackParameters = PlaybackParameters(1f)
        if (deck.currentPosition != 0L && deck.playbackState != Player.STATE_IDLE) {
            bendFor(deck).beginMonitoring(0)
            deck.seekTo(0)
        }
    }

    /** Prepare and beat-cue the incoming deck, then exchange independently
     * controlled frequency bands at a fixed matched tempo. */
    fun crossfadeTo(
        item: MediaItem,
        fadeMs: Int,
        outgoingSpeed: Float,
        startDelayMs: Long,
        seam: Boolean,
        eq: EqTransition.Context,
        onComplete: () -> Unit,
    ) {
        if (crossfading || released) {
            onComplete()
            return
        }
        crossfading = true
        prelistenJob?.cancel()
        prelistenJob = null
        val reusePreload = preloadedKey == item.mediaId && standby.playerError == null &&
            standby.playbackState != Player.STATE_IDLE && standby.playbackState != Player.STATE_ENDED
        // The standby deck is about to become the incoming track.
        preloadedKey = null
        preloadedIndex = -1
        crossfadeProgress = 0f
        incomingAttackGain = 0f
        transitionSeam = seam
        transitionEq = EqTransition.WAITING
        transitionDurationMs = fadeMs.coerceAtLeast(500)
        val nextIndex = index + 1
        if (nextIndex < playlist.size) playlist[nextIndex] = item else playlist.add(item)

        val outgoing = active
        val incoming = standby
        // A tempo glide left over from the last mix stops here; this mix sets the speeds.
        stopGlide()
        val dj = eq.dj
        // Half/double matches can land near 1.4x; don't silently clip the bend here.
        // A conforming mix leaves the outgoing at whatever speed it is playing now...
        val outSpeed = if (dj?.holdOutgoing == true) outgoing.playbackParameters.speed
            else outgoingSpeed.coerceIn(0.5f, 2f)
        // ...and stretches the incoming onto it instead.
        // A mix that ramps the outgoing's tempo inside the overlap matches at its final speed.
        val inSpeed = if (dj?.stretchIncoming == true)
            (dj.incomingBarMs * (dj.rampSpeed ?: outSpeed) / dj.outBarMs).toFloat().coerceIn(.9f, 1.1f) else 1f
        incoming.pause()
        incoming.volume = 0f
        val outBend = bendFor(outgoing)
        val inBend = bendFor(incoming)
        setBands(inBend, 0f, 0f, 0f, 0f)
        if (reusePreload) {
            // Always seek, even onto the same position: the preloaded deck already
            // queued PCM processed at its old (unity) gains, and a hard v2 entry would
            // play that split second before the silent lanes take over. A seek flushes it.
            bendFor(incoming).beginMonitoring(eq.incomingCueMs)
            incoming.seekTo(eq.incomingCueMs)
        } else {
            incoming.stop()
            bendFor(incoming).beginMonitoring(eq.incomingCueMs)
            incoming.setMediaItem(item, eq.incomingCueMs)
        }
        // Native key always; tempo is bent on one deck only.
        incoming.playbackParameters = PlaybackParameters(inSpeed, 1f)
        setBands(outBend, 1f, 1f, 1f, 1f)
        if (!reusePreload) incoming.prepare()
        android.util.Log.i(
            "PetalXF",
            "crossfade start outPlaying=${outgoing.isPlaying} outPos=${outgoing.currentPosition} " +
                "outDur=${outgoing.duration} outSpeed=$outSpeed inSpeed=$inSpeed delay=${startDelayMs}ms seam=$seam " +
                "reusePreload=$reusePreload cue=${eq.incomingCueMs}",
        )

        crossfadeJob = scope.launch {
            // Get the decoder ready before the beat clock starts ticking.
            val prepareDeadline = android.os.SystemClock.elapsedRealtime() + 15_000L
            while (incoming.playbackState != Player.STATE_READY) {
                if (incoming.playerError != null ||
                    android.os.SystemClock.elapsedRealtime() > prepareDeadline) {
                    PlaybackBus.publishError("AutoMix could not prepare the incoming track")
                    cancelCrossfade()
                    return@launch
                }
                delay(20)
            }
            playbackRequested.first { it }
            // Lock tempo before opening the incoming deck, then hold it stable.
            outgoing.playbackParameters = PlaybackParameters(outSpeed, 1f)
            val phase = eq.outgoingPhaseMs
            val downbeat = eq.outgoingDownbeatMs
            val mix = eq.dj
            // A composed mix starts on its chosen bar line; a late start keeps the
            // arrangement by moving whole bars. v1: a measured bar phase starts on
            // "1", a beat phase on the next beat (planned delays went stale).
            val targetPosition = when {
                mix != null -> {
                    val late = outgoing.currentPosition + 20 - mix.outStartMs
                    if (late <= 0) mix.outStartMs
                    else mix.outStartMs + (kotlin.math.ceil(late / mix.outBarMs) * mix.outBarMs).toLong()
                }
                downbeat != null && eq.outgoingBeatMs > 0f -> BeatAnalyzer.align(
                    outgoing.currentPosition + 1L, 15000f / eq.outgoingBeatMs, downbeat)
                phase != null && eq.outgoingBeatMs > 0f -> BeatAnalyzer.align(
                    outgoing.currentPosition + 1L, 60000f / eq.outgoingBeatMs, phase)
                else -> outgoing.currentPosition + startDelayMs
            }
            // The outgoing processor runs ahead of the playhead: arm it before bar 0.
            if (mix != null) outBend.automation = DeckAutomation(targetPosition.toDouble(), mix.outBarMs, mix.outgoing,
                mix.outStems, mix.clip)
            // Start a composed blend early by the learned output latency so the
            // incoming is heard on the bar line, not one latency after it.
            val lead = if (mix != null && mix.entryBar == 0)
                ((startLatencyMs + mix.prerollMs / inSpeed) * outSpeed).toLong() else 0L
            while (outgoing.currentPosition < targetPosition - lead &&
                outgoing.playbackState != Player.STATE_ENDED) {
                if (incoming.playerError != null) {
                    cancelCrossfade()
                    return@launch
                }
                delay(10)
            }
            // The entrance ramp hides old unity PCM left in the buffer.
            outgoing.volume = volume
            incoming.volume = 0f
            playbackRequested.first { it }
            if (mix != null) {
                if (!renderDj(mix, item, outgoing, incoming, outBend, inBend, targetPosition, outSpeed, inSpeed)) return@launch
            } else {
                val inStart = incoming.currentPosition
                // Loading ate some tail time, so recalculate against the incoming grid.
                val remaining = outgoing.duration.takeIf { it > 0 && it != C.TIME_UNSET }
                    ?.let { (it - outgoing.currentPosition).coerceAtLeast(0).toDouble() / outSpeed }
                val fade = MixTiming.durationMs(
                    requestedMs = transitionDurationMs,
                    availableMs = remaining,
                    incomingStartMs = inStart,
                    incomingCueMs = eq.incomingCueMs,
                    beatMs = eq.beatMs,
                    outro = seam,
                )
                transitionDurationMs = fade
                val adaptive = AdaptiveEqController(eq, fade)
                overlapStarted = true
                incoming.play()
                onOverlapStart?.invoke(item.mediaMetadata.artworkUri?.toString(), fade)
                android.util.Log.i("PetalXF", "overlap incoming=$inStart fade=$fade beat=${eq.beatMs}")
                // Native incoming media time owns the envelope, including pauses.
                var lastWall = 0f
                var ticks = 0
                while (crossfadeProgress < 1f) {
                    // Park the loop while paused instead of hammering the CPU.
                    if (!playWhenReady) playbackRequested.first { it }
                    delay(40)
                    if (incoming.playerError != null) {
                        PlaybackBus.publishError("AutoMix could not start the incoming track")
                        cancelCrossfade()
                        return@launch
                    }
                    if (!playWhenReady || (!incoming.isPlaying && incoming.playbackState != Player.STATE_ENDED)) continue
                    val mixWall = (incoming.currentPosition - inStart).coerceAtLeast(0).toFloat()
                    val ended = outgoing.playbackState == Player.STATE_ENDED ||
                        incoming.playbackState == Player.STATE_ENDED
                    val t = if (ended) 1f else (mixWall / fade).coerceIn(0f, 1f)
                    incomingAttackGain = if (ended) 1f else MixTiming.entryGain(mixWall, fade, eq.beatMs)
                    incoming.volume = volume * incomingAttackGain
                    val point = adaptive.update(
                        t, (mixWall - lastWall).coerceAtLeast(if (t == 1f) 40f else 0f),
                        if (outgoing.isPlaying) outBend.featuresAt(outgoing.currentPosition) else null,
                        inBend.featuresAt(incoming.currentPosition),
                    )
                    crossfadeProgress = t
                    applyGains(outBend, point.outgoing)
                    applyGains(inBend, point.incoming)
                    lastWall = mixWall
                    if (++ticks % 5 == 0 || t == 1f) transitionEq = adaptive.snapshot()
                    if (ticks % 25 == 0 || t == 1f) android.util.Log.i("PetalParts",
                        "t=$t ${transitionEq.label} out=${point.outgoing} in=${point.incoming} " +
                            "rms=${point.outgoingRms}/${point.incomingRms} incomingSpeed=1.0")
                }
            }
            // Handover: the incoming becomes live at its native speed instantly.
            active = incoming
            standby = outgoing
            index = nextIndex
            playWhenReady = true
            incoming.volume = volume
            if (inSpeed != 1f && dj != null) {
                // The new song keeps the tempo it was mixed at; it glides home only once
                // there is nothing left to hear it against.
                if (dj.glideBack) glideToNative(incoming, inSpeed, dj.outBarMs / outSpeed)
            } else incoming.playbackParameters = PlaybackParameters(1f)
            outgoing.stop()
            outgoing.volume = 0f
            outgoing.playbackParameters = PlaybackParameters(1f)
            setBands(outBend, 1f, 1f, 1f, 1f)
            setBands(inBend, 1f, 1f, 1f, 1f)
            // Only after the outgoing stopped: its arrangement had silenced it.
            outBend.automation = null
            inBend.automation = null
            // Swap the art before dropping progress, or the old cover flashes back.
            crossfading = false
            overlapStarted = false
            transitionSeam = false
            onOverlapEnd?.invoke()
            invalidateState()
            onComplete()
            crossfadeProgress = 0f
            crossfadeJob = null
            onSwapped?.invoke()
        }
    }

    /**
     * Play a composed v2 mix. Each deck's processor evaluates its own lanes on
     * its media clock; this loop starts the incoming at its entry bar, keeps the
     * beats locked during blends, records the commanded levels and decides when
     * the arrangement has finished. False when the mix was abandoned.
     */
    private suspend fun renderDj(
        mix: DjComposer.Mix,
        item: MediaItem,
        outgoing: ExoPlayer,
        incoming: ExoPlayer,
        outBend: EqBendProcessor,
        inBend: EqBendProcessor,
        outOrigin: Long,
        startSpeed: Float,
        inSpeed: Float,
    ): Boolean {
        val outBar = mix.outBarMs
        // A loop ride moves the outgoing from its start speed to rampSpeed between two mix
        // bars; the incoming enters after that, so it is timed at the final speed.
        val rampTo = mix.rampSpeed
        var outSpeed = rampTo ?: startSpeed
        var rampSet = startSpeed
        // Wall time of one bar at the speed the outgoing is actually playing, and the
        // incoming's bar in its own media time at the speed it was stretched to.
        val wallBar = outBar / outSpeed
        val inBar = wallBar * inSpeed
        val entry = mix.entryBar
        // The incoming's bar 0 is defined by its cue, not by a sampled playhead.
        val inOrigin = mix.incomingCueMs - entry * inBar
        val total = (mix.bars * wallBar).roundToInt()
        transitionDurationMs = total
        val label = "DJ · ${mix.label}"
        val o = mix.outgoing
        val n = mix.incoming
        /** Commanded levels at outgoing bar [outAt] / incoming bar [inBar]. */
        fun point(t: Float, outAt: Float, inBar: Float, outRms: Float, inRms: Float): EqTransition.Point {
            val dry = o.at(DjTransitionPlan.P.DRY, outAt)
            val inOn = if (inBar >= entry) 1f else 0f
            return EqTransition.Point(
                t,
                BandLevels(o.at(DjTransitionPlan.P.SUB, outAt) * dry, o.at(DjTransitionPlan.P.BASS, outAt) * dry,
                    o.at(DjTransitionPlan.P.MID, outAt) * dry, o.at(DjTransitionPlan.P.HIGH, outAt) * dry),
                BandLevels(n.at(DjTransitionPlan.P.SUB, inBar) * inOn, n.at(DjTransitionPlan.P.BASS, inBar) * inOn,
                    n.at(DjTransitionPlan.P.MID, inBar) * inOn, n.at(DjTransitionPlan.P.HIGH, inBar) * inOn),
                outRms, inRms,
                EqTransition.Fx(maxOf(o.at(DjTransitionPlan.P.HPF, outAt), o.at(DjTransitionPlan.P.LPF, outAt)),
                    o.at(DjTransitionPlan.P.SEND, outAt) * o.at(DjTransitionPlan.P.WET, outAt),
                    if (o.roll(outAt) != null || o.spin(outAt) != null) 1f else 0f,
                    maxOf(n.at(DjTransitionPlan.P.LPF, inBar), n.at(DjTransitionPlan.P.HPF, inBar))),
            )
        }
        // The arrangement is fixed, so show all of it now: build, drop and tail
        // appear ahead of the playhead and the live waveform fills in behind it.
        val planned = (0..240).map { k ->
            val t = k / 240f
            point(t, t * mix.bars, t * mix.bars, 0f, 0f)
        }
        val history = ArrayList<EqTransition.Point>()
        fun publish(t: Float) {
            transitionEq = EqTransition(label, history + planned.filter { it.progress > t })
        }
        history += planned[0]
        publish(0f)
        var started = false
        var ticks = 0
        var nudgeUntil = 0L
        var nextSync = 0L
        var corrections = 0
        var measuredLatency = false
        var entryChecked = false
        val preroll = mix.prerollMs
        val leadBars = ((startLatencyMs + preroll / inSpeed) / wallBar).toFloat()
        var prerollSettled = preroll == 0L
        var startedAt = 0L
        var nextTrim = 0L
        var seeks = 0
        var measuredSeeks = 0
        val readings = ArrayList<Double>()
        AutomixLog.i("PetalXF", "dj ${mix.label} swap=${mix.swapBar} entry=$entry bar=${"%.1f".format(wallBar)}ms " +
            "outOrigin=$outOrigin cue=${mix.incomingCueMs} speed out=$outSpeed in=$inSpeed sync=${mix.sync} " +
            "clock fix out=${"%.1f".format(outBend.originCorrectionMs)} in=${"%.1f".format(inBend.originCorrectionMs)}ms | ${mix.notes}")
        fun startIncoming() {
            started = true
            startedAt = android.os.SystemClock.elapsedRealtime()
            inBend.automation = DeckAutomation(inOrigin, inBar, mix.incoming, mix.inStems)
            overlapStarted = true
            incomingAttackGain = if (mix.hardEntry) 1f else 0f
            incoming.volume = volume * incomingAttackGain
            incoming.play()
            onOverlapStart?.invoke(item.mediaMetadata.artworkUri?.toString(),
                ((mix.bars - entry) * wallBar).toInt())
        }
        if (entry == 0) startIncoming()
        while (true) {
            if (!playWhenReady) playbackRequested.first { it }
            delay(if (!started) 5 else 40)
            if (incoming.playerError != null) {
                PlaybackBus.publishError("AutoMix could not start the incoming track")
                cancelCrossfade()
                return false
            }
            if (!playWhenReady) continue
            val outEnded = outgoing.playbackState == Player.STATE_ENDED
            val outAt = ((outgoing.currentPosition - outOrigin) / outBar).toFloat()
            // The tempo ride follows the outgoing's own bar position (smoothstep). Each step is
            // seamless (TempoStretchProcessor) but drains the sink, so steps are at least 0.25 %:
            // far below what anyone hears as a jump.
            if (rampTo != null && outAt >= mix.rampFromBar && rampSet != rampTo) {
                val u = ((outAt - mix.rampFromBar) / (mix.rampToBar - mix.rampFromBar)).coerceIn(0f, 1f)
                val target = startSpeed + (rampTo - startSpeed) * (u * u * (3 - 2 * u))
                if (u >= 1f || abs(target - rampSet) >= .0025f) {
                    rampSet = if (u >= 1f) rampTo else target
                    outgoing.playbackParameters = PlaybackParameters(rampSet, 1f)
                    if (u >= 1f) AutomixLog.i("PetalSync", "loop ride $startSpeed → $rampTo done at bar ${"%.2f".format(outAt)}")
                }
            }
            if (!started) {
                if (outAt >= entry - leadBars || outEnded) startIncoming() else {
                    val t = (outAt / mix.bars).coerceIn(0f, 1f)
                    crossfadeProgress = t
                    // The build plays before the incoming starts; keep drawing it.
                    if (t > history.last().progress) {
                        history += point(t, outAt, outAt, outBend.featuresAt(outgoing.currentPosition)?.rms ?: 0f, 0f)
                        if (++ticks % 3 == 0) publish(t)
                    }
                    continue
                }
            }
            if (!incoming.isPlaying && incoming.playbackState != Player.STATE_ENDED) continue
            val now = android.os.SystemClock.elapsedRealtime()
            // Muted pre-roll: measure where the incoming will be heard against the
            // outgoing's grid and correct it with one exact seek (the deck's clock
            // rebases on the seek's first decoded buffer). Speed trims re-buffered
            // the audio and shifted the reported position, so they are not used.
            if (!prerollSettled) {
                val rawBar = ((incoming.currentPosition - inOrigin) / inBar).toFloat()
                val left = (entry - rawBar) * wallBar - 120
                if (left <= 100 || outEnded) {
                    prerollSettled = true
                    AutomixLog.i("PetalSync", "pre-roll settled ${"%.0f".format(left + 120)}ms before the cue " +
                        "after $seeks seek(s)")
                } else if (now - startedAt >= 700 && now >= nextTrim && incoming.playbackState == Player.STATE_READY) {
                    // Right after a start or seek the reported position is estimated,
                    // not measured; take the median of three readings once it settles.
                    readings += BeatSync.errorMs(outgoing.currentPosition, outOrigin.toDouble(), outBar,
                        incoming.currentPosition, inOrigin, inBar, wallBar)
                    if (readings.size < 3) { nextTrim = now + 80; continue }
                    val error = readings.sorted()[1]
                    readings.clear()
                    // Before any seek, the offset is how much later the deck became audible
                    // than the lead allowed for: learn it so the next start needs no fix.
                    if (seeks == 0 && !measuredLatency) {
                        measuredLatency = true
                        startLatencyMs = (startLatencyMs + .7 * error).coerceIn(0.0, 300.0)
                        latencyStore.edit().putFloat("start_latency_ms", startLatencyMs.toFloat()).apply()
                        AutomixLog.i("PetalSync", "start err=${"%+.1f".format(error)}ms → start lead " +
                            "${"%.1f".format(startLatencyMs)}ms")
                    }
                    // What remains after a seek is how wrong the assumed seek time was.
                    if (seeks > 0 && seeks == measuredSeeks + 1) {
                        measuredSeeks = seeks
                        seekLatencyMs = (seekLatencyMs + .7 * error).coerceIn(0.0, 400.0)
                        latencyStore.edit().putFloat("seek_latency_ms", seekLatencyMs.toFloat()).apply()
                    }
                    // A seek needs ~0.7 s to resume and be re-measured before the cue.
                    if (abs(error) > 8 && seeks < 3 && left > 950) {
                        // + = outgoing ahead: move the incoming forward by the error plus
                        // the time it stays frozen while the seek rebuffers.
                        val target = (incoming.currentPosition + (error + seekLatencyMs) * inSpeed).toLong().coerceAtLeast(0)
                        inBend.beginMonitoring(target)
                        incoming.seekTo(target)
                        seeks++
                        AutomixLog.i("PetalSync", "pre-roll err=${"%+.1f".format(error)}ms → seek incoming to $target " +
                            "(seek latency ${"%.0f".format(seekLatencyMs)}ms)")
                        // Measure only once it has resumed and settled.
                        nextTrim = now + 700
                    } else {
                        AutomixLog.i("PetalSync", "pre-roll err=${"%+.1f".format(error)}ms ok")
                        nextTrim = now + 300
                    }
                }
                if (!prerollSettled) {
                    // Keep the graph moving on the outgoing's clock while the incoming is muted.
                    val t = (outAt / mix.bars).coerceIn(0f, 1f)
                    crossfadeProgress = t
                    if (t > history.last().progress) {
                        history += point(t, outAt, outAt, outBend.featuresAt(outgoing.currentPosition)?.rms ?: 0f, 0f)
                        if (++ticks % 3 == 0) publish(t)
                    }
                    continue
                }
            }
            val inPosition = incoming.currentPosition
            val bar = ((inPosition - inOrigin) / inBar).toFloat().coerceAtLeast(entry.toFloat())
            val ended = outEnded || incoming.playbackState == Player.STATE_ENDED
            val t = if (ended) 1f else (bar / mix.bars).coerceIn(0f, 1f)
            if (!mix.hardEntry) {
                incomingAttackGain = if (ended) 1f else
                    MixTiming.entryGain(((bar - entry) * wallBar).toFloat(), total, (wallBar / 4).toFloat())
                incoming.volume = volume * incomingAttackGain
            }
            // The first settled measurement shows how late the incoming became
            // audible; future starts lead by that much.
            if (!entryChecked && !outEnded && bar >= entry + .25f && nudgeUntil == 0L) {
                entryChecked = true
                val error = BeatSync.errorMs(outgoing.currentPosition, outOrigin.toDouble(), outBar,
                    incoming.currentPosition, inOrigin, inBar, wallBar)
                // Learn here only when the muted lead-in could not measure it.
                if (!measuredLatency) {
                    measuredLatency = true
                    startLatencyMs = (startLatencyMs + .6 * error).coerceIn(0.0, 300.0)
                    latencyStore.edit().putFloat("start_latency_ms", startLatencyMs.toFloat()).apply()
                }
                AutomixLog.i("PetalSync", "entry err=${"%+.1f".format(error)}ms (pre-roll ${preroll}ms) → start lead " +
                    "${"%.1f".format(startLatencyMs)}ms")
            }
            // Ride the beat while both drum sections are audible.
            if (mix.sync && !outEnded) {
                if (nudgeUntil in 1..now) {
                    outgoing.playbackParameters = PlaybackParameters(outSpeed, 1f)
                    nudgeUntil = 0L
                    nextSync = now + 1200
                }
                if (nudgeUntil == 0L && now >= nextSync && bar >= entry + .1f && bar < mix.bars - .5f && corrections < 8) {
                    val error = BeatSync.errorMs(outgoing.currentPosition, outOrigin.toDouble(), outBar,
                        incoming.currentPosition, inOrigin, inBar, wallBar)
                    val nudge = BeatSync.nudge(error, outSpeed)
                    AutomixLog.i("PetalSync", "bar=${"%.2f".format(bar)} err=${"%+.1f".format(error)}ms" +
                        (nudge?.let { " → speed ${"%.4f".format(it.speed)} for ${it.durationMs}ms" } ?: " ok"))
                    if (nudge != null) {
                        outgoing.playbackParameters = PlaybackParameters(nudge.speed, 1f)
                        nudgeUntil = now + nudge.durationMs
                        corrections++
                    } else nextSync = now + 2000
                }
            }
            crossfadeProgress = t
            val point = point(t, outAt, bar, outBend.featuresAt(outgoing.currentPosition)?.rms ?: 0f,
                inBend.featuresAt(inPosition)?.rms ?: 0f)
            if (history.size >= 2048) {
                val reduced = history.filterIndexed { index, _ -> index % 2 == 0 }
                history.clear()
                history.addAll(reduced)
            }
            history += point
            if (++ticks % 5 == 0 || t == 1f) publish(t)
            if (ticks % 25 == 0 || t == 1f) android.util.Log.i("PetalParts",
                "dj t=$t bar=$bar out=${point.outgoing} in=${point.incoming} fx=${point.fx}")
            if (t >= 1f) {
                outgoing.playbackParameters = PlaybackParameters(outSpeed, 1f)
                return true
            }
        }
    }

    // ---- tempo glide after a conformed mix -----------------------------------------------

    private var glideJob: Job? = null

    /**
     * Stop a glide or ride. A glide moves the stretcher directly, so one cut short leaves the
     * deck's parameters at the old speed: set them to what is actually playing, or a later
     * change back to that old value would be a no-op and the deck would stay where it stopped.
     */
    private fun stopGlide() {
        glideJob?.cancel()
        glideJob = null
        for (deck in arrayOf(slotA, slotB)) {
            val live = tempoFor(deck).speed
            if (kotlin.math.abs(deck.playbackParameters.speed - live) > 1e-4f) {
                deck.playbackParameters = PlaybackParameters(live, 1f)
            }
        }
    }

    /** The live deck's actual tempo (a mix may hold it stretched); [getPlaybackParameters] is the user's. */
    val deckSpeed: Float get() = active.playbackParameters.speed.let { set ->
        // A glide moves the stretcher directly; while it runs, that is the deck's real tempo.
        if (glideJob?.isActive == true) tempoFor(active).speed else set
    }

    /**
     * Return a deck that was stretched for a mix to its native tempo: a beat's hold, then
     * an eased glide over [bars] bars in fine steps (16 a bar). Device feedback: 1 bar of hold
     * plus 8 bars (~30 s at 70 BPM) left the song off its tempo too long; home should come
     * smoothly but quickly. Stops as soon as the deck stops being the live one or another
     * mix starts.
     */
    private fun glideToNative(deck: ExoPlayer, from: Float, barMs: Double, bars: Int = 2) {
        stopGlide()
        glideJob = scope.launch {
            delay((barMs / 4).toLong())
            val steps = bars * 16
            for (k in 1..steps) {
                if (deck !== active || crossfading || released) return@launch
                if (!playWhenReady) playbackRequested.first { it }
                // Smoothstep: eases out of the mix tempo and into the native one, no corner at either end.
                val t = k.toFloat() / steps
                val eased = t * t * (3f - 2f * t)
                val speed = from + (1f - from) * eased
                // Straight to the stretcher: a playbackParameters change drains the sink and
                // restarts the stretch on every step, which came out choppy over a glide.
                tempoFor(deck).setSpeed(speed)
                delay((barMs / 16 * from / speed).toLong())
            }
            // Home: one real parameter change, at a speed the stretcher is already playing.
            deck.playbackParameters = PlaybackParameters(1f)
            AutomixLog.i("PetalXF", "tempo glide from $from back to native over $bars bars")
        }
    }

    /**
     * Ride the live deck's tempo to [target] over [wallMs] before a mix, like a DJ easing
     * the pitch fader: linear in speed, one small step every quarter second (well under
     * 0.05 % each), so the change is not heard. Cancelled by a mix starting or a new song.
     */
    fun rideActive(target: Float, wallMs: Long) {
        if (released || crossfading) return
        val deck = active
        stopGlide()
        val from = deck.playbackParameters.speed
        if (kotlin.math.abs(target - from) < 1e-4f || wallMs < 1000) return
        glideJob = scope.launch {
            val steps = (wallMs / 250).toInt().coerceAtLeast(1)
            for (k in 1..steps) {
                if (deck !== active || crossfading || released) return@launch
                if (!playWhenReady) playbackRequested.first { it }
                deck.playbackParameters = PlaybackParameters(from + (target - from) * k / steps, 1f)
                delay(250)
            }
            AutomixLog.i("PetalXF", "tempo ride $from → $target over ${wallMs}ms")
        }
    }

    // ---- gapless preload ----------------------------------------------------------------

    // Buffer the next tune quietly so handover doesn't wait on the network.
    fun preload(item: MediaItem, positionMs: Long = 0L) {
        if (released || crossfading) return
        val key = item.mediaId
        if (preloadedKey == key && standby.playbackState != Player.STATE_IDLE) return
        prelistenJob?.cancel()
        prelistenJob = null
        cancelPreload()
        // stop() kept the old play request; pause before this deck runs off.
        standby.pause()
        // Shut the output before asking the decoder for anything.
        standby.volume = 0f
        bendFor(standby).beginMonitoring(positionMs)
        standby.setMediaItem(item, positionMs.coerceAtLeast(0))
        standby.playbackParameters = PlaybackParameters(1f)
        standby.prepare()
        preloadedKey = key
        preloadedIndex = index + 1
        invalidateState()
    }

    /** True when [key] is buffered and ready to take over. */
    fun hasPreloaded(key: String): Boolean =
        !crossfading && preloadedKey == key && standby.playbackState == Player.STATE_READY &&
            standby.playerError == null

    fun cancelPreload() {
        prelistenJob?.cancel()
        prelistenJob = null
        if (preloadedKey == null) return
        standby.pause()
        standby.stop()
        preloadedKey = null
        preloadedIndex = -1
    }

    /** Swap to the preloaded deck with no re-prepare; false when not ready. */
    fun promotePreloaded(): Boolean {
        if (crossfading || released) return false
        if (preloadedKey == null) return false
        if (standby.playbackState != Player.STATE_READY || standby.playerError != null) return false
        if (prelistenJob != null) settlePrelisten(standby)
        android.util.Log.i("PetalXF",
            "promote key=$preloadedKey standbyPos=${standby.currentPosition} outgoingPos=${active.currentPosition}")
        stopGlide()
        val outgoing = active
        active = standby
        standby = outgoing
        if (preloadedIndex in playlist.indices) index = preloadedIndex
        preloadedKey = null
        preloadedIndex = -1
        playWhenReady = true
        active.volume = volume
        active.playbackParameters = PlaybackParameters(1f)
        setBands(bendFor(active), 1f, 1f, 1f, 1f)
        outgoing.pause()
        outgoing.stop()
        outgoing.volume = 0f
        outgoing.playbackParameters = PlaybackParameters(1f)
        setBands(bendFor(outgoing), 1f, 1f, 1f, 1f)
        active.play()
        invalidateState()
        onSwapped?.invoke()
        return true
    }

    private fun setBands(b: EqBendProcessor, sub: Float, bass: Float, mid: Float, high: Float) {
        b.setGains(sub, bass, mid, high)
    }

    private fun applyGains(processor: EqBendProcessor, gains: BandLevels) {
        processor.setGains(gains.sub, gains.bass, gains.mid, gains.high)
    }

    // ---- SimpleBasePlayer -----------------------------------------------------------------

    override fun getState(): State {
        val cur = active
        // Don't report the outgoing end and trigger a skip halfway through the blend.
        val stateSource = if (overlapStarted) standby else active
        val durationMs = cur.duration
        val items = playlist.mapIndexed { i, item ->
            val itemDurationUs = if (i == index && durationMs > 0) durationMs * 1000 else C.TIME_UNSET
            MediaItemData.Builder(i)
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(itemDurationUs)
                .setIsSeekable(i == index)
                .setPeriods(
                    listOf(
                        PeriodData.Builder(i)
                            .setDurationUs(itemDurationUs)
                            .build(),
                    ),
                )
                .build()
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (released) Player.STATE_IDLE else stateSource.playbackState)
            .setPlayerError(if (released) null else stateSource.playerError)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .setPlaybackParameters(playbackParameters)
            .setVolume(volume)
            .setIsLoading(!released && stateSource.isLoading)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMaxSeekToPreviousPositionMs(5_000)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)))
            .setContentPositionMs(PositionSupplier { if (released) 0L else cur.currentPosition })
            .setContentBufferedPositionMs(
                PositionSupplier { if (released) 0L else cur.bufferedPosition },
            )
            .setTotalBufferedDurationMs(PositionSupplier { 0L })
            .build()
    }

    /** Public kill switch for a running blend; AutoMix got switched off. */
    fun abortTransition() = cancelCrossfade()

    /** Abort an in-flight blend and settle on the outgoing slot alone. */
    private fun cancelCrossfade() {
        active.playbackParameters = playbackParameters
        crossfadeJob?.cancel()
        crossfadeJob = null
        if (!crossfading) return
        val wasOverlap = overlapStarted
        bendA.automation = null
        bendB.automation = null
        standby.stop()
        standby.volume = 0f
        active.volume = volume
        active.playbackParameters = playbackParameters
        setBands(bendFor(active), 1f, 1f, 1f, 1f)
        setBands(bendFor(standby), 1f, 1f, 1f, 1f)
        crossfading = false
        overlapStarted = false
        if (wasOverlap) onOverlapEnd?.invoke()
        crossfadeProgress = 0f
        transitionSeam = false
        invalidateState()
    }

    private fun loadActive(positionMs: Long) {
        if (playlist.isEmpty()) {
            active.stop()
            return
        }
        android.util.Log.i("PetalXF", "loadActive idx=$index pos=$positionMs playWhenReady=$playWhenReady")
        active.stop()
        // A new song never inherits the last mix's stretch.
        stopGlide()
        active.playbackParameters = playbackParameters
        bendFor(active).beginMonitoring(positionMs)
        active.setMediaItem(playlist[index], positionMs.coerceAtLeast(0))
        active.prepare()
        active.volume = volume
        if (playWhenReady) active.play()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<Void> {
        this.playWhenReady = playWhenReady
        // Pause both decks, mate; the incoming one is audible too.
        if (playWhenReady) {
            active.play()
            if (overlapStarted) standby.play()
        } else {
            active.pause()
            standby.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<Void> {
        active.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<Void> {
        cancelCrossfade()
        cancelPreload()
        active.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<Void> {
        released = true
        scope.cancel()
        slotA.release()
        slotB.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<Void> {
        this.volume = volume.coerceIn(0f, 1f)
        active.volume = this.volume
        if (overlapStarted) {
            standby.volume = this.volume * incomingAttackGain
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<Void> {
        this.repeatMode = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<Void> {
        this.shuffleModeEnabled = shuffleModeEnabled
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): ListenableFuture<Void> {
        this.playbackParameters = playbackParameters
        active.playbackParameters = playbackParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<Void> {
        // A manual skip/replace must not leave the old blend running.
        cancelCrossfade()
        cancelPreload()
        playlist.clear()
        playlist.addAll(mediaItems)
        index = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        loadActive(startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<Void> {
        val at = index.coerceIn(0, playlist.size)
        playlist.addAll(at, mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<Void> {
        if (fromIndex < 0 || toIndex > playlist.size || fromIndex >= toIndex) {
            return Futures.immediateVoidFuture()
        }
        // Removing shifts the preloaded index; drop it rather than swap wrong.
        cancelPreload()
        repeat(toIndex - fromIndex) { playlist.removeAt(fromIndex) }
        index = index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<Void> {
        val moved = playlist.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) { playlist.removeAt(fromIndex) }
        playlist.addAll(newIndex.coerceIn(0, playlist.size), moved)
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<Void> {
        if (fromIndex < 0 || toIndex > playlist.size || fromIndex > toIndex) {
            return Futures.immediateVoidFuture()
        }
        repeat(toIndex - fromIndex) { playlist.removeAt(fromIndex) }
        playlist.addAll(fromIndex, mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<Void> {
        android.util.Log.i(
            "PetalXF",
            "handleSeek idx=$mediaItemIndex index=$index pos=$positionMs cmd=$seekCommand " +
                "playlist=$playlist size=${playlist.size}",
        )
        cancelCrossfade()
        cancelPreload()
        if (mediaItemIndex in playlist.indices) {
            if (mediaItemIndex == index) {
                bendFor(active).beginMonitoring(positionMs)
                active.seekTo(positionMs)
            } else {
                index = mediaItemIndex
                loadActive(positionMs)
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val PRELISTEN_SPEED = 3f
        private val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            )
            .build()
    }
}

/** Injects an [EqBendProcessor] into one player's audio chain (integer path). */
private class BendRenderersFactory(
    context: android.content.Context,
    private val bend: EqBendProcessor,
    private val tempo: TempoStretchProcessor,
) : DefaultRenderersFactory(context) {
    // Report every rendered buffer's presentation time, so the processor's media
    // clock starts where a seek actually resumed rather than where it was aimed.
    override fun buildAudioRenderers(
        context: android.content.Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        out.add(object : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
            context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, audioSink,
        ) {
            override fun processOutputBuffer(
                positionUs: Long, elapsedRealtimeUs: Long,
                codec: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter?, buffer: java.nio.ByteBuffer?,
                bufferIndex: Int, bufferFlags: Int, sampleCount: Int, bufferPresentationTimeUs: Long,
                isDecodeOnlyBuffer: Boolean, isLastBuffer: Boolean, format: androidx.media3.common.Format,
            ): Boolean {
                // Renderer timestamps carry the stream offset; subtract it for media time.
                if (!isDecodeOnlyBuffer) bend.noteBufferTime(bufferPresentationTimeUs - outputStreamOffsetUs)
                return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
                    bufferFlags, sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format)
            }
        })
    }

    override fun buildAudioSink(
        context: android.content.Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // Time-stretch in software, never the AudioTrack resampler: the tempo bend
        // must change speed only, never pitch. The sink drains on every speed change;
        // TempoStretchProcessor (not Sonic, which padded each drain with silence and
        // clicked) makes that seamless, so rides and glides may step as often as they like.
        .setEnableAudioTrackPlaybackParams(false)
        .setAudioTrackBufferSizeProvider(MixBufferSizeProvider)
        .setAudioProcessorChain(DeckAudioChain(bend, tempo))
        .build()
}
