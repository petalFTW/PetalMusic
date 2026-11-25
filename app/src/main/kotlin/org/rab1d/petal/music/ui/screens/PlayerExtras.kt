/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.rab1d.petal.music.data.LyricsParser
import org.rab1d.petal.music.model.Credit
import org.rab1d.petal.music.model.Lyrics
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.playback.SleepTimer
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.components.ArtistLink
import org.rab1d.petal.music.ui.components.Backdrop
import org.rab1d.petal.music.ui.components.CoverArt
import org.rab1d.petal.music.ui.components.GlassLevel
import org.rab1d.petal.music.ui.components.PetalIcons
import org.rab1d.petal.music.ui.components.glass
import org.rab1d.petal.music.ui.components.pressable
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

// ---- lyrics ----------------------------------------------------------------------------

/**
 * Lyrics in place of the cover. Timed lyrics follow the song: the line being sung is
 * large and lit in the cover's accent, sung lines dim, the list keeps the current line a
 * third of the way down, and tapping a line seeks to it. Scrolling by hand pauses the
 * follow for a few seconds. Untimed lyrics are plain text.
 */
@Composable
fun LyricsPanel(
    viewModel: PetalViewModel,
    track: Track,
    positionMs: () -> Long,
    theme: ArtTheme,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lyrics by remember(track.key()) { mutableStateOf(viewModel.cachedLyrics(track)) }
    var loading by remember(track.key()) { mutableStateOf(lyrics == null) }
    val lrclib = viewModel.settings.collectAsState().value.lrclibLyrics
    // Fetched only when the panel is open, so no lookups happen for songs you never read.
    LaunchedEffect(track.key()) {
        if (lyrics == null) {
            lyrics = viewModel.lyrics(track)
            loading = false
        }
    }
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
        ) {
            CoverArt(track.artUri, Modifier.size(52.dp), seed = "${track.title} · ${track.artist}")
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, color = theme.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ArtistLink(track, MaterialTheme.typography.bodyMedium, theme.accentOnArt)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val l = lyrics
            when {
                loading -> CircularProgressIndicator(color = theme.accentOnArt, strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.Center).size(28.dp))
                l == null -> Column(Modifier.align(Alignment.CenterStart).padding(horizontal = Space.gutter)) {
                    Text("No lyrics for this one", style = MaterialTheme.typography.headlineMedium, color = theme.ink)
                    Text(
                        if (lrclib) "${track.source.label} and LRCLIB don't have words for it. It might be an instrumental, or just too new."
                        else "${track.source.label} doesn't have them, and LRCLIB lookups are off in Settings.",
                        style = MaterialTheme.typography.bodyLarge, color = theme.text2, modifier = Modifier.padding(top = Space.s),
                    )
                }
                l.synced -> TimedLyrics(l, positionMs(), theme, onSeek)
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
                ) {
                    Text("Not timed to the music", style = PetalType.time, color = theme.text3,
                        modifier = Modifier.padding(bottom = Space.m))
                    l.lines.forEach { line ->
                        Text(line.text.ifEmpty { " " }, style = MaterialTheme.typography.titleLarge, color = theme.text2,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                    ProviderLine(l, theme)
                }
            }
            // Lines fade out at both edges instead of being cut.
            Box(Modifier.fillMaxWidth().height(Space.xxl).background(Brush.verticalGradient(listOf(theme.base.copy(alpha = .3f), Color.Transparent))))
        }
    }
}

@Composable
private fun TimedLyrics(lyrics: Lyrics, positionMs: Long, theme: ArtTheme, onSeek: (Long) -> Unit) {
    val reduced = LocalReducedMotion.current
    val list = rememberLazyListState()
    // A little lead, so the line lights as it starts rather than after.
    val current = LyricsParser.current(lyrics.lines, positionMs + 200)
    var height by remember { mutableStateOf(0) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) touchedAt = System.currentTimeMillis() }
    LaunchedEffect(current, height) {
        if (height == 0 || System.currentTimeMillis() - touchedAt < 4000) return@LaunchedEffect
        val target = current.coerceAtLeast(0)
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        val anchor = height / 3
        if (info != null && !reduced) list.animateScrollBy((info.offset - anchor).toFloat(), Motion.enter(Motion.SLOW))
        else list.scrollToItem(target, -anchor)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = 200.dp),
        modifier = Modifier.fillMaxSize().onSizeChanged { height = it.height },
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == current -> theme.accentOnArt
                    i < current -> theme.ink.copy(alpha = .38f)
                    else -> theme.ink.copy(alpha = .72f)
                },
                Motion.press(Motion.SLOW), label = "lyricLine",
            )
            Text(
                line.text.ifEmpty { "♪" },
                style = if (i == current) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radius.tile)
                    .pressable(onClick = { onSeek(line.timeMs) }, onClickLabel = "Play from this line")
                    .padding(vertical = 6.dp),
            )
        }
        item { ProviderLine(lyrics, theme) }
    }
}

