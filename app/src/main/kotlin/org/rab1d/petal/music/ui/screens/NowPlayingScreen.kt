/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.playback.PlaybackBus
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.GlassLevel
import org.rab1d.petal.music.ui.components.GlyphButton
import org.rab1d.petal.music.ui.components.HeartGlyph
import org.rab1d.petal.music.ui.components.HeardWaveform
import org.rab1d.petal.music.ui.components.HeroText
import org.rab1d.petal.music.ui.components.LiveLevel
import org.rab1d.petal.music.ui.components.LocalAddToPlaylist
import org.rab1d.petal.music.ui.components.MixWaveform
import org.rab1d.petal.music.ui.components.PetalIcons
import org.rab1d.petal.music.ui.components.PlayPauseGlyph
import org.rab1d.petal.music.ui.components.SourceBadge
import org.rab1d.petal.music.ui.components.backdropSource
import org.rab1d.petal.music.ui.components.drawSleeveLight
import org.rab1d.petal.music.ui.components.glass
import org.rab1d.petal.music.ui.components.pressable
import org.rab1d.petal.music.ui.components.pullUp
import org.rab1d.petal.music.ui.components.rememberArt2x
import org.rab1d.petal.music.ui.components.rememberBackdrop
import org.rab1d.petal.music.ui.components.rememberHeardWaveform
import org.rab1d.petal.music.ui.components.rememberLiveLevel
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.LocalNowPlayingTheme
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space
import org.rab1d.petal.music.ui.theme.rememberArtTheme
import kotlin.math.abs

/**
 * The player. The cover lights the whole screen (its colours, placed where they sit on
 * the sleeve), a blurred copy of it fills the room, and the controls sit on a glass deck
 * over that light. Always night roles: it is lit by the art in either theme.
 */
@Composable
fun NowPlayingScreen(
    viewModel: PetalViewModel,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit = {},
    artModifier: Modifier = Modifier,
    /** False while the player is animating away. */
    open: Boolean = true,
) {
    org.rab1d.petal.music.ui.theme.NightSurface {
        NowPlayingContent(viewModel, onClose, onOpenQueue, artModifier, open)
    }
}

