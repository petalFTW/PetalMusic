/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

enum class Source(val id: String, val label: String) {
    LOCAL("local", "Device"),
    SPOTIFY("spotify", "Spotify"),
    YT_MUSIC("ytmusic", "YT Music"),
    DEEZER("deezer", "Deezer"),
    TIDAL("tidal", "Tidal");

    companion object {
        fun fromId(id: String): Source = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

/** A playable track from any source (Parcelable so MediaItem tags survive). */
@Parcelize
@Serializable
data class Track(
    /** Globally unique id: "local:1234", "spotify:xxxx", "ytmusic:VideoId", "deezer:123", "tidal:123". */
    val id: String,
    val source: Source,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val year: Int = 0,
    /** Content uri (local) or remote artwork url. */
    val artUri: String? = null,
    /** File path for local playback. */
    val localPath: String? = null,
    val genre: String? = null,
    val isrc: String? = null,
    /** Tempo in BPM from the source's own metadata (TIDAL), when known. */
    val bpm: Float? = null,
    /** Camelot wheel number (1..12) from source metadata, when known. */
    val camelot: Int? = null,
    /** Musical key name from source metadata, e.g. "C#". */
    val musicalKey: String? = null,
    /**
     * The source the user picked this song from, when audio is served by a
     * different provider (Spotify Free playback through YT Music).
     */
    val originSource: Source? = null,
    /** The source marks it explicit. */
    val explicit: Boolean = false,
    /** The source's id for the (first) artist, when the source has artist pages. */
    val artistId: String? = null,
    /** Writer from the file's tags (device files only). */
    val composer: String? = null,
) : Parcelable {
    val sourceScopedId: String get() = "${source.id}:$id".let { if (id.startsWith("${source.id}:")) id else it }
}

fun Track.key(): String = "${source.id}/${id}"

/**
 * True when a track carries enough identity/metadata to be shown and played.
 * Providers occasionally return placeholder entries for region-locked or
 * removed songs; those must not appear in playlists or count as downloads.
 */
val Track.isPlayable: Boolean
    get() = id.isNotBlank() && title.isNotBlank() && !title.equals("Unknown", ignoreCase = true)

fun List<Track>.playable(): List<Track> = filter { it.isPlayable }

@Serializable
data class Album(
    val id: String,
    val source: Source,
    val title: String,
    val artist: String = "",
    val year: Int = 0,
    val artUri: String? = null,
    val trackCount: Int = 0,
)

@Serializable
data class Artist(
    val id: String,
    val source: Source,
    val name: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackKeys: MutableList<String> = mutableListOf(),
    /**
     * Full metadata for every entry, kept alongside [trackKeys] so remote
     * tracks render and play from any playlist, online or offline. Older
     * playlists only carry [trackKeys]; those are resolved from the metadata
     * cache / device library when opened.
     */
    val tracks: MutableList<Track> = mutableListOf(),
) {
    @Transient val source: Source = Source.LOCAL

    /** Ready-to-play entries, with unknown/unavailable songs filtered out. */
    fun playable(): List<Track> = tracks.filter { it.isPlayable }

    fun contains(trackKey: String): Boolean =
        tracks.any { it.key() == trackKey } || trackKeys.contains(trackKey)
}

/** A playlist as exposed by a source. */
@Serializable
data class PlaylistSummary(
    val id: String,
    val source: Source,
    val name: String,
    val trackCount: Int = 0,
    val artUri: String? = null,
    val editable: Boolean = false,
)

/** What a source returns for searches. */
data class SearchResults(
    var tracks: List<Track> = emptyList(),
    var albums: List<Album> = emptyList(),
    var artists: List<Artist> = emptyList(),
    var playlists: List<PlaylistSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

/** One line of lyrics; [timeMs] is -1 for untimed lyrics. */
@Serializable
data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics for a song, timed when [synced]. [provider] is who supplied them, for the credit line. */
@Serializable
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val provider: String)

/** A credit role ("Producer") and the people in it. */
@Serializable
data class Credit(val role: String, val names: List<String>)

/** Exception carrying a user-presentable message from a source provider. */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
