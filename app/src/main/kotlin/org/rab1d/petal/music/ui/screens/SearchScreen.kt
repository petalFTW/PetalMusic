/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.CoverArt
import org.rab1d.petal.music.ui.components.EmptyState
import org.rab1d.petal.music.ui.components.HeaderPlay
import org.rab1d.petal.music.ui.components.LocalChromeInset
import org.rab1d.petal.music.ui.components.SearchField
import org.rab1d.petal.music.ui.components.SectionLabel
import org.rab1d.petal.music.ui.components.SourceLogo
import org.rab1d.petal.music.ui.components.TrackRow
import org.rab1d.petal.music.ui.components.TrackRowSkeletons
import org.rab1d.petal.music.ui.components.backdropSource
import org.rab1d.petal.music.ui.components.glass
import org.rab1d.petal.music.ui.components.GlassLevel
import org.rab1d.petal.music.ui.components.pressable
import org.rab1d.petal.music.ui.components.rememberBackdrop
import org.rab1d.petal.music.ui.components.sleeveLight
import org.rab1d.petal.music.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalPalette
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space
import org.rab1d.petal.music.ui.theme.rememberArtTheme
import org.rab1d.petal.music.ui.theme.seedArtTheme

/**
 * Search across every connected source. The field floats on glass over the results;
 * with no query it offers the artists you've been playing and the genres on the phone.
 */
@Composable
fun SearchScreen(
    viewModel: PetalViewModel,
    onAlbum: (String, String) -> Unit = { _, _ -> },
    onArtist: (String, String) -> Unit = { _, _ -> },
    onPlaylist: (String, String) -> Unit = { _, _ -> },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val results = viewModel.searchResults
    val sources = viewModel.searchSources.ifEmpty { viewModel.activeSearchSources() }
    // Deezer is disabled for now: keep it out of the search filters.
    val sourceFilters = listOf(Source.LOCAL, Source.TIDAL, Source.SPOTIFY, Source.YT_MUSIC)
    val recents = viewModel.recentsTracks()
    val c = Petal.colors

    // An artist name tapped somewhere with no artist page lands here as a search.
    val requested = org.rab1d.petal.music.ui.components.SearchRequest.pending
    LaunchedEffect(requested) {
        org.rab1d.petal.music.ui.components.SearchRequest.take()?.let { query = it }
    }
    LaunchedEffect(query) {
        delay(350)
        debounced = query
    }
    LaunchedEffect(debounced, sources) {
        viewModel.search(debounced)
    }

    val setPalette = LocalPaletteSetter.current
    LaunchedEffect(Unit) { setPalette(PetalPalette.Petal); viewModel.loadTidalLibrary() }

    val backdrop = rememberBackdrop()
    val density = LocalDensity.current
    var headerHeight by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = with(density) { headerHeight.toDp() },
                bottom = Space.xxl + LocalChromeInset.current,
            ),
            modifier = Modifier.fillMaxSize().backdropSource(backdrop).background(c.bg),
        ) {
            viewModel.searchErrors.forEach { (source, message) ->
                item { Text("${source.label}: $message", color = c.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs)) }
            }
            when {
                viewModel.searching -> item { TrackRowSkeletons(7) }
                query.isBlank() -> browse(viewModel, recents, onAlbum, onSearch = { query = it })
                results.isEmpty -> item {
                    EmptyState(
                        title = "Nothing for \"$query\"",
                        body = "Checked ${sources.joinToString { it.label }}. Try fewer words, or the artist's name on its own.",
                    )
                }
                else -> results(viewModel, query, state.current?.key(), onAlbum, onArtist, onPlaylist)
            }
        }

        // The header: title, the field and the source toggles, on glass over the results.
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = it.height }
                // Results scroll right under the field and toggles, where frosted text would
                // fight the labels: sheet glass plus extra page colour, and a hairline edge.
                .glass(backdrop, GlassLevel.Sheet, c.bg)
                .background(c.bg.copy(alpha = .35f))
                .drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Text("Search", style = MaterialTheme.typography.displayMedium, color = c.ink,
                modifier = Modifier.padding(start = Space.gutter, top = Space.l, bottom = Space.m))
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Songs, artists, albums",
                modifier = Modifier.padding(horizontal = Space.gutter),
                tint = c.ink.copy(alpha = .07f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter, vertical = Space.m),
            ) {
                sourceFilters.forEach { s ->
                    SourceToggle(s, on = sources.contains(s)) {
                        val next = if (sources.contains(s)) sources - s else sources + s
                        viewModel.updateSearchSources(if (next.isEmpty()) setOf(Source.LOCAL) else next)
                        if (query.isNotBlank()) viewModel.search(query)
                    }
                }
            }
        }
    }
}