@Composable
private fun NowPlayingContent(
    viewModel: PetalViewModel,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    artModifier: Modifier,
    open: Boolean,
) {
    var showLyrics by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        PlayerBack.handler = {
            when {
                sheet != null -> { sheet = null; true }
                showLyrics -> { showLyrics = false; true }
                else -> false
            }
        }
        onDispose { PlayerBack.handler = null }
    }
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val track = state.current
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val isFavorite = track != null && favorites.contains(track.key())
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current

    // While a mix runs, the incoming cover's light rises through the outgoing one.
    val artDiffers = state.next?.artUri != null && state.next?.artUri != track?.artUri
    val blend = if (state.transitionProgress > 0f && artDiffers) state.transitionProgress.coerceIn(0f, 1f) else 0f
    val nowTheme = LocalNowPlayingTheme.current
    val nextTheme = rememberArtTheme(state.next?.artUri, seed = state.next?.let { "${it.title} · ${it.artist}" })
    val theme = if (blend > 0f) ArtTheme.lerp(nowTheme, nextTheme, blend) else nowTheme

    // The glow and the title's breath: 30 a second is smooth for light, half a 60 Hz screen's work.
    val level = rememberLiveLevel(active = state.playing, hz = 30)

    var positionMs by remember { mutableLongStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.current?.key(), state.pumpActive, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                positionMs = PlaybackBus.positionProvider?.invoke()
                    ?: viewModel.client?.controller?.currentPosition
                    ?: PlaybackBus.pumpPositionProvider?.invoke() ?: 0
                delay(250)
            }
        }
    }
    // Track metadata can be unknown for some sources; fall back to the player's own
    // duration so the bar is always seekable.
    val duration = (PlaybackBus.durationProvider?.invoke() ?: 0L).takeIf { it > 0 }
        ?: (track?.durationMs ?: 0L).takeIf { it > 0 }
        ?: (viewModel.client?.controller?.duration ?: 0L)
    val wave = rememberHeardWaveform(
        track?.key(), level,
        fraction = {
            val d = duration
            if (d > 0) ((PlaybackBus.positionProvider?.invoke() ?: positionMs).toFloat() / d) else -1f
        },
        playing = state.playing,
    )
    // Files on the phone get their real waveform, decoded once; streams keep the heard one.
    val downloads by viewModel.downloads.collectAsState()
    val wavePath = track?.localPath ?: track?.let { t ->
        downloads.firstOrNull { it.id == t.id && it.source == t.source.id }?.playbackFile()?.path
    }
    val realWave = org.rab1d.petal.music.ui.components.rememberTrackWaveform(track?.key(), wavePath)

    val roomBackdrop = rememberBackdrop()
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Pull down anywhere: the whole player follows the finger, and lets go past 120dp or on
    // a downward flick. Children that drag sideways (the scrubber) or scroll (lyrics) keep theirs.
    // The offset is written straight from the drag (no coroutine per step, which let a late
    // step cancel the spring back and left the player stuck halfway when swiped fast).
    var dismissY by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Reopened while it was still closing: start from the top again.
    LaunchedEffect(open) {
        if (open) {
            settle?.cancel()
            androidx.compose.animation.core.animate(dismissY, 0f, animationSpec = Motion.drag()) { v, _ -> dismissY = v }
        }
    }
    val pull = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        dismissY = (dismissY + delta).coerceAtLeast(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dismissY }
            .draggable(
                pull,
                androidx.compose.foundation.gestures.Orientation.Vertical,
                enabled = sheet == null && open,
                onDragStarted = { settle?.cancel() },
                onDragStopped = { velocity ->
                    val far = dismissY > with(density) { 120.dp.toPx() }
                    if (far || velocity > with(density) { 1200.dp.toPx() }) onClose()
                    else settle = scope.launch {
                        androidx.compose.animation.core.animate(dismissY, 0f, velocity, Motion.drag()) { v, _ -> dismissY = v }
                    }
                },
            )
            .background(theme.base),
    ) {
        // ---- the room: sleeve light + a blurred, enlarged copy of the cover -------------
        Box(Modifier.fillMaxSize().backdropSource(roomBackdrop)) {
            Canvas(Modifier.fillMaxSize()) { drawSleeveLight(theme, if (reduced) 0f else level.level) }
            // The blurred room: a 24 px copy of the cover, stretched with bilinear filtering and
            // softened a little. It reads the same as a big blur of the full cover at a sliver of
            // the cost (a 72dp blur over a full-screen 2x cover brought the emulator's GPU down).
            RoomArt(track?.artUri, alpha = .42f * (1f - blend))
            if (blend > 0f) RoomArt(state.next?.artUri, alpha = .42f * blend)
            // A floor of the base under the deck, so the controls always sit on a known colour.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        .55f to Color.Transparent,
                        1f to theme.base.copy(alpha = .72f),
                    ),
                ),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(Modifier.fillMaxSize()) {
                TopRow(track, theme, onClose, onSleep = { sheet = PlayerSheet.Sleep })

                if (showLyrics && track != null) {
                    // Lyrics take the cover's place and the space it had.
                    LyricsPanel(
                        viewModel = viewModel,
                        track = track,
                        positionMs = { positionMs },
                        theme = theme,
                        onSeek = { viewModel.seekTo(it) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    // ---- the cover -------------------------------------------------------------
                    // The cover takes whatever height is left once the title, scrubber and deck
                    // have theirs, so nothing below it is ever pushed under the scrubber.
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = Space.s),
                        contentAlignment = Alignment.Center,
                    ) {
                        val artSize = minOf(maxWidth - 48.dp, maxHeight)
                        if (artSize >= 72.dp) Cover(
                            track = track,
                            next = state.next,
                            blend = blend,
                            theme = theme,
                            level = level,
                            onDismiss = onClose,
                            modifier = artModifier.size(artSize),
                        )
                    }

                    // ---- title ----------------------------------------------------------------
                    TitleBlock(
                        track = track,
                        theme = theme,
                        level = level,
                        audioLabel = state.audio?.label.orEmpty(),
                        onBrowse = onClose,
                    )
                }

                MixReadout(state, theme)
                Spacer(Modifier.height(Space.l))

                // ---- scrubber --------------------------------------------------------------
                // The playhead is passed as a lambda: only what reads it recomposes as it moves.
                Scrubber(
                    positionMs = { positionMs },
                    durationMs = duration,
                    wave = wave,
                    realWave = realWave,
                    theme = theme,
                    state = state,
                    onSeek = { ms -> viewModel.seekTo(ms) },
                )

                // ---- the deck --------------------------------------------------------------
                Deck(
                    backdrop = roomBackdrop,
                    theme = theme,
                    state = state,
                    automix = settings.automixEnabled,
                    favorite = isFavorite,
                    onShuffle = { viewModel.toggleShuffle() },
                    onPrevious = { viewModel.previous() },
                    onPlayPause = { viewModel.playPause() },
                    onNext = { viewModel.next() },
                    onRepeat = { viewModel.setRepeat((state.repeatMode + 1) % 3) },
                    onFavorite = { track?.let { viewModel.toggleFavorite(it) } },
                    onAutomix = viewModel::toggleAutomix,
                    onAddTo = track?.let { t -> LocalAddToPlaylist.current?.let { add -> { add(t) } } },
                    lyricsOn = showLyrics,
                    onLyrics = { showLyrics = !showLyrics },
                    onMore = { sheet = PlayerSheet.More },
                    onQueue = onOpenQueue,
                )
            }
        }

        PlayerSheetHost(
            sheet = sheet,
            onDismiss = { sheet = null },
            onOpen = { sheet = it },
            backdrop = roomBackdrop,
            theme = theme,
            track = track,
            viewModel = viewModel,
            onShare = { track?.let { shareTrack(context, it) } },
            onOutput = { openOutputSwitcher(context) },
        )
    }
}

