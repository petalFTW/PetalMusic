/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data

import android.content.Context
import android.provider.MediaStore
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.Artist
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key

/** MediaStore-backed local library cache, refreshed on demand. */
class MediaStoreLibrary(private val context: Context) {

    @Volatile private var tracks: List<Track> = emptyList()
    @Volatile private var albums: List<Album> = emptyList()
    @Volatile private var artists: List<Artist> = emptyList()

    fun all(): List<Track> {
        if (tracks.isEmpty()) refresh()
        return tracks
    }

    fun albumList(): List<Album> {
        if (albums.isEmpty()) refresh()
        return albums
    }

    fun artistList(): List<Artist> {
        if (artists.isEmpty()) refresh()
        return artists
    }

    fun track(key: String): Track? = all().firstOrNull { it.key() == key }

    fun albumTracks(albumId: String): List<Track> =
        all().filter { it.albumId == albumId }.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))

    /** Accepts the key in either form ("Petal Sessions", "petal sessions", "petalsessions"). */
    fun artistTracks(artistId: String): List<Track> {
        val key = artistId.lowercase().replace(" ", "")
        return all().filter { it.localArtistKey == key }
    }

    fun refresh() {
        val t = ArrayList<Track>(512)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISC_NUMBER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.COMPOSER,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>=10000",
            null,
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE ASC, ${MediaStore.Audio.Media.TRACK} ASC",
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val artistIdC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val discC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISC_NUMBER)
            val yearC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val composerC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                t.add(
                    Track(
                        id = id.toString(),
                        source = Source.LOCAL,
                        title = c.getString(titleC) ?: "Unknown",
                        artist = c.getString(artistC) ?: "",
                        album = c.getString(albumC) ?: "",
                        albumId = c.getString(albumIdC),
                        durationMs = c.getLong(durC),
                        trackNumber = c.getInt(trackC) % 1000,
                        discNumber = c.getInt(discC),
                        year = c.getInt(yearC),
                        artUri = albumArtUri(c.getString(albumIdC)),
                        localPath = c.getString(dataC),
                        composer = c.getString(composerC)?.takeIf { it.isNotBlank() },
                        artistId = (c.getString(artistC) ?: "").lowercase().replace(" ", ""),
                    ),
                )
            }
        }
        val a = ArrayList<Album>(128)
        t.groupBy { it.albumId }.forEach { (aid, ts) ->
            val first = ts.firstOrNull() ?: return@forEach
            a.add(
                Album(
                    id = aid ?: return@forEach,
                    source = Source.LOCAL,
                    title = first.album,
                    artist = first.artist,
                    year = ts.maxOf { it.year },
                    artUri = first.artUri,
                    trackCount = ts.size,
                ),
            )
        }
        val ar = ArrayList<Artist>(64)
        // The id is the same key tracks carry as artistId, so an artist opened from the
        // grid or from an artist link finds the same songs.
        t.groupBy { it.localArtistKey }.forEach { (k, ts) ->
            val first = ts.firstOrNull() ?: return@forEach
            ar.add(
                Artist(
                    id = k,
                    source = Source.LOCAL,
                    name = first.artist.ifEmpty { "Unknown artist" },
                    albumCount = ts.mapNotNull { it.albumId }.distinct().size,
                    trackCount = ts.size,
                ),
            )
        }
        tracks = t
        albums = a.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        artists = ar.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private val Track.localArtistKey: String get() = artist.lowercase().replace(" ", "")

    private fun albumArtUri(albumId: String?): String? =
        albumId?.let { "content://media/external/audio/albumart/$it" }
}
