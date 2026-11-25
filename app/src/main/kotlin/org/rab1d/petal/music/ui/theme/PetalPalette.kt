/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The environment palette. Colours now come from the theme tokens ([Petal.colors]);
 * this type survives so screens can keep asking for "the palette" and art-driven
 * pages can still override the wash.
 */
data class PetalPalette(
    val name: String,
    /** Page wash. */
    val wash: Color,
    /** Tile surface. */
    val glass: Color,
    /** Chrome accent (the dye). */
    val accent: Color,
    /** Same as [accent]: the dye is never a gradient. */
    val accentAlt: Color,
    val onAccent: Color = Color(0xFF190D22),
) {
    val accentBrush: Brush
        get() = SolidColor(accent)

    companion object {
        /** Placeholder until the theme provides the real one. */
        val Petal = PetalPalette("petal", Color(0xFF120D16), Color(0xFF1B1420), Color(0xFFEE44FF), Color(0xFFEE44FF))
        val Ambient = Petal
        val Tidal = Petal.copy(name = "tidal")
        val Spotify = Petal.copy(name = "spotify")
        val YtMusic = Petal.copy(name = "ytmusic")
        val Deezer = Petal.copy(name = "deezer")

        fun of(c: PetalColors, name: String = "petal") =
            PetalPalette(name, c.bg, c.surface1, c.accent, c.accent, c.onAccent)
    }
}

/** The palette of the current environment. */
val LocalPetalPalette = staticCompositionLocalOf { PetalPalette.Petal }

/** Screens call this to name their environment (tab/source changes). */
val LocalPaletteSetter = staticCompositionLocalOf<(PetalPalette) -> Unit> { {} }

/** Current palette, always in the theme's colours. */
@Composable
fun petalPalette(): PetalPalette = LocalPetalPalette.current

/** The app canvas: the page colour and the palette derived from the tokens. */
@Composable
fun PetalEnvironment(
    palette: PetalPalette,
    modifier: Modifier = Modifier,
    artUri: String? = null,
    content: @Composable () -> Unit,
) {
    val c = Petal.colors
    val themed = remember(palette.name, c) { PetalPalette.of(c, palette.name) }
    CompositionLocalProvider(LocalPetalPalette provides themed) {
        Box(modifier.fillMaxSize().background(c.bg)) { content() }
    }
}
