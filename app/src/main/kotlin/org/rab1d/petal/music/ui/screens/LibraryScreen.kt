/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.CoverArt
import org.rab1d.petal.music.ui.components.CreatePlaylistButton
import org.rab1d.petal.music.ui.components.EmptyState
import org.rab1d.petal.music.ui.components.FilterPills
import org.rab1d.petal.music.ui.components.GlyphButton
import org.rab1d.petal.music.ui.components.LocalChromeInset
import org.rab1d.petal.music.ui.components.OfflineDrawing
import org.rab1d.petal.music.ui.components.Pill
import org.rab1d.petal.music.ui.components.PillButton
import org.rab1d.petal.music.ui.components.SearchField
import org.rab1d.petal.music.ui.components.SectionLabel
import org.rab1d.petal.music.ui.components.SharedCovers
import org.rab1d.petal.music.ui.components.ShelfSkeleton
import org.rab1d.petal.music.ui.components.SourceLogo
import org.rab1d.petal.music.ui.components.TrackRow
import org.rab1d.petal.music.ui.components.pressable
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

/** What the library can be filtered to. */
enum class LibraryKind(val label: String) {
    ALL("All"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists"), SONGS("Songs"), DOWNLOADED("Downloaded")
}

private enum class Sort(val label: String) { RECENT("As added"), TITLE("A–Z"), ARTIST("By artist") }

/** One library, whatever the source: what the environment renders. */
private data class LibraryContent(
    val playlists: List<Shelved>,
    val albums: List<Shelved>,
    val artists: List<Shelved>,
    val songs: List<Track>,
    val downloaded: List<Track>,
    val downloadBytes: Long,
)

/** Standalone library (kept for callers that open it directly). */
@Composable
fun LibraryScreen(
    viewModel: PetalViewModel,
    initialSource: Source = Source.TIDAL,
    onAlbum: (String, String) -> Unit,
    onArtist: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
    onDownloads: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var selected by rememberSaveable(initialSource) { mutableStateOf(initialSource) }
    LibraryEnvironment(
        viewModel, selected, state.current?.key(), onAlbum, onArtist, onPlaylist, onDownloads, onSettings,
        leading = {
            item(key = "library-title") { Text("Library", style = MaterialTheme.typography.displayMedium, color = Petal.colors.ink,
                modifier = Modifier.padding(start = Space.gutter, top = Space.xxl)) }
            item(key = "sources") { SourcePills(selected) { selected = it } }
        },
    )
}

/** The sources as pills, each with its own mark. */
@Composable
fun SourcePills(selected: Source, onSelect: (Source) -> Unit) {
    FilterPills(
        options = listOf(Source.TIDAL to "TIDAL", Source.LOCAL to "Device", Source.SPOTIFY to "Spotify", Source.YT_MUSIC to "YT Music")
            .map { (s, label) -> Pill(s, label) { SourceLogo(s, size = 16, tint = if (s == selected) Petal.colors.bg else Petal.colors.text2) } },
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.padding(vertical = Space.s),
    )
}

/** The per-source body, shared by the Library tab and the merged home page. */
@Composable
fun LibraryEnvironment(
    viewModel: PetalViewModel,
    selected: Source,
    currentKey: String?,
    onAlbum: (String, String) -> Unit,
    onArtist: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    leading: (LazyListScope.() -> Unit)? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    var kind by rememberSaveable { mutableStateOf(LibraryKind.ALL) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf(Sort.RECENT) }
    var filter by rememberSaveable(selected, kind) { mutableStateOf("") }

    val offline by viewModel.offline.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val savedPlaylists by viewModel.playlists.collectAsState()
    val dlTracks = downloads.map { it.toTrack() }
    val dlBytes = downloads.sumOf { it.bytes }

    // Offline first: until the streaming sessions have connected, and until the remote
    // collection has actually arrived, remote tabs keep showing the device library
    // rather than a spinner or an empty shelf. Online content replaces it in place.
    val remotePending = !viewModel.servicesReady || when (selected) {
        Source.TIDAL -> viewModel.tidalLibrary.loading &&
            viewModel.tidalLibrary.tracks.isEmpty() &&
            viewModel.tidalLibrary.albums.isEmpty() &&
            viewModel.tidalLibrary.playlists.isEmpty()
        else -> false
    }
    // No network: never show a remote source, fall back to the downloaded set.
    val offlineSet = offline && selected != Source.LOCAL
    val mode = if (remotePending && selected != Source.LOCAL && !offlineSet) Source.LOCAL else selected
    val remote = !offlineSet && mode != Source.LOCAL
    if (remote) LaunchedEffect(mode) { viewModel.loadSourceLibrary(mode) }
    val lib = if (remote) viewModel.libraryFor(mode) else null
    val signedOut = remote && !viewModel.sourceLoggedIn(mode)

    val content = when {
        offlineSet -> LibraryContent(emptyList(), emptyList(), emptyList(), dlTracks, dlTracks, dlBytes)
        mode == Source.LOCAL -> LibraryContent(
            playlists = savedPlaylists.map {
                Shelved("local/${it.id}", it.name, "${it.tracks.size.coerceAtLeast(it.trackKeys.size)} songs", it.tracks.firstOrNull()?.artUri)
            },
            albums = viewModel.albums().map { Shelved("${it.source.id}/${it.id}", it.title, it.artist, it.artUri) },
            artists = viewModel.artists().map { Shelved("local/${it.id}", it.name, "${it.trackCount} songs", null) },
            songs = viewModel.allTracks(),
            downloaded = dlTracks,
            downloadBytes = dlBytes,
        )
        else -> LibraryContent(
            playlists = lib!!.playlists.map {
                Shelved("${mode.id}/${it.id}", it.name, if (it.trackCount >= 0) "${it.trackCount} songs" else it.source.label, it.artUri)
            },
            albums = lib.albums.map { Shelved("${mode.id}/${it.id}", it.title, it.artist, it.artUri) },
            artists = lib.artists.map { Shelved("${mode.id}/${it.id}", it.name, "", null) },
            songs = lib.tracks,
            downloaded = dlTracks,
            downloadBytes = dlBytes,
        )
    }

    fun open(kindOf: LibraryKind, key: String) {
        val (s, id) = key.split("/", limit = 2)
        when (kindOf) {
            LibraryKind.PLAYLISTS -> onPlaylist(s, id)
            LibraryKind.ARTISTS -> onArtist(s, id)
            else -> onAlbum(s, id)
        }
    }
    fun <T> sorted(list: List<T>, title: (T) -> String, artist: (T) -> String): List<T> {
        val q = filter.trim()
        val f = if (q.isEmpty()) list else list.filter { title(it).contains(q, true) || artist(it).contains(q, true) }
        return when (sort) {
            Sort.RECENT -> f
            Sort.TITLE -> f.sortedBy { title(it).lowercase() }
            Sort.ARTIST -> f.sortedBy { artist(it).lowercase() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Space.xxl + LocalChromeInset.current),
    ) {
        leading?.invoke(this)

        if (signedOut) {
            item(key = "sign-in") { SignIn(mode, onSettings) }
            return@LazyColumn
        }
        if (offlineSet) item(key = "offline") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                Text("No connection. Showing what's downloaded.", style = MaterialTheme.typography.bodyMedium, color = Petal.colors.text2)
            }
        }
        item(key = "kinds") {
            FilterPills(
                options = LibraryKind.entries.filter { !offlineSet || it == LibraryKind.SONGS || it == LibraryKind.DOWNLOADED || it == LibraryKind.ALL }
                    .map { Pill(it, it.label) },
                selected = kind,
                onSelect = { kind = it },
                height = 34.dp,
                modifier = Modifier.padding(vertical = Space.xs),
            )
        }

        if (lib?.loading == true && content.albums.isEmpty() && content.playlists.isEmpty() && content.songs.isEmpty()) {
            item(key = "skeleton") { Box(Modifier.padding(top = Space.xl)) { ShelfSkeleton() } }
        }
        lib?.error?.let { error ->
            item(key = "error") {
                EmptyState(
                    title = "${mode.label} didn't answer",
                    body = error,
                    action = "Try again",
                    onAction = { viewModel.loadSourceLibrary(mode, force = true) },
                    drawing = { OfflineDrawing() },
                )
            }
        }

        when (kind) {
            LibraryKind.ALL -> allShelves(
                content, mode, offlineSet, currentKey, viewModel,
                onKind = { kind = it }, onOpen = ::open, onDownloads = onDownloads,
            )
            LibraryKind.SONGS, LibraryKind.DOWNLOADED -> {
                val list = sorted(if (kind == LibraryKind.SONGS) content.songs else content.downloaded, { it.title }, { it.artist })
                item(key = "toolbar") { Toolbar(filter, { filter = it }, sort, { sort = it }, grid = null, onGrid = {}) }
                if (list.isEmpty()) item(key = "empty") { EmptyFor(kind, mode, filter) }
                else {
                    item(key = "shuffle") {
                        Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                            PillButton("Shuffle ${list.size}", { viewModel.playTracks(list, 0, shuffle = true) })
                        }
                    }
                    items(list.take(500), key = { "${kind.name}-${it.key()}" }) { track ->
                        TrackRow(track = track, playing = track.key() == currentKey,
                            onClick = { viewModel.playTracks(list, list.indexOf(track)) })
                    }
                }
            }
            else -> {
                val list = sorted(
                    when (kind) { LibraryKind.PLAYLISTS -> content.playlists; LibraryKind.ARTISTS -> content.artists; else -> content.albums },
                    { it.title }, { it.subtitle },
                )
                item(key = "toolbar") { Toolbar(filter, { filter = it }, sort, { sort = it }, grid = grid, onGrid = { grid = !grid }) }
                if (kind == LibraryKind.PLAYLISTS && mode in viewModel.playlistSources()) {
                    item(key = "create-playlist") { CreatePlaylistButton(viewModel, mode) }
                }
                if (list.isEmpty()) item(key = "empty") { EmptyFor(kind, mode, filter) }
                else if (grid) {
                    val round = kind == LibraryKind.ARTISTS
                    items(list.chunked(2), key = { row -> "${kind.name}-${row.first().key}" }) { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.l),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
                        ) {
                            row.forEach { item -> GridTile(item, round, "${kind.name}-grid", Modifier.weight(1f)) { open(kind, item.key) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(list, key = { "${kind.name}-l-${it.key}" }) { item ->
                        ListTile(item, kind == LibraryKind.ARTISTS, "${kind.name}-list") { open(kind, item.key) }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.allShelves(
    content: LibraryContent,
    mode: Source,
    offlineSet: Boolean,
    currentKey: String?,
    viewModel: PetalViewModel,
    onKind: (LibraryKind) -> Unit,
    onOpen: (LibraryKind, String) -> Unit,
    onDownloads: () -> Unit,
) {
    val nothing = content.playlists.isEmpty() && content.albums.isEmpty() && content.artists.isEmpty() && content.songs.isEmpty()
    if (nothing) {
        item(key = "empty") {
            EmptyState(
                title = if (mode == Source.LOCAL) "No music on this phone yet" else "Your ${mode.label} library is empty",
                body = if (mode == Source.LOCAL) "Copy some files into Music, or open TIDAL above and save what you like."
                    else "Heart songs, save albums or make a playlist in ${mode.label} and they'll show up here.",
            )
        }
        return
    }
    if (content.playlists.isNotEmpty()) {
        item(key = "playlists-title") { ShelfHeader("Playlists", content.playlists.size) { onKind(LibraryKind.PLAYLISTS) } }
        item(key = "playlists") { CoverShelf(content.playlists.take(16), "lib-playlists", { onOpen(LibraryKind.PLAYLISTS, it) }, cover = 136.dp) }
    }
    if (content.albums.isNotEmpty()) {
        item(key = "albums-title") { ShelfHeader("Albums", content.albums.size) { onKind(LibraryKind.ALBUMS) } }
        item(key = "albums") { CoverShelf(content.albums.take(16), "lib-albums", { onOpen(LibraryKind.ALBUMS, it) }, cover = 176.dp) }
    }
    if (content.artists.isNotEmpty()) {
        item(key = "artists-title") { ShelfHeader("Artists", content.artists.size) { onKind(LibraryKind.ARTISTS) } }
        item(key = "artists") { CoverShelf(content.artists.take(20), "lib-artists", { onOpen(LibraryKind.ARTISTS, it) }, cover = 104.dp, round = true) }
    }
    if (content.downloaded.isNotEmpty() && !offlineSet) {
        item(key = "downloads") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.xl)
                    .pressable(onClick = onDownloads)
                    .padding(horizontal = Space.gutter, vertical = Space.m),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Downloads", style = MaterialTheme.typography.headlineSmall, color = Petal.colors.ink)
                    Text("${content.downloaded.size} songs · ${"%.0f".format(content.downloadBytes / 1_000_000.0)} MB on this phone",
                        style = PetalType.time.copy(fontSize = 12.sp), color = Petal.colors.text2, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Petal.colors.text3)
            }
        }
    }
    if (content.songs.isNotEmpty()) {
        item(key = "songs-title") { ShelfHeader(if (mode == Source.LOCAL) "Songs" else "Liked songs", content.songs.size) { onKind(LibraryKind.SONGS) } }
        items(content.songs.take(8), key = { "all-${it.key()}" }) { track ->
            TrackRow(track = track, playing = track.key() == currentKey,
                onClick = { viewModel.playTracks(content.songs, content.songs.indexOf(track)) })
        }
    }
}

/** A section title with its count, tappable to see all of it. */
@Composable
private fun ShelfHeader(title: String, count: Int, onAll: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.s, top = Space.section, bottom = Space.m)
            .pressable(onClick = onAll, onClickLabel = "See all $title"),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Petal.colors.ink)
        Text("$count", style = PetalType.time, color = Petal.colors.text3, modifier = Modifier.padding(start = Space.s, bottom = 4.dp).weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Petal.colors.text3)
    }
}

@Composable
private fun Toolbar(
    filter: String,
    onFilter: (String) -> Unit,
    sort: Sort,
    onSort: (Sort) -> Unit,
    grid: Boolean?,
    onGrid: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s, top = Space.m, bottom = Space.s),
    ) {
        SearchField(filter, onFilter, "Filter", modifier = Modifier.weight(1f), height = 44.dp)
        GlyphButton({ onSort(Sort.entries[(sort.ordinal + 1) % Sort.entries.size]) }, "Sort: ${sort.label}") {
            Icon(Icons.Rounded.SortByAlpha, null, tint = if (sort == Sort.RECENT) Petal.colors.text2 else Petal.colors.accent)
        }
        if (grid != null) GlyphButton(onGrid, if (grid) "Show as list" else "Show as grid") {
            Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, null, tint = Petal.colors.text2)
        }
    }
    if (sort != Sort.RECENT) {
        Text(sort.label, style = PetalType.time, color = Petal.colors.accent, modifier = Modifier.padding(start = Space.gutter))
    }
}

