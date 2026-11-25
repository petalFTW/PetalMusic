/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Desktop mirror of the Android Petal design system palette. */
data class PetalPalette(
    val name: String,
    val wash: Color,
    val glass: Color,
    val accent: Color,
    val accentAlt: Color,
    val onAccent: Color = Color(0xFF171921),
) {
    val accentBrush: Brush
        get() = Brush.linearGradient(listOf(accent, accentAlt))

    companion object {
        val Petal = PetalPalette(
            name = "petal",
            wash = Color(0xFF0A0B10),
            glass = Color(0xFF222430),
            accent = Color(0xFFB9A5FF),
            accentAlt = Color(0xFF7C5CFF),
            onAccent = Color(0xFF171921),
        )
        val Ambient = Petal
        val Tidal = Petal.copy(name = "tidal")
        val Spotify = Petal.copy(name = "spotify")
        val YtMusic = Petal.copy(name = "ytmusic")
        val Deezer = Petal.copy(name = "deezer")
    }
}

val LocalPetalPalette = staticCompositionLocalOf { PetalPalette.Petal }
val LocalPaletteSetter = staticCompositionLocalOf<(PetalPalette) -> Unit> { {} }

@Composable
fun petalPalette(): PetalPalette = LocalPetalPalette.current

val PetalTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A5FF),
    onPrimary = Color(0xFF171921),
    primaryContainer = Color(0xFF3B3156),
    onPrimaryContainer = Color(0xFFB9A5FF),
    secondary = Color(0xFFB9BAC4),
    secondaryContainer = Color(0xFF292B35),
    onSecondaryContainer = Color(0xFFF5F5F8),
    tertiary = Color(0xFFB9A5FF),
    background = Color(0xFF0A0B10),
    onBackground = Color(0xFFF5F5F8),
    surface = Color(0xFF191B23),
    onSurface = Color(0xFFF5F5F8),
    surfaceVariant = Color(0xFF222430),
    surfaceContainer = Color(0xFF191B23),
    surfaceContainerHigh = Color(0xFF222430),
    onSurfaceVariant = Color(0xFFB9BAC4),
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
)

/** Mirrors Android's PetalEnvironment: palette drives the Material scheme. */
@Composable
fun PetalEnvironment(
    palette: PetalPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = remember(palette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.glass,
            onPrimaryContainer = palette.accent,
            secondary = palette.accentAlt,
            onSecondary = palette.onAccent,
            background = palette.wash,
            onBackground = Color(0xFFF5F5F8),
            surface = palette.wash,
            onSurface = Color(0xFFF5F5F8),
            surfaceVariant = palette.glass,
            onSurfaceVariant = Color(0xFFB9BAC4),
            surfaceContainer = palette.glass,
            surfaceContainerHigh = palette.glass,
            outline = palette.accent.copy(alpha = 0.45f),
            outlineVariant = palette.wash,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = PetalTypography) {
        CompositionLocalProvider(LocalPetalPalette provides palette) {
            Box(modifier.fillMaxSize().background(palette.wash)) { content() }
        }
    }
}
