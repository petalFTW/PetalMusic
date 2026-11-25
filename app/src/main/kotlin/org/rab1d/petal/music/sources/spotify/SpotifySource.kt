/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.spotify

import com.google.gson.JsonObject
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.Artist
import org.rab1d.petal.music.model.PlaylistSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rab1d.petal.music.model.SearchResults
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.sources.SourceProvider

/**
 * Unofficial Spotify metadata/automix provider. Audio itself goes through the
 * librespot PCM pump (pumpsPcm), not the ExoPlayer data path.
 */
class SpotifySource(private val bridge: LibrespotBridge) : SourceProvider {
    override val source = Source.SPOTIFY
    override val pumpsPcm = true
    override val supportsPlaylistEditing = true

    override fun isLoggedIn(): Boolean = bridge.loggedIn

    override suspend fun search(query: String): SearchResults {
        val json = bridge.graphql("searchDesktop",
            "d9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026",
            JsonObject().apply {
                addProperty("searchTerm", query); addProperty("offset", 0); addProperty("limit", 25)
                addProperty("numberOfTopResults", 5); addProperty("includeAudiobooks", false)
                addProperty("includeArtistHasConcertsField", false); addProperty("includePreReleases", true)
                addProperty("includeLocalConcertsField", false); addProperty("includeAuthors", false)
            }).obj("searchV2") ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no search results")
        val tracks = json.obj("tracksV2").items().mapNotNull { it.entity().toTrack() }
        val albums = json.obj("albumsV2").items().mapNotNull { it.entity().toAlbum() }
        val artists = json.obj("artists").items().mapNotNull { it.entity().toArtist() }
        val playlists = json.obj("playlists").items().mapNotNull { it.entity().toPlaylist(bridge.username) }
        return SearchResults(tracks, albums, artists, playlists)
    }

    override suspend fun album(albumId: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        var offset = 0
        while (true) {
            val album = bridge.graphql("getAlbum",
                "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10", JsonObject().apply {
                    addProperty("uri", "spotify:album:$albumId"); addProperty("locale", "en")
                    addProperty("offset", offset); addProperty("limit", 50)
                }).obj("albumUnion") ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no album")
            val page = album.obj("tracksV2")
                ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no album tracks")
            val rows = page.items()
            out += rows.mapNotNull { it.obj("track")?.toTrack()?.copy(
                album = album.str("name").orEmpty(), albumId = albumId,
                artUri = album.obj("coverArt").imageUrl(),
            ) }
            offset += rows.size
            if (rows.isEmpty() || offset >= (page.get("totalCount")?.asInt ?: offset)) break
        }
        out
    }

    private suspend fun libraryEntities(filter: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var offset = 0
        while (true) {
            val page = bridge.graphql("libraryV3",
                "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455", JsonObject().apply {
                    add("filters", com.google.gson.JsonArray().apply {
                        if (filter == "Writable") add("Playlists")
                        add(filter)
                    })
                    addProperty("offset", offset); addProperty("limit", 50); addProperty("flatten", true)
                    addProperty("textFilter", ""); add("order", com.google.gson.JsonNull.INSTANCE)
                    add("features", com.google.gson.JsonArray().apply { add("LIKED_SONGS") })
                    add("expandedFolders", com.google.gson.JsonArray()); add("folderUri", com.google.gson.JsonNull.INSTANCE)
                    addProperty("includeFoldersWhenFlattening", false)
                }).obj("me")?.obj("libraryV3")
                ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no library")
            val rows = page.items()
            out += rows.map { it.entity() }
            offset += rows.size
            if (rows.isEmpty() || offset >= (page.get("totalCount")?.asInt ?: offset)) break
        }
        return out
    }

    override suspend fun playlists(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        val playlists = libraryEntities("Playlists").mapNotNull { it.toPlaylist(bridge.username) }
        val writable = libraryEntities("Writable").mapNotNull { it.toPlaylist(bridge.username)?.id }.toSet()
        playlists.map { if (it.id in writable) it.copy(editable = true) else it }
    }