@Composable
private fun GridTile(item: Shelved, round: Boolean, shelf: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.pressable(onClick = { SharedCovers.tapped(item.key, shelf); onClick() }),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CoverArt(
            item.artUri,
            Modifier.fillMaxWidth().aspectRatio(1f).sharedCover(item.key, shelf),
            shape = if (round) Radius.circle else Radius.sleeve,
            seed = "${item.title} · ${item.subtitle}",
        )
        Text(item.title, style = MaterialTheme.typography.titleSmall, color = Petal.colors.ink, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodyMedium,
            color = Petal.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListTile(item: Shelved, round: Boolean, shelf: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = { SharedCovers.tapped(item.key, shelf); onClick() })
            .padding(horizontal = Space.gutter, vertical = Space.s),
    ) {
        CoverArt(item.artUri, Modifier.size(56.dp).sharedCover(item.key, shelf),
            shape = if (round) Radius.circle else Radius.sleeve, seed = "${item.title} · ${item.subtitle}")
        Column(Modifier.padding(start = Space.m).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = Petal.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodyMedium,
                color = Petal.colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyFor(kind: LibraryKind, source: Source, filter: String) {
    if (filter.isNotBlank()) {
        Text("Nothing here matches \"$filter\".", color = Petal.colors.text2, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(Space.gutter))
        return
    }
    val (title, body) = when (kind) {
        LibraryKind.PLAYLISTS -> "No playlists yet" to "Start one with the button above, then long-press songs to add them."
        LibraryKind.ALBUMS -> "No albums yet" to "Save an album in ${source.label} and it lands here."
        LibraryKind.ARTISTS -> "No artists yet" to "Follow someone in ${source.label}, or play a few of their songs."
        LibraryKind.DOWNLOADED -> "Nothing downloaded" to "Tap the arrow on an album or a song to keep it for when the signal drops."
        else -> "No songs yet" to "Like a song in ${source.label} and it shows up here."
    }
    EmptyState(title = title, body = body)
}

@Composable
private fun SignIn(source: Source, onSettings: () -> Unit) {
    EmptyState(
        title = "Your ${source.label}, in Petal",
        body = "Sign in once and your saved songs, albums and playlists sit next to everything else, mixed into one queue.",
        action = "Sign in to ${source.label}",
        onAction = onSettings,
        drawing = { SourceLogo(source, size = 48, tint = Petal.colors.ink) },
    )
}
