/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.data.downloads.DownloadedTrack

class DownloadMetadataTest {
    @Test fun offlineTrackRetainsArtistAndContentMetadataAfterPersistence() {
        val entry = DownloadedTrack(
            "review", "tidal", "Song", "Artist", "Album", file = "/music/review.flac",
            explicit = true, artistId = "artist-123", composer = "Writer",
        )
        val restored = Json.decodeFromString<DownloadedTrack>(Json.encodeToString(entry)).toTrack()
        assertTrue(restored.explicit)
        assertEquals("artist-123", restored.artistId)
        assertEquals("Writer", restored.composer)
    }

    @Test fun oldDownloadIndexesStillLoadWithoutInventingMetadata() {
        val entry = Json.decodeFromString<DownloadedTrack>(
            """{"id":"old","source":"tidal","title":"Song","artist":"Artist","album":"Album","file":"/music/old.flac"}""",
        ).toTrack()
        assertFalse(entry.explicit)
        assertNull(entry.artistId)
        assertNull(entry.composer)
    }
}
