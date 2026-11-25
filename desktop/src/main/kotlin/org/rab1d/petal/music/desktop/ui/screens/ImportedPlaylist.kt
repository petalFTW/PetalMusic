/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.link.DeviceLinkServer
import org.rab1d.petal.music.desktop.link.LinkTrack
import org.rab1d.petal.music.desktop.ui.components.ArtHeaderScreen
import org.rab1d.petal.music.desktop.ui.components.CoverArt
import org.rab1d.petal.music.desktop.ui.components.PetalButton

/** Playlist metadata imported from the phone. Audio is not downloaded here. */
@Composable
fun ImportedPlaylistScreen(
    link: DeviceLinkServer,
    name: String,
    onBack: () -> Unit,
) {
    val playlist = link.playlists.firstOrNull { it.name == name }
    if (playlist == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            Text("Playlist not found", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            PetalButton("Back", onBack)
        }
        return
    }

    ArtHeaderScreen(
        artUri = null,
        title = playlist.name,
        metaLine = "IMPORTED PLAYLIST · ${playlist.tracks.size} SONG" +
            "${if (playlist.tracks.size == 1) "" else "S"} · METADATA ONLY",
        onBack = onBack,
        headerExtras = {
            Text(
                "Imported from your phone. Audio was not downloaded; enable downloads " +
                    "on the PC to make these tracks playable.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        },
    ) {
        items(playlist.tracks.size) { i ->
            ImportedTrackRow(i + 1, playlist.tracks[i])
        }
    }
}

@Composable
private fun ImportedTrackRow(number: Int, track: LinkTrack) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "$number",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.width(26.dp),
        )
        CoverArt(null, Modifier.size(44.dp), corner = 8)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
