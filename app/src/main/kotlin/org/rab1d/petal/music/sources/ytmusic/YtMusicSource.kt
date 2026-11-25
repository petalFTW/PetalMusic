/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.ytmusic

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rab1d.petal.music.model.PlaylistSummary
import org.rab1d.petal.music.model.SourceException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.SearchResults
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.sources.SourceProvider
import org.rab1d.petal.music.sources.StreamHandle
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OkHttp bridge for NewPipeExtractor. When a YouTube account is connected the
 * stored cookie header is attached to every extractor request.
 */
class OkHttpDownloader(
    private val cookieProvider: () -> String? = { null },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.let { okhttp3.RequestBody.create(null, it) }
        val rb = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0",
            )
        for ((k, vs) in request.headers()) vs.firstOrNull()?.let { rb.header(k, it) }
        // Merge cookies by name: a repeated SOCS/CONSENT makes Google honor
        // only the first value, which would keep the consent wall up.
        val consentCookie = "CONSENT=YES+cb.20210328-17-p0.en+FX+419; SOCS=CAI"
        val host = rb.build().url.host
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val existingCookie = request.headers().entries
                .firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value?.joinToString("; ")
            rb.header("Cookie", mergeYouTubeCookies(consentCookie, existingCookie, cookieProvider()))
        }

        val response = client.newCall(rb.build()).execute()
        if (response.code == 429) {
            val url = response.request.url.toString()
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }
        return response.use {
            Response(
                it.code,
                it.message,
                it.headers.toMultimap(),
                it.body?.string(),
                it.request.url.toString(),
            )
        }
    }
}

/** Unofficial YT Music client: search + stream resolution via NewPipeExtractor. */
class YtMusicSource(private val context: Context) : SourceProvider {
    override val source = Source.YT_MUSIC
    override val supportsPlaylistEditing = true
    private val apiClient = OkHttpClient()
    private val editablePlaylists = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private data class CachedStream(val handle: StreamHandle, val expiresAt: Long)
    private val streams = java.util.concurrent.ConcurrentHashMap<String, CachedStream>()
    private val streamLock = Mutex()

    companion object {
        const val TAG = "PetalMusic/YTMusic"

        /** YouTube Music "songs" search filter (ytmusicapi filter param). */
        private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val ALBUMS_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0"
        private const val MWEB_UA = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        private const val WEB_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val STATE_FILE = "ytmusic_state.json"

        @Volatile private var initialized = false

        @Synchronized fun init(cookieProvider: () -> String?) {
            if (initialized) return
            // SOCS=CAE (reject) makes YouTube serve consent shells instead of
            // data pages; the extractor must send the accepted variant.
            runCatching {
                org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
                    .setConsentAccepted(true)
            }
            runCatching { NewPipe.init(OkHttpDownloader(cookieProvider)) }
                .onFailure { Log.w(TAG, "NewPipe init failed: ${it.message}") }
                .onSuccess {
                    initialized = true
                    Log.i(TAG, "NewPipe initialized with petal downloader")
                }
        }
    }

    @Volatile private var cookies: String? = null

    private val stateFile: File get() = File(context.filesDir, STATE_FILE)

    override fun isLoggedIn(): Boolean = hasYouTubeSession(cookies)

    /** Cookie header captured from the in-app Google/YouTube sign-in WebView. */
    fun cookieHeader(): String? = cookies

    fun init() = Companion.init { cookies }

    /** Persist the account cookies captured by the WebView sign-in. */
    fun login(cookieHeader: String): Boolean {
        val trimmed = cookieHeader.trim()
        if (!hasYouTubeSession(trimmed)) return false
        cookies = trimmed
        streams.clear()
        editablePlaylists.clear()
        runCatching {
            stateFile.writeText(JSONObject().put("cookies", trimmed).toString())
        }
        return true
    }

