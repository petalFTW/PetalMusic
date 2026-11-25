/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalPalette
import org.rab1d.petal.music.ui.theme.PetalType
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space
import org.rab1d.petal.music.ui.theme.rememberArtTheme

enum class HeaderStyle {
    /** Album and playlist: the sleeve itself, lit by its own colours. */
    Sleeve,
    /** Artist: a full-bleed portrait that blurs and darkens as you scroll. */
    Portrait,
}

/**
 * The detail page. The header is lit by the item's cover (night roles from [ArtTheme],
 * in either app theme); the list below sits on the page. On scroll, the header
 * parallaxes and a glass bar takes over with the title.
 */
@Composable
fun DetailScaffold(
    artUri: String?,
    seed: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    style: HeaderStyle = HeaderStyle.Sleeve,
    coverKey: String? = null,
    byline: (@Composable (ArtTheme) -> Unit)? = null,
    meta: String = "",
    actions: (@Composable RowScope.(ArtTheme) -> Unit)? = null,
    headerExtras: @Composable () -> Unit = {},
    listContent: LazyListScope.() -> Unit,
) {
    val theme = rememberArtTheme(artUri, seed)
    val setPalette = LocalPaletteSetter.current
    LaunchedEffect(Unit) { setPalette(PetalPalette.Petal) }
    val c = Petal.colors
    val reduced = LocalReducedMotion.current
    val list = rememberLazyListState()
    val backdrop = rememberBackdrop()
    var headerPx by remember { mutableIntStateOf(1) }
    // 0 while the header is in view, 1 once it has scrolled under the bar.
    val collapse by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f
            else (list.firstVisibleItemScrollOffset / (headerPx * .72f)).coerceIn(0f, 1f)
        }
    }
    val scrolled = { if (list.firstVisibleItemIndex > 0) headerPx.toFloat() else list.firstVisibleItemScrollOffset.toFloat() }

    Box(modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = Space.xxl + LocalChromeInset.current),
            // The page colour goes inside the backdrop: glass over it must hide what's beneath.
            modifier = Modifier.fillMaxSize().backdropSource(backdrop).background(c.bg),
        ) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().onSizeChanged { headerPx = it.height.coerceAtLeast(1) }) {
                    when (style) {
                        HeaderStyle.Sleeve -> SleeveHeader(theme, artUri, seed, title, coverKey, scrolled, reduced, byline, meta, actions)
                        HeaderStyle.Portrait -> PortraitHeader(theme, artUri, seed, title, scrolled, reduced, byline, meta, actions)
                    }
                }
            }
            item(key = "extras") { headerExtras() }
            listContent()
        }

        // The bar the big title shrinks into: glass, appearing as the header leaves.
        val barTint = if (c.dark) theme.base else c.bg
        Box(
            Modifier
                .fillMaxWidth()
                // Fully opaque by .7, before its own title fades in: a half-faded bar let the
                // big title show through crisp behind the small one.
                .graphicsLayer { alpha = (collapse / .7f).coerceIn(0f, 1f) }
                .glass(backdrop, GlassLevel.Sheet, barTint)
                .background(barTint.copy(alpha = .85f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 64.dp, end = Space.l)
                    .graphicsLayer {
                        val t = ((collapse - .7f) / .3f).coerceIn(0f, 1f)
                        alpha = t
                        if (!reduced) translationY = (1f - t) * 12.dp.toPx()
                    },
            )
        }
        // Keep system icons readable in Paper too, even while the dark sleeve is expanded.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.bg))
        // Back stays reachable on the art: a small Control-glass disc until the bar arrives.
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = Space.xs, top = 4.dp)) {
            GlyphButton(
                onClick = onBack, label = "Back",
                modifier = Modifier
                    .size(48.dp)
                    .padding(4.dp)
                    .glass(backdrop, GlassLevel.Control, androidx.compose.ui.graphics.lerp(theme.base, barTint, collapse), Radius.circle),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null,
                    tint = androidx.compose.ui.graphics.lerp(theme.ink, c.ink, collapse))
            }
        }
    }
}

@Composable
private fun SleeveHeader(
    theme: ArtTheme,
    artUri: String?,
    seed: String,
    title: String,
    coverKey: String?,
    scrolled: () -> Float,
    reduced: Boolean,
    byline: (@Composable (ArtTheme) -> Unit)?,
    meta: String,
    actions: (@Composable RowScope.(ArtTheme) -> Unit)?,
) {
    val c = Petal.colors
    Column(
        Modifier
            .fillMaxWidth()
            .sleeveLight(theme),
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        // Clear of the back button, which floats over the top-left corner.
        Spacer(Modifier.height(Space.huge))
        // The cover sits left of centre and a touch large: the page is about the record.
        Box(
            Modifier
                .padding(start = Space.gutter)
                .fillMaxWidth(.72f)
                .aspectRatio(1f)
                // Leaves slowly and fades as it goes, so it never slides under the title.
                .graphicsLayer {
                    val s = scrolled()
                    if (!reduced) translationY = s * .2f
                    alpha = (1f - s / (size.height * .9f)).coerceIn(0f, 1f)
                }
                .sharedArt(coverKey, LocalNavScope.current)
                .shadow(24.dp, Radius.sleeveHero, ambientColor = theme.darkVibrant, spotColor = theme.darkVibrant)
                .clip(Radius.sleeveHero),
        ) {
            CoverArt(artUri, Modifier.fillMaxSize(), shape = Radius.sleeveHero, seed = seed)
            // The sleeve falls off into its own light where the title overlaps it.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(.62f to Color.Transparent, 1f to theme.base)))
        }
        HeaderText(theme, title, byline, meta, actions, overlap = true)
        // The header's text is night-on-art, so the light only gives way to the page below it.
        FadeToPage()
    }
}

