/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import org.rab1d.petal.music.ui.theme.Petal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import org.rab1d.petal.music.model.key
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track

val Source.tint: Color
    get() = when (this) {
        Source.SPOTIFY -> Color(0xFF1DB954)
        Source.YT_MUSIC -> Color(0xFFFF0033)
        Source.DEEZER -> Color(0xFFA238FF)
        Source.TIDAL -> Color(0xFF00B3E3)
        Source.LOCAL -> Color(0xFF8E4957)
    }

@Composable
fun SourceBadge(source: Source, modifier: Modifier = Modifier, size: Int = 26, origin: Source? = null) {
    if (origin != null && origin != source) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            SourceBadge(origin, size = size)
            SourceBadge(source, size = size)
        }
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.35f).toInt().dp))
            .background(source.tint.copy(alpha = 0.22f)),
    ) {
        SourceLogo(
            source = source,
            size = (size * 0.58f).toInt().coerceAtLeast(10),
            tint = source.tint,
        )
    }
}

/**
 * The list row every screen shares: cover, title, artist, and quiet trailing facts
 * (download state, duration). The playing row shows the live equaliser and takes the
 * accent. Swipe right to like, left to play it next; long-press for the menu. The
 * artist name is its own link.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    showCover: Boolean = true,
    menuExtras: List<MenuAction> = emptyList(),
    swipe: Boolean = true,
    showArtist: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val actions = LocalTrackActions.current
    val c = Petal.colors
    SwipeRow(
        track = track,
        actions = actions.takeIf { swipe },
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = actions?.let { a -> { a.openMenu(track, menuExtras) } },
                    onLongClickLabel = "More options",
                )
                .padding(horizontal = org.rab1d.petal.music.ui.theme.Space.gutter, vertical = 8.dp),
        ) {
            leading?.invoke()
            if (showCover) CoverArt(track.artUri, Modifier.size(48.dp), seed = "${track.title} · ${track.artist}")
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (showCover || leading != null) 12.dp else 0.dp, end = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing && leading == null) {
                        EqualiserGlyph(LocalLiveLevel.current, c.accent, Modifier.padding(end = 6.dp), size = 13.dp)
                    }
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (playing) c.accent else c.ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (track.explicit) ExplicitBadge(Modifier.padding(start = 6.dp))
                }
                if (showArtist && track.artist.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    SourceBadge(track.source, size = 15, origin = track.originSource)
                    ArtistLink(
                        track = track,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text2,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            trailing?.invoke()
            actions?.download?.invoke(track)?.let { DownloadMark(it, c.text3) }
            if (track.durationMs > 0) {
                Text(
                    formatDuration(track.durationMs),
                    style = org.rab1d.petal.music.ui.theme.PetalType.time,
                    color = c.text3,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (actions != null) {
                GlyphButton(onClick = { actions.openMenu(track, menuExtras) }, label = "More options", size = 40.dp) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.MoreVert, null,
                        tint = c.text3, modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                TrackOverflowButton(track)
            }
        }
    }
}

/**
 * Horizontal swipe on a row: right likes (the heart fills as you pull), left queues it
 * to play next. The row settles back without overshoot. Vertical scrolling is untouched:
 * the drag only starts past horizontal touch slop.
 */