    override suspend fun playlistTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        playlistRows(playlistId).mapNotNull { it.entity().toTrack() }
    }

    private suspend fun playlistRows(id: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var offset = 0
        while (true) {
            val page = bridge.graphql("fetchPlaylist",
                "cd2275433b29f7316176e7b5b5e098ae7744724e1a52d63549c76636b3257749", JsonObject().apply {
                    addProperty("uri", "spotify:playlist:$id"); addProperty("offset", offset)
                    addProperty("limit", 100); addProperty("enableWatchFeedEntrypoint", false)
                }).obj("playlistV2")?.obj("content")
                ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no playlist items")
            val rows = page.items()
            out += rows
            offset += rows.size
            if (rows.isEmpty() || offset >= (page.get("totalCount")?.asInt ?: offset)) break
        }
        return out
    }

    override suspend fun favoriteTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = ArrayList<Track>()
        var offset = 0
        while (true) {
            val page = bridge.graphql("fetchLibraryTracks",
                "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240", JsonObject().apply {
                    addProperty("offset", offset); addProperty("limit", 50)
                }).obj("me")?.obj("library")?.obj("tracks")
                ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no liked songs")
            val rows = page.items()
            tracks += rows.mapNotNull { it.obj("track")?.entity()?.toTrack() }
            offset += rows.size
            if (rows.isEmpty() || offset >= (page.get("totalCount")?.asInt ?: offset)) break
        }
        tracks
    }

    override suspend fun favoriteAlbums(): List<Album> = withContext(Dispatchers.IO) {
        libraryEntities("Albums").mapNotNull { it.toAlbum() }
    }

    override suspend fun favoriteArtists(): List<Artist> = withContext(Dispatchers.IO) {
        libraryEntities("Artists").mapNotNull { it.toArtist() }
    }

    override val hasArtistPages: Boolean get() = isLoggedIn()

    override suspend fun artistTopTracks(artistId: String): List<Track> = withContext(Dispatchers.IO) {
        bridge.graphql("queryArtistOverview",
            "7f86ff63e38c24973a2842b672abe44c910c1973978dc8a4a0cb648edef34527", JsonObject().apply {
                addProperty("uri", "spotify:artist:$artistId"); addProperty("locale", "en")
                addProperty("preReleaseV2", false)
            }).obj("artistUnion")?.obj("discography")?.obj("topTracks").items()
            .mapNotNull { it.obj("track")?.toTrack() }
    }

    override suspend fun createPlaylist(name: String): PlaylistSummary = withContext(Dispatchers.IO) {
        val result = bridge.sessionJson("https://spclient.wg.spotify.com/playlist/v2/playlist?format=json",
            JsonObject().apply { add("ops", com.google.gson.JsonArray().apply { add(nameOperation(name)) }) })
        val uri = result.str("uri")?.takeIf { it.startsWith("spotify:playlist:") }
            ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no playlist")
        val id = uri.substringAfterLast(':')
        rootlistChange(itemOperation("ADD", "add", listOf(uri)))
        PlaylistSummary(id, source, name, editable = true)
    }

    override suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        playlistChange(id, nameOperation(name))
        Unit
    }

    override suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        rootlistChange(itemOperation("REM", "rem", listOf("spotify:playlist:$id")))
        Unit
    }

    override suspend fun addPlaylistTracks(id: String, tracks: List<Track>) = withContext(Dispatchers.IO) {
        require(tracks.all { it.source == source })
        tracks.chunked(100).forEach { batch ->
            playlistChange(id, itemOperation("ADD", "add", batch.map { "spotify:track:${it.id}" }))
        }
    }

    override suspend fun removePlaylistTrack(id: String, track: Track) = withContext(Dispatchers.IO) {
        playlistChange(id, itemOperation("REM", "rem", listOf("spotify:track:${track.id}")))
        Unit
    }

    private fun nameOperation(name: String) = JsonObject().apply {
        addProperty("kind", "UPDATE_LIST_ATTRIBUTES")
        add("updateListAttributes", JsonObject().apply { add("newAttributes", JsonObject().apply {
            add("values", JsonObject().apply { addProperty("name", name) })
        }) })
    }

    private fun itemOperation(kind: String, field: String, uris: List<String>) = JsonObject().apply {
        addProperty("kind", kind)
        add(field, JsonObject().apply {
            addProperty(if (kind == "ADD") "addLast" else "itemsAsKey", true)
            add("items", com.google.gson.JsonArray().apply {
                uris.forEach { add(JsonObject().apply { addProperty("uri", it) }) }
            })
        })
    }

    private suspend fun changes(path: String, operation: JsonObject) {
        bridge.sessionJson("https://spclient.wg.spotify.com/playlist/v2/$path/changes?format=json", JsonObject().apply {
            add("deltas", com.google.gson.JsonArray().apply { add(JsonObject().apply {
                add("ops", com.google.gson.JsonArray().apply { add(operation) })
                add("info", JsonObject().apply { add("source", JsonObject().apply { addProperty("client", "WEBPLAYER") }) })
            }) })
        })
    }

    private suspend fun playlistChange(id: String, operation: JsonObject) = changes("playlist/$id", operation)
    private suspend fun rootlistChange(operation: JsonObject) = changes("user/${bridge.username}/rootlist", operation)

    /** Spotify Radio — the real recommender, over the unofficial session. */
    override suspend fun automixNext(seed: Track, limit: Int): List<Track> {
        if (seed.id.length != 22) return emptyList()
        val json = bridge.radioForTrack(seed.id) ?: return emptyList()
        val tracks = json.arr("tracks").orEmpty().mapNotNull { el ->
            val o = el.asObj()
            val uri = o.str("uri") ?: return@mapNotNull null
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            Track(
                id = uri.substringAfterLast(':'),
                source = Source.SPOTIFY,
                title = o.str("name") ?: "",
                artist = o.arr("artists").orEmpty().joinToString(", ") { it.asObj().str("name") ?: "" },
                album = o.obj("album")?.str("name") ?: "",
                durationMs = (o.get("duration_ms")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0),
                artUri = o.obj("album")?.arr("images")?.firstOrNull()?.asObj()?.str("url"),
            )
        }
        return tracks.filter { it.id != seed.id }.take(limit)
    }
}