    fun restore(): Boolean = runCatching {
        if (!stateFile.exists()) return false
        cookies = JSONObject(stateFile.readText()).optString("cookies").ifEmpty { null }
        hasYouTubeSession(cookies)
    }.getOrDefault(false)

    fun logout() {
        stateFile.delete()
        cookies = null
        streams.clear()
        editablePlaylists.clear()
    }

    private fun sapisidHash(origin: String): String? {
        val cookie = cookies ?: return null
        val sapisid = cookie.split(';').map { it.trim().split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] in listOf("SAPISID", "__Secure-3PAPISID") }?.get(1)
            ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hash"
    }

    private fun musicApi(endpoint: String, payload: JSONObject, requireLogin: Boolean = true): JSONObject {
        if (requireLogin && !isLoggedIn()) throw SourceException("Sign in to YT Music first")
        val cookie = cookies
        val origin = "https://music.youtube.com"
        val authorization = sapisidHash(origin)
        if (requireLogin && authorization == null) throw SourceException("YT Music session needs a fresh sign-in")
        payload.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", "1.20261007.01.00")
            .put("hl", "en").put("gl", "US")))
        val req = okhttp3.Request.Builder().url("$origin/youtubei/v1/$endpoint?prettyPrint=false")
            .header("Cookie", mergeYouTubeCookies("SOCS=CAI", cookie)).header("Origin", origin)
            .header("User-Agent", DESKTOP_UA)
            .apply { authorization?.let { header("Authorization", it) } }
            .header("X-Youtube-Client-Name", "67").header("X-Youtube-Client-Version", "1.20261007.01.00")
            .header("X-Goog-AuthUser", "0")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return apiClient.newCall(req).execute().use { response ->
            val result = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (!response.isSuccessful || result.has("error"))
                throw SourceException("YT Music: HTTP ${response.code} ${result.optJSONObject("error")?.optString("message").orEmpty()}")
            val status = result.optString("status")
            if (status.isNotBlank() && status != "STATUS_SUCCEEDED") throw SourceException("YT Music: $status")
            result
        }
    }

    private fun nodes(value: Any?, key: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(key)?.let(out::add)
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return out
    }

    private fun browseAll(id: String, playlist: Boolean = false): List<JSONObject> {
        val pages = ArrayList<JSONObject>()
        var payload = JSONObject().put("browseId", id)
        val seen = HashSet<String>()
        while (true) {
            val page = musicApi("browse", payload)
            pages += page
            val roots = if (playlist) playlistShelves(page) else listOf(page)
            val continuation = roots.flatMap { nodes(it, "nextContinuationData") + nodes(it, "continuationCommand") }
                .firstOrNull()?.let { it.optString("continuation").ifBlank { it.optString("token") } }
                ?.takeIf { it.isNotBlank() && seen.add(it) } ?: break
            payload = JSONObject().put("continuation", continuation)
        }
        return pages
    }

    private fun JSONObject.text(): String = optJSONArray("runs")?.let { runs ->
        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    } ?: optString("simpleText")

    private fun JSONObject.art(): String? = nodes(this, "thumbnail").flatMap { node ->
        node.optJSONArray("thumbnails")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url") } }.orEmpty()
    }.lastOrNull()

    override suspend fun playlists(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        browseAll("FEmusic_liked_playlists").flatMap { nodes(it, "musicTwoRowItemRenderer") }
            .mapNotNull { renderer ->
                val browseId = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?: return@mapNotNull null
                if (!browseId.startsWith("VL")) return@mapNotNull null
                val id = browseId.removePrefix("VL")
                val editable = editablePlaylists[id] ?: run {
                    // Library tiles omit the editor affordance even for owned
                    // playlists. The playlist header supplies that capability.
                    val page = musicApi("browse", JSONObject().put("browseId", browseId))
                    (nodes(page, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty() ||
                        nodes(page, "musicPlaylistEditEndpoint").isNotEmpty() ||
                        nodes(page, "icon").any { it.optString("iconType") == "EDIT" })
                        .also { editablePlaylists[id] = it }
                }
                PlaylistSummary(id, source,
                    renderer.optJSONObject("title")?.text().orEmpty(), trackCount = -1, artUri = renderer.art(),
                    editable = editable)
            }.distinctBy { it.id }
    }

    private fun playlistShelves(page: JSONObject): List<JSONObject> =
        nodes(page, "musicPlaylistShelfRenderer") + nodes(page, "musicPlaylistShelfContinuation") +
            nodes(page, "musicShelfContinuation")

    private fun playlistRows(id: String) = browseAll("VL${id.removePrefix("VL")}", playlist = true)
        .flatMap { page -> playlistShelves(page).flatMap { nodes(it, "musicResponsiveListItemRenderer") } }

    private fun JSONObject.flexColumn(index: Int): String =
        optJSONArray("flexColumns")?.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.text().orEmpty()

    /** Albums-filter row: subtitle reads "Album • Daft Punk • 2013". */
    private fun JSONObject.musicAlbum(): Album? {
        val id = optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            ?.takeIf { it.startsWith("MPREb") } ?: return null
        val details = flexColumn(1).split(" • ").drop(1)
        return Album(
            id, source, flexColumn(0),
            artist = details.firstOrNull { it.toIntOrNull() == null }.orEmpty(),
            year = details.lastOrNull()?.toIntOrNull() ?: 0,
            // Rows only list small thumbnails, the same url serves any size.
            artUri = optJSONObject("thumbnail")?.art()?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544"),
        )
    }

    private fun JSONObject.musicTrack(): Track? {
        val videoId = optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: nodes(this, "watchEndpoint").firstOrNull()?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null
        if (!has("flexColumns")) return null
        fun column(index: Int) = flexColumn(index)
        // Songs-filter rows carry the duration in a fixed column ("3:49").
        val durationMs = optJSONArray("fixedColumns")?.let { fixed ->
            (0 until fixed.length()).asSequence().mapNotNull { i ->
                fixed.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")?.text()
            }.firstOrNull { it.contains(':') }
        }.let { parseDurationMs(it ?: "") }
        return Track(videoId, source, column(0), column(1).substringBefore(" • "),
            durationMs = durationMs, artUri = art(),
            explicit = optJSONArray("badges")?.toString()?.contains("MUSIC_EXPLICIT_BADGE") == true)
    }

    private fun parseDurationMs(text: String): Long {
        val parts = text.trim().split(":").map { it.trim().toLongOrNull() ?: return 0 }
        return when (parts.size) {
            2 -> parts[0] * 60_000 + parts[1] * 1_000
            3 -> parts[0] * 3_600_000 + parts[1] * 60_000 + parts[2] * 1_000
            else -> 0
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        playlistRows(playlistId).mapNotNull { it.musicTrack() }
    }

    override suspend fun favoriteTracks(): List<Track> = withContext(Dispatchers.IO) {
        browseAll("FEmusic_liked_videos", playlist = true)
            .flatMap { page -> playlistShelves(page).flatMap { nodes(it, "musicResponsiveListItemRenderer") } }
            .mapNotNull { it.musicTrack() }
    }

    override suspend fun createPlaylist(name: String): PlaylistSummary = withContext(Dispatchers.IO) {
        val result = musicApi("playlist/create", JSONObject().put("title", name).put("privacyStatus", "PRIVATE"))
        val id = result.optString("playlistId").takeIf { it.isNotBlank() }
            ?: throw SourceException("YT Music returned no playlist")
        editablePlaylists[id] = true
        PlaylistSummary(id, source, name, editable = true)
    }

    private fun editPlaylist(id: String, actions: JSONArray) {
        musicApi("browse/edit_playlist", JSONObject().put("playlistId", id.removePrefix("VL")).put("actions", actions))
    }

    override suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        editPlaylist(id, JSONArray().put(JSONObject().put("action", "ACTION_SET_PLAYLIST_NAME").put("playlistName", name)))
    }

    override suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        musicApi("playlist/delete", JSONObject().put("playlistId", id.removePrefix("VL")))
        editablePlaylists.remove(id.removePrefix("VL"))
        Unit
    }

    override suspend fun addPlaylistTracks(id: String, tracks: List<Track>) = withContext(Dispatchers.IO) {
        require(tracks.all { it.source == source })
        tracks.chunked(50).forEach { batch ->
            editPlaylist(id, JSONArray().apply { batch.forEach { track ->
                put(JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", track.id))
            } })
        }
    }

    override suspend fun removePlaylistTrack(id: String, track: Track) = withContext(Dispatchers.IO) {
        val actions = JSONArray()
        playlistRows(id).forEach { row ->
            val item = row.optJSONObject("playlistItemData") ?: return@forEach
            if (item.optString("videoId") == track.id) {
                val setVideoId = item.optString("playlistSetVideoId").takeIf { it.isNotBlank() }
                    ?: throw SourceException("YT Music did not return an editable playlist item")
                actions.put(JSONObject().put("action", "ACTION_REMOVE_VIDEO")
                    .put("removedVideoId", track.id).put("setVideoId", setVideoId))
            }
        }
        if (actions.length() > 0) editPlaylist(id, actions)
    }

    private fun searchAlbums(query: String): List<Album> = runCatching {
        val result = musicApi("search", JSONObject().put("query", query).put("params", ALBUMS_FILTER), requireLogin = false)
        nodes(result, "musicResponsiveListItemRenderer").mapNotNull { it.musicAlbum() }
            .filter { it.title.isNotBlank() }.distinctBy { it.id }
    }.onFailure { Log.w(TAG, "album search failed", it) }.getOrDefault(emptyList())

    override suspend fun album(albumId: String): List<Track> = withContext(Dispatchers.IO) {
        val page = musicApi("browse", JSONObject().put("browseId", albumId), requireLogin = false)
        val header = nodes(page, "musicResponsiveHeaderRenderer").firstOrNull()
        val title = header?.optJSONObject("title")?.text().orEmpty()
        val artist = header?.optJSONObject("straplineTextOne")?.text().orEmpty()
        val year = header?.optJSONObject("subtitle")?.text()?.substringAfterLast(" • ")?.toIntOrNull() ?: 0
        // Not header.art(): that would also pick up the artist's avatar.
        val art = header?.optJSONObject("thumbnail")?.art()
        // Album rows carry no artwork or album name, and the page's "more from
        // this artist" carousels sit outside the shelf.
        nodes(page, "musicShelfRenderer").flatMap { nodes(it, "musicResponsiveListItemRenderer") }
            .mapNotNull { it.musicTrack() }.filter { it.title.isNotBlank() }.distinctBy { it.id }
            .mapIndexed { i, track ->
                track.copy(
                    artist = track.artist.ifBlank { artist }, album = title, albumId = albumId,
                    artUri = art, year = year, trackNumber = i + 1,
                )
            }
    }

    override suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val albums = async { searchAlbums(query) }
        // Songs filter: clean titles, real artists and durations. Without it
        // YouTube returns video rows where the artist column is just "Video".
        val result = musicApi("search", JSONObject().put("query", query).put("params", SONGS_FILTER), requireLogin = false)
        val tracks = nodes(result, "musicResponsiveListItemRenderer").mapNotNull { it.musicTrack() }
            .filter { it.title.isNotBlank() }.distinctBy { it.id }
        if (tracks.isNotEmpty()) return@withContext SearchResults(tracks = tracks, albums = albums.await())
        // Fallback: unfiltered, tolerating the noisy video rows.
        val any = musicApi("search", JSONObject().put("query", query), requireLogin = false)
        SearchResults(tracks = nodes(any, "musicResponsiveListItemRenderer")
            .mapNotNull { it.musicTrack() }.filter { it.title.isNotBlank() }.distinctBy { it.id },
            albums = albums.await())
    }

    override suspend fun stream(track: Track): StreamHandle? = withContext(Dispatchers.IO) {
        // Let the maintained extractor handle visitor data, client selection,
        // signatures and throttling parameters before trying legacy clients.
        streamLock.withLock {
            val now = System.currentTimeMillis()
            streams[track.id]?.takeIf { it.expiresAt > now }?.let { return@withLock it.handle }
            init()
            val handle = newPipeStream(track)
                ?: innertubeStream(track)
                ?: mwebStream(track)
                ?: throw SourceException("YouTube could not resolve audio. If YouTube requests verification, complete it in the YT Music sign-in browser and reconnect.")
            val urlExpiry = (handle as? StreamHandle.Url)?.url?.let {
                android.net.Uri.parse(it).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
            }
            // Reuse recent resolutions, but leave a margin before signed URLs expire.
            val expiresAt = minOf(now + 5 * 60_000, urlExpiry?.minus(60_000) ?: Long.MAX_VALUE)
            streams.entries.removeAll { it.value.expiresAt <= now }
            streams[track.id] = CachedStream(handle, expiresAt)
            handle
        }
    }

    @Volatile private var decipherFnName: String? = null
    @Volatile private var decipherBaseJsHash: String? = null

    /** Fetch the watch page for the player JS url (needs the consent cookie). */
    private fun playerJsUrl(videoId: String): String? = runCatching {
        val html = apiClient.newCall(okhttp3.Request.Builder()
            .url("https://www.youtube.com/watch?v=$videoId")
            .header("User-Agent", DESKTOP_UA)
            .header("Cookie", "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.en+FX+419")
            .get().build()).execute().use { it.body?.string().orEmpty() }
        Regex("/s/player/([a-zA-Z0-9_-]+)/base\\.js").find(html)
            ?.let { "https://www.youtube.com${it.value}" }
    }.getOrNull()

    /**
     * Decipher [scrambled] by running YouTube's player JS in Rhino. The whole
     * file is evaluated in a sandbox scope; whichever load errors occur after
     * the decipher definition still leave the function in scope.
     */
    private fun decipher(baseJsUrl: String, scrambled: String): String? {
        val js = runCatching {
            apiClient.newCall(okhttp3.Request.Builder().url(baseJsUrl)
                .header("User-Agent", DESKTOP_UA).get().build())
                .execute().use { it.body?.string().orEmpty() }
        }.getOrNull().orEmpty()
        if (js.isEmpty()) return null

        val match = Regex("(\\w+)=function\\((\\w+)\\)\\{\\2=\\2\\.split\\(\"\"\\);")
            .find(js) ?: run {
            Log.w(TAG, "no decipher function in player js")
            return null
        }
        val fnName = match.groupValues[1]
        val argName = match.groupValues[2]

        val rhino = org.mozilla.javascript.Context.enter()
        try {
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            runCatching { rhino.evaluateString(scope, js, "base.js", 1, null) }
            val fn = scope.get(fnName, scope) as? org.mozilla.javascript.Function
                ?: run { Log.w(TAG, "decipher fn $fnName not defined"); return null }
            val result = fn.call(rhino, scope, scope, arrayOf(scrambled))
            decipherFnName = fnName
            return result as? String
        } catch (e: Exception) {
            Log.w(TAG, "decipher failed: ${e.message}")
            return null
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    private suspend fun mwebStream(track: Track): StreamHandle? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("videoId", track.id)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put("params", "8AEB")
                .put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "MWEB").put("clientVersion", "2.20261007.01.00")
                    .put("hl", "en").put("gl", "US")))
            val req = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$WEB_KEY")
                .header("User-Agent", MWEB_UA)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            apiClient.newCall(req).execute().use { resp ->
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (resp.code != 200 || (status != null && status != "OK")) {
                    throw SourceException("MWEB player: ${status ?: "HTTP ${resp.code}"}")
                }
                val formats = json.optJSONObject("streamingData")
                    ?.optJSONArray("adaptiveFormats") ?: JSONArray()
                val audio = ArrayList<Pair<Int, JSONObject>>()
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    if (!f.optString("mimeType").startsWith("audio/")) continue
                    if (f.optString("signatureCipher").isBlank()) continue
                    audio.add(f.optInt("bitrate") to f)
                }
                val best = audio.maxByOrNull { it.first }?.second
                    ?: throw SourceException("No ciphered audio stream")
                val cipher = best.optString("signatureCipher")
                val parts = cipher.split('&').mapNotNull {
                    it.split('=', limit = 2).takeIf { p -> p.size == 2 }
                        ?.let { p -> java.net.URLDecoder.decode(p[0], "UTF-8") to
                            java.net.URLDecoder.decode(p[1], "UTF-8") }
                }.toMap()
                val scrambled = parts["s"] ?: throw SourceException("No s param")
                val url = parts["url"] ?: throw SourceException("No url param")
                val sigParam = parts["sp"] ?: "sig"
                val baseJs = playerJsUrl(track.id) ?: throw SourceException("No player js")
                if (decipherBaseJsHash != baseJs || decipherFnName == null) {
                    decipherBaseJsHash = baseJs
                    decipher(baseJs, scrambled)
                }
                val signature = decipherFnName?.let { decipher(baseJs, scrambled) }
                    ?: throw SourceException("Decipher failed")
                StreamHandle.Url("$url&$sigParam=$signature", mimeType = "audio/mp4")
            }
        }.getOrElse {
            Log.w(TAG, "mweb stream resolve failed for ${track.id}: ${it.message}")
            null
        }
    }

    private fun newPipeStream(track: Track): StreamHandle? = runCatching {
        android.util.Log.i(TAG, "newpipe resolve ${track.id} downloader=${NewPipe.getDownloader()::class.java.simpleName}")
        val url = if (track.id.startsWith("http")) track.id
        else "https://www.youtube.com/watch?v=${track.id}"
        // Playback only needs audio: skip StreamInfo's related videos, artwork,
        // subtitles and other metadata extraction.
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val best = extractor.audioStreams
            .filter { it.content.isNotEmpty() }
            .maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("no audio streams")
        StreamHandle.Url(best.content, mimeType = best.format?.mimeType ?: "audio/mp4")
    }.onFailure {
        Log.w(TAG, "newpipe stream resolve failed for ${track.id}", it)
    }.getOrNull()

    private suspend fun innertubeStream(track: Track): StreamHandle? = withContext(Dispatchers.IO) {
        runCatching {
            // YouTube now rejects bare native clients ("Precondition check
            // failed"). The VR client is what yt-dlp uses in 2025/2026 — it
            // still hands out direct audio URLs without a PO token. ANDROID
            // needs params=8AEB, IOS is the last resort.
            data class Attempt(
                val client: JSONObject, val userAgent: String,
                val clientNameHeader: String, val clientVersionHeader: String,
                val apiKey: String, val params: String? = null,
                val cookie: String? = null, val authorization: String? = null,
                val origin: String? = null,
            )
            var lastError: Exception? = null
            // Signed-in playback first: your YouTube session satisfies the
            // "confirm you're not a bot" gate that blocks anonymous clients.
            val origin = "https://music.youtube.com"
            val cookieAuth = sapisidHash(origin)?.let { cookies!! to it }
            val attempts = buildList {
                if (cookieAuth != null) add(Attempt(
                    JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20261007.01.00")
                        .put("hl", "en").put("gl", "US"),
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "67", "1.20261007.01.00", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                    cookie = cookieAuth.first, authorization = cookieAuth.second, origin = origin,
                ))
                add(Attempt(
                    JSONObject().put("clientName", "ANDROID_VR").put("clientVersion", "1.60.19")
                        .put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                        .put("osName", "Android").put("osVersion", "12L").put("androidSdkVersion", 32)
                        .put("hl", "en").put("gl", "US"),
                    "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
                    "28", "1.60.19", "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                ))
                add(Attempt(
                    JSONObject().put("clientName", "ANDROID").put("clientVersion", "19.09.37")
                        .put("androidSdkVersion", 30).put("osName", "Android").put("osVersion", "11")
                        .put("hl", "en").put("gl", "US"),
                    "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
                    "3", "19.09.37", "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w", params = "8AEB",
                ))
                add(Attempt(
                    JSONObject().put("clientName", "IOS").put("clientVersion", "19.09.3")
                        .put("deviceModel", "iPhone14,3").put("osName", "iPhone").put("osVersion", "15.6.0.21G90")
                        .put("hl", "en").put("gl", "US"),
                    "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)",
                    "5", "19.09.3", "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                ))
            }
            for (attempt in attempts) {
                val payload = JSONObject()
                    .put("videoId", track.id)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                    .put("context", JSONObject().put("client", attempt.client))
                attempt.params?.let { payload.put("params", it) }
                val req = okhttp3.Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=${attempt.apiKey}")
                    .header("User-Agent", attempt.userAgent)
                    .header("Content-Type", "application/json")
                    .header("X-YouTube-Client-Name", attempt.clientNameHeader)
                    .header("X-YouTube-Client-Version", attempt.clientVersionHeader)
                    .apply {
                        attempt.cookie?.let { header("Cookie", it) }
                        attempt.authorization?.let {
                            header("Authorization", it)
                            header("Origin", attempt.origin ?: "https://music.youtube.com")
                            header("X-Goog-AuthUser", "0")
                        }
                    }
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                try {
                    apiClient.newCall(req).execute().use { resp ->
                        val bodyText = resp.body?.string().orEmpty()
                        val json = runCatching { JSONObject(bodyText) }.getOrDefault(JSONObject())
                        val status = json.optJSONObject("playabilityStatus")?.optString("status")
                        if (resp.code != 200 || (status != null && status != "OK")) {
                            val reason = runCatching {
                                json.optJSONObject("error")?.optString("message")
                                    ?: json.optJSONObject("playabilityStatus")?.optString("reason")
                                    ?: json.optJSONObject("playabilityStatus")
                                        ?.optJSONArray("errorScreen")?.optJSONObject(0)
                                        ?.optJSONObject("playerErrorMessageRenderer")?.optJSONObject("reason")?.text()
                            }.getOrNull() ?: bodyText.take(160)
                            throw SourceException("YouTube refused playback (${status ?: "HTTP ${resp.code}"}): $reason")
                        }
                        val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: JSONArray()
                        var best: JSONObject? = null
                        for (i in 0 until formats.length()) {
                            val f = formats.optJSONObject(i) ?: continue
                            if (!f.optString("mimeType").startsWith("audio/")) continue
                            if (f.optString("url").isBlank()) continue
                            if (best == null || f.optLong("bitrate") > best!!.optLong("bitrate")) best = f
                        }
                        val url = best?.optString("url")?.takeIf { it.isNotBlank() }
                            ?: throw SourceException("No downloadable audio stream")
                        return@withContext StreamHandle.Url(url, mimeType = "audio/mp4")
                    }
                } catch (e: org.rab1d.petal.music.model.SourceException) {
                    lastError = e
                    Log.w(TAG, "player client ${attempt.client.optString("clientName")} failed: ${e.message}")
                }
            }
            throw lastError ?: SourceException("No playable stream client")
        }.getOrElse {
            Log.w(TAG, "stream resolve failed for ${track.id}: ${it.message}")
            null
        }
    }

    /** Continuation heuristic: same title/artist neighbourhood on YT Music. */
    override suspend fun automixNext(seed: Track, limit: Int): List<Track> {
        val q = buildString {
            append(seed.title)
            if (seed.artist.isNotBlank()) append(' ').append(seed.artist)
        }
        return search(q).tracks
            .filter { it.id != seed.id && it.durationMs > 45_000 }
            .take(limit)
    }
}
