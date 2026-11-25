/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rab1d.petal.music.data.RecentEntry
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.CoverArt
import org.rab1d.petal.music.ui.components.GlyphButton
import org.rab1d.petal.music.ui.components.HeaderAction
import org.rab1d.petal.music.ui.components.HeaderPlay
import org.rab1d.petal.music.ui.components.HeroText
import org.rab1d.petal.music.ui.components.PetalIcons
import org.rab1d.petal.music.ui.components.SharedCovers
import org.rab1d.petal.music.ui.components.GlassLevel
import org.rab1d.petal.music.ui.components.backdropSource
import org.rab1d.petal.music.ui.components.drawGrain
import org.rab1d.petal.music.ui.components.glass
import org.rab1d.petal.music.ui.components.pressable
import org.rab1d.petal.music.ui.components.pullUp
import org.rab1d.petal.music.ui.components.sleeveLight
import org.rab1d.petal.music.ui.theme.LocalNowPlayingTheme
import org.rab1d.petal.music.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalPalette
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space
import org.rab1d.petal.music.ui.theme.rememberArtTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

// One page for the lot: what you were listening to, then your whole library.
// The whole page takes its colours from the featured cover.
@Composable
fun HomeScreen(
    viewModel: PetalViewModel,
    onSettings: () -> Unit,
    onOpenSource: (Source) -> Unit,
    onAlbum: (String, String) -> Unit,
    onArtist: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
    onDownloads: () -> Unit = {},
    initialSource: Source = Source.TIDAL,
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val recentMedia by viewModel.recentMedia.collectAsState()
    val track = state.current
    val heroPlaylist = recentMedia.firstOrNull { it.type == "playlist" && it.artUri != null }
    // Same art and seed as the hero tile, so the page and the hero share one base colour.
    val art = track?.artUri ?: heroPlaylist?.artUri
    val seed = track?.let { "${it.title} · ${it.artist}" }
        ?: heroPlaylist?.let { "${viewModel.recentTitle(it)} · ${it.subtitle}" }
    val body = @Composable {
        HomeBody(viewModel, onSettings, onOpenSource, onAlbum, onArtist, onPlaylist, onDownloads, initialSource)
    }
    if (art == null) body()
    else org.rab1d.petal.music.ui.theme.ArtSurface(rememberArtTheme(art, seed), body)
}