// ---- defensive JSON helpers -------------------------------------------------------

internal fun JsonObject?.obj(k: String): JsonObject? =
    this?.get(k)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject?.arr(k: String): List<com.google.gson.JsonElement>? =
    this?.get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

internal fun JsonObject?.str(k: String): String? =
    this?.get(k)?.takeIf { it.isJsonPrimitive }?.asString

internal fun com.google.gson.JsonElement.asObj(): JsonObject =
    if (isJsonObject) asJsonObject else JsonObject()

internal fun JsonObject.toTrack(): Track? {
    val uri = str("uri") ?: return null
    if (!uri.startsWith("spotify:track:")) return null
    if (obj("playability")?.get("playable")?.asBoolean == false) return null
    return Track(
        id = uri.substringAfterLast(':'),
        source = Source.SPOTIFY,
        title = str("name") ?: "",
        artist = artistNames(),
        album = (obj("album") ?: obj("albumOfTrack"))?.str("name") ?: "",
        albumId = (obj("album") ?: obj("albumOfTrack"))?.str("uri")?.substringAfterLast(':'),
        durationMs = get("duration_ms")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: (obj("duration") ?: obj("trackDuration"))?.get("totalMilliseconds")?.asLong ?: 0,
        artUri = obj("albumOfTrack")?.obj("coverArt").imageUrl()
            ?: obj("album")?.arr("images")?.firstOrNull()?.asObj()?.str("url"),
        isrc = obj("external_ids")?.str("isrc"),
        trackNumber = get("trackNumber")?.asInt ?: get("track_number")?.asInt ?: 0,
        discNumber = get("discNumber")?.asInt ?: get("disc_number")?.asInt ?: 1,
        explicit = get("explicit")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            obj("contentRating")?.str("label") == "EXPLICIT",
        artistId = (obj("artists").items().ifEmpty { arr("artists").orEmpty().map { it.asObj() } })
            .firstOrNull()?.str("uri")?.substringAfterLast(':'),
    )
}

internal fun JsonObject.toAlbum(): Album? {
    val uri = str("uri") ?: return null
    if (!uri.startsWith("spotify:album:")) return null
    return Album(
        id = uri.substringAfterLast(':'),
        source = Source.SPOTIFY,
        title = str("name") ?: "",
        artist = artistNames(),
        artUri = obj("coverArt").imageUrl() ?: arr("images")?.firstOrNull()?.asObj()?.str("url"),
    )
}

internal fun JsonObject.toArtist(): Artist? {
    val uri = str("uri") ?: return null
    if (!uri.startsWith("spotify:artist:")) return null
    return Artist(
        id = uri.substringAfterLast(':'),
        source = Source.SPOTIFY,
        name = obj("profile")?.str("name") ?: str("name") ?: "",
    )
}

internal fun JsonObject.toPlaylist(username: String?): PlaylistSummary? {
    val uri = str("uri") ?: return null
    if (!uri.startsWith("spotify:playlist:")) return null
    val id = str("id") ?: uri.substringAfterLast(':')
    return PlaylistSummary(id, Source.SPOTIFY, str("name").orEmpty(),
        obj("content")?.get("totalCount")?.asInt ?: obj("tracks")?.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1,
        obj("images").items().firstOrNull()?.imageUrl() ?: arr("images")?.firstOrNull()?.asObj()?.str("url"),
        editable = obj("currentUserCapabilities")?.get("canEditItems")?.asBoolean == true ||
            obj("ownerV2")?.obj("data")?.str("username") == username ||
            obj("owner")?.str("id") == username || get("collaborative")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
}

internal fun JsonObject?.items(): List<JsonObject> = arr("items").orEmpty().filter { it.isJsonObject }.map { it.asJsonObject }
internal fun JsonObject.entity(): JsonObject = (obj("itemV2") ?: obj("item") ?: this).let { it.obj("data") ?: it }
internal fun JsonObject?.imageUrl(): String? = arr("sources").orEmpty().map { it.asObj() }
    .maxByOrNull { it.get("width")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0 }?.str("url")
private fun JsonObject.artistNames(): String = (obj("artists").items().ifEmpty { arr("artists").orEmpty().map { it.asObj() } })
    .joinToString(", ") { it.obj("profile")?.str("name") ?: it.str("name").orEmpty() }
