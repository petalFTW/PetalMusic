/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

/** One row of a context menu. */
data class MenuAction(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val run: () -> Unit)

/**
 * What any track row can do, provided once by the app root: rows stay dumb and every
 * list gets the same like, download and menu behaviour.
 */
@Stable
class TrackActions(
    val isFavorite: (Track) -> Boolean,
    val toggleFavorite: (Track) -> Unit,
    val download: (Track) -> DownloadDot?,
    val toggleDownload: (Track) -> Unit,
    val addToPlaylist: (Track) -> Unit,
    val goToArtist: ((Track) -> Unit)?,
    val goToAlbum: ((Track) -> Unit)?,
    val playNext: (Track) -> Unit = {},
    val addToQueue: (Track) -> Unit = {},
) {
    var open by mutableStateOf<Pair<Track, List<MenuAction>>?>(null)
        private set

    /** Open the menu for [track], with [extra] actions (e.g. "Remove from playlist") first. */
    fun openMenu(track: Track, extra: List<MenuAction> = emptyList()) {
        val dl = download(track)
        open = track to buildList {
            addAll(extra)
            add(MenuAction("Play next", PetalIcons.PlayNext) { playNext(track) })
            add(MenuAction("Add to queue", PetalIcons.Queue) { addToQueue(track) })
            add(MenuAction("Add to playlist", PetalIcons.AddTo) { addToPlaylist(track) })
            // A file already on the phone has nothing to download.
            if (track.source != org.rab1d.petal.music.model.Source.LOCAL || dl != null) add(
                when (dl) {
                    DownloadDot.Downloaded -> MenuAction("Remove download", Icons.Rounded.DeleteOutline) { toggleDownload(track) }
                    is DownloadDot.InProgress -> MenuAction("Downloading…", Icons.Rounded.Download) {}
                    null -> MenuAction("Download", Icons.Rounded.Download) { toggleDownload(track) }
                },
            )
            goToArtist?.let { go -> if (track.artist.isNotBlank()) add(MenuAction("Go to ${track.artist}", Icons.Rounded.Person) { go(track) }) }
            goToAlbum?.let { go -> if (track.albumId != null && track.album.isNotBlank()) add(MenuAction("Go to ${track.album}", Icons.Rounded.Album) { go(track) }) }
        }
    }

    fun close() { open = null }
}

val LocalTrackActions = staticCompositionLocalOf<TrackActions?> { null }

/** The audible deck's level, shared by every row's "now playing" equaliser. */
val LocalLiveLevel = staticCompositionLocalOf<LiveLevel?> { null }

/**
 * The long-press menu: the page frosts behind a preview of the song (its cover and
 * title, lit by its own colours) and a Sheet-level glass list of actions.
 */
@Composable
fun TrackMenuHost(actions: TrackActions, backdrop: Backdrop?, tint: Color) {
    val reduced = LocalReducedMotion.current
    val entry = actions.open
    AnimatedVisibility(
        visible = entry != null,
        enter = fadeIn(Motion.enter(Motion.BASE)),
        exit = fadeOut(Motion.exit(Motion.FAST)),
    ) {
        val last = remember { mutableStateOf(entry) }
        if (entry != null) last.value = entry
        val (track, items) = last.value ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .42f))
                .clickable(remember { MutableInteractionSource() }, null) { actions.close() }
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(Space.xl)
                    .widthIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .animateEnterExit(
                        enter = if (reduced) fadeIn() else scaleIn(Motion.enter(Motion.BASE), .94f, TransformOrigin.Center),
                        exit = if (reduced) fadeOut() else scaleOut(Motion.exit(Motion.FAST), .97f),
                    ),
            ) {
                val theme = org.rab1d.petal.music.ui.theme.rememberArtTheme(track.artUri, "${track.title} · ${track.artist}")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Space.m)
                        .sleeveLight(theme)
                        .padding(Space.m),
                ) {
                    CoverArt(track.artUri, Modifier.size(64.dp), seed = "${track.title} · ${track.artist}")
                    Column(Modifier.padding(start = Space.m)) {
                        Text(track.title, style = MaterialTheme.typography.titleLarge, color = theme.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // The artist opens their page, closing the menu on the way.
                        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = theme.accentOnArt,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.pressable(
                                onClick = { actions.close(); actions.goToArtist?.invoke(track) },
                                onClickLabel = "Open ${track.artist}",
                            ))
                        if (track.durationMs > 0) Text(formatDuration(track.durationMs), style = PetalType.time,
                            color = theme.text3, modifier = Modifier.padding(top = Space.xs))
                    }
                }
                Column(Modifier.fillMaxWidth().glass(backdrop, GlassLevel.Sheet, tint, Radius.menu).padding(vertical = Space.s)) {
                    items.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressable(onClick = { actions.close(); a.run() })
                                .padding(horizontal = Space.l, vertical = 14.dp),
                        ) {
                            val color = if (a.danger) Petal.colors.danger else Petal.colors.ink
                            a.icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(22.dp)) }
                            Text(a.label, color = color, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = if (a.icon != null) Space.l else 0.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Remove-from-playlist, for playlist rows' menus. */
fun removeFromPlaylistAction(run: () -> Unit) = MenuAction("Remove from playlist", Icons.Rounded.RemoveCircleOutline, danger = true, run = run)

/** Small trailing download state: nothing when not downloaded, so rows stay quiet. */
@Composable
fun DownloadMark(state: DownloadDot?, tint: Color) {
    when (state) {
        DownloadDot.Downloaded -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = tint, modifier = Modifier.size(16.dp))
        is DownloadDot.InProgress -> androidx.compose.material3.CircularProgressIndicator(
            progress = { state.fraction },
            strokeWidth = 1.75.dp,
            color = tint,
            trackColor = tint.copy(alpha = .2f),
            modifier = Modifier.size(14.dp),
        )
        null -> {}
    }
}
