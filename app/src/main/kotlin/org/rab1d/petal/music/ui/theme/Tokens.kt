/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

/** Light or dark chrome. [SYSTEM] follows the system setting. */
enum class ThemeMode(val label: String) { SYSTEM("System"), NIGHT("Night"), PAPER("Paper") }

/**
 * The accent families from the Ribbon logo pack. [light] is the accent on Night,
 * [deep] the accent on Paper, [ink] the colour that sits on a [light] fill.
 * [SYSTEM] takes the Material You seed instead (see [PetalTheme]). [SLEEVE] takes the
 * playing cover's accent (see [ArtTheme]) and is Orchid when nothing is playing.
 */
enum class Dye(val label: String, val light: Color, val deep: Color, val ink: Color) {
    ORCHID("Orchid", Color(0xFFEE44FF), Color(0xFF8A00B7), Color(0xFF190D22)),
    AMETHYST("Amethyst", Color(0xFFBA83F4), Color(0xFF6130AC), Color(0xFF17111F)),
    ROSE("Rose", Color(0xFFF582BD), Color(0xFFA5297A), Color(0xFF21101B)),
    TWILIGHT("Twilight", Color(0xFFB68CF0), Color(0xFF4050A6), Color(0xFF131423)),
    JADE("Jade", Color(0xFFA0DEB6), Color(0xFF16816F), Color(0xFF10231F)),
    SYSTEM("System", Color(0xFFEE44FF), Color(0xFF8A00B7), Color(0xFF190D22)),
    SLEEVE("Sleeve", Color(0xFFEE44FF), Color(0xFF8A00B7), Color(0xFF190D22)),
}

/**
 * Colour roles for every screen. Nothing outside the theme package should name
 * a hex value except source brand marks and artwork-derived colours.
 */
@Immutable
data class PetalColors(
    val dark: Boolean,
    /** Page. */
    val bg: Color,
    /** Tiles, cards, rows that need a ground. */
    val surface1: Color,
    /** Raised: secondary buttons, the mini-player, menus. */
    val surface2: Color,
    /** Primary text and icons. Use `.copy(alpha)` sparingly; prefer [text2]/[text3]. */
    val ink: Color,
    val text2: Color,
    val text3: Color,
    /** Chrome accent: play button, seam, selection. Never a gradient. */
    val accent: Color,
    val onAccent: Color,
    /** A wash of the accent for selected backgrounds. */
    val accentSoft: Color,
    /** Hairlines and borders. */
    val line: Color,
    /** Glass fills: controls on art, bars, sheets (alpha baked in). */
    val glassControl: Color,
    val glassBar: Color,
    val glassSheet: Color,
    val danger: Color,
    val dangerSoft: Color,
)

private fun tint(base: Color, dye: Color, amount: Float) = lerp(base, dye, amount)

fun nightColors(dye: Dye, accent: Color = dye.light, onAccent: Color = dye.ink): PetalColors {
    val bg = tint(Color(0xFF120D16), accent, .015f)
    return PetalColors(
        dark = true,
        bg = bg,
        surface1 = tint(Color(0xFF1B1420), accent, .02f),
        surface2 = tint(Color(0xFF261D2C), accent, .03f),
        ink = Color(0xFFF6EFF7),
        text2 = Color(0xFFB9AABD),
        text3 = Color(0xFF7E7083),
        accent = accent,
        onAccent = onAccent,
        accentSoft = accent.copy(alpha = .16f).compositeOver(bg),
        line = Color.White.copy(alpha = .10f),
        glassControl = Color.White.copy(alpha = .13f),
        glassBar = bg.copy(alpha = .52f),
        glassSheet = Color(0xFF1B1420).copy(alpha = .50f),
        danger = Color(0xFFFF6B7A),
        dangerSoft = Color(0xFF5A1F2A),
    )
}