// ---- pieces ----------------------------------------------------------------------------

@Composable
private fun TopRow(track: Track?, theme: ArtTheme, onClose: () -> Unit, onSleep: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs),
    ) {
        GlyphButton(onClick = onClose, label = "Close player") {
            Icon(PetalIcons.Down, null, tint = theme.ink, modifier = Modifier.size(26.dp))
        }
        SleepReadout(theme, onSleep)
        Spacer(Modifier.weight(1f))
        if (track != null) {
            // Where it plays from: the source, and the one serving it when they differ.
            SourceBadge(track.source, size = 18, origin = track.originSource)
            Text(
                if (track.originSource != null && track.originSource != track.source)
                    "${track.originSource.label} via ${track.source.label}" else track.source.label,
                color = theme.text2,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = Space.s, end = Space.l),
            )
        }
    }
}

@Composable
private fun Cover(
    track: Track?,
    next: Track?,
    blend: Float,
    theme: ArtTheme,
    level: LiveLevel,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val baseArt = rememberArt2x(track?.artUri)
    val nextArt = rememberArt2x(next?.artUri)
    Box(
        modifier
            // The light behind the sleeve breathes with the deck's level: the glow comes
            // from the artwork's own accent, never a decorative orb.
            .drawBehind {
                val g = if (reduced) 0f else level.level
                val r = size.maxDimension * (.62f + .10f * g)
                drawCircle(
                    Brush.radialGradient(
                        0f to theme.accentOnArt.copy(alpha = .30f + .30f * g),
                        .6f to theme.accentOnArt.copy(alpha = .08f + .10f * g),
                        1f to Color.Transparent,
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                )
            }
            .graphicsLayer {
                rotationX = tiltX.value
                rotationY = tiltY.value
                cameraDistance = 14f * density
            }
            // A real shadow, tinted by the cover's own dark colour.
            .shadow(28.dp, Radius.sleeveHero, ambientColor = theme.darkVibrant, spotColor = theme.darkVibrant)
            .clip(Radius.sleeveHero)
            .pointerInput(reduced) {
                if (reduced) return@pointerInput
                val max = 9f
                // Watches the finger without consuming it: the tilt follows, and the same drag
                // still reaches the player's pull-down.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val amount = change.position - change.previousPosition
                        scope.launch {
                            tiltY.snapTo((tiltY.value + amount.x / size.width * 30f).coerceIn(-max, max))
                            tiltX.snapTo((tiltX.value - amount.y / size.height * 30f).coerceIn(-max, max))
                        }
                    }
                    scope.launch { tiltX.animateTo(0f, Motion.drag()) }
                    scope.launch { tiltY.animateTo(0f, Motion.drag()) }
                }
            }
            .semantics { contentDescription = track?.let { "Cover of ${it.album.ifBlank { it.title }}" } ?: "No cover" },
    ) {
        org.rab1d.petal.music.ui.components.CoverArt(
            null, Modifier.fillMaxSize(), Radius.sleeveHero,
            seed = track?.let { "${it.title} · ${it.artist}" },
        )
        if (baseArt != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(baseArt).size(coil.size.Size.ORIGINAL).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The signature moment: during a mix the incoming sleeve rises through the outgoing.
        if (blend > 0f && nextArt != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(nextArt).size(coil.size.Size.ORIGINAL).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = blend,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val grow = 1f + .06f * (1f - blend)
                        scaleX = grow
                        scaleY = grow
                    },
            )
        }
        // The light falls off at the bottom, where the title overlaps the sleeve.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(.72f to Color.Transparent, 1f to theme.base.copy(alpha = .78f)),
            ),
        )
    }
}

