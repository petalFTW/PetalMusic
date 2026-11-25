/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.library.DesktopTrack
import org.rab1d.petal.music.desktop.ui.components.AutoSizeText
import org.rab1d.petal.music.desktop.ui.components.CoverArt
import org.rab1d.petal.music.desktop.ui.screens.AlbumDetailScreen
import org.rab1d.petal.music.desktop.ui.screens.ArtistDetailScreen
import org.rab1d.petal.music.desktop.ui.screens.HomeScreen
import org.rab1d.petal.music.desktop.ui.screens.ImportedPlaylistScreen
import org.rab1d.petal.music.desktop.ui.screens.NowPlayingScreen
import org.rab1d.petal.music.desktop.ui.screens.QueueScreen
import org.rab1d.petal.music.desktop.ui.screens.SearchScreen
import org.rab1d.petal.music.desktop.ui.screens.SettingsScreen
import org.rab1d.petal.music.desktop.ui.state.DesktopController
import org.rab1d.petal.music.desktop.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.desktop.ui.theme.PetalEnvironment
import org.rab1d.petal.music.desktop.ui.theme.PetalPalette
import org.rab1d.petal.music.desktop.link.DeviceLinkServer

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Queue : Route
    data object Settings : Route
    data class Album(val id: String) : Route
    data class Artist(val id: String) : Route
    data class ImportedPlaylist(val name: String) : Route
}

@Composable
fun AppRoot(controller: DesktopController, link: DeviceLinkServer) {
    var route by remember { mutableStateOf<Route>(Route.Home) }
    var palette by remember { mutableStateOf(PetalPalette.Petal) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showQueueOverlay by remember { mutableStateOf(false) }

    PetalEnvironment(palette = palette) {
        CompositionLocalProvider(LocalPaletteSetter provides { palette = it }) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (val r = route) {
                            Route.Home -> HomeScreen(
                                controller,
                                link,
                                onSettings = { route = Route.Settings },
                                onAlbum = { route = Route.Album(it) },
                                onArtist = { route = Route.Artist(it) },
                                onImportedPlaylist = { route = Route.ImportedPlaylist(it) },
                            )
                            Route.Search -> SearchScreen(controller, { route = Route.Album(it) }, { route = Route.Artist(it) })
                            Route.Queue -> QueueScreen(controller)
                            Route.Settings -> SettingsScreen(controller, link, onBack = { route = Route.Home })
                            is Route.Album -> AlbumDetailScreen(controller, r.id, onBack = { route = Route.Home })
                            is Route.Artist -> ArtistDetailScreen(controller, r.id, onBack = { route = Route.Home })
                            is Route.ImportedPlaylist -> ImportedPlaylistScreen(
                                link, r.name, onBack = { route = Route.Home },
                            )
                        }
                    }
                    if (!showNowPlaying) {
                        if (controller.current != null) {
                            MiniPlayer(
                                controller.current!!,
                                controller.playing,
                                onClick = { showNowPlaying = true },
                                onPlayPause = { controller.playPause() },
                                onNext = { controller.next() },
                            )
                        }
                        PetalNavBar(route, onSelect = { route = it })
                    }
                }

                AnimatedVisibility(
                    visible = showNowPlaying,
                    modifier = Modifier.fillMaxSize(),
                    enter = slideInVertically(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
                        initialOffsetY = { it },
                    ) + fadeIn(tween(200)),
                    exit = slideOutVertically(
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = 550f),
                        targetOffsetY = { it },
                    ) + fadeOut(tween(180)),
                ) {
                    NowPlayingScreen(controller, onClose = { showNowPlaying = false }, onOpenQueue = { showQueueOverlay = true })
                }

                AnimatedVisibility(
                    visible = showQueueOverlay,
                    modifier = Modifier.fillMaxSize(),
                    enter = slideInVertically(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 480f),
                        initialOffsetY = { it },
                    ) + fadeIn(tween(180)),
                    exit = slideOutVertically(
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = 550f),
                        targetOffsetY = { it },
                    ) + fadeOut(tween(160)),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        QueueScreen(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun PetalNavBar(selected: Route?, onSelect: (Route) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val items = listOf(
        Route.Home to (Icons.Filled.Home to "Listen now"),
        Route.Search to (Icons.Filled.Search to "Search"),
        Route.Queue to (Icons.AutoMirrored.Filled.QueueMusic to "Queue"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF191B23))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (route, item) ->
            val active = selected == route
            Column(
                Modifier
                    .weight(1f)
                    .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(route) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(item.first, null, tint = if (active) accent else Color(0xFFB9BAC4), modifier = Modifier.size(25.dp))
                Text(item.second, color = if (active) accent else Color(0xFFB9BAC4), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    track: DesktopTrack,
    playing: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = Color(0xFF29282D),
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onVerticalDrag = { _, drag -> acc += drag },
                        onDragEnd = { if (acc < -70.dp.toPx()) onClick() },
                    )
                }
                .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(null, Modifier.size(44.dp), corner = 7)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                AutoSizeText(track.title, baseSize = 16.sp, minSize = 11.sp, maxLines = 1, color = Color.White)
                AutoSizeText(track.artist, baseSize = 12.sp, minSize = 9.sp, maxLines = 1, color = Color(0xFFBBB5BE))
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next", tint = Color.White) }
        }
    }
}
