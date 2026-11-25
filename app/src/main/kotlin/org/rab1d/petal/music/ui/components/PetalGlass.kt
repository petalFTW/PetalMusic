/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import org.rab1d.petal.music.ui.theme.Petal
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.ui.theme.petalPalette

/**
 * Shared Petal surfaces: quiet charcoal cards, source-aware accents and
 * restrained press feedback. Legacy component names keep callers stable.
 */
@Composable
fun Modifier.springyClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !org.rab1d.petal.music.ui.theme.LocalReducedMotion.current) 0.97f else 1f,
        animationSpec = org.rab1d.petal.music.ui.theme.Motion.press(),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * A plain raised surface for grouped settings-like content. Despite the name it is not
 * glass: it sits on a plain page, where frosting would have nothing to frost.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = org.rab1d.petal.music.ui.theme.Radius.control
    val base = Modifier.clip(shape).background(Petal.colors.surface1)
    val m = if (onClick != null) base.springyClickable(onClick = onClick) else base
    Box(modifier = modifier.then(m)) { content() }
}

/** Solid accent action with a comfortable touch target. */
@Composable
fun PetalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
) {
    val palette = petalPalette()
    val shape = org.rab1d.petal.music.ui.theme.Radius.pill
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (prominent) palette.accent else Petal.colors.surface2)
            .springyClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (prominent) palette.onAccent else Petal.colors.ink,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Round glass icon button (transport, actions). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Petal.colors.ink,
    size: Int = 48,
    filled: Boolean = false,
) {
    val palette = petalPalette()
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(
                if (filled) palette.accentBrush
                else Brush.verticalGradient(listOf(Petal.colors.glassControl, Petal.colors.glassControl)),
            )
            .border(BorderStroke(1.dp, Petal.colors.ink.copy(alpha = 0.12f)), shape)
            .springyClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (filled) palette.onAccent else tint)
    }
}

/** Round glass button labelled with a short glyph (e.g. "U" for upscale). */
@Composable
fun GlassTextButton(
    text: String,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Petal.colors.ink,
    size: Int = 48,
    filled: Boolean = false,
) {
    val palette = petalPalette()
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(
                if (filled) palette.accentBrush
                else Brush.verticalGradient(listOf(Petal.colors.glassControl, Petal.colors.glassControl)),
            )
            .border(BorderStroke(1.dp, Petal.colors.ink.copy(alpha = 0.12f)), shape)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .springyClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) palette.onAccent else tint,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

/** Source chip used for the library's environment switcher. */
@Composable
fun SourceChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    source: org.rab1d.petal.music.model.Source? = null,
) {
    val shape = org.rab1d.petal.music.ui.theme.Radius.pill
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(shape)
            .background(if (selected) Petal.colors.ink else Petal.colors.ink.copy(alpha = .07f))
            .springyClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        source?.let { SourceLogo(source = it, size = 16, tint = if (selected) Petal.colors.bg else Petal.colors.text2) }
        Text(
            label,
            color = if (selected) Petal.colors.bg else Petal.colors.text2,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Small round source dot (brand accent) for rows/cards. */
@Composable
fun SourceDot(accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(accent),
    )
}

/** Editorial section heading. */
@Composable
fun PetalSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = Petal.colors.ink,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 12.dp),
    )
}

/**
 * Liquid-glass seek bar. A tall transparent touch target with a thin,
 * Apple-Music-style track so scrubbing is easy to grab even on the very
 * edge of the screen. Handles taps and drags in one gesture loop.
 */
@Composable
fun PetalSeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = petalPalette().accent,
) {
    val currentSeek by rememberUpdatedState(onSeek)
    val currentFinish by rememberUpdatedState(onSeekFinished)
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (dragging) snap() else tween(220),
        label = "seek",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                setProgress { value ->
                    currentSeek(value.coerceIn(0f, 1f))
                    currentFinish(value.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var f = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    dragging = true
                    currentSeek(f)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        currentSeek(f)
                        val released = !change.pressed
                        change.consume()
                        if (released) break
                    }
                    dragging = false
                    currentFinish(f)
                }
            },
    ) {
        val ink = Petal.colors.ink
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackH = 5.dp.toPx()
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = ink.copy(alpha = 0.22f),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            val filled = (size.width * shown).coerceIn(0f, size.width)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.85f), accent),
                ),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(filled, trackH),
                cornerRadius = radius,
            )
            val thumbR = if (dragging) 9.dp.toPx() else 7.dp.toPx()
            val cx = filled.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR))
            drawCircle(
                color = ink.copy(alpha = 0.25f),
                radius = thumbR + 3.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(color = ink, radius = thumbR, center = Offset(cx, cy))
        }
    }
}

/** Two-line row used by shelf cards (title + artist). */
@Composable
fun CardCaption(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
    ) {
        Text(
            title,
            color = Petal.colors.ink,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            color = Petal.colors.ink.copy(alpha = 0.55f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