@Composable
private fun TitleBlock(
    track: Track?,
    theme: ArtTheme,
    level: LiveLevel,
    audioLabel: String,
    onBrowse: () -> Unit,
) {
    val lift = Shadow(theme.base.copy(alpha = .55f), Offset(0f, 2f), 18f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.l)
            .padding(horizontal = Space.xl),
    ) {
        HeroText(
            text = track?.title ?: "Nothing's playing",
            color = theme.ink,
            style = MaterialTheme.typography.displayMedium.copy(shadow = lift),
            maxLines = 2,
        )
        if (track != null && track.artist.isNotBlank()) {
            org.rab1d.petal.music.ui.components.ArtistLink(
                track = track,
                style = MaterialTheme.typography.headlineMedium.copy(shadow = lift),
                color = theme.accentOnArt,
                modifier = Modifier.padding(top = Space.s),
            )
        } else if (track == null) {
            // Say what to do, and give the way there: closing the player lands on the library.
            Text("Choose a song, album or playlist and it plays here.", style = MaterialTheme.typography.bodyLarge,
                color = theme.text2, modifier = Modifier.padding(top = Space.s))
            org.rab1d.petal.music.ui.components.PillButton("Browse music", onBrowse, Modifier.padding(top = Space.l))
        }
        if (track != null) MetaRow(track, audioLabel, theme)
    }
}