fun paperColors(dye: Dye, accent: Color = dye.deep): PetalColors {
    val bg = Color(0xFFFBF8FA)
    return PetalColors(
        dark = false,
        bg = bg,
        surface1 = Color(0xFFF1EAF0),
        surface2 = Color(0xFFE8DFE7),
        ink = Color(0xFF1D1320),
        text2 = Color(0xFF665769),
        text3 = Color(0xFF9A8C9D),
        accent = accent,
        onAccent = Color.White,
        accentSoft = accent.copy(alpha = .10f).compositeOver(bg),
        line = Color(0xFF1D1320).copy(alpha = .10f),
        glassControl = Color(0xFF1D1320).copy(alpha = .07f),
        glassBar = bg.copy(alpha = .72f),
        glassSheet = bg.copy(alpha = .80f),
        danger = Color(0xFFC8243A),
        dangerSoft = Color(0xFFF8D9DE),
    )
}

/** Fixed light-on-dark roles for things drawn over artwork, in either theme. */
val OnArt = nightColors(Dye.ORCHID)

val LocalPetalColors = staticCompositionLocalOf { nightColors(Dye.ORCHID) }

object Petal {
    val colors: PetalColors
        @Composable @ReadOnlyComposable get() = LocalPetalColors.current
}

/** Night roles for a subtree that always sits on dark art (the player, art overlays). */
@Composable
fun NightSurface(content: @Composable () -> Unit) {
    val c = LocalPetalColors.current
    val night = if (c.dark) c else nightColors(Dye.ORCHID, accent = lerp(c.accent, Color.White, .45f), onAccent = c.ink)
    CompositionLocalProvider(LocalPetalColors provides night) { PetalMaterial(content) }
}

/**
 * These roles with the accent taken from artwork ([Dye.SLEEVE]). The cover's accent is
 * moved in OKLCH lightness until it reads on this theme's page: ≥ 3:1 for icons and
 * fills, and its on-colour ≥ 4.5:1.
 */
fun PetalColors.withArtAccent(art: Color): PetalColors {
    val lch = art.toOklch()
    val tuned = if (dark) ensureContrast(lch.withL(maxOf(lch.l, .74f)), bg, 4.5f)
        else ensureContrast(lch.withL(minOf(lch.l, .45f)), bg, 4.5f)
    val a = tuned.toColor()
    val darkOn = Oklch(.16f, .03f, tuned.h).toColor()
    val on = if (contrast(darkOn, a) >= 4.5f) darkOn else Color.White
    return copy(
        accent = a,
        onAccent = on,
        accentSoft = a.copy(alpha = if (dark) .16f else .10f).compositeOver(bg),
    )
}

/**
 * Every role taken from a cover: the page is the art's base, text and accent are the
 * art's own (already contrast-checked against that base in [ArtTheme]).
 */
fun artColors(t: ArtTheme): PetalColors {
    val night = nightColors(Dye.ORCHID)
    return PetalColors(
        dark = t.base.relativeLuminance() < .4f,
        bg = t.base,
        surface1 = lerp(t.base, t.ink, .07f),
        surface2 = lerp(t.base, t.ink, .12f),
        ink = t.ink,
        text2 = t.text2,
        text3 = t.text3,
        accent = t.accentOnArt,
        onAccent = t.onAccent,
        accentSoft = t.accentOnArt.copy(alpha = .16f).compositeOver(t.base),
        line = t.ink.copy(alpha = .10f),
        glassControl = t.ink.copy(alpha = .13f),
        glassBar = t.base.copy(alpha = .52f),
        glassSheet = lerp(t.base, t.ink, .07f).copy(alpha = .50f),
        danger = night.danger,
        dangerSoft = night.dangerSoft,
    )
}

/** A subtree themed by a cover: [artColors] for every role, Material included. */
@Composable
fun ArtSurface(theme: ArtTheme, content: @Composable () -> Unit) {
    val colors = remember(theme) { artColors(theme) }
    CompositionLocalProvider(LocalPetalColors provides colors) { PetalMaterial(content) }
}
