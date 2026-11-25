/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.data.LyricsParser

class LyricsParserTest {
    @Test fun parsesTimedLinesAndSkipsTags() {
        val l = LyricsParser.parse("[ar:Someone]\n[00:12.50] First line\n[01:02.3]Second\n[00:05.125]Zero", "test")!!
        assertTrue(l.synced)
        assertEquals(listOf(5125L, 12500L, 62300L), l.lines.map { it.timeMs })
        assertEquals("First line", l.lines[1].text)
    }

    @Test fun repeatedStampsExpand() {
        val l = LyricsParser.parse("[00:10.00][00:40.00] Chorus", "test")!!
        assertEquals(listOf(10_000L, 40_000L), l.lines.map { it.timeMs })
        assertEquals("Chorus", l.lines[1].text)
    }

    @Test fun plainTextIsUntimed() {
        val l = LyricsParser.parse("\nOne\n\nTwo\n", "test")!!
        assertFalse(l.synced)
        assertEquals(listOf("One", "", "Two"), l.lines.map { it.text })
    }

    @Test fun emptyIsNull() = assertNull(LyricsParser.parse("  \n", "test"))

    @Test fun currentLine() {
        val l = LyricsParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c", "t")!!.lines
        assertEquals(-1, LyricsParser.current(l, 500))
        assertEquals(0, LyricsParser.current(l, 4999))
        assertEquals(1, LyricsParser.current(l, 5000))
        assertEquals(2, LyricsParser.current(l, 99_000))
    }
}
