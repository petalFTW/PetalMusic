/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.rab1d.petal.music.data.downloads.DownloadManager
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.model.playable
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.CoverArt
import org.rab1d.petal.music.ui.components.DetailScaffold
import org.rab1d.petal.music.ui.components.DiscSeparator
import org.rab1d.petal.music.ui.components.DownloadDot
import org.rab1d.petal.music.ui.components.EmptyState
import org.rab1d.petal.music.ui.components.HeaderAction
import org.rab1d.petal.music.ui.components.HeaderPlay
import org.rab1d.petal.music.ui.components.HeaderStyle
import org.rab1d.petal.music.ui.components.NumberedTrackRow
import org.rab1d.petal.music.ui.components.PetalIcons
import org.rab1d.petal.music.ui.components.PlaylistActionsButton
import org.rab1d.petal.music.ui.components.SearchField
import org.rab1d.petal.music.ui.components.SectionLabel
import org.rab1d.petal.music.ui.components.SharedCovers
import org.rab1d.petal.music.ui.components.TrackRow
import org.rab1d.petal.music.ui.components.TrackRowSkeletons
import org.rab1d.petal.music.ui.components.LocalNavScope
import org.rab1d.petal.music.ui.components.pressable
import org.rab1d.petal.music.ui.components.sharedArt
import org.rab1d.petal.music.ui.components.removeFromPlaylistAction
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

