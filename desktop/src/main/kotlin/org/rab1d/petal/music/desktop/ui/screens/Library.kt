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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.library.DesktopTrack
import org.rab1d.petal.music.desktop.link.DeviceLinkServer
import org.rab1d.petal.music.desktop.ui.components.CardCaption
import org.rab1d.petal.music.desktop.ui.components.CoverArt
import org.rab1d.petal.music.desktop.ui.components.DesktopSource
import org.rab1d.petal.music.desktop.ui.components.GlassCard
import org.rab1d.petal.music.desktop.ui.components.PetalButton
import org.rab1d.petal.music.desktop.ui.components.PetalSectionLabel
import org.rab1d.petal.music.desktop.ui.components.SourceChip
import org.rab1d.petal.music.desktop.ui.components.SourceLogo
import org.rab1d.petal.music.desktop.ui.components.TrackRow
import org.rab1d.petal.music.desktop.ui.state.DesktopController
import org.rab1d.petal.music.desktop.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.desktop.ui.theme.PetalPalette
import org.rab1d.petal.music.desktop.ui.theme.petalPalette
import java.util.Calendar

@Composable
fun HomeScreen(
    controller: DesktopController,
    link: DeviceLinkServer,
    onSettings: () -> Unit,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onImportedPlaylist: (String) -> Unit,
    initialSource: DesktopSource = DesktopSource.LOCAL,
) {
    val palette = petalPalette()
    val sources = listOf(
        DesktopSource.TIDAL to "TIDAL",
        DesktopSource.LOCAL to "Device",
        DesktopSource.SPOTIFY to "Spotify",
        DesktopSource.YT_MUSIC to "YT Music",
        DesktopSource.DEEZER to "Deezer",
    )
    var selected by remember { mutableStateOf(initialSource) }
    val setPalette = LocalPaletteSetter.current
    LaunchedEffect(Unit) { setPalette(PetalPalette.Petal) }

    Box(modifier = Modifier.fillMaxSize().background(palette.wash)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.labelLarge, color = palette.accent.copy(alpha = 0.95f))
                    Text("Listen now", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                sources.forEach { (source, label) ->
                    SourceChip(label, selected == source, palette.accent, { selected = source }, source = source)
                }
            }
            LibraryEnvironment(
                controller = controller,
                link = link,
                selected = selected,
                currentKey = controller.current?.path?.toString(),
                onAlbum = onAlbum,
                onArtist = onArtist,
                onImportedPlaylist = onImportedPlaylist,
                leading = { item { HeroCard(controller) } },
            )
        }
    }
}

@Composable
fun LibraryEnvironment(
    controller: DesktopController,
    link: DeviceLinkServer,
    selected: DesktopSource,
    currentKey: String?,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onImportedPlaylist: (String) -> Unit,
    leading: (LazyListScope.() -> Unit)? = null,
) {
    when (selected) {
        DesktopSource.LOCAL -> LocalEnvironment(
            controller, link, currentKey, onAlbum, onArtist, onImportedPlaylist, leading,
        )
        else -> SignInEnvironment(selected)
    }
}

@Composable
private fun LocalEnvironment(
    controller: DesktopController,
    link: DeviceLinkServer,
    currentKey: String?,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onImportedPlaylist: (String) -> Unit,
    leading: (LazyListScope.() -> Unit)? = null,
) {
    val tracks = controller.library
    val albums = remember(tracks) { controller.albums() }
    val artists = remember(tracks) { controller.artists() }
    val recents = controller.recents
    val imported = link.playlists

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        leading?.invoke(this)
        if (recents.isNotEmpty()) {
            item { PetalSectionLabel("Recently played") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                    items(recents) { track ->
                        Column(Modifier.width(150.dp).clickable { controller.play(track) }) {
                            CoverArt(null, Modifier.size(150.dp), corner = 10)
                            CardCaption(track.title, track.artist)
                        }
                    }
                }
            }
        }
        if (imported.isNotEmpty()) {
            item { PetalSectionLabel("From your phone") }
            items(imported, key = { it.name }) { playlist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onImportedPlaylist(playlist.name) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(petalPalette().accent.copy(alpha = 0.25f)),
                    ) {
                        Text(
                            playlist.name.take(1).uppercase(),
                            color = petalPalette().accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(playlist.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${playlist.tracks.size} songs · metadata only",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PetalButton("Shuffle ${tracks.size} songs", { controller.playAll(shuffle = true) }, Modifier.weight(1f))
                    PetalButton("Play all", { controller.playAll(shuffle = false) }, Modifier.weight(1f), prominent = false)
                }
            }
        }
        if (albums.isNotEmpty()) {
            item { PetalSectionLabel("Albums") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
                    items(albums) { album ->
                        Column(Modifier.width(150.dp).clickable { onAlbum(album.id) }) {
                            CoverArt(null, Modifier.size(150.dp), corner = 10)
                            CardCaption(album.title, album.artist)
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { PetalSectionLabel("Artists") }
            items(artists.take(24)) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onArtist(artist.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(petalPalette().accent.copy(alpha = 0.25f)),
                    ) {
                        Text(artist.name.take(1).uppercase(), color = petalPalette().accent, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(artist.name, color = Color.White, maxLines = 1)
                        Text("${artist.trackCount} songs", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item { PetalSectionLabel("Songs") }
            items(tracks.take(200)) { track ->
                TrackRow(
                    track = track,
                    playing = track.path.toString() == currentKey,
                    onClick = { controller.play(track) },
                )
            }
        }
        if (tracks.isEmpty() && imported.isEmpty() && !controller.scanning) {
            item {
                EmptyEnvironment(
                    "No audio files yet in ${controller.libraryPath}. " +
                        "Playlists imported from your phone will appear above.",
                )
            }
        }
    }
}

@Composable
private fun SignInEnvironment(source: DesktopSource) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                SourceLogo(source, size = 40, tint = Color.White)
                Spacer(Modifier.height(20.dp))
                Text("Your ${source.label}, in Petal", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Streaming sources are connected in the Android app. The desktop edition plays your local library.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(controller: DesktopController) {
    val track = controller.current
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().height(380.dp)
            .then(if (track != null) Modifier.clickable { controller.playPause() } else Modifier),
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width * 0.90f, size.height * 0.26f)
            for (i in 1..7) {
                drawCircle(
                    color = accent.copy(alpha = 0.12f),
                    radius = size.width * (0.12f + i * 0.065f),
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Box(Modifier.matchParentSize().background(
            Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x880A0B10), 1f to Color(0xF20A0B10)),
        ))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text(
                if (track != null) "IN YOUR ROTATION" else "MADE FOR YOUR EVERYDAY",
                color = accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                track?.title ?: "All your music.\nOne little world.",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            )
            Text(
                track?.artist ?: "Your local library, in the desktop player.",
                color = Color(0xFFB9BAC4), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 2,
            )
            Spacer(Modifier.height(22.dp))
            if (track != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PetalButton(if (controller.playing) "Pause" else "Keep listening", { controller.playPause() }, Modifier.weight(1f))
                    GlassCard(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)), onClick = { controller.toggleShuffle() }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Shuffle, "Shuffle", tint = Color.White)
                        }
                    }
                }
            } else {
                PetalButton("Shuffle your music", { controller.playAll(shuffle = true) }, Modifier.width(200.dp))
            }
        }
    }
}

@Composable
private fun EmptyEnvironment(message: String) {
    GlassCard(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(message, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp))
    }
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Late night listening"
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}
