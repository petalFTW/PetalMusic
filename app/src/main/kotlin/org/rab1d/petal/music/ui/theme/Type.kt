/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package org.rab1d.petal.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.R

// One face for the whole app: Bricolage Grotesque ExtraBold (800, normal width), SIL OFL 1.1
// (licence in assets/licenses). Only the optical size follows the text size.
// Scale: ratio 1.25 from 14sp → 11 · 14 · 17.5 · 22 · 27 · 34 · 43 · 53 · 67 · 84.

/** Widths on Bricolage's `wdth` axis (75..100). Everything is set at normal width; fitting only shrinks. */
object DisplayWidth {
    const val CONDENSED = 75f
    const val HERO = 80f
    const val LARGE = 84f
    const val MEDIUM = 88f
    const val SMALL = 92f
    const val HEADLINE = 96f
    const val NORMAL = 100f
    val steps = floatArrayOf(NORMAL)
}

/** The one weight: ExtraBold. Kept as an array for HeroText's weightStep callers. */
val HeroBreath = intArrayOf(800)

/** ExtraBold. */
const val EXTRA_BOLD = 800

private val displayFamilies = HashMap<Pair<Float, Float>, FontFamily>()

/**
 * Bricolage at one width and optical size, with the weights the type scale uses.
 * Cached: every distinct variation is its own typeface.
 */
fun bricolage(width: Float, opticalSize: Float = 48f): FontFamily = synchronized(displayFamilies) {
    displayFamilies.getOrPut(width to opticalSize) {
        FontFamily(
            listOf(EXTRA_BOLD).map { w ->
                Font(
                    R.font.bricolage_grotesque,
                    FontWeight(w),
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(w),
                        FontVariation.width(width),
                        FontVariation.Setting("opsz", opticalSize.coerceIn(12f, 96f)),
                    ),
                )
            },
        )
    }
}

private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** Display and titles. */
val PetalDisplay = bricolage(DisplayWidth.NORMAL, 24f)

/** Body, lists, buttons: the same face as display, at text optical size. */
val PetalSans = bricolage(DisplayWidth.NORMAL, 14f)

/** BPM, key, sample rate, times: Bricolage with tabular figures, so time codes never jitter. */
val PetalMono = PetalSans

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private fun display(size: Float, line: Float, tracking: Float) = TextStyle(
    fontFamily = bricolage(DisplayWidth.NORMAL, size),
    fontWeight = FontWeight(EXTRA_BOLD),
    fontSize = size.sp,
    lineHeight = line.coerceAtLeast(size * 1.1f).sp,
    letterSpacing = tracking.coerceAtLeast(-.015f).em,
    lineHeightStyle = tight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private fun text(size: Float, line: Float, tabular: Boolean = false) = TextStyle(
    fontFamily = bricolage(DisplayWidth.NORMAL, size),
    fontWeight = FontWeight(EXTRA_BOLD),
    fontSize = size.sp,
    lineHeight = line.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

/** Type roles beyond Material's: the hero size and the data face. */
object PetalType {
    /** Artist pages and the Now Playing title: 67sp, very tight. */
    val hero = display(67f, 60f, -.045f)
    /** Chart ranks and other numbers set as display. */
    val rank = display(53f, 48f, -.05f).copy(fontFeatureSettings = "tnum")
    /** Time codes: tabular figures. */
    val time = text(11f, 14f, tabular = true)
}

val PetalTypography = Typography(
    displayLarge = display(53f, 50f, -.04f),
    displayMedium = display(43f, 42f, -.035f),
    displaySmall = display(34f, 34f, -.03f),
    headlineLarge = display(34f, 34f, -.03f),
    headlineMedium = display(27f, 28f, -.02f),
    headlineSmall = display(22f, 24f, -.015f),
    titleLarge = display(22f, 24f, -.01f),
    titleMedium = text(17.5f, 22f),
    titleSmall = text(14f, 18f),
    bodyLarge = text(15f, 20f),
    bodyMedium = text(14f, 20f),
    bodySmall = text(11f, 14f),
    labelLarge = text(14f, 18f),
    labelMedium = text(11f, 14f),
    // Data: tabular figures for the numbers music people read.
    labelSmall = text(11f, 14f, tabular = true),
)