@Composable
fun AlbumDetailScreen(
    viewModel: PetalViewModel,
    source: Source,
    albumId: String,
    onBack: () -> Unit,
    onArtist: (String, String) -> Unit = { _, _ -> },
    onAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, albumId, attempt) {
        loading = true
        tracks = if (source == Source.LOCAL) {
            viewModel.albumTracks(albumId)
        } else {
            viewModel.sourceAlbumTracks(source, albumId)
        }
        loading = false
    }
    val album = if (source == Source.LOCAL) viewModel.albums().firstOrNull { it.id == albumId } else null
    val art = album?.artUri ?: tracks.firstOrNull()?.artUri
    val title = album?.title ?: tracks.firstOrNull()?.album?.ifEmpty { null } ?: "Album"
    val artist = album?.artist ?: tracks.firstOrNull()?.artist.orEmpty()
    val year = album?.year?.takeIf { it > 0 } ?: tracks.firstOrNull()?.year.takeIf { (it ?: 0) > 0 }
    LaunchedEffect(source, albumId, tracks.isNotEmpty()) {
        if (tracks.isNotEmpty()) {
            viewModel.addRecentMedia("album", source, albumId, title, artist, art)
        }
    }
    val dlProgress by viewModel.downloadProgress.collectAsState()
    val downloadedKeys by viewModel.downloadedKeys.collectAsState()
    val artistLink = tracks.firstOrNull()?.let { viewModel.findArtist(it) }
    val runtime = tracks.sumOf { it.durationMs.coerceAtLeast(0) }
    val more = remember(artist, albumId, tracks.size) {
        val all = if (source == Source.LOCAL) viewModel.albums() else viewModel.libraryFor(source).albums
        all.filter { it.id != albumId && it.artist.equals(artist, ignoreCase = true) }.take(10)
    }

    DetailScaffold(
        artUri = art,
        seed = "$title · $artist",
        title = title,
        onBack = onBack,
        coverKey = SharedCovers.headerKey("${source.id}/$albumId"),
        byline = { theme ->
            val goToArtist = org.rab1d.petal.music.ui.components.LocalTrackActions.current?.goToArtist
            Byline(artist, theme, artistLink?.let { (s, id) -> { onArtist(s, id) } }
                ?: tracks.firstOrNull()?.let { t -> goToArtist?.let { go -> { go(t) } } })
        },
        meta = listOfNotNull(
            "Album",
            year?.toString(),
            tracks.size.takeIf { it > 0 }?.let { "$it song${if (it == 1) "" else "s"}" },
            runtime.takeIf { it > 0 }?.let { formatRuntime(it) },
        ).joinToString(" · "),
        actions = if (tracks.isEmpty()) null else { theme ->
            HeaderPlay(theme, { viewModel.playTracks(tracks, 0) })
            HeaderAction(theme, "Shuffle", { viewModel.playTracks(tracks, 0, shuffle = true) }) {
                Icon(PetalIcons.Shuffle, null, tint = theme.ink)
            }
            DownloadAction(
                theme,
                downloading = tracks.any {
                    val p = dlProgress[it.key()]
                    p?.state == DownloadManager.State.DOWNLOADING || p?.state == DownloadManager.State.QUEUED
                },
                done = tracks.all { it.key() in downloadedKeys },
                onClick = { viewModel.downloadAll(tracks) },
            )
        },
    ) {
        when {
            loading && tracks.isEmpty() -> item { TrackRowSkeletons(8) }
            tracks.isEmpty() -> item {
                EmptyState(
                    title = "This one didn't come through",
                    body = "${source.label} didn't send the songs on this album. It may be off the service in your region, or the connection dropped.",
                    action = "Try again",
                    onAction = { attempt++ },
                )
            }
        }
        val multiDisc = tracks.map { it.discNumber }.distinct().size > 1
        tracks.forEachIndexed { i, track ->
            if (multiDisc && (i == 0 || tracks[i - 1].discNumber != track.discNumber)) {
                item(key = "disc-${track.discNumber}") { DiscSeparator(track.discNumber) }
            }
            item(key = "t-$i-${track.key()}") {
                NumberedTrackRow(
                    number = track.trackNumber.takeIf { it > 0 } ?: (i + 1),
                    track = track,
                    playing = track.key() == state.current?.key(),
                    onClick = { viewModel.playTracks(tracks, i) },
                    albumArtist = artist,
                )
            }
        }
        if (more.isNotEmpty()) {
            item(key = "more") {
                SectionLabel("More by $artist")
                CoverShelf(
                    items = more.map { Shelved("${it.source.id}/${it.id}", it.title, it.year.takeIf { y -> y > 0 }?.toString() ?: "", it.artUri) },
                    shelf = "more",
                    onOpen = { key -> key.split("/", limit = 2).let { (s, id) -> onAlbum(s, id) } },
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    viewModel: PetalViewModel,
    source: Source,
    artistId: String,
    onBack: () -> Unit,
    onAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, artistId, attempt) {
        loading = true
        tracks = if (source == Source.LOCAL) {
            viewModel.artistTracks(artistId)
        } else {
            viewModel.sourceArtistTracks(source, artistId)
        }
        loading = false
    }
    val artist = if (source == Source.LOCAL) viewModel.artists().firstOrNull { it.id == artistId.lowercase().replace(" ", "") } else null
    val name = artist?.name ?: tracks.firstOrNull()?.artist ?: "Artist"
    val art = tracks.firstOrNull()?.artUri
    // The records this artist's songs come from, newest first: real albums, not guesses.
    val records = remember(tracks) {
        tracks.filter { it.albumId != null && it.album.isNotBlank() }
            .groupBy { it.albumId }
            .map { (id, ts) -> Shelved("${source.id}/$id", ts.first().album, ts.first().year.takeIf { it > 0 }?.toString() ?: "", ts.first().artUri) to ts.first().year }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    DetailScaffold(
        artUri = art,
        seed = name,
        title = name,
        onBack = onBack,
        style = HeaderStyle.Portrait,
        meta = when {
            loading && tracks.isEmpty() -> ""
            source == Source.LOCAL -> "${tracks.size} song${if (tracks.size == 1) "" else "s"} on this phone"
            else -> "${tracks.size} song${if (tracks.size == 1) "" else "s"} on ${source.label}"
        },
        actions = if (tracks.isEmpty()) null else { theme ->
            HeaderPlay(theme, { viewModel.playTracks(tracks, 0) })
            HeaderAction(theme, "Shuffle", { viewModel.playTracks(tracks, 0, shuffle = true) }) {
                Icon(PetalIcons.Shuffle, null, tint = theme.ink)
            }
        },
    ) {
        when {
            loading && tracks.isEmpty() -> item { TrackRowSkeletons(6) }
            tracks.isEmpty() -> item {
                EmptyState(
                    title = "No songs to show",
                    body = "Nothing by $name came back from ${source.label}.",
                    action = "Try again",
                    onAction = { attempt++ },
                )
            }
        }
        if (tracks.isNotEmpty()) item(key = "top") { SectionLabel("Songs") }
        val top = tracks.take(5)
        top.forEachIndexed { i, track ->
            item(key = "top-$i") {
                // The first five in the order the source ranks them, with big rank numbers.
                TrackRow(
                    track = track,
                    playing = track.key() == state.current?.key(),
                    onClick = { viewModel.playTracks(tracks, i) },
                    leading = {
                        Text("${i + 1}", style = PetalType.rank.copy(fontSize = PetalType.rank.fontSize * .62f),
                            color = Petal.colors.text3, modifier = Modifier.width(40.dp))
                    },
                )
            }
        }
        if (records.size > 1 || (records.size == 1 && tracks.size > 5)) {
            item(key = "records") {
                SectionLabel("Records")
                CoverShelf(records, shelf = "artist-records", onOpen = { key -> key.split("/", limit = 2).let { (s, id) -> onAlbum(s, id) } })
            }
        }
        if (tracks.size > 5) {
            item(key = "rest") { SectionLabel("Everything else") }
            tracks.drop(5).forEachIndexed { j, track ->
                item(key = "rest-$j") {
                    TrackRow(
                        track = track,
                        playing = track.key() == state.current?.key(),
                        onClick = { viewModel.playTracks(tracks, j + 5) },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    viewModel: PetalViewModel,
    source: Source,
    playlistId: String,
    onBack: () -> Unit,
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val savedPlaylists by viewModel.playlists.collectAsState()
    var rawTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision = viewModel.playlistRevision

    val saved = if (source == Source.LOCAL) savedPlaylists.firstOrNull { it.id == playlistId } else null
    LaunchedEffect(source, playlistId) { viewModel.loadSourceLibrary(source) }
    LaunchedEffect(source, playlistId, saved?.tracks, saved?.trackKeys, revision) {
        loading = true
        error = null
        try {
            rawTracks = (if (source != Source.LOCAL) {
                viewModel.sourcePlaylistTracks(source, playlistId, force = revision > 0)
            } else saved?.let { viewModel.playlistTracks(it) }.orEmpty()).playable()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't load playlist" }
        finally { loading = false }
    }
    val tracks = if (query.isBlank()) {
        rawTracks
    } else {
        rawTracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }
    val summary = viewModel.playlistSummaries(source).firstOrNull { it.id == playlistId }
        ?: viewModel.searchResults.playlists.firstOrNull { it.source == source && it.id == playlistId }
    val editable = source == Source.LOCAL || summary?.editable == true
    val name = if (source != Source.LOCAL) summary?.name ?: "Playlist" else saved?.name ?: "Playlist"
    val art = (if (source != Source.LOCAL) summary?.artUri else null)
    LaunchedEffect(source, playlistId, rawTracks.isNotEmpty(), name) {
        if (rawTracks.isNotEmpty() && name.isNotBlank() && name != "Playlist") {
            viewModel.addRecentMedia(
                "playlist", source, playlistId, name,
                "${rawTracks.size} tracks", art ?: rawTracks.firstOrNull()?.artUri,
            )
        }
    }
    val dlProgress by viewModel.downloadProgress.collectAsState()
    val downloadedKeys by viewModel.downloadedKeys.collectAsState()
    val runtime = rawTracks.sumOf { it.durationMs.coerceAtLeast(0) }
    // No cover of its own: a collage of the first four different sleeves in it.
    val collage = remember(rawTracks) { rawTracks.mapNotNull { it.artUri }.distinct().take(4) }

    DetailScaffold(
        artUri = art ?: collage.firstOrNull(),
        seed = name,
        title = name,
        onBack = onBack,
        coverKey = SharedCovers.headerKey("${source.id}/$playlistId"),
        byline = if (art == null && collage.size == 4) { _ -> CollageStrip(collage) } else null,
        meta = listOfNotNull(
            "Playlist",
            source.label,
            rawTracks.size.takeIf { it > 0 }?.let { "$it song${if (it == 1) "" else "s"}" },
            runtime.takeIf { it > 0 }?.let { formatRuntime(it) },
        ).joinToString(" · "),
        actions = { theme ->
            if (rawTracks.isNotEmpty()) {
                HeaderPlay(theme, { viewModel.playTracks(tracks, 0) })
                HeaderAction(theme, "Shuffle", { viewModel.playTracks(tracks, 0, shuffle = true) }) {
                    Icon(PetalIcons.Shuffle, null, tint = theme.ink)
                }
                DownloadAction(
                    theme,
                    downloading = tracks.any {
                        val p = dlProgress[it.key()]
                        p?.state == DownloadManager.State.DOWNLOADING || p?.state == DownloadManager.State.QUEUED
                    },
                    done = tracks.isNotEmpty() && tracks.all { it.key() in downloadedKeys },
                    onClick = { viewModel.downloadAll(tracks) },
                )
            }
            Spacer(Modifier.weight(1f))
            HeaderAction(theme, "Refresh playlist", { if (!loading) viewModel.refreshPlaylist(source, playlistId) }) {
                Icon(Icons.Rounded.Refresh, null, tint = theme.text2)
            }
            if (editable) PlaylistActionsButton(viewModel, source, playlistId, name, onDeleted = onBack)
        },
        headerExtras = {
            error?.let {
                Text(it, color = Petal.colors.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
            }
            if (rawTracks.size > 8) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Find in $name",
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                )
            }
        },
    ) {
        if (loading && rawTracks.isEmpty()) item { TrackRowSkeletons(8) }
        else if (rawTracks.isEmpty() && error == null) item {
            EmptyState(
                title = "Nothing on it yet",
                body = "Long-press any song, or swipe it left, and pick Add to playlist to put it here.",
            )
        } else if (tracks.isEmpty() && query.isNotBlank()) item {
            Text("Nothing in $name matches \"$query\".", color = Petal.colors.text2,
                modifier = Modifier.padding(Space.gutter))
        }
        items(tracks.size, key = { i -> "p-$i-${tracks[i].key()}" }) { i ->
            val track = tracks[i]
            // A filtered tap still queues the whole playlist from that song.
            val queueIndex = rawTracks.indexOfFirst { it.key() == track.key() }.coerceAtLeast(0)
            TrackRow(
                track = track,
                playing = track.key() == state.current?.key(),
                onClick = { viewModel.playTracks(rawTracks, queueIndex) },
                menuExtras = if (editable) listOf(removeFromPlaylistAction {
                    scope.launch {
                        try { viewModel.removeSourcePlaylistTrack(source, playlistId, track) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            android.widget.Toast.makeText(context, e.message ?: "Couldn't remove track",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) else emptyList(),
            )
        }
    }
}

// ---- shared pieces ---------------------------------------------------------------------

@Composable
private fun Byline(artist: String, theme: ArtTheme, onClick: (() -> Unit)?) {
    if (artist.isBlank()) return
    Text(
        artist,
        style = MaterialTheme.typography.headlineMedium,
        color = if (onClick != null) theme.accentOnArt else theme.text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (onClick != null) Modifier.pressable(onClick = onClick, onClickLabel = "Open artist") else Modifier,
    )
}

/** Four sleeves from the playlist, side by side under the title: what's in it at a glance. */
@Composable
private fun CollageStrip(arts: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        arts.forEach { AsyncImage(it, null, modifier = Modifier.size(36.dp).clip(Radius.sleeve)) }
    }
}

@Composable
private fun DownloadAction(theme: ArtTheme, downloading: Boolean, done: Boolean, onClick: () -> Unit) {
    HeaderAction(theme, if (done) "Downloaded" else "Download all", onClick) {
        when {
            downloading -> CircularProgressIndicator(strokeWidth = 2.dp, color = theme.accentOnArt, modifier = Modifier.size(20.dp))
            else -> Icon(if (done) Icons.Rounded.DownloadDone else Icons.Rounded.Download, null,
                tint = if (done) theme.accentOnArt else theme.ink)
        }
    }
}

/** An item on a cover shelf: [key] is "source/id". */
data class Shelved(val key: String, val title: String, val subtitle: String, val artUri: String?)

/**
 * A horizontal shelf of square sleeves. The tapped cover is remembered so the detail
 * header can grow out of it.
 */
@Composable
fun CoverShelf(
    items: List<Shelved>,
    shelf: String,
    onOpen: (String) -> Unit,
    cover: androidx.compose.ui.unit.Dp = 148.dp,
    round: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        contentPadding = PaddingValues(horizontal = Space.gutter),
    ) {
        items(items, key = { it.key }) { item ->
            Column(
                Modifier
                    .width(cover)
                    .pressable(onClick = { SharedCovers.tapped(item.key, shelf); onOpen(item.key) }),
                horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                CoverArt(
                    item.artUri,
                    Modifier.size(cover).sharedCover(item.key, shelf),
                    shape = if (round) Radius.circle else Radius.sleeve,
                    seed = "${item.title} · ${item.subtitle}",
                )
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = Petal.colors.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
                if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = Petal.colors.text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The tapped-cover side of the cover → header morph. */
@Composable
fun Modifier.sharedCover(item: String, shelf: String): Modifier =
    sharedArt(SharedCovers.key(item, shelf), LocalNavScope.current)