@Composable
private fun HomeBody(
    viewModel: PetalViewModel,
    onSettings: () -> Unit,
    onOpenSource: (Source) -> Unit,
    onAlbum: (String, String) -> Unit,
    onArtist: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
    onDownloads: () -> Unit,
    initialSource: Source,
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val recentMedia by viewModel.recentMedia.collectAsState()
    val heroPlaylist = recentMedia.firstOrNull { it.type == "playlist" && it.artUri != null }
        ?: recentMedia.firstOrNull { it.type == "playlist" }
    var selected by rememberSaveable(initialSource) { mutableStateOf(initialSource) }
    val c = Petal.colors
    val now = LocalNowPlayingTheme.current

    val setPalette = LocalPaletteSetter.current
    LaunchedEffect(Unit) { setPalette(PetalPalette.Petal) }
    LaunchedEffect(Unit) { viewModel.loadTidalLibrary() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        // The page is lit from the top by whatever is playing; the list scrolls over it.
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        var greetingPx by remember { mutableIntStateOf(0) }
        // The light belongs to the top of the page: it moves with the greeting and is gone once that is.
        Canvas(Modifier.fillMaxSize().clipToBounds().graphicsLayer {
            // Greeting (0) and feature (1) carry it; follow whichever is on screen.
            val items = list.layoutInfo.visibleItemsInfo
            val greeting = items.firstOrNull { it.index == 0 }
            val feature = items.firstOrNull { it.index == 1 }
            if (greeting != null) greetingPx = greeting.size
            translationY = when {
                greeting != null -> greeting.offset.toFloat()
                feature != null -> (feature.offset - greetingPx).toFloat()
                else -> -size.height
            }
        }) {
            val h = size.height * .62f
            val span = maxOf(size.width, h)
            for (stop in now.stops) {
                val r = span * (.4f + .6f * sqrt(stop.weight))
                val center = Offset(stop.x * size.width, stop.y * h * .8f)
                val a = if (c.dark) .85f else .30f
                drawCircle(Brush.radialGradient(listOf(stop.color.copy(alpha = a), stop.color.copy(alpha = 0f)), center, r), r, center)
            }
            // Fade from the page colour itself (not transparent black), finishing well above the bottom.
            drawRect(Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), h * .45f / size.height to c.bg.copy(alpha = 0f), h / size.height to c.bg, 1f to c.bg))
        }
        // Grain over the whole page, so the light has no edge where it ends.
        Canvas(Modifier.fillMaxSize()) { drawGrain(.04f) }

        val listBackdrop = org.rab1d.petal.music.ui.components.rememberBackdrop()
        Box(Modifier.fillMaxSize().backdropSource(listBackdrop)) {
        LibraryEnvironment(
            viewModel = viewModel,
            selected = selected,
            currentKey = state.current?.key(),
            onAlbum = onAlbum,
            onArtist = onArtist,
            onPlaylist = onPlaylist,
            onDownloads = onDownloads,
            onSettings = onSettings,
            listState = list,
            leading = {
                item(key = "greeting") { Greeting(onSettings) }
                item(key = "hero") {
                    val track = state.current
                    when {
                        track != null -> FeatureTile(
                            label = if (state.playing) "Playing now" else "Paused",
                            title = track.title,
                            subtitle = track.artist,
                            artUri = track.artUri,
                            action = if (state.playing) "Pause" else "Keep listening",
                            onPrimary = { viewModel.playPause() },
                        )
                        heroPlaylist != null -> FeatureTile(
                            label = "Where you left off",
                            title = viewModel.recentTitle(heroPlaylist),
                            subtitle = heroPlaylist.subtitle,
                            artUri = heroPlaylist.artUri,
                            action = "Play",
                            onPrimary = { viewModel.playRecent(heroPlaylist) },
                            onShuffle = { viewModel.playRecent(heroPlaylist, shuffle = true) },
                            onOpen = { openRecent(heroPlaylist, onAlbum, onPlaylist) },
                        )
                        else -> {
                            val local = viewModel.allTracks()
                            FeatureTile(
                                label = "Nothing's playing",
                                title = if (local.isNotEmpty()) "${"%,d".format(local.size)} songs on this phone" else "Start with TIDAL",
                                subtitle = if (local.isNotEmpty()) "Shuffle the lot, or pick a source below."
                                    else "Sign in and your saved music lands here, next to anything on the phone.",
                                artUri = viewModel.tidalLibrary.albums.firstOrNull()?.artUri ?: viewModel.albums().firstOrNull()?.artUri,
                                action = if (local.isNotEmpty()) "Shuffle" else "Open TIDAL",
                                onPrimary = {
                                    if (local.isNotEmpty()) viewModel.playTracks(local, 0, shuffle = true)
                                    else onOpenSource(Source.TIDAL)
                                },
                            )
                        }
                    }
                }
                val heroId = heroPlaylist?.id.takeIf { state.current == null }
                val shelf = recentMedia.filterNot { it.type == "playlist" && it.id == heroId }
                if (shelf.isNotEmpty()) {
                    item(key = "recent-title") {
                        Text("Recently played", style = MaterialTheme.typography.headlineMedium, color = c.ink,
                            modifier = Modifier.padding(start = Space.gutter, top = Space.section, bottom = Space.m))
                    }
                    item(key = "recent") { RecentShelf(viewModel, shelf.take(12), onAlbum, onPlaylist) }
                }
                item(key = "library-title") {
                    Text("Your library", style = MaterialTheme.typography.displaySmall, color = c.ink,
                        modifier = Modifier.padding(start = Space.gutter, top = Space.section + Space.l, bottom = Space.s))
                }
                item(key = "sources") {
                    SourcePills(selected) { selected = it }
                }
            },
        )
        }
        // The status bar gets a strip of glass, so the page scrolls under it, not into it.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .glass(listBackdrop, GlassLevel.Sheet, c.bg),
        )
    }
}

