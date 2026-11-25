/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop

import org.rab1d.petal.music.desktop.link.DeviceLinkServer
import org.rab1d.petal.music.desktop.link.renderCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceLinkTest {

    @Test
    fun `render command substitutes placeholders`() {
        val rendered = renderCommand(
            DeviceLinkServer.DEFAULT_DOWNLOADER,
            "Boards of Canada - Roygbiv",
            "/music/%(title)s.%(ext)s",
        )
        assertTrue(rendered.contains("\"ytsearch1:Boards of Canada - Roygbiv\""))
        assertTrue(rendered.contains("\"/music/%(title)s.%(ext)s\""))
        assertTrue(!rendered.contains("{query}") && !rendered.contains("{output}"))
    }

    @Test
    fun `render command leaves a template without placeholders`() {
        assertEquals("echo hi", renderCommand("echo hi", "x", "y"))
    }
}