/** Album · year · genre, then the badges: format, tempo and key. Only what's known. */
@Composable
private fun MetaRow(track: Track, audioLabel: String, theme: ArtTheme) {
    val line = listOfNotNull(
        track.album.ifBlank { null },
        track.year.takeIf { it > 0 }?.toString(),
        track.genre?.ifBlank { null },
    ).joinToString(" · ")
    if (line.isNotEmpty()) {
        Text(line, color = theme.text2, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
    }
    val label = audioLabel
    val codec = label.substringBefore(" ·").uppercase()
    val rate = Regex("([0-9.]+) kHz").find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    val badge = when {
        codec in setOf("FLAC", "ALAC") && rate > 48f -> "Hi-Res Lossless"
        codec in setOf("FLAC", "ALAC") -> "Lossless"
        else -> null
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Space.m),
    ) {
        if (track.explicit) org.rab1d.petal.music.ui.components.ExplicitBadge(color = theme.ink)
        badge?.let { Badge(it, theme, strong = true) }
        if (label.isNotEmpty()) Text(label, style = PetalType.time, color = theme.text3, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        track.bpm?.takeIf { it > 0 }?.let { Badge("${it.toInt()} BPM", theme) }
        (track.musicalKey ?: track.camelot?.let { "${it}A" })?.let { Badge(it, theme) }
    }
}

@Composable
private fun Badge(text: String, theme: ArtTheme, strong: Boolean = false) {
    Text(
        text,
        style = if (strong) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold) else PetalType.time,
        color = if (strong) theme.onAccent else theme.text2,
        modifier = Modifier
            .clip(Radius.tile)
            .background(if (strong) theme.accentOnArt else theme.ink.copy(alpha = .10f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** The mix, stated plainly: when it starts and what the EQ does. */
@Composable
private fun MixReadout(state: PlaybackBus.State, theme: ArtTheme) {
    AnimatedVisibility(
        visible = state.transitionStartMs >= 0,
        enter = expandVertically(Motion.enter(Motion.SLOW)) + fadeIn(Motion.enter()),
        exit = shrinkVertically(Motion.exit(Motion.BASE)) + fadeOut(Motion.exit()),
    ) {
        val dj = state.dj
        val start = state.transitionStartMs
        val what = when {
            state.transitionActive && dj != null && dj.kind.startsWith("Beatmatch") ->
                "${dj.kind} · ${dj.engineName}" + if (dj.speed != 1f) " · ${"%.2f×".format(dj.speed)}" else ""
            state.transitionActive -> "Mixing · ${state.style.label}"
            else -> "Mix at ${formatMs(start)}"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Space.xl, end = Space.xl, top = Space.s),
        ) {
            Icon(PetalIcons.Seam, null, tint = theme.accentOnArt, modifier = Modifier.size(18.dp))
            Text(
                if (state.eqSummary.isNotEmpty()) "$what · ${state.eqSummary}" else what,
                style = PetalType.time,
                color = theme.accentOnArt,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Space.s),
            )
        }
    }
}

/**
 * The scrubber is the song's waveform, drawn as it is heard (slots not yet heard are a
 * dotted floor). The seam notch marks where AutoMix will start the next song. While a
 * mix runs it becomes the two-lane mix waveform. Drag to preview; the time follows the
 * finger in large type.
 */
@Composable
private fun Scrubber(
    positionMs: () -> Long,
    durationMs: Long,
    wave: HeardWaveform?,
    realWave: FloatArray?,
    theme: ArtTheme,
    state: PlaybackBus.State,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction
        else if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = (fraction * durationMs).toLong()
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        androidx.compose.animation.Crossfade(state.transitionActive, label = "scrub", animationSpec = Motion.enter(Motion.SLOW)) { mixing ->
            if (mixing) {
                MixWaveform(
                    // Same height as the scrubber, so starting a mix never moves the layout.
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    progress = state.transitionProgress,
                    bpm = state.dj?.bpmIn ?: 0f,
                    eq = state.transitionEq,
                    durationMs = state.transitionDurationMs,
                    accent = theme.accentOnArt,
                )
            } else {
                val seamAt = if (state.transitionStartMs >= 0 && durationMs > 0)
                    state.transitionStartMs.toFloat() / durationMs else -1f
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .semantics {
                            contentDescription = "Playback position"
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                            setProgress { v -> onSeek((v.coerceIn(0f, 1f) * durationMs).toLong()); true }
                        }
                        .pointerInput(durationMs) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                dragging = true
                                dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                                down.consume()
                                // A mix starting mid-drag swaps this bar for the mix waveform and
                                // cancels the gesture: let go without seeking, never stay "dragging".
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                        val released = !change.pressed
                                        change.consume()
                                        if (released) break
                                    }
                                    if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                                } finally {
                                    dragging = false
                                }
                            }
                        },
                ) {
                    wave?.version // redraw as the song is written in
                    val bins = realWave?.size ?: HeardWaveform.BINS
                    val step = size.width / bins
                    val barW = step * .56f
                    val mid = size.height / 2f
                    val head = fraction * size.width
                    for (i in 0 until bins) {
                        val v = realWave?.get(i) ?: wave?.bins?.get(i) ?: 0f
                        val x = i * step + step / 2f
                        val played = x <= head
                        val color = if (played) theme.ink else theme.ink.copy(alpha = .30f)
                        if (v <= 0f) {
                            // Not heard yet: a dot on the floor, not an invented bar.
                            drawCircle(color.copy(alpha = color.alpha * .7f), barW / 2.4f, Offset(x, mid))
                        } else {
                            // Decoded RMS is already even; the heard level is squared to spread it.
                            val shape = if (realWave != null) v else v * v
                            val h = (size.height * .86f) * (.12f + .88f * shape)
                            drawLine(color, Offset(x, mid - h / 2f), Offset(x, mid + h / 2f), barW, StrokeCap.Round)
                        }
                    }
                    if (seamAt in 0f..1f) {
                        val x = seamAt * size.width
                        drawLine(theme.accentOnArt, Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()),
                            2.dp.toPx(), StrokeCap.Round)
                    }
                    // The playhead: a narrow bar in the accent.
                    drawRoundRect(
                        theme.accentOnArt,
                        topLeft = Offset(head - 1.5.dp.toPx(), 0f),
                        size = Size(3.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                formatMs(shownMs),
                style = if (dragging) MaterialTheme.typography.displaySmall.copy(fontFamily = org.rab1d.petal.music.ui.theme.PetalMono, fontFeatureSettings = "tnum", fontSize = 34.sp, letterSpacing = 0.sp)
                    else PetalType.time.copy(fontSize = 12.sp),
                color = if (dragging) theme.ink else theme.text2,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "−" + formatMs((durationMs - shownMs).coerceAtLeast(0)),
                style = PetalType.time.copy(fontSize = 12.sp),
                color = theme.text2,
            )
        }
    }
}

