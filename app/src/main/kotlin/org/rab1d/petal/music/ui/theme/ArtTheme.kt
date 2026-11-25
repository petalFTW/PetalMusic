/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One light in a sleeve background: a colour from the cover, placed where that colour
 * sits in the artwork ([x], [y] are 0..1 of the cover), sized by how much of it there is.
 */
@Immutable
data class ArtStop(val color: Color, val x: Float, val y: Float, val weight: Float)

/**
 * Everything a screen needs to take its colour from a cover: the classified swatches,
 * the lit background, and text/icon colours that pass WCAG AA on that background.
 * Always night roles; art surfaces are lit, never papered.
 */
@Immutable
data class ArtTheme(
    val dominant: Color,
    val vibrant: Color,
    val muted: Color,
    val darkVibrant: Color,
    val lightMuted: Color,
    /** Raw accent swatch, before contrast adjustment. */
    val accent: Color,
    /** Page colour under the lights. */
    val base: Color,
    /** Up to [MAX_STOPS], largest first. */
    val stops: List<ArtStop>,
    /** ≥ 4.5:1 on the lightest stop. */
    val ink: Color,
    /** ≥ 4.5:1 on the lightest stop. */
    val text2: Color,
    /** ≥ 3:1 on the lightest stop (large text, icons, hints). */
    val text3: Color,
    /** The accent, lifted to ≥ 3:1 on the lightest stop: icons, the play button. */
    val accentOnArt: Color,
    /** Text/icons drawn on an [accentOnArt] fill, ≥ 4.5:1. */
    val onAccent: Color,
    /** True when this came from real artwork rather than a hash or the default. */
    val fromArt: Boolean,
) {
    /** The lightest light on the page: what contrast is checked against. */
    val brightest: Color get() = stops.maxByOrNull { it.color.relativeLuminance() }?.color ?: base

    companion object {
        const val MAX_STOPS = 6

        /** Neutral theme for when there is no cover and no title to hash (the dye's own). */
        fun neutral(accent: Color = Dye.ORCHID.light): ArtTheme = fromClusters(
            listOf(
                Cluster(accent.toOklch().withL(.30f).withC(.06f), .5f, .1f, .6f),
                Cluster(accent.toOklch().withL(.24f).withC(.04f), .9f, .7f, .4f),
            ),
            fromArt = false,
            accentHint = accent.toOklch(),
        )

        fun lerp(a: ArtTheme, b: ArtTheme, t: Float): ArtTheme {
            if (t <= 0f) return a
            if (t >= 1f) return b
            fun c(x: Color, y: Color) = lerpColorOklch(x, y, t)
            val n = max(a.stops.size, b.stops.size)
            fun pad(s: List<ArtStop>, other: List<ArtStop>) = List(n) { i ->
                // A stop that only exists on one side fades in or out where it stands.
                s.getOrNull(i) ?: other[i].copy(weight = 0f, color = other[i].color.copy(alpha = 0f))
            }
            val sa = pad(a.stops, b.stops)
            val sb = pad(b.stops, a.stops)
            val stops = List(n) { i ->
                ArtStop(
                    c(sa[i].color, sb[i].color),
                    sa[i].x + (sb[i].x - sa[i].x) * t,
                    sa[i].y + (sb[i].y - sa[i].y) * t,
                    sa[i].weight + (sb[i].weight - sa[i].weight) * t,
                )
            }
            return ArtTheme(
                c(a.dominant, b.dominant), c(a.vibrant, b.vibrant), c(a.muted, b.muted),
                c(a.darkVibrant, b.darkVibrant), c(a.lightMuted, b.lightMuted), c(a.accent, b.accent),
                c(a.base, b.base), stops, c(a.ink, b.ink), c(a.text2, b.text2), c(a.text3, b.text3),
                c(a.accentOnArt, b.accentOnArt), c(a.onAccent, b.onAccent), b.fromArt,
            )
        }
    }
}

/** A k-means cluster: its OKLCH colour, centroid on the cover and share of pixels. */
internal data class Cluster(val lch: Oklch, val x: Float, val y: Float, val weight: Float)

/**
 * Palette extraction: k-means in OKLab over a small copy of the cover ([pixels] is
 * ARGB, row-major, [width] wide). Deterministic: the seeds are chosen by farthest-point,
 * so the same cover always gives the same theme and nothing flickers on recomposition.
 */
