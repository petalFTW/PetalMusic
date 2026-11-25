/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.launch
import org.rab1d.petal.music.desktop.audio.BridgeFXWin
import org.rab1d.petal.music.desktop.link.DeviceLinkServer
import org.rab1d.petal.music.desktop.link.LinkStatus
import org.rab1d.petal.music.desktop.ui.state.DesktopController
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    controller: DesktopController,
    link: DeviceLinkServer,
    onBack: () -> Unit,
) {
    var folder by remember { mutableStateOf(controller.libraryPath) }
    var linkPort by remember { mutableStateOf(DeviceLinkServer.DEFAULT_PORT.toString()) }
    var linkRunning by remember { mutableStateOf(link.isRunning) }
    var linkStatus by remember { mutableStateOf(if (link.isRunning) "Device Link running" else "Device Link stopped") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        SectionHeader("Accounts")
        AccountCard("Spotify", "Connected in the Android app")
        AccountCard("YT Music", "Connected in the Android app")
        AccountCard("Deezer", "Connected in the Android app")
        AccountCard("Tidal", "Connected in the Android app")
        AccountCard("Discord", "Connected in the Android app")

        SectionHeader("Library")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Music folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    AssistChip(
                        onClick = { if (folder.isNotBlank()) controller.scanLibrary(folder) },
                        label = { Text(if (controller.scanning) "Scanning…" else "Scan folder") },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${controller.library.size} tracks", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionHeader("AutoMix")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                SettingSwitch("Enable AutoMix", "Crossfade transitions between tracks", controller.automix) { controller.updateAutomix(it) }
                Text("Fade ${controller.fadeMs} ms", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                Slider(
                    value = controller.fadeMs.toFloat(),
                    onValueChange = { controller.updateFadeMs(it.roundToInt()) },
                    valueRange = BridgeFXWin.MIN_FADE_MS.toFloat()..BridgeFXWin.MAX_FADE_MS.toFloat(),
                )
                Text("Style", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Clean", "Reverb", "Stutter", "Adaptive").forEachIndexed { i, label ->
                        AssistChip(
                            onClick = { controller.updateStyle(i) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (i == controller.style) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                }
                Text("Volume ${"%.0f".format(controller.volume * 100)}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                Slider(value = controller.volume, onValueChange = { controller.updateVolume(it) }, valueRange = 0f..1f)
            }
        }

        SectionHeader("Device Link")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Receive playlists from the Android app. Playlist metadata is imported and " +
                        "saved on this PC; audio downloading is optional and off by default.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "Download audio on this PC",
                    subtitle = "Off: import playlist metadata only. On: also fetch each track with the command below.",
                    checked = link.downloadAudio,
                    onChecked = { link.updateDownloadAudio(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        value = linkPort,
                        onValueChange = { linkPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !linkRunning,
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = {
                        if (linkRunning) {
                            link.stop()
                            linkRunning = false
                            linkStatus = "Device Link stopped"
                        } else {
                            val port = linkPort.toIntOrNull() ?: DeviceLinkServer.DEFAULT_PORT
                            link.onLog = { linkStatus = it }
                            linkRunning = link.start(port)
                            if (!linkRunning) linkStatus = "Could not bind port $port"
                        }
                    }) { Text(if (linkRunning) "Stop" else "Start") }
                }
                if (link.downloadAudio) {
                    OutlinedTextField(
                        value = link.downloaderCommand,
                        onValueChange = { link.downloaderCommand = it },
                        label = { Text("Downloader command ({query} / {output})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                Text(
                    linkStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (link.playlists.isNotEmpty() || link.items.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        Text(
                            if (link.downloadAudio) "Downloads" else "Imported playlists",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (link.downloadAudio) {
                            AssistChip(
                                onClick = { if (controller.libraryPath.isNotBlank()) controller.scanLibrary(controller.libraryPath) },
                                label = { Text("Rescan") },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        AssistChip(onClick = { link.clearQueue() }, label = { Text("Clear") })
                    }
                    link.playlists.forEach { pl ->
                        Text(
                            "${pl.name} · ${pl.tracks.size} tracks" +
                                if (link.downloadAudio) "" else " · metadata only",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    link.items.take(30).forEach { item ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${item.title} — ${item.artist}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    when (item.status) {
                                        LinkStatus.QUEUED -> "queued"
                                        LinkStatus.DOWNLOADING -> "${(item.progress * 100).roundToInt()}%"
                                        LinkStatus.DONE -> "done"
                                        LinkStatus.FAILED -> item.error ?: "failed"
                                        LinkStatus.IMPORTED -> "imported"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (item.status) {
                                        LinkStatus.DONE,
                                        LinkStatus.IMPORTED -> MaterialTheme.colorScheme.primary
                                        LinkStatus.FAILED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (item.status == LinkStatus.DOWNLOADING) {
                                LinearProgressIndicator(
                                    progress = { item.progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionHeader("About")
        Text(
            "Petal Music — BridgeFXWin desktop edition. The same look as the Android player, " +
                "driving the software AutoMix engine and Device Link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun AccountCard(name: String, status: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
