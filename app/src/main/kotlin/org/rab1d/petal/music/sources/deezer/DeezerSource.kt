/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.deezer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.sources.SourceProvider
import org.rab1d.petal.music.sources.StreamHandle
import java.io.File
import java.security.MessageDigest

/**
 * Unofficial Deezer client: ARL-cookie session over the gw-light gateway,
 * streams via media.deezer.com get_url + Blowfish/CBC chunk decryption
 * (served through StreamHandle.Blowfish).
 */
class DeezerSource(private val context: Context) : SourceProvider {
    override val source = Source.DEEZER

    companion object {
        const val TAG = "PetalMusic/Deezer"
        const val STATE_FILE = "deezer_state.json"
        private const val GW = "https://www.deezer.com/ajax/gw-light.php"
        private const val MEDIA = "https://media.deezer.com/v2/get_url"
        private const val IV = "0123456789ABCDEF"
        private val client = OkHttpClient.Builder().build()
        private val JSON = "application/json".toMediaType()
    }

    private var apiToken: String? = null
    private var licenseToken: String? = null
    private var arl: String? = null
    private var userId: Long = 0

    val stateFile: File get() = File(context.filesDir, STATE_FILE)

    override fun isLoggedIn(): Boolean = apiToken != null && licenseToken != null

    private fun call(method: String, body: JSONObject, token: String? = null): JSONObject {
        val t = token ?: apiToken ?: "null"
        val req = Request.Builder()
            .url("$GW?method=$method&input=3&api_version=1.0&api_token=$t")
            .header("Cookie", "arl=$arl")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return JSONObject(text).optJSONObject("results") ?: JSONObject()
        }
    }

    /** Login with the user's arl cookie (from a logged-in browser session). */
    suspend fun login(arlCookie: String): Boolean = withContext(Dispatchers.IO) {
        arl = arlCookie.trim()
        val results = call("deezer.getUserData", JSONObject())
        val user = results.optJSONObject("USER")
        val license = user?.optJSONObject("OPTIONS")?.optString("license_token")
        val form = results.optString("checkForm")
        userId = user?.optLong("USER_ID", 0) ?: 0
        if (form.isNullOrEmpty() || license.isNullOrEmpty()) {
            Log.w(TAG, "ARL login rejected")
            return@withContext false
        }
        apiToken = form
        licenseToken = license
        stateFile.writeText(
            JSONObject()
                .put("arl", arl)
                .put("apiToken", form)
                .put("license", license)
                .put("userId", userId)
                .toString(),
        )
        true
    }

    fun restore(): Boolean = runCatching {
        if (!stateFile.exists()) return false
        val s = JSONObject(stateFile.readText())
        arl = s.getString("arl")
        apiToken = s.getString("apiToken")
        licenseToken = s.getString("license")
        userId = s.optLong("userId")
        true
    }.getOrDefault(false)

    fun logout() {
        stateFile.delete()
        apiToken = null
        licenseToken = null
        arl = null
    }

    private fun refresh(): Boolean {
        val savedArl = arl ?: return false
        return kotlinx.coroutines.runBlocking { login(savedArl) }
    }

    override suspend fun search(query: String): org.rab1d.petal.music.model.SearchResults {
        if (!isLoggedIn()) return org.rab1d.petal.music.model.SearchResults()
        val results = call("search.music", JSONObject().put("q", query).put("nb", 25))
        val tracks = results.optJSONArray("data").orEmpty<JSONObject>().mapNotNull { el ->
            val o = el as JSONObject
            Track(
                id = o.optString("SNG_ID").ifEmpty { o.optString("id") },
                source = Source.DEEZER,
                title = o.optString("SNG_TITLE").ifEmpty { o.optString("title") },
                artist = o.optJSONObject("artist")?.optString("ART_NAME")
                    ?: o.optJSONObject("artist")?.optString("name") ?: "",
                album = o.optJSONObject("album")?.optString("ALB_TITLE")
                    ?: o.optJSONObject("album")?.optString("title") ?: "",
                albumId = o.optJSONObject("album")?.optString("ALB_ID"),
                durationMs = o.optLong("DURATION", o.optLong("duration", 0)) * 1000,
                trackNumber = o.optInt("TRACK_NUMBER", 0),
                artUri = o.optJSONObject("album")?.optString("cover_medium")
                    ?: o.optJSONObject("album")?.optString("cover_xl"),
            )
        }
        val albums = results.optJSONArray("data").orEmpty<JSONObject>().mapNotNull { el ->
            val al = (el as JSONObject).optJSONObject("album") ?: return@mapNotNull null
            Album(
                id = al.optString("ALB_ID"),
                source = Source.DEEZER,
                title = al.optString("ALB_TITLE").ifEmpty { al.optString("title") },
                artist = (el.optJSONObject("artist")?.optString("ART_NAME")).orEmpty(),
                artUri = al.optString("cover_medium"),
            )
        }.distinctBy { it.id }
        return org.rab1d.petal.music.model.SearchResults(tracks = tracks, albums = albums)
    }

    override suspend fun album(albumId: String): List<Track> {
        if (!isLoggedIn()) return emptyList()
        val results = call("album.getData", JSONObject().put("ALB_ID", albumId))
        val out = ArrayList<Track>()
        val title = results.optString("ALB_TITLE")
        val artist = results.optJSONObject("ARTIST")?.optString("ART_NAME").orEmpty()
        results.optJSONArray("TRACKS").orEmpty<JSONObject>().forEach { el ->
            val t = el as JSONObject
            out.add(
                Track(
                    id = t.optString("SNG_ID"),
                    source = Source.DEEZER,
                    title = t.optString("SNG_TITLE"),
                    artist = t.optJSONObject("ARTISTS")?.optJSONArray("data")
                        ?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("ART_NAME") } }
                        ?.joinToString(", ") ?: artist,
                    album = title,
                    albumId = albumId,
                    durationMs = t.optLong("DURATION") * 1000,
                    trackNumber = t.optInt("TRACK_NUMBER"),
                    discNumber = t.optInt("DISK_NUMBER", 1),
                    artUri = results.optString("ALB_PICTURE").takeIf { it.isNotEmpty() }
                        ?.let { "https://e-cdns-images.dzcdn.net/images/cover/$it/500x500-000000-80-0-0.jpg" },
                ),
            )
        }
        return out.sortedBy { it.trackNumber }
    }

    override suspend fun stream(track: Track): StreamHandle? = withContext(Dispatchers.IO) {
        if (!isLoggedIn() && !refresh()) return@withContext null
        val data = call("song.getData", JSONObject().put("SNG_ID", track.id.toLongOrNull() ?: track.id))
        val md5 = data.optString("MD5_ORIGIN")
        val trackToken = data.optString("TRACK_TOKEN")
        if (md5.isEmpty() || trackToken.isEmpty()) return@withContext null

        val mediaReq = JSONObject()
            .put(
                "license_token", licenseToken,
            )
            .put(
                "media",
                JSONArray().put(
                    JSONObject()
                        .put("type", "FULL")
                        .put(
                            "formats",
                            JSONArray()
                                .put(JSONObject().put("cipher", "BF_AES_CBC").put("format", "MP3_128"))
                                .put(JSONObject().put("cipher", "BF_AES_CBC").put("format", "MP3_64")),
                        ),
                ),
            )
            .put("track_tokens", JSONArray().put(trackToken))
        val req = Request.Builder().url(MEDIA)
            .header("Content-Type", "application/json")
            .post(mediaReq.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val o = JSONObject(resp.body?.string().orEmpty())
            val url = o.optJSONArray("data")?.optJSONObject(0)
                ?.optJSONArray("media")?.optJSONObject(0)
                ?.optJSONArray("sources")?.optJSONObject(0)
                ?.optString("url")
            if (url.isNullOrEmpty()) {
                Log.w(TAG, "get_url failed: $o")
                return@withContext null
            }
            StreamHandle.Blowfish(
                url = url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"),
                key = md5HexBytes(track.id),
                iv = IV.toByteArray(),
            )
        }
    }

    override suspend fun automixNext(seed: Track, limit: Int): List<Track> {
        if (!isLoggedIn()) return emptyList()
        // Deezer radio via album/artist neighbourhood search.
        val q = buildString {
            append(seed.title)
            if (seed.artist.isNotBlank()) append(' ').append(seed.artist)
        }
        return (search(q).tracks).filter { it.id != seed.id }.take(limit)
    }
}

private fun md5HexBytes(s: String): ByteArray =
    MessageDigest.getInstance("MD5").digest(s.toByteArray())

private fun <T> JSONArray?.orEmpty(): List<T> =
    if (this == null) emptyList() else (0 until length()).map { get(it) as T }