@Composable
private fun Greeting(onSettings: () -> Unit) {
    val c = Petal.colors
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = Space.gutter, end = Space.xs, top = Space.l)) {
            Column(Modifier.weight(1f)) {
                Text(
                    SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date()),
                    style = PetalType.time.copy(fontSize = 12.sp), color = c.text2,
                )
                Text(greeting(), style = MaterialTheme.typography.displayLarge, color = c.ink,
                    modifier = Modifier.padding(top = Space.xs))
            }
            GlyphButton(onSettings, "Settings") { Icon(Icons.Rounded.Settings, null, tint = c.text2) }
        }
    }
}

/**
 * The featured tile: the cover, full bleed and edge to edge, settling in with a slow zoom.
 * It darkens into its own colour under the title, then melts into the page, so there is
 * no edge anywhere for the page's light to be cut off against.
 */
@Composable
private fun FeatureTile(
    label: String,
    title: String,
    subtitle: String,
    artUri: String?,
    action: String,
    onPrimary: () -> Unit,
    onShuffle: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    val theme = rememberArtTheme(artUri, "$title · $subtitle")
    val c = Petal.colors
    val reduced = org.rab1d.petal.music.ui.theme.LocalReducedMotion.current
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    // Square to the width, but never more than about 60% of the screen's height.
    val artHeight = minOf(screen.screenWidthDp * 1f, screen.screenHeightDp * .6f).dp
    // One slow settle per cover: the art arrives a little close and eases back. Not a loop.
    val zoom = remember(artUri) { androidx.compose.animation.core.Animatable(if (reduced) 1f else 1.08f) }
    LaunchedEffect(artUri) { zoom.animateTo(1f, androidx.compose.animation.core.tween(1400, easing = org.rab1d.petal.music.ui.theme.Motion.Enter)) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.pressable(onClick = onOpen) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(artHeight)
                .clipToBounds()
                // Top: the art fades to nothing, so whatever light is behind the greeting shows through.
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .22f to Color.Black),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) {
            CoverArt(
                artUri,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                shape = androidx.compose.ui.graphics.RectangleShape,
                seed = "$title · $subtitle",
            )
            // Bottom: the art runs into its own colour, under the title.
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.verticalGradient(.42f to Color.Transparent, .78f to theme.base.copy(alpha = .82f), 1f to theme.base))
                drawGrain(.05f)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = theme.ink,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = Space.gutter, bottom = Space.s))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to theme.base, .5f to theme.base, 1f to c.bg))
                .padding(horizontal = Space.gutter),
        ) {
            HeroText(title, theme.ink, MaterialTheme.typography.displayMedium, maxLines = 3, minSize = 27.sp)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = theme.text2, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Space.l, bottom = Space.section + Space.xl),
            ) {
                HeaderPlay(theme, onPrimary, label = action, shuffle = action == "Shuffle", playing = action == "Pause")
                if (onShuffle != null) HeaderAction(theme, "Shuffle", onShuffle) {
                    Icon(PetalIcons.Shuffle, null, tint = theme.ink)
                }
            }
        }
    }
}

/** Recently played: the newest gets a big sleeve, the rest step down. */
@Composable
private fun RecentShelf(
    viewModel: PetalViewModel,
    entries: List<RecentEntry>,
    onAlbum: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        verticalAlignment = Alignment.Bottom,
        contentPadding = PaddingValues(horizontal = Space.gutter),
    ) {
        itemsIndexed(entries, key = { _, e -> "${e.type}/${e.source}/${e.id}" }) { i, entry ->
            val size = if (i == 0) 196.dp else 132.dp
            val key = "${entry.source}/${entry.id}"
            Column(
                Modifier
                    .width(size)
                    .pressable(onClick = { SharedCovers.tapped(key, "recent"); openRecent(entry, onAlbum, onPlaylist) }),
            ) {
                CoverArt(
                    entry.artUri,
                    Modifier.size(size).sharedCover(key, "recent"),
                    shape = if (entry.type == "playlist") Radius.tile else Radius.sleeve,
                    seed = "${entry.title} · ${entry.subtitle}",
                )
                Text(viewModel.recentTitle(entry), style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = Petal.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
                Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = Petal.colors.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun openRecent(
    entry: RecentEntry,
    onAlbum: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
) {
    if (entry.type == "playlist") onPlaylist(entry.source, entry.id)
    else onAlbum(entry.source, entry.id)
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Up late"
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        h < 22 -> "Good evening"
        else -> "Late one"
    }
}
