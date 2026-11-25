/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// OKLab / OKLCH (Björn Ottosson, 2020). Artwork colours are adjusted and mixed here,
// not in sRGB, so lightening keeps the hue and a cross-fade doesn't pass through grey.

/** A colour in OKLCH. [h] is in degrees; [c] is unbounded but ~0.37 is the sRGB ceiling. */
data class Oklch(val l: Float, val c: Float, val h: Float, val alpha: Float = 1f) {
    fun withL(l: Float) = copy(l = l.coerceIn(0f, 1f))
    fun withC(c: Float) = copy(c = c.coerceAtLeast(0f))
    fun toColor(): Color = oklchToColor(this)
}

private fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

private fun linearToSrgb(v: Float): Float =
    if (v <= 0.0031308f) v * 12.92f else (1.055f * v.toDouble().pow(1 / 2.4).toFloat() - 0.055f)

/** Linear-light sRGB components → OKLab (L, a, b). */
internal fun linearToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
    out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
}

/** OKLab → linear-light sRGB, unclamped (values outside 0..1 are out of gamut). */
internal fun oklabToLinear(L: Float, a: Float, b: Float, out: FloatArray) {
    val l = (L + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
    val m = (L - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
    val s = (L - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
    out[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
    out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
    out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
}

/** sRGB pixel (0..255 per channel) → OKLab, for the extractor's hot loop. */
internal fun argbToOklab(argb: Int, out: FloatArray) {
    linearToOklab(
        srgbToLinear((argb shr 16 and 0xFF) / 255f),
        srgbToLinear((argb shr 8 and 0xFF) / 255f),
        srgbToLinear((argb and 0xFF) / 255f),
        out,
    )
}

fun Color.toOklch(): Oklch {
    val lab = FloatArray(3)
    linearToOklab(srgbToLinear(red), srgbToLinear(green), srgbToLinear(blue), lab)
    return labToLch(lab[0], lab[1], lab[2], alpha)
}

internal fun labToLch(L: Float, a: Float, b: Float, alpha: Float = 1f): Oklch {
    val c = sqrt(a * a + b * b)
    var h = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
    if (h < 0) h += 360f
    return Oklch(L, c, h, alpha)
}

private fun inGamut(rgb: FloatArray) = rgb.all { it >= -0.0005f && it <= 1.0005f }

/**
 * OKLCH → sRGB. Out-of-gamut colours keep their lightness and hue and lose chroma
 * (binary search), which is how a too-vivid cover swatch is tamed without going muddy.
 */
fun oklchToColor(c: Oklch): Color {
    val rad = Math.toRadians(c.h.toDouble())
    val cosH = cos(rad).toFloat()
    val sinH = sin(rad).toFloat()
    val rgb = FloatArray(3)
    oklabToLinear(c.l, c.c * cosH, c.c * sinH, rgb)
    if (!inGamut(rgb)) {
        var lo = 0f
        var hi = c.c
        repeat(14) {
            val mid = (lo + hi) / 2f
            oklabToLinear(c.l, mid * cosH, mid * sinH, rgb)
            if (inGamut(rgb)) lo = mid else hi = mid
        }
        oklabToLinear(c.l, lo * cosH, lo * sinH, rgb)
    }
    return Color(
        linearToSrgb(rgb[0].coerceIn(0f, 1f)).coerceIn(0f, 1f),
        linearToSrgb(rgb[1].coerceIn(0f, 1f)).coerceIn(0f, 1f),
        linearToSrgb(rgb[2].coerceIn(0f, 1f)).coerceIn(0f, 1f),
        c.alpha,
    )
}

/** Shortest-arc hue difference in degrees, 0..180. */
fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** Interpolate in OKLCH along the shorter hue arc. Achromatic ends borrow the other hue. */
fun lerpOklch(a: Oklch, b: Oklch, t: Float): Oklch {
    val ha = if (a.c < 0.01f) b.h else a.h
    val hb = if (b.c < 0.01f) a.h else b.h
    var dh = hb - ha
    if (dh > 180f) dh -= 360f
    if (dh < -180f) dh += 360f
    var h = ha + dh * t
    if (h < 0) h += 360f
    if (h >= 360f) h -= 360f
    return Oklch(
        a.l + (b.l - a.l) * t,
        a.c + (b.c - a.c) * t,
        h,
        a.alpha + (b.alpha - a.alpha) * t,
    )
}

fun lerpColorOklch(a: Color, b: Color, t: Float): Color =
    if (t <= 0f) a else if (t >= 1f) b else lerpOklch(a.toOklch(), b.toOklch(), t).toColor()

/** WCAG 2.x relative luminance. */
fun Color.relativeLuminance(): Float =
    0.2126f * srgbToLinear(red) + 0.7152f * srgbToLinear(green) + 0.0722f * srgbToLinear(blue)

/** WCAG 2.x contrast ratio, 1..21. */
fun contrast(a: Color, b: Color): Float {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * Move [fg]'s OKLCH lightness away from [bg] until the pair reaches [ratio]. Hue and
 * chroma stay, so the colour remains the artwork's own; it is never swapped for grey.
 */
fun ensureContrast(fg: Oklch, bg: Color, ratio: Float): Oklch {
    if (contrast(fg.toColor(), bg) >= ratio) return fg
    val up = bg.relativeLuminance() < 0.18f
    var lo = fg.l
    var hi = if (up) 1f else 0f
    // If even the extreme fails (mid-grey backgrounds), take the extreme.
    if (contrast(fg.withL(hi).toColor(), bg) < ratio) return fg.withL(hi)
    repeat(16) {
        val mid = (lo + hi) / 2f
        if (contrast(fg.withL(mid).toColor(), bg) >= ratio) hi = mid else lo = mid
    }
    return fg.withL(hi)
}
