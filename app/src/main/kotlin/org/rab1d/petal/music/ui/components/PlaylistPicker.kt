/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import org.rab1d.petal.music.ui.theme.Petal
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.ui.PetalViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Lets any row in the app raise the "add to playlist" picker without threading
 * a callback through every screen. [AppRoot] provides the implementation and
 * hosts the dialog.
 */
val LocalAddToPlaylist = staticCompositionLocalOf<((Track) -> Unit)?> { null }

/** Holds the track whose picker is open (null = closed). */
class AddToPlaylistController {
    var track by mutableStateOf<Track?>(null)
        private set

    fun open(track: Track) {
        this.track = track
    }

    fun close() {
        track = null
    }
}

/** Overflow affordance on a track row; hidden when no host is installed. */
@Composable
fun TrackOverflowButton(track: Track, tint: Color = Petal.colors.ink.copy(alpha = 0.6f), onRemove: (() -> Unit)? = null) {
    val add = LocalAddToPlaylist.current ?: return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = tint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onRemove != null) DropdownMenuItem(
                text = { Text("Remove from playlist") },
                onClick = { expanded = false; onRemove() },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                onClick = {
                    expanded = false
                    add(track)
                },
                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
            )
        }
    }
}

/** The picker dialog itself; render once at the root of the app. */
@Composable
fun AddToPlaylistDialog(viewModel: PetalViewModel, controller: AddToPlaylistController) {
    val track = controller.track ?: return
    val playlists by viewModel.playlists.collectAsState()
    val context = LocalContext.current
    var newName by remember(track.key()) { mutableStateOf("") }
    var destination by remember(track.key()) { mutableStateOf(Source.LOCAL) }
    var busy by remember(track.key()) { mutableStateOf(false) }
    var error by remember(track.key()) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sources = viewModel.playlistSources()
    LaunchedEffect(track.key()) { sources.filter { it != Source.LOCAL }.forEach { viewModel.loadSourceLibrary(it) } }
    val destinations = if (destination == Source.LOCAL) {
        playlists.map { org.rab1d.petal.music.model.PlaylistSummary(it.id, Source.LOCAL, it.name,
            it.trackKeys.size.coerceAtLeast(it.tracks.size), editable = true) }
    } else viewModel.playlistSummaries(destination).filter { it.editable }

    fun perform(name: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
                Toast.makeText(context, "Added to \"$name\"", Toast.LENGTH_SHORT).show()
                controller.close()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn't update playlist" }
            finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) controller.close() },
        title = { Text("Add to playlist") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${track.title} — ${track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEach { source ->
                        androidx.compose.material3.FilterChip(
                            selected = source == destination, enabled = !busy,
                            onClick = { destination = source; error = null },
                            label = { Text(source.label) },
                        )
                    }
                }
                if (busy || (destination != Source.LOCAL && viewModel.libraryFor(destination).loading)) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                (error ?: viewModel.libraryFor(destination).error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(destinations, key = { "${it.source}/${it.id}" }) { pl ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    perform(pl.name) { viewModel.addTracksToPlaylist(pl.source, pl.id, listOf(track)) }
                                }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                Icons.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(pl.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (pl.trackCount >= 0) "${pl.source.label} · ${pl.trackCount} tracks" else pl.source.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (destinations.isEmpty()) item {
                        Text("No editable ${destination.label} playlists yet. Create one below.",
                            modifier = Modifier.padding(10.dp))
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    enabled = !busy,
                    label = { Text("New playlist") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            enabled = !busy && newName.isNotBlank(),
                            onClick = {
                                val name = newName.trim()
                                perform(name) { viewModel.createSourcePlaylist(destination, name, listOf(track)) }
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Create")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !busy, onClick = { controller.close() }) { Text("Done") } },
    )
}
