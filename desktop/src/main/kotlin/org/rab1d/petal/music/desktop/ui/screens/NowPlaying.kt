/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.ui.components.AutoSizeText
import org.rab1d.petal.music.desktop.ui.components.ChromaticAura
import org.rab1d.petal.music.desktop.ui.components.PetalSeekBar
import org.rab1d.petal.music.desktop.ui.components.SourceBadge
import org.rab1d.petal.music.desktop.ui.state.DesktopController
import org.rab1d.petal.music.desktop.ui.theme.petalPalette

@Composable
fun NowPlayingScreen(
    controller: DesktopController,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val palette = petalPalette()
    val track = controller.current
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.wash)
            .pointerInput(Unit) {
                val threshold = 120.dp.toPx()
                var dy = 0f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!change.isConsumed) {
                            dy += change.positionChange().y
                            change.consume()
                        }
                    }
                    if (dy > threshold) onClose()
                }
            },
    ) {
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to palette.glass.copy(alpha = 0.55f),
                0.5f to palette.wash.copy(alpha = 0.9f),
                1f to palette.wash,
            ),
        ))
        if (controller.isMixing) {
            ChromaticAura(modifier = Modifier.fillMaxSize(), intensity = 1.1f)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Column(Modifier.weight(1f)) {
                    AutoSizeText(
                        text = track?.title ?: "Nothing playing",
                        baseSize = 28.sp, minSize = 16.sp, maxLines = 1,
                        color = Color.White, fontWeight = FontWeight.Bold,
                    )
                    AutoSizeText(
                        text = track?.artist ?: "", baseSize = 18.sp, minSize = 12.sp, maxLines = 1,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        SourceBadge(org.rab1d.petal.music.desktop.ui.components.DesktopSource.LOCAL, size = 16)
                        Text("Device", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                IconButton(onClick = { controller.updateAutomix(!controller.automix) }) {
                    Icon(
                        if (controller.automix) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                        "AutoMix",
                        tint = if (controller.automix) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = onOpenQueue) { Icon(Icons.Filled.MoreHoriz, "Queue", tint = Color.White.copy(alpha = 0.85f)) }
            }

            Text(
                if (controller.isMixing) "Mixing · ${styleLabel(controller.style)}" else "",
                style = MaterialTheme.typography.labelLarge,
                color = palette.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                val duration = controller.durationMs.takeIf { it > 0 } ?: (track?.durationMs ?: 0L)
                val position = if (dragging) (sliderValue * duration).toLong() else controller.positionMs
                if (!dragging) sliderValue = if (duration > 0) controller.positionMs / duration.toFloat() else 0f
                PetalSeekBar(
                    progress = sliderValue,
                    accent = palette.accent,
                    onSeek = { f -> dragging = true; sliderValue = f },
                    onSeekFinished = { f -> dragging = false; sliderValue = f; if (duration > 0) controller.seekTo((f * duration).toLong()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatMs(position), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    Text("-" + formatMs((duration - position).coerceAtLeast(0)), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                IconButton(onClick = { controller.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (controller.shuffle) palette.accent else Color.White.copy(alpha = 0.22f))
                }
                IconButton(onClick = { controller.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = { controller.playPause() }) {
                    Icon(
                        if (controller.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/Pause", tint = Color.White, modifier = Modifier.size(64.dp),
                    )
                }
                IconButton(onClick = { controller.next() }) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = { controller.setRepeat(if (controller.repeatMode == 1) 0 else 1) }) {
                    Icon(
                        Icons.Filled.Repeat,
                        "Repeat list",
                        tint = if (controller.repeatMode == 1) palette.accent else Color.White.copy(alpha = 0.22f),
                    )
                }
                IconButton(onClick = { controller.setRepeat(if (controller.repeatMode == 2) 0 else 2) }) {
                    Icon(
                        Icons.Filled.RepeatOne,
                        "Repeat one",
                        tint = if (controller.repeatMode == 2) palette.accent else Color.White.copy(alpha = 0.22f),
                    )
                }
            }
        }
    }
}

private fun styleLabel(style: Int): String = when (style) {
    1 -> "Reverb"
    2 -> "Stutter"
    3 -> "Adaptive"
    else -> "Clean"
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
