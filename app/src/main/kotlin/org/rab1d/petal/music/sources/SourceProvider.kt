/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.Artist
import org.rab1d.petal.music.model.PlaylistSummary
import org.rab1d.petal.music.model.SearchResults
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.TrackAnalysis

/**
 * A music source. Audio for remote sources is exposed either as an url (played
 * by the main ExoPlayer) or as raw PCM (pumped by the source itself, e.g.
 * Spotify's encrypted stream). Sources with PCM output set [pumpsPcm].
 */
interface SourceProvider {
    val source: Source
    val pumpsPcm: Boolean get() = false
    val supportsPlaylistEditing: Boolean get() = false

    /** Whether credentials are configured and usable. */
    fun isLoggedIn(): Boolean = true

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        SearchResults()
    }

    suspend fun album(albumId: String): List<Track> = withContext(Dispatchers.IO) { emptyList() }
    suspend fun artistTopTracks(artistId: String): List<Track> = withContext(Dispatchers.IO) { emptyList() }

    /** Whether [artistTopTracks] returns anything: decides if an artist name opens a page. */
    val hasArtistPages: Boolean get() = false

    /** The source's own lyrics for [track], or null. */
    suspend fun lyrics(track: Track): org.rab1d.petal.music.model.Lyrics? = null

    /** Songwriting and production credits, grouped by role. */
    suspend fun credits(track: Track): List<org.rab1d.petal.music.model.Credit> = emptyList()

    /** Library surface: user playlists + favourites. */
    suspend fun playlists(): List<PlaylistSummary> = withContext(Dispatchers.IO) { emptyList() }
    suspend fun playlistTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) { emptyList() }
    suspend fun favoriteTracks(): List<Track> = withContext(Dispatchers.IO) { emptyList() }
    suspend fun favoriteAlbums(): List<Album> = withContext(Dispatchers.IO) { emptyList() }
    suspend fun favoriteArtists(): List<Artist> = withContext(Dispatchers.IO) { emptyList() }

    suspend fun createPlaylist(name: String): PlaylistSummary =
        throw org.rab1d.petal.music.model.SourceException("${source.label} playlist creation is unavailable")
    suspend fun renamePlaylist(id: String, name: String): Unit =
        throw org.rab1d.petal.music.model.SourceException("${source.label} playlist editing is unavailable")
    suspend fun deletePlaylist(id: String): Unit =
        throw org.rab1d.petal.music.model.SourceException("${source.label} playlist deletion is unavailable")
    suspend fun addPlaylistTracks(id: String, tracks: List<Track>): Unit =
        throw org.rab1d.petal.music.model.SourceException("${source.label} playlist editing is unavailable")
    suspend fun removePlaylistTrack(id: String, track: Track): Unit =
        throw org.rab1d.petal.music.model.SourceException("${source.label} playlist editing is unavailable")

    /**
     * Resolve a playable stream for [track]. Returns null for local tracks and
     * for PCM sources (they resolve through their own pump).
     */
    suspend fun stream(track: Track): StreamHandle? = withContext(Dispatchers.IO) { null }

    /**
     * Suggested continuations for AutoMix after [seed]. Sources may return
     * provider recommendations (Spotify Radio, YTM related) or heuristics.
     */
    suspend fun automixNext(seed: Track, limit: Int = 5): List<Track> =
        withContext(Dispatchers.IO) { emptyList() }

    /**
     * Authoritative musical facts (BPM/key) from the source's own metadata.
     * TIDAL ships these per track, so the DJ planner can beatmatch on real
     * numbers instead of guessing from the waveform. Null when unavailable.
     */
    suspend fun analyze(track: Track): TrackAnalysis? = withContext(Dispatchers.IO) { null }
}

/** See PetalStreamResolver.kt for the StreamHandle variants. */
