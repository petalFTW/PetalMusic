/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.tidal

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.rab1d.petal.music.model.SearchResults
import org.rab1d.petal.music.model.SourceException
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.TrackAnalysis
import org.rab1d.petal.music.sources.SourceProvider
import org.rab1d.petal.music.sources.StreamHandle
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Unofficial Tidal client via the OAuth2 **device pairing flow**: the app
 * shows a pairing code (userCode), the user approves it at link.tidal.com on
 * any device, and we poll for account-bound access/refresh tokens. No tokens
 * to paste, correct country, user-quality streams.
 *
 * The client credentials are the public Tidal Android TV identity used by the
 * open-source unofficial clients; overrideable via [overrideClientId] /
 * [overrideClientSecret] for resilience.
 */
class TidalSource(private val context: Context) :
    SourceProvider,
    org.rab1d.petal.music.data.downloads.DownloadableSource {
    override val source = Source.TIDAL
    override val supportsPlaylistEditing = true

    companion object {
        const val TAG = "PetalMusic/Tidal"
        const val STATE_FILE = "tidal_state.json"

        private const val AUTH = "https://auth.tidal.com/v1/oauth2"
        private const val API = "https://api.tidal.com/v1"

        /**
         * Public Fire TV client identity — the TV-app secret was revoked
         * (token polls return invalid_client while device_authorization
         * still passes), this pair survives the token endpoint.
         * Overridable via the Settings dialog / tidal_state.json.
         */
        const val DEFAULT_CLIENT_ID = "Pzd0ExNVHkyZLiYN"
        const val DEFAULT_CLIENT_SECRET = "W7X6UvBaho+XOi1MUeCX6ewv2zTdSOV3Y7qC3p3675I="
        private const val SCOPE = "r_usr w_usr r_sub device_login"

        /**
         * Phone app client (from assets/secrets.properties of TIDAL 2.209.0).
         * Full playback entitlements; login via webview (authorization_code +
         * PKCE). Redirect is the app's popupbridge callback — intercepted in
         * the WebViewClient, no OS registration needed.
         */
        const val PHONE_CLIENT_ID = "JEdLAXtZAvJYmDgY"
        const val PHONE_CLIENT_SECRET = "tLxFsNJhAeA12lINJrpaLxEfWBHLdx4o42n42iMjRcM="

        /**
         * The app's registered login redirect (AuthTab HTTPS_REDIRECT_HOST/
         * PATH + App-Link intent filter): https://tidal.com/android/login/auth
         */
        const val PHONE_REDIRECT_URI = "https://tidal.com/android/login/auth"

        /** Legacy public device token — fallback for logged-out search only. */
        private const val FALLBACK_TOKEN = "7m7Ap0JC9j1cOM3n"

        private val client = OkHttpClient.Builder().build()
    }

    var overrideClientId: String? = null
    var overrideClientSecret: String? = null

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiresAtMs: Long = 0
    private var userId: Long = 0
    private var userUuid: String? = null
    private var displayName: String? = null

    @Volatile private var countryCode: String = "US"

    val stateFile: File get() = File(context.filesDir, STATE_FILE)

    override fun isLoggedIn(): Boolean = accessToken != null

    private fun clientId() = overrideClientId ?: DEFAULT_CLIENT_ID
    private fun clientSecret() = overrideClientSecret ?: DEFAULT_CLIENT_SECRET

    /** Cover ids are uuids; the CDN shards them into path segments. */
    private fun coverUrl(cover: String?, dim: String = "640x640"): String? =
        cover?.takeIf { it.isNotBlank() }
            ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/$dim.jpg" }

    /**
     * Playlist artwork is creator-supplied, so the CDN only serves the
     * original (sized variants 403). Prefer a custom image when set.
     */
    private fun playlistCoverUrl(image: String?, customImageUrl: String? = null): String? =
        customImageUrl?.takeIf { it.startsWith("http") }
            ?: image?.takeIf { it.isNotBlank() }
                ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/origin.jpg" }

    // ---- WebView login (phone client, authorization_code + PKCE) ------------------------

    /** A web login session: load [url], intercept [redirectPrefix], then [verifier] exchanges. */
    data class WebLoginRequest(
        val url: String,
        val redirectPrefix: String,
        val verifier: String,
        val uniqueKey: String,
    )

    sealed interface RedirectParse {
        /** Not our callback — let the webview continue. */
        data object Ignore : RedirectParse
        data class GotCode(val code: String) : RedirectParse
        data class Failed(val message: String) : RedirectParse
    }

    /** Inspect an intercepted url: our callback carries ?code= or ?error=... */
    fun parseRedirect(url: String): RedirectParse {
        if (!url.startsWith(PHONE_REDIRECT_URI)) return RedirectParse.Ignore
        val uri = android.net.Uri.parse(url)
        val err = uri.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error")
        if (!err.isNullOrBlank()) return RedirectParse.Failed(err)
        val code = uri.getQueryParameter("code")
        return if (code.isNullOrBlank()) {
            RedirectParse.Failed("no code in callback")
        } else {
            RedirectParse.GotCode(code)
        }
    }

    fun startWebLogin(): WebLoginRequest {
        val b64Flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        val verifierBytes = ByteArray(48)
        java.security.SecureRandom().nextBytes(verifierBytes)
        val verifier = android.util.Base64.encodeToString(verifierBytes, b64Flags)
        val challenge = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray())
            .let { android.util.Base64.encodeToString(it, b64Flags) }
        val uniqueKey = java.util.UUID.randomUUID().toString()
        val url = "https://login.tidal.com/authorize" +
            "?response_type=code" +
            "&client_id=$PHONE_CLIENT_ID" +
            "&redirect_uri=${java.net.URLEncoder.encode(PHONE_REDIRECT_URI, "UTF-8")}" +
            "&scope=${java.net.URLEncoder.encode("r_usr w_usr r_sub", "UTF-8")}" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&client_unique_key=$uniqueKey"
        return WebLoginRequest(url, PHONE_REDIRECT_URI, verifier, uniqueKey)
    }

    suspend fun completeWebLogin(req: WebLoginRequest, code: String): Boolean =
        withContext(Dispatchers.IO) {
            // Exactly the SDK's getTokenWithCodeVerifier: PUBLIC client,
            // PKCE only — sending client_secret here fails with
            // "This token was issued to another client".
            val body = okhttp3.FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", PHONE_CLIENT_ID)
                .add("redirect_uri", req.redirectPrefix)
                .add("scope", "r_usr w_usr r_sub")
                .add("code_verifier", req.verifier)
                .add("client_unique_key", req.uniqueKey)
                .build()
            val request = okhttp3.Request.Builder().url("$AUTH/token").post(body).build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val o = JSONObject(resp.body?.string().orEmpty())
                    if (resp.code != 200 || !o.has("access_token")) {
                        Log.w(TAG, "web login exchange failed: $o")
                        return@withContext false
                    }
                    accessToken = o.getString("access_token")
                    refreshToken = o.optString("refresh_token").ifEmpty { null }
                    expiresAtMs = System.currentTimeMillis() + o.optLong("expires_in", 604800) * 1000
                    overrideClientId = PHONE_CLIENT_ID
                    overrideClientSecret = PHONE_CLIENT_SECRET
                    val user = o.optJSONObject("user")
                    userId = user?.optLong("userId") ?: 0
                    user?.optJSONArray("countryCodes")?.optString(0)?.takeIf { it.isNotBlank() }
                        ?.let { countryCode = it }
                    displayName = user?.optString("displayName")?.ifEmpty { null }
                    persist()
                    fetchProfileCountry()
                    true
                }
            }.getOrDefault(false)
        }

    // ---- OAuth2 device pairing ---------------------------------------------------------

    /** One pairing session: show [userCode] and poll [TidalSource.pollPairing]. */
    data class TidalPairing(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val verificationUrlComplete: String?,
        val intervalSec: Int,
        val expiresAtMs: Long,
    )

    sealed interface TidalPairResult {
        data object Pending : TidalPairResult
        data class Approved(val displayName: String?) : TidalPairResult
        data object Expired : TidalPairResult
        data class Error(val message: String) : TidalPairResult
    }

    suspend fun startPairing(): TidalPairing? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId())
            .add("client_secret", clientSecret())
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder()
            .url("$AUTH/device_authorization")
            .post(body)
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val o = JSONObject(resp.body?.string().orEmpty())
                val deviceCode = o.optString("deviceCode").ifEmpty { null }
                    ?: return@withContext null.also { Log.w(TAG, "device_authorization: $o") }
                TidalPairing(
                    deviceCode = deviceCode,
                    userCode = o.optString("userCode"),
                    verificationUrl = o.optString("verificationUri", "https://link.tidal.com"),
                    verificationUrlComplete = o.optString("verificationUriComplete").ifEmpty { null },
                    intervalSec = o.optInt("interval", 5).coerceAtLeast(2),
                    expiresAtMs = System.currentTimeMillis() + o.optLong("expiresIn", 300L) * 1000,
                )
            }
        }.getOrElse {
            Log.w(TAG, "startPairing failed: ${it.message}")
            null
        }
    }

    suspend fun pollPairing(p: TidalPairing): TidalPairResult = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() > p.expiresAtMs) return@withContext TidalPairResult.Expired
        val body = FormBody.Builder()
            .add("client_id", clientId())
            .add("client_secret", clientSecret())
            .add("device_code", p.deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder().url("$AUTH/token").post(body).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 200) {
                    val o = JSONObject(text)
                    accessToken = o.getString("access_token")
                    refreshToken = o.optString("refresh_token").ifEmpty { null }
                    expiresAtMs = System.currentTimeMillis() + o.optLong("expires_in", 604800) * 1000
                    val user = o.optJSONObject("user")
                    userId = user?.optLong("userId") ?: 0
                    user?.optJSONArray("countryCodes")?.optString(0)?.takeIf { it.isNotBlank() }
                        ?.let { countryCode = it }
                    displayName = user?.optString("displayName")?.ifEmpty { null }
                    persist()
                    fetchProfileCountry()
                    TidalPairResult.Approved(displayName)
                } else {
                    val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                    when (o.optString("error")) {
                        "authorization_pending" -> TidalPairResult.Pending
                        "slow_down" -> TidalPairResult.Pending
                        "expired_token" -> TidalPairResult.Expired
                        else -> TidalPairResult.Error(o.optString("error", "http ${resp.code}"))
                    }
                }
            }
        }.getOrElse {
            TidalPairResult.Error(it.message ?: "network error")
        }
    }

    private fun persist() {
        stateFile.writeText(
            JSONObject()
                .put("accessToken", accessToken)
                .put("refreshToken", refreshToken)
                .put("expiresAt", expiresAtMs)
                .put("userId", userId)
                .put("displayName", displayName ?: "")
                .put("country", countryCode)
                .put("clientId", clientId())
                .put("clientSecret", clientSecret())
                .toString(),
        )
    }

    fun restore(): Boolean = runCatching {
        if (!stateFile.exists()) return false
        val s = JSONObject(stateFile.readText())
        accessToken = s.optString("accessToken").ifEmpty { null }
        refreshToken = s.optString("refreshToken").ifEmpty { null }
        expiresAtMs = s.optLong("expiresAt")
        userId = s.optLong("userId")
        userUuid = s.optString("userUuid").ifEmpty { null }
        displayName = s.optString("displayName").ifEmpty { null }
        countryCode = s.optString("country", "US")
        if (s.has("clientId")) overrideClientId = s.optString("clientId")
        if (s.has("clientSecret")) overrideClientSecret = s.optString("clientSecret")
        true
    }.getOrDefault(false)

    fun logout() {
        stateFile.delete()
        accessToken = null
        refreshToken = null
        userId = 0
        displayName = null
    }

    private fun refreshTokens(): Boolean {
        val rt = refreshToken ?: return false
        val body = FormBody.Builder()
            .add("client_id", clientId())
            .add("client_secret", clientSecret())
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .build()
        val req = Request.Builder().url("$AUTH/token").post(body).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val o = JSONObject(resp.body?.string().orEmpty())
                accessToken = o.getString("access_token")
                o.optString("refresh_token").ifEmpty { null }?.let { refreshToken = it }
                expiresAtMs = System.currentTimeMillis() + o.optLong("expires_in", 604800) * 1000
                persist()
                true
            }
        }.getOrDefault(false)
    }

    private fun fetchProfileCountry() {
        if (userId <= 0) return
        val o = get("$API/users/$userId", emptyMap()) ?: return
        o.optString("country").takeIf { it.isNotBlank() }?.let { countryCode = it }
        o.optString("uuid").takeIf { it.isNotBlank() }?.let { userUuid = it }
        persist()
    }

    // ---- Library (favorites + playlists) -------------------------------------------

    private suspend fun ensureUser(): Boolean {
        if (userId <= 0) return false
        fetchProfileCountry()
        return userId > 0
    }

    /**
     * Follow [offset]/[totalNumberOfItems] until every page is in. Tidal caps
     * most list endpoints at 100 per request, so anything larger (playlists,
     * favourites) used to silently truncate at the first page.
     */
    private inline fun <T> paged(
        url: String,
        params: Map<String, String> = emptyMap(),
        limit: Int = 100,
        extract: (JSONObject) -> T?,
    ): List<T> {
        val out = ArrayList<T>()
        var offset = 0
        while (true) {
            val page = get(
                url,
                params + mapOf("limit" to "$limit", "offset" to "$offset"),
            ) ?: break
            val arr = page.optJSONArray("items") ?: break
            val n = arr.length()
            for (i in 0 until n) {
                arr.optJSONObject(i)?.let { o -> extract(o)?.let(out::add) }
            }
            val total = page.optInt("totalNumberOfItems", offset + n)
            offset += n
            if (n == 0 || offset >= total) break
        }
        return out
    }

    /** TIDAL playlists of the paired user. */
    override suspend fun playlists(): List<org.rab1d.petal.music.model.PlaylistSummary> =
        withContext(Dispatchers.IO) {
            if (!isLoggedIn() || !ensureUser()) return@withContext emptyList()
            paged("$API/users/$userId/playlists", limit = 100) { p ->
                org.rab1d.petal.music.model.PlaylistSummary(
                    id = p.optString("uuid"),
                    source = Source.TIDAL,
                    name = p.optString("title"),
                    trackCount = p.optInt("numberOfTracks"),
                    artUri = playlistCoverUrl(
                        p.optString("image"),
                        p.optString("customImageUrl"),
                    ),
                    editable = p.optJSONObject("creator")?.optLong("id") == userId ||
                        (p.optJSONObject("creator") == null && p.optString("type") == "USER"),
                )
            }
        }

    override suspend fun playlistTracks(id: String): List<Track> = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) return@withContext emptyList()
        paged("$API/playlists/$id/tracks") { it.toTrack() }
    }

    private fun playlistRequest(
        path: String, method: String = "GET", fields: Map<String, String> = emptyMap(),
        etag: String? = null,
    ): Pair<JSONObject, String?> {
        if (!isLoggedIn()) throw SourceException("Sign in to TIDAL first")
        val body = if (method == "POST") FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
        }.build() else null
        val req = Request.Builder().url("$API/$path${if ('?' in path) '&' else '?'}countryCode=$countryCode")
            .header("Authorization", "Bearer $accessToken")
            .header("Cache-Control", "no-cache")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .method(method, body).build()
        fun execute(request: Request): Pair<JSONObject, String?> = client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("userMessage") }.getOrNull()
                throw SourceException("TIDAL: ${message?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"}")
            }
            (if (text.isBlank()) JSONObject() else JSONObject(text)) to resp.header("ETag")
        }
        // Refresh before writes so retrying never accidentally duplicates a write.
        if (expiresAtMs <= System.currentTimeMillis() && refreshTokens()) {
            return execute(req.newBuilder().header("Authorization", "Bearer $accessToken").build())
        }
        return execute(req)
    }

    override suspend fun createPlaylist(name: String) = withContext(Dispatchers.IO) {
        if (!ensureUser()) throw SourceException("Couldn't load TIDAL user")
        val (p, _) = playlistRequest("users/$userId/playlists", "POST",
            mapOf("title" to name, "description" to ""))
        val id = p.optString("uuid").takeIf { it.isNotBlank() }
            ?: throw SourceException("TIDAL returned no playlist")
        org.rab1d.petal.music.model.PlaylistSummary(id, source, name, editable = true)
    }

    override suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        playlistRequest("playlists/$id", "POST", mapOf("title" to name))
        Unit
    }

    override suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        playlistRequest("playlists/$id", "DELETE")
        Unit
    }

    override suspend fun addPlaylistTracks(id: String, tracks: List<Track>) = withContext(Dispatchers.IO) {
        require(tracks.all { it.source == source })
        tracks.chunked(100).forEach { batch ->
            val (p, etag) = playlistRequest("playlists/$id")
            playlistRequest("playlists/$id/items", "POST", mapOf(
                "trackIds" to batch.joinToString(",") { it.id },
                "toIndex" to p.optInt("numberOfTracks").toString(),
                "onDupes" to "SKIP", "onArtifactNotFound" to "FAIL",
            ), etag)
        }
    }

    override suspend fun removePlaylistTrack(id: String, track: Track) = withContext(Dispatchers.IO) {
        // Item indices include videos. Never derive a removal index from the
        // filtered track-only list shown by the player.
        val indices = ArrayList<Int>()
        var offset = 0
        val (_, revision) = playlistRequest("playlists/$id")
        if (revision == null) throw SourceException("TIDAL returned no playlist revision")
        while (true) {
            val (page, _) = playlistRequest("playlists/$id/items?limit=100&offset=$offset")
            val items = page.optJSONArray("items") ?: throw SourceException("TIDAL returned no playlist items")
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i)
                val item = row?.optJSONObject("item") ?: row
                if (row?.optString("type") != "VIDEO" && item?.optString("id") == track.id) indices += offset + i
            }
            offset += items.length()
            if (items.length() == 0 || offset >= page.optInt("totalNumberOfItems", offset)) break
        }
        if (indices.isNotEmpty()) {
            val (_, currentRevision) = playlistRequest("playlists/$id")
            if (currentRevision != revision) throw SourceException("Playlist changed — please retry")
            playlistRequest("playlists/$id/items/${indices.joinToString(",")}", "DELETE", etag = revision)
        }
        Unit
    }

    /** Tracks favourited by the paired user. */
    override suspend fun favoriteTracks(): List<Track> = withContext(Dispatchers.IO) {
        if (!isLoggedIn() || !ensureUser()) return@withContext emptyList()
        paged("$API/users/$userId/favorites/tracks") { it.optJSONObject("item")?.toTrack() }
    }

    override suspend fun favoriteAlbums(): List<org.rab1d.petal.music.model.Album> =
        withContext(Dispatchers.IO) {
            if (!isLoggedIn() || !ensureUser()) return@withContext emptyList()
            paged("$API/users/$userId/favorites/albums") { entry ->
                val a = entry.optJSONObject("item")
                if (a == null) null
                else org.rab1d.petal.music.model.Album(
                    id = a.optString("id"),
                    source = Source.TIDAL,
                    title = a.optString("title"),
                    artist = a.optJSONObject("artist")?.optString("name").orEmpty(),
                    year = a.optInt("releaseDate").takeIf { it > 0 } ?: 0,
                    artUri = coverUrl(a.optString("cover")),
                )
            }
        }

    override suspend fun favoriteArtists(): List<org.rab1d.petal.music.model.Artist> =
        withContext(Dispatchers.IO) {
            if (!isLoggedIn() || !ensureUser()) return@withContext emptyList()
            paged("$API/users/$userId/favorites/artists") { entry ->
                val a = entry.optJSONObject("item")
                if (a == null) null
                else org.rab1d.petal.music.model.Artist(
                    id = a.optString("id"),
                    source = Source.TIDAL,
                    name = a.optString("name"),
                )
            }
        }

    // ---- API ---------------------------------------------------------------------------

    /** Last API error detail, for callers that need to react (e.g. 4005). */
    @Volatile var lastErrorSub: Int = 0
        private set
    @Volatile var lastErrorMessage: String = ""
        private set

    private fun get(url: String, params: Map<String, String>): JSONObject? {
        val q = url + (if (url.contains('?')) "&" else "?") +
            params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            } + "&countryCode=$countryCode"
        val req = Request.Builder().url(q).apply {
            if (accessToken != null) {
                header("Authorization", "Bearer $accessToken")
            } else {
                header("X-Tidal-Token", FALLBACK_TOKEN)
            }
            header("User-Agent", "PetalMusic/2.0")
        }.get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 && accessToken != null && refreshTokens()) {
                    return runCatching {
                        client.newCall(
                            req.newBuilder()
                                .header("Authorization", "Bearer $accessToken")
                                .build(),
                        ).execute().use { r2 ->
                            if (!r2.isSuccessful) return null
                            JSONObject(r2.body?.string().orEmpty())
                        }
                    }.getOrNull()
                }
                if (!resp.isSuccessful) {
                    val err = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                    lastErrorSub = err.optInt("subStatus")
                    lastErrorMessage = err.optString("userMessage")
                    return null
                }
                lastErrorSub = 0
                lastErrorMessage = ""
                JSONObject(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

        private fun JSONObject.toTrack(): Track? {
        val id = optString("id").takeIf { it.isNotEmpty() } ?: return null
        return Track(
            id = id,
            source = Source.TIDAL,
            title = optString("title"),
            artist = optJSONObject("artist")?.optString("name").orEmpty(),
            album = optJSONObject("album")?.optString("title").orEmpty(),
            albumId = optJSONObject("album")?.optString("id"),
            durationMs = optLong("duration") * 1000,
            trackNumber = optInt("trackNumber"),
            discNumber = optInt("volumeNumber", 1),
            artUri = coverUrl(optJSONObject("album")?.optString("cover")),
            isrc = optString("isrc").takeIf { it.isNotBlank() },
            explicit = optBoolean("explicit"),
            artistId = optJSONObject("artist")?.optString("id")?.takeIf { it.isNotBlank() },
        )
    }

    // ---- Artist, lyrics, credits ---------------------------------------------------

    override val hasArtistPages: Boolean get() = true

    override suspend fun artistTopTracks(artistId: String): List<Track> = withContext(Dispatchers.IO) {
        val items = get("$API/artists/$artistId/toptracks", mapOf("limit" to "50"))?.optJSONArray("items")
            ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.toTrack() }
    }

    override suspend fun lyrics(track: Track): org.rab1d.petal.music.model.Lyrics? = withContext(Dispatchers.IO) {
        if (track.source != Source.TIDAL) return@withContext null
        val body = get("$API/tracks/${track.id.removePrefix("tidal:")}/lyrics", emptyMap()) ?: return@withContext null
        val timed = body.optString("subtitles").takeIf { it.isNotBlank() }
        val plain = body.optString("lyrics").takeIf { it.isNotBlank() }
        val provider = body.optString("lyricsProvider").ifBlank { "TIDAL" }
        org.rab1d.petal.music.data.LyricsParser.parse(timed ?: plain ?: return@withContext null, provider)
    }

    override suspend fun credits(track: Track): List<org.rab1d.petal.music.model.Credit> = withContext(Dispatchers.IO) {
        if (track.source != Source.TIDAL) return@withContext emptyList()
        val items = get("$API/tracks/${track.id.removePrefix("tidal:")}/contributors", mapOf("limit" to "100"))
            ?.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .mapNotNull { c -> c.optString("role").takeIf { it.isNotBlank() }?.let { it to c.optString("name") } }
            .filter { it.second.isNotBlank() }
            .groupBy({ it.first }, { it.second })
            .map { (role, names) -> org.rab1d.petal.music.model.Credit(role, names.distinct()) }
    }

    // ---- Musical metadata (BPM / key) ----------------------------------------------

    private val factsCache =
        java.util.concurrent.ConcurrentHashMap<String, org.rab1d.petal.music.model.TrackAnalysis>()

    /** Camelot wheel numbers indexed by pitch class (C=0 … B=11). */
    private val majorCamelot = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val minorCamelot = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

    /**
     * TIDAL carries tempo and key in the track body. Read them straight from
     * the API so the DJ planner beatmatches on real numbers, not estimates.
     */
    override suspend fun analyze(track: Track): TrackAnalysis? = withContext(Dispatchers.IO) {
        factsCache[track.id]?.let { return@withContext it }
        if (!isLoggedIn()) return@withContext null
        val o = get("$API/tracks/${track.id}", emptyMap()) ?: return@withContext null
        val facts = parseFacts(o) ?: return@withContext null
        factsCache[track.id] = facts
        facts
    }

    private fun parseFacts(o: JSONObject): TrackAnalysis? {
        val bpm = o.optDouble("bpm", 0.0).takeIf { it > 0.0 }?.toFloat()
        val scale = o.optString("keyScale")
        val minor = scale.startsWith("MINOR", ignoreCase = true) ||
            scale == "m"
        val knownMode = when {
            minor -> true
            scale.startsWith("MAJOR", ignoreCase = true) || scale == "M" -> false
            else -> null
        }
        val pc = when (val keyRaw = o.opt("key")) {
            is Number -> keyRaw.toInt().mod(12)
            is String -> pitchClassOf(keyRaw)
            else -> null
        }
        val camelot = pc?.let { if (minor) minorCamelot[it] else majorCamelot[it] }
        if (bpm == null && camelot == null) return null
        return TrackAnalysis(
            bpm = bpm ?: 0f,
            // Metadata supplies tempo/key, not a measured beat origin.
            strength = 0f,
            camelot = camelot,
            authoritative = true,
            minor = knownMode,
        )
    }

    private fun pitchClassOf(name: String): Int? {
        val s = name.trim()
        if (s.isEmpty()) return null
        val base = when (s[0].uppercaseChar()) {
            'C' -> 0
            'D' -> 2
            'E' -> 4
            'F' -> 5
            'G' -> 7
            'A' -> 9
            'B' -> 11
            else -> return null
        }
        if (s.length > 1) {
            when (s[1]) {
                '#' -> return (base + 1) % 12
                'b', 'B' -> return (base + 11) % 12
            }
        }
        return base
    }

    override suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val o = get("$API/search", mapOf("query" to query, "limit" to "25", "types" to "ARTISTS,ALBUMS,TRACKS,PLAYLISTS"))
            ?: throw SourceException(lastErrorMessage.ifBlank { "TIDAL search failed" })
        fun items(kind: String): List<JSONObject> {
            val arr = o.optJSONObject(kind)?.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        SearchResults(
            tracks = items("tracks").mapNotNull { it.toTrack() },
            albums = items("albums").map { a -> org.rab1d.petal.music.model.Album(
                a.optString("id"), source, a.optString("title"),
                a.optJSONObject("artist")?.optString("name").orEmpty(),
                artUri = coverUrl(a.optString("cover")), trackCount = a.optInt("numberOfTracks")) },
            artists = items("artists").map { a -> org.rab1d.petal.music.model.Artist(
                a.optString("id"), source, a.optString("name")) },
            playlists = items("playlists").map { p -> org.rab1d.petal.music.model.PlaylistSummary(
                p.optString("uuid"), source, p.optString("title"), p.optInt("numberOfTracks"),
                playlistCoverUrl(p.optString("image"), p.optString("customImageUrl")),
                editable = p.optJSONObject("creator")?.optLong("id") == userId) },
        )
    }

    override suspend fun album(albumId: String): List<Track> = withContext(Dispatchers.IO) {
        // album metadata carries the cover (the tracks endpoint does not)
        val cover = get("$API/albums/$albumId", emptyMap())
            ?.optString("cover")
        val art = coverUrl(cover)
        paged("$API/albums/$albumId/tracks") { t ->
            // Same mapping as everywhere else, so album tracks keep their explicit
            // flag, artist id and ISRC (and downloads made from here keep them too).
            val base = t.toTrack() ?: Track(
                id = t.optString("id"),
                source = Source.TIDAL,
                title = t.optString("title"),
                artist = t.optJSONObject("artist")?.optString("name").orEmpty(),
                album = "",
            )
            base.copy(albumId = albumId, artUri = art ?: base.artUri)
        }.sortedBy { it.trackNumber }
    }

    // ---- Offline downloads -----------------------------------------------------------

    /**
     * Offline download: resolve the best manifest the account allows, then pull
     * the DASH init + media segments and concatenate them into one fragmented
     * MP4. FLAC rides along untouched, so the file plays with ExoPlayer exactly
     * like the streamed version — with no network afterwards.
     */
    override suspend fun download(
        track: Track,
        store: org.rab1d.petal.music.data.downloads.DownloadStore,
        onProgress: (fraction: Float, quality: String) -> Unit,
    ): org.rab1d.petal.music.data.downloads.DownloadedTrack = withContext(Dispatchers.IO) {
        val manifest = resolveBestManifest(track.id)
            ?: throw org.rab1d.petal.music.model.SourceException(
                "No downloadable manifest (${lastErrorSub} ${lastErrorMessage})",
            )
        if (manifest.mimeType != "application/dash+xml") {
            throw org.rab1d.petal.music.model.SourceException(
                "Unsupported download manifest ${manifest.mimeType}",
            )
        }
        val mpd = String(Base64.decode(manifest.base64, Base64.DEFAULT))
        val dash = parseDashAudio(mpd)
            ?: throw org.rab1d.petal.music.model.SourceException("Unparseable DASH manifest")

        val dir = store.dirFor(Source.TIDAL)
        val audioFile = File(dir, "${track.id}.m4a")
        val quality = qualityLabel(dash.codecs, dash.bandwidth, manifest.quality)

        val client = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var bytes = 0L
        val partial = File.createTempFile("${track.id}-", ".download", dir)
        try {
            BufferedOutputStream(FileOutputStream(partial)).use { out ->
                suspend fun fetch(url: String) {
                    val request = Request.Builder().url(url)
                        .header("User-Agent", "PetalMusic/2.0")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw java.io.IOException("segment ${resp.code}")
                        }
                        val body = resp.body ?: throw java.io.IOException("empty segment")
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                bytes += n
                            }
                        }
                    }
                }

                fetch(dash.initUrl)
                val count = dash.segmentCount.coerceAtLeast(1)
                for (i in 0 until count) {
                    val number = dash.startNumber + i
                    fetch(dash.mediaTemplate.replace("\$Number\$", number.toString()))
                    onProgress((i + 1).toFloat() / count, quality)
                }
            }
            onProgress(1f, quality)

            // Publish the complete, seekable file in one rename so playback
            // cannot mistake an in-progress repair download for offline audio.
            org.rab1d.petal.music.data.downloads.Mp4Index.ensureSeekable(partial, track.durationMs)
            if (!partial.renameTo(audioFile)) throw java.io.IOException("Couldn't save downloaded audio")
        } finally {
            partial.delete()
        }

        // artwork for offline display
        val artFile = track.artUri?.takeIf { it.startsWith("http") }?.let { url ->
            runCatching {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "PetalMusic/2.0")
                    .build()
                val target = File(dir, "${track.id}.jpg")
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target
                }
            }.getOrNull()
        }

        org.rab1d.petal.music.data.downloads.DownloadedTrack(
            id = track.id,
            source = Source.TIDAL.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumId = track.albumId,
            durationMs = track.durationMs,
            trackNumber = track.trackNumber,
            file = audioFile.absolutePath,
            artFile = artFile?.absolutePath,
            quality = quality,
            bytes = bytes,
            downloadedAt = System.currentTimeMillis(),
            explicit = track.explicit,
            artistId = track.artistId,
            composer = track.composer,
        )
    }

    private data class TidalManifest(
        val mimeType: String,
        val base64: String,
        val quality: String,
    )

    /** Try the account's best quality first, then fall back down the ladder. */
    private fun resolveBestManifest(trackId: String): TidalManifest? {
        for (quality in listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH")) {
            val o = get(
                "$API/tracks/$trackId/playbackinfo",
                mapOf(
                    "audioquality" to quality,
                    "playbackmode" to "STREAM",
                    "assetpresentation" to "FULL",
                ),
            )
            val manifest = o?.optString("manifest").orEmpty()
            if (manifest.isNotEmpty()) {
                return TidalManifest(o!!.optString("manifestMimeType"), manifest, quality)
            }
            // legacy endpoint for TV-style clients
            val legacy = get(
                "$API/tracks/$trackId/playbackinfopostpaywall",
                mapOf(
                    "audioquality" to quality,
                    "playbackmode" to "STREAM",
                    "assetpresentation" to "FULL",
                ),
            )
            val legacyManifest = legacy?.optString("manifest").orEmpty()
            if (legacyManifest.isNotEmpty()) {
                return TidalManifest(
                    legacy!!.optString("manifestMimeType"), legacyManifest, quality,
                )
            }
        }
        return null
    }

    private data class DashAudio(
        val initUrl: String,
        val mediaTemplate: String,
        val startNumber: Int,
        val segmentCount: Int,
        val codecs: String,
        val bandwidth: Int,
    )

    /** Minimal DASH audio parser: one audio Representation + SegmentTemplate. */
    private fun parseDashAudio(mpd: String): DashAudio? {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(mpd))
        var initUrl: String? = null
        var media: String? = null
        var startNumber = 1
        var timescale = 0
        var templateDuration = 0L
        var segmentCount = 0
        var codecs = ""
        var bandwidth = 0
        var mediaPresentationMs = 0L

        fun parseDuration(text: String?): Long {
            // ISO-8601: PT#H#M#S
            val t = text ?: return 0
            val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?([\\d.]+)?S?").find(t) ?: return 0
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val min = m.groupValues[2].toLongOrNull() ?: 0
            val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
            return ((h * 3600 + min * 60 + s) * 1000).toLong()
        }

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "MPD" -> mediaPresentationMs = parseDuration(
                        parser.getAttributeValue(null, "mediaPresentationDuration"),
                    )
                    "Representation" -> {
                        parser.getAttributeValue(null, "codecs")?.let { codecs = it }
                        parser.getAttributeValue(null, "bandwidth")?.toIntOrNull()
                            ?.let { bandwidth = it }
                    }
                    "SegmentTemplate" -> {
                        parser.getAttributeValue(null, "initialization")?.let { initUrl = it }
                        parser.getAttributeValue(null, "media")?.let { media = it }
                        parser.getAttributeValue(null, "startNumber")?.toIntOrNull()
                            ?.let { startNumber = it }
                        parser.getAttributeValue(null, "timescale")?.toIntOrNull()
                            ?.let { timescale = it }
                        parser.getAttributeValue(null, "duration")?.toLongOrNull()
                            ?.let { templateDuration = it }
                    }
                    "S" -> {
                        val repeat = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                        segmentCount += repeat + 1
                    }
                }
            }
            event = parser.next()
        }

        if (initUrl == null || media == null) return null
        if (segmentCount == 0) {
            // no SegmentTimeline: derive the count from the presentation length
            if (templateDuration > 0 && timescale > 0 && mediaPresentationMs > 0) {
                val segMs = templateDuration * 1000.0 / timescale
                segmentCount = kotlin.math.ceil(mediaPresentationMs / segMs).toInt()
            } else {
                segmentCount = 1
            }
        }
        return DashAudio(
            initUrl = initUrl,
            mediaTemplate = media,
            startNumber = startNumber,
            segmentCount = segmentCount,
            codecs = codecs,
            bandwidth = bandwidth,
        )
    }

    private fun qualityLabel(codecs: String, bandwidth: Int, requested: String): String {
        val codec = when {
            codecs.startsWith("flac") -> "FLAC"
            codecs.startsWith("mp4a") -> "AAC"
            codecs.isNotEmpty() -> codecs.uppercase()
            else -> "Audio"
        }
        val rate = if (bandwidth > 0) " · ${bandwidth / 1000} kbps" else ""
        val tier = when (requested) {
            "HI_RES_LOSSLESS" -> " · Hi-Res"
            "LOSSLESS" -> " · Lossless"
            else -> ""
        }
        return "$codec$rate$tier"
    }

    override suspend fun stream(track: Track): StreamHandle? = withContext(Dispatchers.IO) {
        // The streaming SDK uses /playbackinfo (phone client is allowlisted);
        // postpaywall is the legacy path kept for TV-style clients.
        val o = get(
            "$API/tracks/${track.id}/playbackinfo",
            mapOf(
                "audioquality" to "LOSSLESS",
                "playbackmode" to "STREAM",
                "assetpresentation" to "FULL",
            ),
        ) ?: run {
            if (lastErrorSub == 4022) {
                get(
                    "$API/tracks/${track.id}/playbackinfopostpaywall",
                    mapOf(
                        "audioquality" to "LOSSLESS",
                        "playbackmode" to "STREAM",
                        "assetpresentation" to "FULL",
                    ),
                )
            } else null
        } ?: return@withContext null
        val manifest = o.optString("manifest")
        val mimeType = o.optString("manifestMimeType")
        if (manifest.isEmpty()) {
            if (lastErrorSub == 4005) {
                throw SourceException(
                    "Tidal blocked playback for this client (4005) — " +
                        "pair again with Custom client credentials from your own TIDAL app",
                )
            }
            Log.w(TAG, "no manifest: $lastErrorSub $lastErrorMessage")
            return@withContext null
        }
        when (mimeType) {
            // Current phone-client path: base64-encoded DASH MPD (FLAC/AAC).
            "application/dash+xml" -> {
                var mpd = String(Base64.decode(manifest, Base64.DEFAULT))
                val head = mpd.takeWhile { it != '>' } + ">"
                Log.i(TAG, "dash mpd: $head")
                // TIDAL ships VOD tracks as dynamic manifests; ExoPlayer then
                // treats them as live and disables seeking entirely. Flip them
                // to static so the timeline is seekable.
                if (mpd.contains("type=\"dynamic\"")) {
                    mpd = mpd.replace("type=\"dynamic\"", "type=\"static\"")
                    Log.i(TAG, "dash mpd: dynamic -> static for seek")
                }
                StreamHandle.Mpd(mpd)
            }
            // Legacy progressive manifests (TV clients / postpaywall).
            "application/vnd.tidal.bts" -> {
                val json = JSONObject(String(Base64.decode(manifest, Base64.DEFAULT)))
                val url = json.optJSONArray("urls")?.optString(0)
                val codecs = json.optString("codecs")
                url?.takeIf { it.isNotEmpty() }?.let {
                    StreamHandle.Url(it, mimeType = if (codecs.startsWith("flac")) "audio/flac" else "audio/mp4")
                }
            }
            else -> {
                Log.w(TAG, "unsupported manifest $mimeType")
                null
            }
        }
    }

    override suspend fun automixNext(seed: Track, limit: Int): List<Track> {
        val artistId = runCatching {
            get("$API/tracks/${seed.id}", emptyMap())
                ?.optJSONObject("artist")?.optString("id")
        }.getOrNull()
        if (artistId != null) {
            val top = get("$API/artists/$artistId/toptracks", mapOf("limit" to "10"))
            val arr = top?.optJSONArray("items")
            val tracks = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val t = arr?.optJSONObject(i) ?: return@mapNotNull null
                Track(
                    id = t.optString("id"),
                    source = Source.TIDAL,
                    title = t.optString("title"),
                    artist = t.optJSONObject("artist")?.optString("name").orEmpty(),
                    album = t.optJSONObject("album")?.optString("title").orEmpty(),
                    albumId = t.optJSONObject("album")?.optString("id"),
                    durationMs = t.optLong("duration") * 1000,
                )
            }
            val filtered = tracks.filter { it.id != seed.id }
            if (filtered.isNotEmpty()) return filtered.take(limit)
        }
        return search("${seed.title} ${seed.artist}").tracks.filter { it.id != seed.id }.take(limit)
    }
}
