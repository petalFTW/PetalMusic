/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.CoverArt
import org.rab1d.petal.music.ui.components.EmptyState
import org.rab1d.petal.music.ui.components.EqualiserGlyph
import org.rab1d.petal.music.ui.components.GlyphButton
import org.rab1d.petal.music.ui.components.LocalChromeInset
import org.rab1d.petal.music.ui.components.LocalLiveLevel
import org.rab1d.petal.music.ui.components.PetalIcons
import org.rab1d.petal.music.ui.components.TrackRow
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space
import kotlin.math.roundToInt

/**
 * Up Next. As a tab it sits on the page; as the player's drawer ([drawer]) it sits on
 * glass over the player. Drag a handle to reorder (rows settle without overshoot),
 * swipe a row left to take it out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(viewModel: PetalViewModel, drawer: Boolean = false, onClose: () -> Unit = {}) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val c = Petal.colors

    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    var saved by remember { mutableStateOf(false) }

    val current = state.current
    val queueList = state.queueSnapshot
    val currentIdx = queueList.indexOfFirst { it.key() == current?.key() }
    val upNext = queueList.drop(currentIdx + 1)
    val leftMs = upNext.sumOf { it.durationMs.coerceAtLeast(0) }

    Column(Modifier.fillMaxSize().then(if (drawer) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))) {
        if (drawer) {
            Box(Modifier.fillMaxWidth().padding(top = Space.s), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(Radius.pill).background(c.ink.copy(alpha = .28f)))
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.gutter, end = Space.s, top = if (drawer) Space.m else Space.xl),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.displaySmall, color = c.ink)
                if (upNext.isNotEmpty()) {
                    Text(
                        "${upNext.size} song${if (upNext.size == 1) "" else "s"} · ${formatRuntime(leftMs)}",
                        style = PetalType.time, color = c.text2, modifier = Modifier.padding(top = Space.xs),
                    )
                }
            }
            GlyphButton({ viewModel.toggleShuffle() }, if (state.shuffle) "Shuffle on" else "Shuffle off") {
                Icon(PetalIcons.Shuffle, null, tint = if (state.shuffle) c.accent else c.text3)
            }
            GlyphButton({ viewModel.setRepeat((state.repeatMode + 1) % 3) },
                when (state.repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" }) {
                Icon(if (state.repeatMode == 2) PetalIcons.RepeatOne else PetalIcons.Repeat, null,
                    tint = if (state.repeatMode != 0) c.accent else c.text3)
            }
            if (drawer) GlyphButton(onClose, "Close") { Icon(Icons.Rounded.Close, null, tint = c.text2) }
        }

        LazyColumn(
            contentPadding = PaddingValues(top = Space.l, bottom = Space.xxl + if (drawer) 0.dp else LocalChromeInset.current),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (current == null) {
                item {
                    EmptyState(
                        title = "Quiet in here",
                        body = "Start an album or a playlist and the songs lined up after it show here. " +
                            "Drag them into the order you want.",
                    )
                }
                return@LazyColumn
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
                ) {
                    CoverArt(current.artUri, Modifier.size(72.dp), seed = "${current.title} · ${current.artist}")
                    Column(Modifier.weight(1f).padding(start = Space.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EqualiserGlyph(LocalLiveLevel.current.takeIf { state.playing }, c.accent, size = 13.dp)
                            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = c.accent,
                                modifier = Modifier.padding(start = 6.dp))
                        }
                        Text(current.title, style = MaterialTheme.typography.titleLarge, color = c.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        org.rab1d.petal.music.ui.components.ArtistLink(current, MaterialTheme.typography.bodyMedium, c.text2)
                    }
                }
            }

            if (upNext.isEmpty()) {
                item {
                    Text(
                        if (state.automixEnabled && state.automixSnapshot.isNotEmpty())
                            "Nothing queued after this. AutoMix keeps going with the songs below."
                        else "Nothing queued after this one.",
                        color = c.text2, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
                    )
                }
            } else {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s, top = Space.l),
                    ) {
                        if (state.automixEnabled) {
                            // The seam: these songs will be mixed, not just played in turn.
                            Icon(PetalIcons.Seam, null, tint = c.accent, modifier = Modifier.size(18.dp))
                            Text("Mixed into each other", style = MaterialTheme.typography.labelLarge, color = c.text2,
                                modifier = Modifier.padding(start = Space.s).weight(1f))
                        } else {
                            Text("Next", style = MaterialTheme.typography.labelLarge, color = c.text2, modifier = Modifier.weight(1f))
                        }
                        androidx.compose.material3.TextButton(
                            enabled = !saved,
                            onClick = {
                                viewModel.createPlaylist("Up next · ${current.title}", listOf(current) + upNext)
                                saved = true
                            },
                        ) { Text(if (saved) "Saved" else "Save as playlist", color = if (saved) c.text3 else c.accent) }
                    }
                }
            }

            val stride = rowHeight
            val dragTarget = if (dragIndex >= 0 && rowHeight > 0f && upNext.isNotEmpty()) {
                (dragIndex + (dragOffset / stride).roundToInt()).coerceIn(currentIdx + 1, queueList.lastIndex)
            } else dragIndex
            items(count = upNext.size, key = { i -> "${upNext[i].key()}@${currentIdx + 1 + i}" }) { i ->
                val track = upNext[i]
                val absolute = currentIdx + 1 + i
                val dragging = absolute == dragIndex
                val placement by animateFloatAsState(
                    when {
                        dragIndex < absolute && absolute <= dragTarget -> -stride
                        dragTarget <= absolute && absolute < dragIndex -> stride
                        else -> 0f
                    },
                    Motion.drag(), label = "queuePlacement",
                )
                val lift by animateFloatAsState(if (dragging) 1f else 0f, Motion.press(), label = "lift")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else placement
                            scaleX = 1f + .02f * lift
                            scaleY = 1f + .02f * lift
                            shadowElevation = 12.dp.toPx() * lift
                        }
                        .onSizeChanged { rowHeight = it.height.toFloat() },
                ) {
                    val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) { viewModel.removeQueueItem(absolute); true } else false
                            },
                        )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) Box(
                                Modifier.fillMaxSize().background(c.dangerSoft).padding(horizontal = Space.xl),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Text("Remove", color = c.ink, style = MaterialTheme.typography.labelLarge) }
                        },
                    ) {
                        TrackRow(
                            track = track,
                            swipe = false,
                            modifier = Modifier.background(if (dragging) c.surface2 else if (drawer) androidx.compose.ui.graphics.Color.Transparent else c.bg),
                            onClick = { viewModel.playFromQueue(absolute) },
                            trailing = {
                                Icon(
                                    Icons.Rounded.DragHandle, null, tint = c.text3,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .padding(10.dp)
                                        .semantics { contentDescription = "Reorder ${track.title}" }
                                        .pointerInput(absolute) {
                                            detectDragGestures(
                                                onDragStart = { dragIndex = absolute; dragOffset = 0f },
                                                onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                                                onDragEnd = {
                                                    val steps = if (rowHeight > 0f) (dragOffset / rowHeight).roundToInt() else 0
                                                    val target = (absolute + steps).coerceIn(currentIdx + 1, queueList.lastIndex)
                                                    if (target != absolute) viewModel.moveQueueItem(absolute, target)
                                                    dragIndex = -1; dragOffset = 0f
                                                },
                                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                                            )
                                        },
                                )
                            },
                        )
                    }
                }
            }

            if (state.automixEnabled && state.automixSnapshot.isNotEmpty()) {
                item {
                    Text("AutoMix continues with", style = MaterialTheme.typography.labelLarge, color = c.text2,
                        modifier = Modifier.padding(start = Space.gutter, top = Space.xl, bottom = Space.xs))
                }
                items(state.automixSnapshot.take(3).size) { i ->
                    val t: Track = state.automixSnapshot[i]
                    // Suggestions, not queue entries: shown, not tappable.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
                    ) {
                        CoverArt(t.artUri, Modifier.size(40.dp), seed = "${t.title} · ${t.artist}")
                        Column(Modifier.padding(start = Space.m)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = c.text2, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${t.artist} · ${t.source.label}", style = MaterialTheme.typography.bodyMedium,
                                color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

internal fun formatRuntime(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}