@Composable
private fun FadeToPage() {
    val bg = Petal.colors.bg
    Box(Modifier.fillMaxWidth().height(Space.huge).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
}

@Composable
private fun PortraitHeader(
    theme: ArtTheme,
    artUri: String?,
    seed: String,
    title: String,
    scrolled: () -> Float,
    reduced: Boolean,
    byline: (@Composable (ArtTheme) -> Unit)?,
    meta: String,
    actions: (@Composable RowScope.(ArtTheme) -> Unit)?,
) {
    val c = Petal.colors
    // Leave 430dp under the portrait for a two-line name, meta, Play row and mini player: on short
    // phones a full .92 portrait pushed them under the chrome. Tall phones never reach it.
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val portraitHeight = minOf(screen.screenWidthDp.dp / .92f, screen.screenHeightDp.dp - 430.dp)
        .coerceAtLeast(160.dp)
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(portraitHeight)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = scrolled()
                        if (!reduced) {
                            translationY = s * .5f
                            val r = (s / size.height * 40f).coerceIn(0f, 28f)
                            renderEffect = if (r > .5f) BlurEffect(r, r, TileMode.Clamp) else null
                        }
                    }
                    .sleeveLight(theme),
            ) {
                AsyncImage(artUri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            // Darkens toward the page as it scrolls; always dark enough at the foot for the name.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (.55f + scrolled() / size.height).coerceIn(0f, 1f) }
                    // Strong enough that words printed on a cover never fight the name set over it.
                    .background(Brush.verticalGradient(.2f to Color.Transparent, .62f to theme.base.copy(alpha = .9f), .8f to theme.base)),
            )
        }
        Column(Modifier.fillMaxWidth().background(theme.base)) {
            HeaderText(theme, title, byline, meta, actions, overlap = true, hero = true)
            FadeToPage()
        }
    }
}

/** Title (very large, overlapping whatever sits above), byline, data line, actions. */
@Composable
private fun HeaderText(
    theme: ArtTheme,
    title: String,
    byline: (@Composable (ArtTheme) -> Unit)?,
    meta: String,
    actions: (@Composable RowScope.(ArtTheme) -> Unit)?,
    overlap: Boolean,
    hero: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.l)
            .padding(horizontal = Space.gutter),
    ) {
        HeroText(
            text = title,
            color = theme.ink,
            style = if (hero) PetalType.hero else MaterialTheme.typography.displayLarge,
            maxLines = 3,
            minSize = if (hero) 43.sp else 34.sp,
        )
        byline?.let { Box(Modifier.padding(top = Space.s)) { it(theme) } }
        if (meta.isNotEmpty()) {
            Text(meta, style = PetalType.time.copy(fontSize = 12.sp), color = theme.text2,
                modifier = Modifier.padding(top = Space.s))
        }
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Space.l, bottom = Space.s),
            ) { actions(theme) }
        }
    }
}

/** The play pill on a header: the cover's accent, the one solid fill on the page. */
@Composable
fun RowScope.HeaderPlay(
    theme: ArtTheme,
    onPlay: () -> Unit,
    label: String = "Play",
    shuffle: Boolean = false,
    playing: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(52.dp)
            .clip(Radius.pill)
            .background(theme.accentOnArt)
            .pressable(onClick = onPlay, role = androidx.compose.ui.semantics.Role.Button)
            .padding(start = Space.l, end = Space.xl),
    ) {
        if (shuffle) Icon(PetalIcons.Shuffle, null, tint = theme.onAccent, modifier = Modifier.size(22.dp))
        else PlayPauseGlyph(playing, theme.onAccent, size = 22.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = theme.onAccent,
            modifier = Modifier.padding(start = Space.s))
    }
}

/** A secondary header action: a glyph on a Control-glass-like disc tinted from the art. */
@Composable
fun HeaderAction(theme: ArtTheme, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(Radius.circle)
            .background(theme.ink.copy(alpha = .10f))
            .pressable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A numbered album row: the number (or the live equaliser) in the data face, then the
 * title. The artist line shows only when it differs from the album's (features, VA).
 */
@Composable
fun NumberedTrackRow(
    number: Int,
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    albumArtist: String? = null,
    menuExtras: List<MenuAction> = emptyList(),
) {
    val c = Petal.colors
    val showArtist = albumArtist == null || !track.artist.equals(albumArtist, ignoreCase = true)
    TrackRow(
        track = track,
        showArtist = showArtist,
        onClick = onClick,
        playing = playing,
        showCover = false,
        menuExtras = menuExtras,
        leading = {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) EqualiserGlyph(LocalLiveLevel.current, c.accent, size = 14.dp)
                else Text("$number", style = PetalType.time.copy(fontSize = 13.sp), color = c.text3)
            }
        },
    )
}

/** A disc break in a multi-disc album. */
@Composable
fun DiscSeparator(disc: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.xs),
    ) {
        Text("Disc $disc", style = MaterialTheme.typography.labelLarge, color = Petal.colors.text2)
        Box(Modifier.padding(start = Space.m).weight(1f).height(1.dp).background(Petal.colors.line))
    }
}

/** Download state shown on a row / button. */
sealed interface DownloadDot {
    data object Downloaded : DownloadDot
    data class InProgress(val fraction: Float) : DownloadDot
}

/** Section heading: the display face at title size, sentence case. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = Petal.colors.ink,
        modifier = modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.section, bottom = Space.m),
    )
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