/** The glass control deck: transport on top, the song's actions below. */
@Composable
private fun Deck(
    backdrop: org.rab1d.petal.music.ui.components.Backdrop,
    theme: ArtTheme,
    state: PlaybackBus.State,
    automix: Boolean,
    favorite: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: () -> Unit,
    onAutomix: () -> Unit,
    onAddTo: (() -> Unit)?,
    lyricsOn: Boolean,
    onLyrics: () -> Unit,
    onMore: () -> Unit,
    onQueue: () -> Unit,
) {
    val off = theme.text3
    Column(
        Modifier
            .padding(horizontal = Space.m, vertical = Space.m)
            .fillMaxWidth()
            .glass(backdrop, GlassLevel.Bar, theme.base, Radius.deck)
            .padding(vertical = Space.m),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GlyphButton(onShuffle, if (state.shuffle) "Shuffle on" else "Shuffle off") {
                Toggle(state.shuffle, theme) {
                    Icon(PetalIcons.Shuffle, null, tint = if (state.shuffle) theme.accentOnArt else off)
                }
            }
            GlyphButton(onPrevious, "Previous", size = 56.dp) {
                Icon(PetalIcons.Previous, null, tint = theme.ink, modifier = Modifier.size(32.dp))
            }
            // Play: the one solid fill on the screen, in the cover's accent.
            Box(
                Modifier
                    .size(76.dp)
                    .clip(Radius.circle)
                    .background(theme.accentOnArt)
                    .pressable(onClick = onPlayPause)
                    .semantics { contentDescription = if (state.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) { PlayPauseGlyph(state.playing, theme.onAccent, size = 34.dp) }
            GlyphButton(onNext, "Next", size = 56.dp) {
                Icon(PetalIcons.Next, null, tint = theme.ink, modifier = Modifier.size(32.dp))
            }
            val repeatLabel = when (state.repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" }
            GlyphButton(onRepeat, repeatLabel) {
                Toggle(state.repeatMode != 0, theme) {
                    Icon(
                        if (state.repeatMode == 2) PetalIcons.RepeatOne else PetalIcons.Repeat, null,
                        tint = if (state.repeatMode != 0) theme.accentOnArt else off,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth().padding(top = Space.s),
        ) {
            GlyphButton(onFavorite, if (favorite) "Remove from favourites" else "Add to favourites") {
                HeartGlyph(favorite, outline = theme.text2, fill = theme.accentOnArt)
            }
            GlyphButton(onAutomix, if (automix) "AutoMix on" else "AutoMix off") {
                Toggle(automix, theme) {
                    Icon(PetalIcons.Seam, null, tint = if (automix) theme.accentOnArt else off)
                }
            }
            GlyphButton(onLyrics, if (lyricsOn) "Hide lyrics" else "Lyrics") {
                Toggle(lyricsOn, theme) {
                    Icon(PetalIcons.Lyrics, null, tint = if (lyricsOn) theme.accentOnArt else theme.text2)
                }
            }
            if (onAddTo != null) GlyphButton(onAddTo, "Add to playlist") {
                Icon(PetalIcons.AddTo, null, tint = theme.text2)
            }
            GlyphButton(onQueue, "Up next") { Icon(PetalIcons.Queue, null, tint = theme.text2) }
            GlyphButton(onMore, "More: credits, sleep timer, share, output") { Icon(PetalIcons.More, null, tint = theme.text2) }
        }
    }
}

/** An on/off control shows "on" as a small dot under the glyph, not a coloured disc. */
@Composable
private fun Toggle(on: Boolean, theme: ArtTheme, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        content()
        if (on) Box(
            Modifier
                .offset(y = 15.dp)
                .size(4.dp)
                .clip(Radius.circle)
                .background(theme.accentOnArt),
        )
    }
}

// ---- actions ---------------------------------------------------------------------------

private fun shareTrack(context: Context, track: Track) {
    val text = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" by ")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The system output switcher (speaker, Bluetooth, cast): Android 14's API, else SystemUI's dialog. */
private fun openOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        val shown = runCatching {
            android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        }.getOrDefault(false)
        if (shown) return
    }
    runCatching {
        context.sendBroadcast(
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui")
                .putExtra("package_name", context.packageName),
        )
    }
}

/** The artist page for [track], when its artist is in the device or source library. */
fun PetalViewModel.findArtist(track: Track): Pair<String, String>? {
    val name = track.artist.substringBefore(",").trim()
    if (name.isEmpty()) return null
    // The source's own id first: exact, and works for artists not in your library.
    track.artistId?.let { id -> if (hasArtistPages(track.source)) return track.source.id to id }
    if (track.source == org.rab1d.petal.music.model.Source.LOCAL || track.localPath != null) {
        artists().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return "local" to it.id }
    }
    libraryFor(track.source).artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.let { return track.source.id to it.id }
    return artists().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { "local" to it.id }
}

internal fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun RoomArt(uri: String?, alpha: Float) {
    if (uri == null || alpha <= 0f) return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(uri).size(24).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
            .blur(12.dp),
    )
}
