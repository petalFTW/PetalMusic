/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.sources.spotify.*

class SpotifyJsonTest {
    @Test fun pathfinderTrackWrappersKeepPlayableIdsAndMetadata() {
        val row = JsonParser.parseString("""{
          "item":{"data":{"uri":"spotify:track:abcdefghij012345678901","name":"Recording",
          "artists":{"items":[{"profile":{"name":"Artist"}}]},
          "albumOfTrack":{"uri":"spotify:album:album-id","name":"Album",
            "coverArt":{"sources":[{"width":64,"url":"small"},{"width":640,"url":"large"}]}},
          "duration":{"totalMilliseconds":213000}}}
        }""").asJsonObject
        val track = row.entity().toTrack()!!
        assertEquals("abcdefghij012345678901", track.id)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(213000L, track.durationMs)
        assertEquals("large", track.artUri)
        assertEquals(track, JsonParser.parseString("""{"itemV2":${row.obj("item")}}""").asJsonObject.entity().toTrack())
    }

    @Test fun libraryOwnershipAndCapabilitiesControlEditing() {
        val playlist = JsonParser.parseString("""{
          "uri":"spotify:playlist:playlist-id","name":"My playlist",
          "ownerV2":{"data":{"username":"owner"}},"content":{"totalCount":7},
          "images":{"items":[{"sources":[{"url":"cover"}]}]}
        }""").asJsonObject
        assertTrue(playlist.toPlaylist("owner")!!.editable)
        assertFalse(playlist.toPlaylist("someone-else")!!.editable)
        assertEquals(7, playlist.toPlaylist("owner")!!.trackCount)
        assertEquals("cover", playlist.toPlaylist("owner")!!.artUri)
        playlist.add("currentUserCapabilities", JsonParser.parseString("""{"canEditItems":true}"""))
        assertTrue(playlist.toPlaylist("collaborator")!!.editable)
        assertNull(JsonParser.parseString("""{"uri":"spotify:collection:tracks"}""").asJsonObject.toPlaylist("owner"))
    }
}