fun extractArtTheme(pixels: IntArray, width: Int, k: Int = 6, iterations: Int = 10): ArtTheme? {
    if (width <= 0 || pixels.isEmpty()) return null
    val height = pixels.size / width
    var n = 0
    val L = FloatArray(pixels.size)
    val A = FloatArray(pixels.size)
    val B = FloatArray(pixels.size)
    val X = FloatArray(pixels.size)
    val Y = FloatArray(pixels.size)
    val lab = FloatArray(3)
    for (i in pixels.indices) {
        val p = pixels[i]
        if ((p ushr 24) < 128) continue
        argbToOklab(p, lab)
        L[n] = lab[0]; A[n] = lab[1]; B[n] = lab[2]
        X[n] = ((i % width) + .5f) / width
        Y[n] = ((i / width) + .5f) / height
        n++
    }
    if (n < 8) return null
    val kk = min(k, n)

    fun dist(i: Int, cl: Float, ca: Float, cb: Float): Float {
        val dl = L[i] - cl; val da = A[i] - ca; val db = B[i] - cb
        return dl * dl + da * da + db * db
    }

    // Farthest-point seeding from the pixel nearest the mean colour.
    val cL = FloatArray(kk); val cA = FloatArray(kk); val cB = FloatArray(kk)
    run {
        var ml = 0f; var ma = 0f; var mb = 0f
        for (i in 0 until n) { ml += L[i]; ma += A[i]; mb += B[i] }
        ml /= n; ma /= n; mb /= n
        var first = 0
        var best = Float.MAX_VALUE
        for (i in 0 until n) { val d = dist(i, ml, ma, mb); if (d < best) { best = d; first = i } }
        cL[0] = L[first]; cA[0] = A[first]; cB[0] = B[first]
        val nearest = FloatArray(n) { dist(it, cL[0], cA[0], cB[0]) }
        for (c in 1 until kk) {
            var far = 0
            for (i in 1 until n) if (nearest[i] > nearest[far]) far = i
            cL[c] = L[far]; cA[c] = A[far]; cB[c] = B[far]
            for (i in 0 until n) nearest[i] = min(nearest[i], dist(i, cL[c], cA[c], cB[c]))
        }
    }

    val assign = IntArray(n)
    val count = IntArray(kk)
    val sL = FloatArray(kk); val sA = FloatArray(kk); val sB = FloatArray(kk)
    val sX = FloatArray(kk); val sY = FloatArray(kk)
    repeat(iterations) {
        count.fill(0); sL.fill(0f); sA.fill(0f); sB.fill(0f); sX.fill(0f); sY.fill(0f)
        for (i in 0 until n) {
            var bi = 0
            var bd = Float.MAX_VALUE
            for (c in 0 until kk) { val d = dist(i, cL[c], cA[c], cB[c]); if (d < bd) { bd = d; bi = c } }
            assign[i] = bi
            count[bi]++
            sL[bi] += L[i]; sA[bi] += A[i]; sB[bi] += B[i]; sX[bi] += X[i]; sY[bi] += Y[i]
        }
        for (c in 0 until kk) if (count[c] > 0) {
            cL[c] = sL[c] / count[c]; cA[c] = sA[c] / count[c]; cB[c] = sB[c] / count[c]
        }
    }

    val clusters = (0 until kk).filter { count[it] > 0 }.map { c ->
        Cluster(labToLch(cL[c], cA[c], cB[c]), sX[c] / count[c], sY[c] / count[c], count[c].toFloat() / n)
    }.sortedByDescending { it.weight }
    return fromClusters(mergeNear(clusters), fromArt = true)
}

/** Clusters closer than ΔE_ok 0.035 are the same colour split in two: merge them. */
private fun mergeNear(list: List<Cluster>): List<Cluster> {
    val out = mutableListOf<Cluster>()
    for (c in list) {
        val twin = out.indexOfFirst { o ->
            val dl = o.lch.l - c.lch.l
            val oa = o.lch.c * kotlin.math.cos(Math.toRadians(o.lch.h.toDouble())).toFloat()
            val ob = o.lch.c * kotlin.math.sin(Math.toRadians(o.lch.h.toDouble())).toFloat()
            val ca = c.lch.c * kotlin.math.cos(Math.toRadians(c.lch.h.toDouble())).toFloat()
            val cb = c.lch.c * kotlin.math.sin(Math.toRadians(c.lch.h.toDouble())).toFloat()
            sqrt(dl * dl + (oa - ca) * (oa - ca) + (ob - cb) * (ob - cb)) < .035f
        }
        if (twin < 0) out += c
        else {
            val o = out[twin]
            val w = o.weight + c.weight
            out[twin] = o.copy(
                x = (o.x * o.weight + c.x * c.weight) / w,
                y = (o.y * o.weight + c.y * c.weight) / w,
                weight = w,
            )
        }
    }
    return out
}