@Composable
private fun ProviderLine(lyrics: Lyrics, theme: ArtTheme) {
    Text("Lyrics from ${lyrics.provider}", style = PetalType.time, color = theme.text3,
        modifier = Modifier.padding(top = Space.xl))
}

// ---- sheets ----------------------------------------------------------------------------

enum class PlayerSheet { More, Credits, Sleep }

/**
 * Back inside the player: the app root owns the back callback (it must sit above the
 * nav stack), so it asks the player first. Returns true when Back was used up.
 */
object PlayerBack {
    var handler: (() -> Boolean)? = null
}

/** The player's sheets: Sheet-level glass over the room, sliding up from the deck. */
@Composable
fun PlayerSheetHost(
    sheet: PlayerSheet?,
    onDismiss: () -> Unit,
    onOpen: (PlayerSheet) -> Unit,
    backdrop: Backdrop,
    theme: ArtTheme,
    track: Track?,
    viewModel: PetalViewModel,
    onShare: () -> Unit,
    onOutput: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    var last by remember { mutableStateOf(sheet) }
    if (sheet != null) last = sheet
    // Swipe a sheet down to close it. Its content scrolls first; once the list is at the
    // top, the rest of the drag pulls the sheet itself, and a flick or a long pull closes it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Not keyed on [sheet]: closing sets it to null, and a reset then would snap the sheet
    // back up before it slides away. It resets when a sheet opens instead.
    var pullY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(sheet) { if (sheet != null) pullY = 0f }
    val sheetPull = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // Pulled down and now pushing back up: the sheet rises before the list scrolls.
                if (pullY > 0f && available.y < 0f) {
                    val used = maxOf(available.y, -pullY)
                    pullY += used
                    return androidx.compose.ui.geometry.Offset(0f, used)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    pullY += available.y
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (pullY <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                val far = pullY > with(density) { 96.dp.toPx() }
                if (far || available.y > with(density) { 1000.dp.toPx() }) onDismiss()
                else androidx.compose.animation.core.animate(pullY, 0f, available.y, Motion.drag()) { v, _ -> pullY = v }
                return available
            }
        }
    }
    AnimatedVisibility(sheet != null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = .35f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .graphicsLayer { translationY = pullY }
                    .animateEnterExit(
                        enter = if (reduced) fadeIn() else slideInVertically(Motion.settle()) { it },
                        exit = if (reduced) fadeOut() else slideOutVertically(Motion.settle()) { it },
                    )
                    .glass(backdrop, GlassLevel.Sheet, theme.base, Radius.sheet)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .nestedScroll(sheetPull)
                    .verticalScroll(rememberScrollState())
                    .padding(top = Space.s, bottom = Space.l),
            ) {
                Box(Modifier.fillMaxWidth().padding(bottom = Space.s), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(Radius.pill).background(theme.ink.copy(alpha = .28f)))
                }
                when (last) {
                    PlayerSheet.More -> MoreSheet(theme, track, { onOpen(PlayerSheet.Credits) }, { onOpen(PlayerSheet.Sleep) },
                        { onDismiss(); onShare() }, { onDismiss(); onOutput() })
                    PlayerSheet.Credits -> CreditsSheet(theme, track, viewModel)
                    PlayerSheet.Sleep -> SleepSheet(theme, track, onDismiss)
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, theme: ArtTheme, detail: String? = null, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = Space.xl, vertical = 14.dp),
    ) {
        Icon(icon, null, tint = theme.ink, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = theme.ink, modifier = Modifier.padding(start = Space.l).weight(1f))
        detail?.let { Text(it, style = PetalType.time.copy(fontSize = 12.sp), color = theme.accentOnArt) }
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = theme.text3)
    }
}

