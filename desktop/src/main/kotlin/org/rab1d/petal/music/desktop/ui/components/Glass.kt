/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.ui.theme.petalPalette

@Composable
fun Modifier.springyClickable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 900f),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val base = Modifier.clip(shape).background(Color(0xFF1D1D21))
    val m = if (onClick != null) base.springyClickable(onClick) else base
    Box(modifier = modifier.then(m)) { content() }
}

@Composable
fun PetalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = true) {
    val palette = petalPalette()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (prominent) palette.accent else Color(0xFF2C2C31))
            .springyClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (prominent) palette.onAccent else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
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
                else Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF))),
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape)
            .springyClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (filled) palette.onAccent else tint)
    }
}

@Composable
fun GlassTextButton(
    text: String,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
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
                else Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF))),
            )
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape)
            .springyClickable(onClick),
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

@Composable
fun SourceDot(accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(accent))
}

@Composable
fun PetalSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 14.dp),
    )
}

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
        label = "seek",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
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
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val cy = size.height / 2f
            val trackH = 5.dp.toPx()
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            val filled = (size.width * shown).coerceIn(0f, size.width)
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), accent)),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(filled, trackH),
                cornerRadius = radius,
            )
            val thumbR = if (dragging) 9.dp.toPx() else 7.dp.toPx()
            val cx = filled.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR))
            drawCircle(Color.White.copy(alpha = 0.25f), radius = thumbR + 3.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.White, radius = thumbR, center = Offset(cx, cy))
        }
    }
}

@Composable
fun CardCaption(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
