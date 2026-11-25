/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources

import org.rab1d.petal.music.data.MediaStoreLibrary
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.Artist
import org.rab1d.petal.music.model.SearchResults
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key

class LocalSource(
    private val library: MediaStoreLibrary,
    private val settings: org.rab1d.petal.music.data.SettingsRepository? = null,
) : SourceProvider {
    override val source = Source.LOCAL

    override suspend fun search(query: String): SearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults()
        val songs = library.all().filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
        val albums = library.albumList().filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
        val artists = library.artistList().filter { it.name.lowercase().contains(q) }
        return SearchResults(songs.take(50), albums.take(20), artists.take(20))
    }

    override suspend fun album(albumId: String): List<Track> = library.albumTracks(albumId)
    override suspend fun artistTopTracks(artistId: String): List<Track> = library.artistTracks(artistId)
    override val hasArtistPages: Boolean get() = true

    override suspend fun playlists(): List<org.rab1d.petal.music.model.PlaylistSummary> {
        val saved = settings?.playlists
        return emptyList() // local playlists surface in the Playlists tab already
    }

    /**
     * Local AutoMix heuristic: continue within the album, then the artist, then
     * fill with tracks sharing genre-ish metadata; falls back to anything.
     */
    override suspend fun automixNext(seed: Track, limit: Int): List<Track> {
        val all = library.all()
        if (all.isEmpty()) return emptyList()
        val pool = all.filter { it.key() != seed.key() }
        val scored = pool.map { it to score(seed, it) }.sortedByDescending { it.second }
        return scored.take(limit).map { it.first }
    }

    private fun score(seed: Track, cand: Track): Int {
        var s = 0
        if (cand.albumId == seed.albumId && seed.albumId != null) s += 40
        if (cand.artist == seed.artist) s += 25
        if (cand.artist.contains(seed.artist, true) || seed.artist.contains(cand.artist, true)) s += 10
        if (cand.year != 0 && cand.year == seed.year) s += 5
        return s
    }
}
