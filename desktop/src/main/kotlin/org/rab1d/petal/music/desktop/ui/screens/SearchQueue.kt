/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.library.DesktopTrack
import org.rab1d.petal.music.desktop.ui.components.CardCaption
import org.rab1d.petal.music.desktop.ui.components.CoverArt
import org.rab1d.petal.music.desktop.ui.components.DesktopSource
import org.rab1d.petal.music.desktop.ui.components.GlassCard
import org.rab1d.petal.music.desktop.ui.components.PetalSectionLabel
import org.rab1d.petal.music.desktop.ui.components.SourceChip
import org.rab1d.petal.music.desktop.ui.components.TrackRow
import org.rab1d.petal.music.desktop.ui.state.DesktopController
import org.rab1d.petal.music.desktop.ui.theme.petalPalette

@Composable
fun SearchScreen(
    controller: DesktopController,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { controller.search(query) }
    val palette = petalPalette()

    Column(Modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 6.dp))
        Text("Find your next favourite.", color = Color(0xFFAAA6AD), modifier = Modifier.padding(start = 20.dp, bottom = 20.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, albums…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF242428), unfocusedContainerColor = Color(0xFF242428),
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = palette.accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            DesktopSource.entries.forEach { s ->
                SourceChip(s.label, s == DesktopSource.LOCAL, palette.accent, {}, source = s)
            }
        }
        when {
            query.isBlank() -> Box(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Search your local library.", color = Color.White.copy(alpha = 0.55f))
            }
            results.isEmpty -> GlassCard(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Nothing found for \"$query\"", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(18.dp))
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                if (results.tracks.isNotEmpty()) {
                    item { PetalSectionLabel("Tracks · ${results.tracks.size}") }
                    items(results.tracks) { track ->
                        TrackRow(track, onClick = { controller.play(track, results.tracks) }, playing = track.path == controller.current?.path)
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { PetalSectionLabel("Albums") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                            items(results.albums) { album ->
                                Column(Modifier.width(132.dp).clickable { onAlbum(album.id) }) {
                                    CoverArt(null, Modifier.size(132.dp), corner = 10)
                                    CardCaption(album.title, album.artist)
                                }
                            }
                        }
                    }
                }
                if (results.artists.isNotEmpty()) {
                    item { PetalSectionLabel("Artists") }
                    items(results.artists) { artist ->
                        Text(
                            artist.name, color = Color.White,
                            modifier = Modifier.fillMaxWidth().clickable { onArtist(artist.id) }.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(controller: DesktopController) {
    val current = controller.current
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        ) {
            Text("Up next", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { controller.toggleShuffle() }) {
                Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (controller.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
            }
            IconButton(onClick = { controller.setRepeat(if (controller.repeatMode == 1) 0 else 1) }) {
                Icon(
                    Icons.Filled.Repeat,
                    "Repeat list",
                    tint = if (controller.repeatMode == 1) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                )
            }
            IconButton(onClick = { controller.setRepeat(if (controller.repeatMode == 2) 0 else 2) }) {
                Icon(
                    Icons.Filled.RepeatOne,
                    "Repeat one",
                    tint = if (controller.repeatMode == 2) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                )
            }
        }
        Text("A little more of what you love.", color = Color(0xFFAAA6AD), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (controller.queue.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Let the music begin", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text("Play a song from your library. Your upcoming songs will appear here.", color = Color(0xFFAAA6AD), modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
            if (current != null) {
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                            CoverArt(null, Modifier.size(64.dp), corner = 16)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge, color = petalPalette().accent)
                                Text(current.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(current.artist, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
            val upNext = controller.queue.drop(controller.queueIndex + 1)
            if (upNext.isNotEmpty()) {
                item { PetalSectionLabel("Up next") }
                items(upNext.size) { i ->
                    val track = upNext[i]
                    val absolute = controller.queueIndex + 1 + i
                    TrackRow(
                        track = track,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = { controller.playFromQueue(absolute) },
                        trailing = {
                            IconButton(onClick = { controller.removeFromQueue(absolute) }) {
                                Icon(Icons.Filled.Close, "Remove from queue", tint = Color.White.copy(alpha = 0.5f))
                            }
                        },
                    )
                }
            }
        }
    }
}