/**
 * Deterministic theme for an item with no artwork, from an FNV-1a hash of [seed]
 * (title + artist), so every item still has its own colour.
 */
fun seedArtTheme(seed: String): ArtTheme {
    var h = 0x811C9DC5.toInt()
    for (ch in seed.lowercase()) { h = h xor ch.code; h *= 0x01000193 }
    val hue = ((h ushr 1) % 360).toFloat()
    fun bit(shift: Int) = ((h ushr shift) and 0xF) / 15f
    val clusters = listOf(
        Cluster(Oklch(.34f, .11f, hue), .15f + .25f * bit(0), .10f + .20f * bit(4), .42f),
        Cluster(Oklch(.40f, .12f, (hue + 28f) % 360f), .70f + .25f * bit(8), .25f + .25f * bit(12), .26f),
        Cluster(Oklch(.26f, .08f, (hue + 332f) % 360f), .20f + .30f * bit(16), .70f + .25f * bit(20), .20f),
        Cluster(Oklch(.30f, .07f, (hue + 150f) % 360f), .75f + .20f * bit(24), .80f + .15f * bit(28), .12f),
    )
    return fromClusters(clusters, fromArt = false, accentHint = Oklch(.74f, .14f, hue))
}

internal fun fromClusters(raw: List<Cluster>, fromArt: Boolean, accentHint: Oklch? = null): ArtTheme {
    val clusters = raw.ifEmpty { listOf(Cluster(Oklch(.3f, .05f, 320f), .5f, .5f, 1f)) }
    val dominant = clusters.maxBy { it.weight }.lch
    fun pick(filter: (Oklch) -> Boolean, by: (Oklch) -> Float): Oklch? =
        clusters.map { it.lch }.filter(filter).maxByOrNull(by)
    val mostChromatic = clusters.maxBy { it.lch.c }.lch

    // A missing role is made from the nearest real colour, moved in L/C: never grey.
    val vibrant = pick({ it.l in .50f..0.85f }, { it.c })
        ?: mostChromatic.withL(mostChromatic.l.coerceIn(.6f, .75f)).withC(max(mostChromatic.c, .10f))
    val muted = pick({ it.l in .35f..0.75f }, { -it.c })
        ?: dominant.withL(dominant.l.coerceIn(.45f, .65f)).withC(min(dominant.c, .05f))
    val darkVibrant = pick({ it.l < .50f }, { it.c })
        ?: mostChromatic.withL(.32f)
    val lightMuted = pick({ it.l > .70f }, { -it.c })
        ?: dominant.withL(.86f).withC(min(dominant.c, .04f))
    val accent = accentHint
        ?: pick({ hueDistance(it.h, dominant.h) >= 35f && it.c > .04f }, { it.c })
        ?: vibrant

    val base = dominant.withL(.17f).withC(min(dominant.c, .06f)).toColor()
    val stops = clusters.take(ArtTheme.MAX_STOPS).map { cl ->
        val c = cl.lch
        ArtStop(
            c.withL(c.l.coerceIn(.20f, .42f)).withC(min(c.c * 1.1f, .14f)).toColor(),
            cl.x, cl.y, cl.weight,
        )
    }
    val brightest = stops.maxByOrNull { it.color.relativeLuminance() }?.color ?: base
    val tintHue = dominant.h
    val ink = ensureContrast(Oklch(.98f, .012f, tintHue), brightest, 4.5f).toColor()
    val text2 = ensureContrast(Oklch(.82f, .03f, tintHue), brightest, 4.5f).toColor()
    val text3 = ensureContrast(Oklch(.68f, .03f, tintHue), brightest, 3f).toColor()
    val accentLch = accent.withL(max(accent.l, .72f)).withC(min(max(accent.c, .08f), .20f))
    val accentOnArt = ensureContrast(accentLch, brightest, 3f).toColor()
    val darkOn = Oklch(.16f, .03f, accent.h).toColor()
    val onAccent = if (contrast(darkOn, accentOnArt) >= 4.5f) darkOn else Oklch(.98f, .01f, accent.h).toColor()

    return ArtTheme(
        dominant = dominant.toColor(),
        vibrant = vibrant.toColor(),
        muted = muted.toColor(),
        darkVibrant = darkVibrant.toColor(),
        lightMuted = lightMuted.toColor(),
        accent = accent.toColor(),
        base = base,
        stops = stops,
        ink = ink,
        text2 = text2,
        text3 = text3,
        accentOnArt = accentOnArt,
        onAccent = onAccent,
        fromArt = fromArt,
    )
}