@Composable
private fun SwipeRow(
    track: Track,
    actions: TrackActions?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (actions == null) { Box(modifier) { content() }; return }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dx = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val c = Petal.colors
    val liked = actions.isFavorite(track)
    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(track.key()) {
                val trigger = 88.dp.toPx()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dx.snapTo((dx.value + amount).coerceIn(-trigger * 1.4f, trigger * 1.4f)) }
                    },
                    onDragEnd = {
                        val v = dx.value
                        if (v > trigger) actions.toggleFavorite(track)
                        if (v < -trigger) actions.playNext(track)
                        scope.launch { dx.animateTo(0f, org.rab1d.petal.music.ui.theme.Motion.drag()) }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, org.rab1d.petal.music.ui.theme.Motion.drag()) } },
                )
            },
    ) {
        // What the swipe will do, revealed under the row.
        Row(
            Modifier.matchParentSize().padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pull = (dx.value / 88.dp.value / density()).coerceIn(-1f, 1f)
            if (pull > 0f) {
                HeartGlyph(liked != (pull >= 1f), outline = c.text2, fill = c.accent, size = 22.dp)
            }
            Box(Modifier.weight(1f))
            if (pull < 0f) {
                androidx.compose.material3.Icon(PetalIcons.PlayNext, "Play next",
                    tint = if (pull <= -1f) c.accent else c.text2, modifier = Modifier.size(22.dp))
            }
        }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(dx.value.toInt(), 0) }
                .background(if (dx.value != 0f) Petal.colors.bg else Color.Transparent),
        ) { content() }
    }
}

@Composable
private fun density(): Float = androidx.compose.ui.platform.LocalDensity.current.density

/**
 * A cover. Without artwork (or while it loads) the item still has its own colour: a
 * sleeve light from a hash of [seed], never a grey box with a note in it.
 */
@Composable
fun CoverArt(
    uri: String?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = org.rab1d.petal.music.ui.theme.Radius.sleeve,
    seed: String? = null,
) {
    val theme = androidx.compose.runtime.remember(seed) {
        seed?.takeIf { it.isNotBlank() }?.let { org.rab1d.petal.music.ui.theme.seedArtTheme(it) }
    }
    Box(
        modifier.clip(shape).then(
            if (theme != null) Modifier.sleeveLight(theme)
            else Modifier.background(Petal.colors.surface2),
        ),
    ) {
        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize())
    }
}

/**
 * [source] as its 2x upscaled copy: the original until the copy is ready (it is
 * produced in the background on first sight), then the copy.
 */
@Composable
fun rememberArt2x(source: String?): Any? {
    // Keyed on the source: a new song starts from its own cover at once, never
    // from the previous song's 2x copy.
    var art by androidx.compose.runtime.remember(source) {
        androidx.compose.runtime.mutableStateOf<Any?>(org.rab1d.petal.music.data.ArtUpscaler.cached(source) ?: source)
    }
    androidx.compose.runtime.LaunchedEffect(source) {
        if (source == null || art != source) return@LaunchedEffect
        val job = org.rab1d.petal.music.data.ArtUpscaler.request(source) ?: return@LaunchedEffect
        val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { job.get() }.getOrNull()
        }
        if (uri != null) art = uri
    }
    return art
}

/** The "E" mark: explicit, as the source labels it. */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier, color: Color = Petal.colors.text2) {
    Box(
        modifier
            .size(15.dp)
            .clip(org.rab1d.petal.music.ui.theme.Radius.tile)
            .background(color.copy(alpha = .22f))
            .semantics { contentDescription = "Explicit" },
        contentAlignment = Alignment.Center,
    ) {
        Text("E", color = color, style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 10.sp))
    }
}

/**
 * An artist name that opens the artist: their page where the source has one, otherwise
 * a search for them. Every artist name in the app goes through this.
 */
@Composable
fun ArtistLink(
    track: Track,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    text: String = track.artist,
    maxLines: Int = 1,
) {
    val go = LocalTrackActions.current?.goToArtist
    Text(
        text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.then(
            if (go != null && track.artist.isNotBlank()) Modifier.pressable(
                onClick = { go(track) },
                onClickLabel = "Open ${track.artist}",
                role = androidx.compose.ui.semantics.Role.Button,
            ) else Modifier,
        ),
    )
}

/** A search the next Search screen should run (an artist with no page of their own). */
object SearchRequest {
    var pending by androidx.compose.runtime.mutableStateOf<String?>(null)
    fun take(): String? = pending.also { pending = null }
}
