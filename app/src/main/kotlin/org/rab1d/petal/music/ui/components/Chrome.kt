/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.rab1d.petal.music.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.playback.PlaybackBus
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- shared elements -----------------------------------------------------------------

val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The NavHost destination's animated scope, for cover → header morphs. */
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Height of the floating chrome (mini-player + tab bar) that pages scroll under. */
val LocalChromeInset = compositionLocalOf { 0.dp }

/** The page layer the chrome frosts; screens can frost their own headers over it too. */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Which tapped cover a detail header should morph from. A cover can sit on two shelves
 * of one page, so the key carries the shelf it was tapped on.
 */
object SharedCovers {
    private var pending by mutableStateOf<Pair<String, String>?>(null)

    fun tapped(item: String, shelf: String) { pending = item to shelf }

    /** The key for a cover on [shelf]. */
    fun key(item: String, shelf: String) = "cover/$item@$shelf"

    /** The key a detail header for [item] should use. */
    fun headerKey(item: String): String? = pending?.takeIf { it.first == item }?.let { key(it.first, it.second) }
}

@Composable
fun Modifier.sharedArt(key: String?, scope: AnimatedVisibilityScope?): Modifier {
    val shared = LocalSharedTransition.current
    if (key == null || scope == null || shared == null || LocalReducedMotion.current) return this
    return with(shared) {
        this@sharedArt.sharedElement(
            rememberSharedContentState(key),
            scope,
            boundsTransform = { _, _ -> Motion.settle() },
        )
    }
}

// ---- playback clock ------------------------------------------------------------------

/**
 * Playhead fraction for thin progress lines, read twice a second while [playing]:
 * the line moves a few pixels a second, so per-frame updates would only burn battery.
 */
@Composable
fun rememberPlaybackFraction(track: Track?, playing: Boolean): State<Float> {
    val f = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(track?.id, playing) {
        fun read() {
            val pos = PlaybackBus.positionProvider?.invoke() ?: 0L
            val dur = (PlaybackBus.durationProvider?.invoke() ?: 0L).takeIf { it > 0 } ?: track?.durationMs ?: 0L
            f.floatValue = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        }
        read()
        while (playing) { kotlinx.coroutines.delay(500); read() }
    }
    return f
}

// ---- tab bar -------------------------------------------------------------------------

data class NavItem(val route: String, val label: String, val icon: ImageVector)

/**
 * The tab bar: Bar-level glass over the page, tinted by what's playing. The selection
 * is one pill that slides between tabs (critically damped, so it lands without a wobble).
 */
@Composable
fun GlassNavBar(
    items: List<NavItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    backdrop: Backdrop?,
    tint: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val index = items.indexOfFirst { it.route == selected }.coerceAtLeast(0)
    val reduced = LocalReducedMotion.current
    Box(
        modifier
            .fillMaxWidth()
            .glass(backdrop, GlassLevel.Bar, tint)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            items.forEach { item ->
                val active = item.route == selected
                val color by animateColorAsState(
                    if (active) Petal.colors.ink else Petal.colors.text2,
                    Motion.press(Motion.BASE), label = "tab",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(active, role = Role.Tab, onClick = { onSelect(item.route) },
                            interactionSource = null, indication = null),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(24.dp))
                    Text(item.label, color = color, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                }
            }
        }
        // The selection mark: a short accent bar under the active label, sliding between tabs.
        // (Above the tabs it would sit on the mini-player's progress line.)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(top = 54.dp).fillMaxWidth().height(3.dp)) {
            val slot = maxWidth / items.size
            val x by animateDpAsState(
                slot * index + slot / 2 - 14.dp,
                Motion.orSnap(reduced, Motion.drag()), label = "tabMark",
            )
            Box(
                Modifier
                    .offset(x = x)
                    .width(28.dp)
                    .height(3.dp)
                    .clip(Radius.pill)
                    .drawBehind { drawRect(accent) },
            )
        }
    }
}

