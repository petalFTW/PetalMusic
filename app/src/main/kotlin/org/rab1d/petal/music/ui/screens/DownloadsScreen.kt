/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

import org.rab1d.petal.music.ui.theme.Petal
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rab1d.petal.music.data.downloads.DownloadedTrack
import org.rab1d.petal.music.data.downloads.upscale.OnDeviceApollo
import org.rab1d.petal.music.data.downloads.upscale.UpscaleManager
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.GlassCard
import org.rab1d.petal.music.ui.components.GlassIconButton
import org.rab1d.petal.music.ui.components.PetalButton
import org.rab1d.petal.music.ui.components.PetalSectionLabel
import org.rab1d.petal.music.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.ui.theme.PetalPalette
import org.rab1d.petal.music.ui.theme.petalPalette

/**
 * Offline library: everything downloaded, grouped by album so a big collection
 * stays readable. This is the proper home for the long list that used to be
 * dumped into the library tab.
 */
@Composable
fun DownloadsScreen(viewModel: PetalViewModel, onBack: () -> Unit) {
    val downloads by viewModel.downloads.collectAsState()
    val upscaleProgress by viewModel.upscaleProgress.collectAsState()
    val deviceLink by viewModel.deviceLink.collectAsState()
    val upscaleSettings by viewModel.upscaleSettings.collectAsState()
    val showUnlockWarning by viewModel.showUpscaleWarning.collectAsState()
    val context = LocalContext.current
    val palette = petalPalette()
    val setPalette = LocalPaletteSetter.current
    LaunchedEffect(Unit) { setPalette(PetalPalette.Petal) }

    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        downloads
    } else {
        downloads.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }
    val groups = filtered.groupBy { it.album.ifBlank { "Singles" } }
    val totalBytes = downloads.sumOf { it.bytes }

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 24.dp + org.rab1d.petal.music.ui.components.LocalChromeInset.current),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Petal.colors.ink)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Downloads",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Petal.colors.ink,
                    )
                    Text(
                        "${downloads.size} tracks · ${formatBytes(totalBytes)} · offline",
                        color = palette.accent,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (downloads.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search downloads…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Petal.colors.surface1,
                        unfocusedContainerColor = Petal.colors.surface1,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = palette.accent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        if (filtered.isNotEmpty()) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    PetalButton(
                        text = "Play all",
                        onClick = {
                            val list = filtered.map { it.toTrack() }
                            viewModel.playTracks(list, 0)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PetalButton(
                        text = "Shuffle",
                        onClick = {
                            val list = filtered.map { it.toTrack() }
                            viewModel.playTracks(list, 0, shuffle = true)
                        },
                        prominent = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        groups.forEach { (album, entries) ->
            item { PetalSectionLabel(album) }
            items(entries) { entry ->
                val track = entry.toTrack()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val list = entries.map { it.toTrack() }
                            viewModel.playTracks(list, entries.indexOf(entry))
                        }
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                ) {
                    AsyncImage(
                        model = track.artUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Petal.colors.glassControl),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            track.title,
                            color = Petal.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (track.explicit) org.rab1d.petal.music.ui.components.ExplicitBadge(Modifier.padding(start = 6.dp))
                        }
                        org.rab1d.petal.music.ui.components.ArtistLink(
                            track, MaterialTheme.typography.bodyMedium, Petal.colors.text2,
                        )
                        if (entry.activeQuality.isNotBlank()) {
                        Text(
                            entry.activeQuality,
                            color = if (entry.upscaleEnabled) {
                                palette.accent
                            } else {
                                Petal.colors.ink.copy(alpha = 0.5f)
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                        }
                        val failure = upscaleProgress["${entry.source}/${entry.id}"]
                        if (failure?.state == UpscaleManager.State.FAILED &&
                            !failure.error.isNullOrBlank()
                        ) {
                            Text(
                                failure.error,
                                color = Petal.colors.danger,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    UpscaleControl(
                        entry = entry,
                        progress = upscaleProgress["${entry.source}/${entry.id}"],
                        accent = palette.accent,
                        onDeviceLocked = OnDeviceApollo.AVAILABLE && !deviceLink.configured && !upscaleSettings.onDeviceUnlocked,
                        onToggle = { enabled ->
                            when (val result = viewModel.toggleUpscale(track, enabled)) {
                                is PetalViewModel.UpscaleRequest.Locked -> {
                                    if (result.taps < result.required) {
                                        Toast.makeText(
                                            context,
                                            "On-device upscaling locked — " +
                                                "${result.required - result.taps} more taps",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                else -> Unit
                            }
                        },
                        onCancel = { viewModel.cancelUpscale(track) },
                        onDelete = {
                            viewModel.deleteUpscale(track)
                            Toast.makeText(context, "Upscaled copy removed", Toast.LENGTH_SHORT).show()
                        },
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    GlassIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Remove download",
                        onClick = { viewModel.deleteDownload(track) },
                        size = 34,
                    )
                }
            }
        }

        if (downloads.isNotEmpty() && filtered.isEmpty()) {
            item {
                Text(
                    "No downloads match “$query”.",
                    color = Petal.colors.ink.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }

        if (downloads.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(20.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "Nothing downloaded yet",
                            color = Petal.colors.ink,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Open an album or playlist from TIDAL and tap the download button — it grabs the best quality your account allows and plays with no network.",
                            color = Petal.colors.ink.copy(alpha = 0.55f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showUnlockWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpscaleWarning() },
            title = { Text("Enable on-device upscaling?") },
            text = {
                Text(
                    "This runs the Apollo restoration model and the Hi-Fi mastering chain " +
                        "on your phone's CPU. It can take several minutes per track, warms " +
                        "the device and uses battery. Device Link (a linked PC) is faster. " +
                        "Audio never leaves the device when it is processed here.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmOnDeviceUpscale() }) {
                    Text("Enable on-device")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpscaleWarning() }) { Text("Not now") }
            },
        )
    }
}

/**
 * Per-download upscale affordance: a sparkle button that turns the upscaled
 * copy on (or reverts to the original), and a progress ring while the model is
 * running that cancels on tap.
 */
@Composable
private fun UpscaleControl(
    entry: DownloadedTrack,
    progress: UpscaleManager.Progress?,
    accent: Color,
    onDeviceLocked: Boolean,
    onToggle: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = progress
    if (p != null &&
        (p.state == UpscaleManager.State.QUEUED ||
            p.state == UpscaleManager.State.MODEL ||
            p.state == UpscaleManager.State.PROCESSING)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clickable { onCancel() },
            contentAlignment = Alignment.Center,
        ) {
            if (p.state == UpscaleManager.State.QUEUED) {
                // No measurable progress yet (waiting in the queue).
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.dp,
                    color = accent,
                    trackColor = Petal.colors.ink.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
            } else {
                // MODEL (downloading the network) and PROCESSING both report a
                // real fraction, so show it rather than a stalled 0.
                CircularProgressIndicator(
                    progress = { p.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.dp,
                    color = accent,
                    trackColor = Petal.colors.ink.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round,
                )
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel upscale",
                tint = Petal.colors.ink.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp),
            )
        }
    } else {
        UpscaleHoldButton(
            text = "U",
            contentDescription = buildString {
                append(
                    when {
                        p?.state == UpscaleManager.State.FAILED -> "Upscale failed — tap to retry"
                        entry.upscaleEnabled -> "Revert to original quality"
                        onDeviceLocked -> "Upscale on device (locked — tap 10 times to enable)"
                        else -> "Upscale"
                    },
                )
                if (entry.hasUpscale) append(" · hold 5 seconds to delete the upscaled copy")
            },
            enabled = true,
            canHold = entry.hasUpscale,
            onTap = { onToggle(!entry.upscaleEnabled) },
            onHold = onDelete,
            tint = when {
                p?.state == UpscaleManager.State.FAILED -> Petal.colors.danger
                entry.hasUpscale -> accent
                else -> Petal.colors.ink
            },
            filled = entry.upscaleEnabled,
            size = 34,
        )
    }
}

private const val UPSCALE_HOLD_MS = 5_000L
private const val UPSCALE_HOLD_ANIM_DELAY_MS = 180L

/**
 * Upscale button with a hold-to-reset gesture. A normal tap toggles the
 * upscaled copy; holding for five seconds shakes/pulses the button and then
 * deletes the generated copy so it can be produced again.
 */
@Composable
private fun UpscaleHoldButton(
    text: String,
    contentDescription: String?,
    enabled: Boolean,
    canHold: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    tint: Color,
    filled: Boolean,
    size: Int = 34,
) {
    val palette = petalPalette()
    val context = LocalContext.current
    val vibrator = remember(context) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    }
    val holdPulse = remember {
        VibrationEffect.createWaveform(
            longArrayOf(0L, 45L, 115L),
            intArrayOf(0, 140, 0),
            1,
        )
    }
    val removedPulse = remember {
        VibrationEffect.createWaveform(
            longArrayOf(0L, 80L, 55L, 150L),
            intArrayOf(0, 220, 0, 255),
            -1,
        )
    }
    val scope = rememberCoroutineScope()
    val currentTap by rememberUpdatedState(onTap)
    val currentHold by rememberUpdatedState(onHold)
    var holding by remember { mutableStateOf(false) }

    val offset: Float
    val scale: Float
    if (holding) {
        val transition = rememberInfiniteTransition(label = "upscale-hold")
        val shake by transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(55, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "shake",
        )
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(180, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        offset = shake * 1.6f
        scale = pulse
    } else {
        offset = 0f
        scale = 1f
    }

    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(size.dp)
            .graphicsLayer {
                translationX = offset
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                if (filled) palette.accentBrush
                else Brush.verticalGradient(listOf(Petal.colors.glassControl, Petal.colors.glassControl)),
            )
            .border(BorderStroke(1.dp, Petal.colors.ink.copy(alpha = 0.12f)), shape)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .pointerInput(enabled, canHold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    var active = true
                    var completed = false
                    val visual = if (canHold) scope.launch {
                        delay(UPSCALE_HOLD_ANIM_DELAY_MS)
                        if (active) {
                            holding = true
                            runCatching { vibrator.vibrate(holdPulse) }
                        }
                    } else null
                    val remove = if (canHold) scope.launch {
                        delay(UPSCALE_HOLD_MS)
                        if (active) {
                            completed = true
                            holding = false
                            runCatching {
                                vibrator.cancel()
                                vibrator.vibrate(removedPulse)
                            }
                            currentHold()
                        }
                    } else null
                    try {
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                released = true
                                change.consume()
                                break
                            }
                        }
                        if (released && !completed) currentTap()
                    } finally {
                        active = false
                        visual?.cancel()
                        remove?.cancel()
                        holding = false
                        if (!completed) runCatching { vibrator.cancel() }
                    }
                }
            },
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
