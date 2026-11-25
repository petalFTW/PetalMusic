/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.rab1d.petal.music.model.Source

/** Read-only integration checks against accounts already connected on the device. */
class SourceBasicsTest {
    private fun check(source: Source) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PetalApp
        withTimeout(60_000) { app.container.servicesReady.first { it } }
        val provider = app.container.providers.getValue(source)
        assumeTrue("${source.label} account not connected", provider.isLoggedIn())
        val results = provider.search("Daft Punk Get Lucky")
        assertTrue("${source.label} catalogue search returned no tracks", results.tracks.isNotEmpty())
        assertTrue(results.tracks.all { it.source == source && it.id.isNotBlank() })
        val playlists = provider.playlists()
        android.util.Log.i("PetalSourceTest", "${source.label}: search=${results.tracks.size} playlists=${playlists.size}")
        playlists.firstOrNull()?.let {
            val tracks = provider.playlistTracks(it.id)
            android.util.Log.i("PetalSourceTest", "${source.label}: playlist tracks=${tracks.size}")
        }
        provider.favoriteTracks()
        provider.favoriteAlbums()
        provider.favoriteArtists()
        if (source == Source.SPOTIFY) {
            results.albums.firstOrNull()?.let { album ->
                val tracks = provider.album(album.id)
                assertTrue("Spotify album has no playable tracks", tracks.isNotEmpty() && tracks.all { it.title.isNotBlank() && it.id.length == 22 })
            }
            results.artists.firstOrNull()?.let { artist ->
                assertTrue("Spotify artist has no top tracks", provider.artistTopTracks(artist.id).isNotEmpty())
            }
        }
        Unit
    }

    @Test fun spotifySearchAndPlaylists() = check(Source.SPOTIFY)
    @Test fun tidalSearchAndPlaylists() = check(Source.TIDAL)
    @Test fun ytMusicSearchAndPlaylists() = check(Source.YT_MUSIC)

    @Test fun tidalPlaylistRoundTrip() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PetalApp
        withTimeout(60_000) { app.container.servicesReady.first { it } }
        val provider = app.container.tidal
        assumeTrue(provider.isLoggedIn())
        val track = provider.search("Daft Punk Get Lucky").tracks.first()
        val playlist = provider.createPlaylist("Petal verification ${System.currentTimeMillis()}")
        try {
            provider.addPlaylistTracks(playlist.id, listOf(track))
            var present = false
            repeat(10) {
                if (!present) {
                    present = provider.playlistTracks(playlist.id).any { it.id == track.id }
                    if (!present) kotlinx.coroutines.delay(500)
                }
            }
            assertTrue("New playlist never contained the added recording", present)
            provider.renamePlaylist(playlist.id, "Petal verification renamed")
            assertEquals("Petal verification renamed", provider.playlists().first { it.id == playlist.id }.name)
            provider.removePlaylistTrack(playlist.id, track)
            assertTrue(provider.playlistTracks(playlist.id).isEmpty())
        } finally { provider.deletePlaylist(playlist.id) }
    }

    @Test fun spotifyPlaylistRoundTrip() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PetalApp
        withTimeout(60_000) { app.container.servicesReady.first { it } }
        val provider = app.container.providers.getValue(Source.SPOTIFY)
        assumeTrue(provider.isLoggedIn())
        val track = provider.search("Daft Punk Get Lucky").tracks.first()
        val playlist = provider.createPlaylist("Petal verification ${System.currentTimeMillis()}")
        try {
            provider.addPlaylistTracks(playlist.id, listOf(track))
            assertTrue(provider.playlistTracks(playlist.id).any { it.id == track.id })
            provider.renamePlaylist(playlist.id, "Petal verification renamed")
            assertEquals("Petal verification renamed", provider.playlists().first { it.id == playlist.id }.name)
            provider.removePlaylistTrack(playlist.id, track)
            assertTrue(provider.playlistTracks(playlist.id).isEmpty())
        } finally { provider.deletePlaylist(playlist.id) }
        assertTrue(provider.playlists().none { it.id == playlist.id })
    }

    @Test fun ytMusicPlaylistRoundTrip() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PetalApp
        withTimeout(60_000) { app.container.servicesReady.first { it } }
        val provider = app.container.ytMusic
        assumeTrue(provider.isLoggedIn())
        val track = provider.search("Daft Punk Get Lucky").tracks.first()
        val playlist = provider.createPlaylist("Petal verification ${System.currentTimeMillis()}")
        try {
            provider.addPlaylistTracks(playlist.id, listOf(track))
            var present = false
            repeat(10) {
                if (!present) {
                    present = provider.playlistTracks(playlist.id).any { it.id == track.id }
                    if (!present) kotlinx.coroutines.delay(500)
                }
            }
            assertTrue("YT Music playlist never contained the added recording", present)
            provider.renamePlaylist(playlist.id, "Petal verification renamed")
            var updated = provider.playlists().first { it.id == playlist.id }
            repeat(10) {
                if (updated.name != "Petal verification renamed") {
                    kotlinx.coroutines.delay(500)
                    updated = provider.playlists().first { it.id == playlist.id }
                }
            }
            assertEquals("Petal verification renamed", updated.name)
            assertTrue("YT Music owned playlist not editable", updated.editable)
            val restored = org.rab1d.petal.music.sources.ytmusic.YtMusicSource(app)
            assertTrue(restored.restore())
            assertTrue("YT Music ownership lost after restart",
                restored.playlists().first { it.id == playlist.id }.editable)
            provider.removePlaylistTrack(playlist.id, track)
            var empty = provider.playlistTracks(playlist.id).isEmpty()
            repeat(10) {
                if (!empty) { kotlinx.coroutines.delay(500); empty = provider.playlistTracks(playlist.id).isEmpty() }
            }
            assertTrue(empty)
        } finally { provider.deletePlaylist(playlist.id) }
        var removed = provider.playlists().none { it.id == playlist.id }
        repeat(10) {
            if (!removed) { kotlinx.coroutines.delay(500); removed = provider.playlists().none { it.id == playlist.id } }
        }
        assertTrue(removed)
    }

    @Test fun localPlaylistRoundTrip() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PetalApp
        val store = app.container.settings
        val track = org.rab1d.petal.music.model.Track("test-id", Source.TIDAL, "Test recording", "Test artist")
        val playlist = store.createPlaylist("Petal local verification")
        try {
            store.addToPlaylist(playlist.id, listOf(track))
            assertTrue(store.playlists.first().first { it.id == playlist.id }.contains("tidal/test-id"))
            store.renamePlaylist(playlist.id, "Petal local renamed")
            assertEquals("Petal local renamed", store.playlists.first().first { it.id == playlist.id }.name)
            store.removeFromPlaylist(playlist.id, "tidal/test-id")
            assertTrue(store.playlists.first().first { it.id == playlist.id }.tracks.isEmpty())
        } finally { store.deletePlaylist(playlist.id) }
        assertTrue(store.playlists.first().none { it.id == playlist.id })
    }
}