@Composable
private fun MoreSheet(theme: ArtTheme, track: Track?, onCredits: () -> Unit, onSleep: () -> Unit, onShare: () -> Unit, onOutput: () -> Unit) {
    val mode by SleepTimer.mode.collectAsState()
    if (track != null) {
        Text(track.title, style = MaterialTheme.typography.titleLarge, color = theme.ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s))
        SheetRow(PetalIcons.Credits, "Credits", theme, chevron = true, onClick = onCredits)
    }
    SheetRow(PetalIcons.Sleep, "Sleep timer", theme, detail = sleepLabel(mode), chevron = true, onClick = onSleep)
    if (track != null) SheetRow(PetalIcons.Share, "Share", theme, onClick = onShare)
    SheetRow(PetalIcons.Output, "Play on another device", theme, onClick = onOutput)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreditsSheet(theme: ArtTheme, track: Track?, viewModel: PetalViewModel) {
    if (track == null) return
    var credits by remember(track.key()) { mutableStateOf<List<Credit>?>(null) }
    LaunchedEffect(track.key()) { credits = viewModel.credits(track) }
    // The sheet itself scrolls; a second scroll here crashed (nested infinite height).
    Column(Modifier.padding(horizontal = Space.xl)) {
        Text("Credits", style = MaterialTheme.typography.displaySmall, color = theme.ink)
        Text(track.title, style = MaterialTheme.typography.bodyLarge, color = theme.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val c = credits
        when {
            c == null -> CircularProgressIndicator(color = theme.accentOnArt, strokeWidth = 2.dp,
                modifier = Modifier.padding(vertical = Space.xl).size(24.dp))
            c.isEmpty() -> Text(
                if (track.source == org.rab1d.petal.music.model.Source.LOCAL) "This file's tags don't name a writer."
                else "${track.source.label} doesn't list who made this one.",
                style = MaterialTheme.typography.bodyLarge, color = theme.text2, modifier = Modifier.padding(top = Space.xl),
            )
            else -> {
                // The artist first, as credited on the record.
                Text("Performed by", style = MaterialTheme.typography.labelLarge, color = theme.text3, modifier = Modifier.padding(top = Space.xl))
                ArtistLink(track, MaterialTheme.typography.titleLarge, theme.accentOnArt)
                c.forEach { credit ->
                    Text(credit.role, style = MaterialTheme.typography.labelLarge, color = theme.text3, modifier = Modifier.padding(top = Space.l))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        credit.names.forEach { Text(it, style = MaterialTheme.typography.titleLarge, color = theme.ink) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepSheet(theme: ArtTheme, track: Track?, onDone: () -> Unit) {
    val mode by SleepTimer.mode.collectAsState()
    Text("Sleep timer", style = MaterialTheme.typography.displaySmall, color = theme.ink,
        modifier = Modifier.padding(horizontal = Space.xl))
    Text(
        when (val m = mode) {
            SleepTimer.Mode.Off -> "Petal pauses when the time's up."
            SleepTimer.Mode.EndOfSong -> "Pausing when this song ends."
            is SleepTimer.Mode.At -> "Pausing in ${formatMs(SleepTimer.remainingMs())}, ${m.minutes} min set."
        },
        style = MaterialTheme.typography.bodyLarge, color = theme.text2,
        modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.m),
    ) {
        listOf(15, 30, 45, 60, 90).forEach { min ->
            val on = (mode as? SleepTimer.Mode.At)?.minutes == min
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(Radius.control)
                    .background(if (on) theme.accentOnArt else theme.ink.copy(alpha = .10f))
                    .pressable(onClick = { SleepTimer.minutes(min); onDone() }),
                contentAlignment = Alignment.Center,
            ) {
                Text("$min", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = org.rab1d.petal.music.ui.theme.PetalMono, fontFeatureSettings = "tnum", letterSpacing = 0.sp),
                    color = if (on) theme.onAccent else theme.ink)
            }
        }
    }
    Text("minutes", style = PetalType.time, color = theme.text3, modifier = Modifier.padding(horizontal = Space.xl))
    if (track != null) SheetRow(PetalIcons.Sleep, "When this song ends", theme,
        detail = if (mode == SleepTimer.Mode.EndOfSong) "On" else null) { SleepTimer.endOfSong(); onDone() }
    if (mode != SleepTimer.Mode.Off) SheetRow(PetalIcons.Down, "Turn off", theme) { SleepTimer.cancel(); onDone() }
}

private fun sleepLabel(mode: SleepTimer.Mode): String? = when (mode) {
    SleepTimer.Mode.Off -> null
    SleepTimer.Mode.EndOfSong -> "End of song"
    is SleepTimer.Mode.At -> formatMs(SleepTimer.remainingMs())
}

/** While a sleep timer runs: a small moon and the time left, next to the close button. */
@Composable
fun SleepReadout(theme: ArtTheme, onClick: () -> Unit) {
    val mode by SleepTimer.mode.collectAsState()
    if (mode == SleepTimer.Mode.Off) return
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mode) { while (mode is SleepTimer.Mode.At) { tick = SleepTimer.remainingMs(); delay(1000) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(Radius.pill)
            .background(theme.ink.copy(alpha = .10f))
            .pressable(onClick = onClick, onClickLabel = "Sleep timer")
            .padding(horizontal = Space.m, vertical = 6.dp),
    ) {
        Icon(PetalIcons.Sleep, null, tint = theme.accentOnArt, modifier = Modifier.size(16.dp))
        Text(
            if (mode is SleepTimer.Mode.At) formatMs(tick) else "End of song",
            style = PetalType.time.copy(fontSize = 12.sp), color = theme.ink, modifier = Modifier.padding(start = 6.dp),
        )
    }
}