// ---- mini-player ---------------------------------------------------------------------

/**
 * The floating glass pill above the tab bar. Swipe sideways to skip, up (or tap) to open
 * the player. The artwork is a shared element: it grows into the player's cover.
 */
@Composable
fun MiniPlayer(
    track: Track,
    playing: Boolean,
    theme: ArtTheme,
    backdrop: Backdrop?,
    paper: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    artModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    val fraction by rememberPlaybackFraction(track, playing)
    // Night: the cover's own dark base. Paper: a light wash of it.
    val tint = if (paper) lerp(Petal.colors.bg, theme.lightMuted, .22f) else theme.base
    val line = if (paper) Petal.colors.accent else theme.accentOnArt
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.m, vertical = Space.s)
            .offset { IntOffset(dx.value.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (abs(dx.value) / (size.width * .9f)).coerceIn(0f, .6f) }
            .glass(backdrop, GlassLevel.Bar, tint, Radius.pill)
            .pointerInput(track.id) {
                val skip = 96.dp.toPx()
                val open = 56.dp.toPx()
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = { totalX = 0f; totalY = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        totalX += amount.x
                        totalY += amount.y
                        if (abs(totalX) > abs(totalY)) scope.launch { dx.snapTo(totalX) }
                    },
                    onDragEnd = {
                        when {
                            totalY < -open && abs(totalY) > abs(totalX) -> { scope.launch { dx.snapTo(0f) }; onOpen() }
                            abs(totalX) > skip -> scope.launch {
                                val out = if (totalX < 0) -size.width.toFloat() else size.width.toFloat()
                                if (!reduced) dx.animateTo(out, Motion.exit(Motion.FAST))
                                if (totalX < 0) onNext() else onPrevious()
                                dx.snapTo(-out * .25f)
                                dx.animateTo(0f, Motion.drag())
                            }
                            else -> scope.launch { dx.animateTo(0f, Motion.drag()) }
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, Motion.drag()) } },
                )
            }
            .pressable(onClick = onOpen, onClickLabel = "Open player")
            .semantics { contentDescription = "${track.title} by ${track.artist}" },
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(track.artUri, artModifier.size(44.dp), shape = Radius.circle)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                // Three passes per song, then it rests: an endless marquee is a frame every vsync.
                androidx.compose.runtime.key(track.id) { Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (paper) Petal.colors.ink else theme.ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (reduced) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = if (reduced) Modifier else Modifier.basicMarquee(iterations = 3, initialDelayMillis = 2400),
                ) }
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (paper) Petal.colors.text2 else theme.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GlyphButton(onClick = onPlayPause, label = if (playing) "Pause" else "Play") {
                PlayPauseGlyph(playing, if (paper) Petal.colors.ink else theme.ink, size = 26.dp)
            }
            GlyphButton(onClick = onNext, label = "Next") {
                Icon(PetalIcons.Next, null, tint = if (paper) Petal.colors.ink else theme.ink, modifier = Modifier.size(22.dp))
            }
        }
        // Progress: a thin line in the cover's accent, inset to sit inside the pill's flat run.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .height(2.dp)
                .drawBehind {
                    drawLine(line.copy(alpha = .22f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                    drawLine(line, Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                },
        )
    }
}

/** A 48dp touch target around a glyph, with the shared press scale. */
@Composable
fun GlyphButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(Radius.circle)
            .pressable(onClick = onClick, enabled = enabled, role = Role.Button)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---- toast ---------------------------------------------------------------------------

/** Errors and notices: Sheet-level glass, menu radius, floating above the chrome. */
@Composable
fun GlassToast(message: String, backdrop: Backdrop?, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = Space.l, vertical = Space.s)
            .glass(backdrop, GlassLevel.Sheet, tint, Radius.menu)
            .padding(horizontal = Space.l, vertical = 14.dp),
    ) {
        Text(message, color = Petal.colors.ink, style = MaterialTheme.typography.bodyMedium)
    }
}