/** A multi-select source toggle: solid when included. */
@Composable
private fun SourceToggle(source: Source, on: Boolean, onClick: () -> Unit) {
    val c = Petal.colors
    val bg by animateColorAsState(if (on) c.ink else c.ink.copy(alpha = .07f), Motion.press(Motion.BASE), label = "toggleBg")
    val fg by animateColorAsState(if (on) c.bg else c.text2, Motion.press(Motion.BASE), label = "toggleFg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(34.dp)
            .clip(Radius.pill)
            .background(bg)
            .pressable(onClick = onClick, role = Role.Checkbox)
            .padding(horizontal = Space.m),
    ) {
        SourceLogo(source, size = 14, tint = fg)
        Text(source.label, style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(start = 6.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.browse(
    viewModel: PetalViewModel,
    recents: List<Track>,
    onAlbum: (String, String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val artists = recents.map { it.artist }.filter { it.isNotBlank() }.distinct().take(8)
    if (artists.isNotEmpty()) {
        item { SectionLabel("You've been playing") }
        items(artists) { artist ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onClick = { onSearch(artist) })
                    .padding(horizontal = Space.gutter, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.History, null, tint = Petal.colors.text3, modifier = Modifier.size(20.dp))
                Text(artist, style = MaterialTheme.typography.bodyLarge, color = Petal.colors.ink,
                    modifier = Modifier.padding(start = Space.l))
            }
        }
    }
    // Genres from the tags on the phone's own files: each tile shuffles that genre.
    val genres = viewModel.allTracks().filter { !it.genre.isNullOrBlank() }.groupBy { it.genre!!.trim() }
        .entries.sortedByDescending { it.value.size }.take(8)
    if (genres.isNotEmpty()) {
        item { SectionLabel("Genres on this phone") }
        items(genres.chunked(2)) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs + 2.dp),
            ) {
                row.forEach { (genre, tracks) ->
                    GenreTile(genre, tracks, Modifier.weight(1f)) { viewModel.playTracks(tracks, 0, shuffle = true) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    val tidal = viewModel.tidalLibrary
    if (tidal.albums.isNotEmpty()) {
        item { SectionLabel("From your TIDAL collection") }
        item {
            CoverShelf(tidal.albums.map { Shelved("tidal/${it.id}", it.title, it.artist, it.artUri) }, "search-tidal",
                onOpen = { k -> onAlbum("tidal", k.substringAfter("/")) }, cover = 132.dp)
        }
    }
    if (artists.isEmpty() && genres.isEmpty() && tidal.albums.isEmpty()) {
        item {
            EmptyState(
                title = "Search everywhere at once",
                body = "One search covers every source you're signed into. Songs from different services can sit in the same queue.",
            )
        }
    }
}

/** A genre: its own colour (hashed from the name) and a cover from it, set at an angle. */
@Composable
private fun GenreTile(genre: String, tracks: List<Track>, modifier: Modifier, onClick: () -> Unit) {
    val theme = remember(genre) { seedArtTheme(genre) }
    val art = tracks.firstOrNull { it.artUri != null }?.artUri
    Box(
        modifier
            .height(96.dp)
            .clip(Radius.tile)
            .sleeveLight(theme)
            .pressable(onClick = onClick, onClickLabel = "Shuffle $genre"),
    ) {
        if (art != null) {
            CoverArt(
                art,
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 18.dp, y = 10.dp)
                    .size(76.dp)
                    .graphicsLayer { rotationZ = 18f },
            )
        }
        Column(Modifier.padding(Space.m)) {
            Text(genre, style = MaterialTheme.typography.titleLarge, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${tracks.size}", style = org.rab1d.petal.music.ui.theme.PetalType.time, color = theme.text2)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(
    viewModel: PetalViewModel,
    query: String,
    currentKey: String?,
    onAlbum: (String, String) -> Unit,
    onArtist: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
) {
    val results = viewModel.searchResults
    // Top result: an artist whose name is the query, else the first song.
    val topArtist = results.artists.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }
    val topTrack = results.tracks.firstOrNull()
    item {
        when {
            topArtist != null -> TopResult(
                title = topArtist.name, subtitle = "Artist · ${topArtist.source.label}", artUri = null,
                onOpen = { onArtist(topArtist.source.id, topArtist.id) }, onPlay = null,
            )
            topTrack != null -> TopResult(
                title = topTrack.title, subtitle = "${topTrack.artist} · ${topTrack.source.label}", artUri = topTrack.artUri,
                onOpen = null, onPlay = { viewModel.playTracks(listOf(topTrack), 0) },
            )
        }
    }
    val songs = results.tracks.drop(if (topArtist == null && topTrack != null) 1 else 0)
    if (songs.isNotEmpty()) {
        item { SectionLabel("Songs") }
        items(songs.take(12), key = { it.key() }) { track ->
            TrackRow(track = track, playing = track.key() == currentKey, onClick = { viewModel.playTracks(listOf(track), 0) })
        }
    }
    if (results.artists.isNotEmpty()) {
        item { SectionLabel("Artists") }
        item {
            CoverShelf(results.artists.map { Shelved("${it.source.id}/${it.id}", it.name, it.source.label, null) }, "search-artists",
                onOpen = { k -> k.split("/", limit = 2).let { (s, id) -> onArtist(s, id) } }, cover = 112.dp, round = true)
        }
    }
    if (results.albums.isNotEmpty()) {
        item { SectionLabel("Albums") }
        item {
            CoverShelf(results.albums.map { Shelved("${it.source.id}/${it.id}", it.title, it.artist, it.artUri) }, "search-albums",
                onOpen = { k -> k.split("/", limit = 2).let { (s, id) -> onAlbum(s, id) } }, cover = 148.dp)
        }
    }
    if (results.playlists.isNotEmpty()) {
        item { SectionLabel("Playlists") }
        item {
            CoverShelf(results.playlists.map {
                Shelved("${it.source.id}/${it.id}", it.name, if (it.trackCount >= 0) "${it.trackCount} songs" else it.source.label, it.artUri)
            }, "search-playlists", onOpen = { k -> k.split("/", limit = 2).let { (s, id) -> onPlaylist(s, id) } }, cover = 132.dp)
        }
    }
}

/** The top result: a big lit card, not another row. */
@Composable
private fun TopResult(title: String, subtitle: String, artUri: String?, onOpen: (() -> Unit)?, onPlay: (() -> Unit)?) {
    val theme = rememberArtTheme(artUri, "$title · $subtitle")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = Space.gutter, vertical = Space.l)
            .fillMaxWidth()
            .clip(Radius.tile)
            .sleeveLight(theme)
            .then(if (onOpen != null) Modifier.pressable(onClick = onOpen) else Modifier)
            .padding(Space.l),
    ) {
        CoverArt(artUri, Modifier.size(104.dp), shape = if (onOpen != null) Radius.circle else Radius.sleeve, seed = "$title · $subtitle")
        Column(Modifier.weight(1f).padding(start = Space.l)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = theme.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Space.xs))
            if (onPlay != null) Row(Modifier.padding(top = Space.m)) { HeaderPlay(theme, onPlay) }
        }
    }
}
