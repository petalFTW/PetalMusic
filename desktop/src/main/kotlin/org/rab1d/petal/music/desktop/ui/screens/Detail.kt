/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.rab1d.petal.music.desktop.ui.components.ArtHeaderScreen
import org.rab1d.petal.music.desktop.ui.components.NumberedTrackRow
import org.rab1d.petal.music.desktop.ui.components.PlayShufflePills
import org.rab1d.petal.music.desktop.ui.components.rememberPagePalette
import org.rab1d.petal.music.desktop.ui.state.DesktopController

@Composable
fun AlbumDetailScreen(controller: DesktopController, albumId: String, onBack: () -> Unit) {
    val tracks = remember(controller.library, albumId) { controller.albumTracks(albumId) }
    val artist = tracks.firstOrNull()?.artist.orEmpty()
    ArtHeaderScreen(
        artUri = null,
        title = albumId,
        metaLine = "ALBUM · ${tracks.size} SONG${if (tracks.size == 1) "" else "S"}",
        onBack = onBack,
        headerExtras = {
            if (tracks.isNotEmpty()) {
                PlayShufflePills(
                    onShuffle = { controller.playAll(tracks, shuffle = true) },
                    onPlay = { controller.playAll(tracks, shuffle = false) },
                    accent = rememberPagePalette(null).accent,
                )
            }
        },
    ) {
        items(tracks.size) { i ->
            val track = tracks[i]
            NumberedTrackRow(
                number = i + 1,
                track = track,
                playing = track.path == controller.current?.path,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { controller.play(track, tracks) },
            )
        }
    }
}

@Composable
fun ArtistDetailScreen(controller: DesktopController, artistId: String, onBack: () -> Unit) {
    val tracks = remember(controller.library, artistId) { controller.artistTracks(artistId) }
    ArtHeaderScreen(
        artUri = null,
        title = artistId,
        metaLine = "ARTIST · ${tracks.size} SONG${if (tracks.size == 1) "" else "S"}",
        onBack = onBack,
        headerExtras = {
            if (tracks.isNotEmpty()) {
                PlayShufflePills(
                    onShuffle = { controller.playAll(tracks, shuffle = true) },
                    onPlay = { controller.playAll(tracks, shuffle = false) },
                    accent = rememberPagePalette(null).accent,
                )
            }
        },
    ) {
        items(tracks.size) { i ->
            val track = tracks[i]
            NumberedTrackRow(
                number = i + 1,
                track = track,
                playing = track.path == controller.current?.path,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { controller.play(track, tracks) },
            )
        }
    }
}
