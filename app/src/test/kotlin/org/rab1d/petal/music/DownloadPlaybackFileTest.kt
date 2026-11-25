/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.rab1d.petal.music.data.downloads.DownloadedTrack

class DownloadPlaybackFileTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun missingOrEmptyUpscaleFallsBackToOriginalDownload() {
        val original = files.newFile("original.m4a").apply { writeBytes(byteArrayOf(1)) }
        val upscale = files.newFile("upscaled.wav")
        val entry = DownloadedTrack("id", "tidal", "song", "artist", "album",
            file = original.path, upscaledFile = upscale.path, upscaleEnabled = true)
        assertEquals(original, entry.playbackFile())
        upscale.delete()
        assertEquals(original, entry.playbackFile())
        original.delete()
        assertNull(entry.playbackFile())
    }

    @Test
    fun selectedUsableUpscaleWinsEvenWhenOriginalIsMissing() {
        val upscale = files.newFile("upscaled.wav").apply { writeBytes(byteArrayOf(1)) }
        val entry = DownloadedTrack("id", "tidal", "song", "artist", "album",
            file = files.root.resolve("missing.m4a").path,
            upscaledFile = upscale.path, upscaleEnabled = true)
        assertEquals(upscale, entry.playbackFile())
        assertNull(entry.copy(upscaleEnabled = false).playbackFile())
    }

    @Test
    fun disabledRetainedCopySurvivesProfileAndRevisionChangesUntilExplicitDeletion() {
        val upscale = files.newFile("retained.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val entry = DownloadedTrack("id", "tidal", "song", "artist", "album",
            file = "missing.m4a", upscaledFile = upscale.path, upscaleEnabled = false,
            upscaleRevision = 0, upscaleWidth = -1f)
        assertEquals(upscale, entry.retainedUpscaleFile())
        assertEquals(upscale, entry.copy(upscaleEnabled = true).playbackFile())
        upscale.writeBytes(byteArrayOf())
        assertNull(entry.retainedUpscaleFile())
        upscale.delete()
        assertNull(entry.retainedUpscaleFile())
    }
}
