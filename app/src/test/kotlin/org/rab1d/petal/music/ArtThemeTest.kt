/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.contrast
import org.rab1d.petal.music.ui.theme.extractArtTheme
import org.rab1d.petal.music.ui.theme.hueDistance
import org.rab1d.petal.music.ui.theme.lerpOklch
import org.rab1d.petal.music.ui.theme.seedArtTheme
import org.rab1d.petal.music.ui.theme.toOklch

class ArtThemeTest {
    private val size = 40

    private fun cover(f: (x: Int, y: Int) -> Int) = IntArray(size * size) { f(it % size, it / size) }

    private fun assertReadable(t: ArtTheme, name: String) {
        val bg = t.brightest
        assertTrue("$name ink ${contrast(t.ink, bg)}", contrast(t.ink, bg) >= 4.5f)
        assertTrue("$name text2 ${contrast(t.text2, bg)}", contrast(t.text2, bg) >= 4.5f)
        assertTrue("$name text3 ${contrast(t.text3, bg)}", contrast(t.text3, bg) >= 3f)
        assertTrue("$name accent ${contrast(t.accentOnArt, bg)}", contrast(t.accentOnArt, bg) >= 3f)
        assertTrue("$name onAccent ${contrast(t.onAccent, t.accentOnArt)}", contrast(t.onAccent, t.accentOnArt) >= 4.5f)
        // Text also has to read on the base itself.
        assertTrue("$name ink on base", contrast(t.ink, t.base) >= 4.5f)
    }

    @Test fun warmTopLeftLightsTheTopLeft() {
        val orange = 0xFFF07020.toInt()
        val navy = 0xFF14204A.toInt()
        val t = extractArtTheme(cover { x, y -> if (x < 14 && y < 14) orange else navy }, size)!!
        assertReadable(t, "two-tone")
        val warm = t.stops.minBy { hueDistance(it.color.toOklch().h, Color(orange).toOklch().h) }
        assertTrue("warm light at ${warm.x},${warm.y}", warm.x < .3f && warm.y < .3f)
        assertTrue(t.stops.first().weight > .8f) // navy dominates
    }

    @Test fun paleCoverStaysTintedNotGrey() {
        val t = extractArtTheme(cover { x, _ -> if (x < 20) 0xFFF6EEE4.toInt() else 0xFFF1E3D3.toInt() }, size)!!
        assertReadable(t, "pale")
        assertTrue("base keeps a hue: ${t.base.toOklch()}", t.base.toOklch().c > .005f)
    }

    @Test fun nearBlackCoverStillReadable() {
        val t = extractArtTheme(cover { x, y -> if ((x + y) % 7 == 0) 0xFF3A0A10.toInt() else 0xFF050505.toInt() }, size)!!
        assertReadable(t, "black")
    }

    @Test fun vividCoverReadable() {
        val t = extractArtTheme(cover { x, _ -> listOf(0xFFFFE600, 0xFF00E0FF, 0xFFFF2D8A)[x / 14].toInt() }, size)!!
        assertReadable(t, "vivid")
        assertTrue(t.stops.size >= 3)
    }

    @Test fun deterministic() {
        val px = cover { x, y -> (0xFF shl 24) or ((x * 6) shl 16) or ((y * 6) shl 8) or 90 }
        assertEquals(extractArtTheme(px, size), extractArtTheme(px, size))
    }

    @Test fun seededThemesDifferPerItemAndRead() {
        val a = seedArtTheme("Glasshouse · Ada Ferro")
        val b = seedArtTheme("Night Bus · Okkervil")
        assertEquals(a, seedArtTheme("Glasshouse · Ada Ferro"))
        assertTrue(a.dominant != b.dominant)
        assertReadable(a, "seed a")
        assertReadable(b, "seed b")
    }

    @Test fun oklchLerpTakesShortArc() {
        val red = Color(0xFFE0302A).toOklch()
        val magenta = Color(0xFFD02A9A).toOklch()
        val mid = lerpOklch(red, magenta, .5f)
        assertTrue(hueDistance(mid.h, red.h) < hueDistance(red.h, magenta.h))
        assertNotNull(mid.toColor())
    }
}
