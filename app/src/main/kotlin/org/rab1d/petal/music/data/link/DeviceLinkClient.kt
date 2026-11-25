/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Device Link client. Sends this phone's playlists (metadata only — never the
 * audio) to the desktop player, which stores the metadata and may optionally
 * download the tracks. Keeps the link fast even on flaky Wi-Fi.
 *
 *   GET  /v1/health     -> {"status","name","version"}
 *   POST /v1/playlists  -> {"accepted": n, "playlists": m}
 */
object DeviceLinkClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Never silently retry a connection: a dropped link must not burn battery
        // or light up the radio again until the user taps Link.
        .retryOnConnectionFailure(false)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    @Serializable
    data class Track(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val source: String = "",
        val durationMs: Long = 0,
        val key: String = "",
    )

    @Serializable
    data class Playlist(val name: String, val tracks: List<Track>)

    @Serializable
    data class Result(val accepted: Int = 0, val playlists: Int = 0)

    class DeviceLinkException(message: String) : Exception(message)

    /** Ask a desktop for its name; null when unreachable. */
    fun probe(host: String, port: Int): String? = runCatching {
        val request = Request.Builder().url("http://$host:$port/v1/health").get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string().orEmpty()
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        }
    }.getOrNull()

    fun sendPlaylists(host: String, port: Int, playlists: List<Playlist>): Result {
        val payload = json.encodeToString(playlists)
        val request = Request.Builder()
            .url("http://$host:$port/v1/playlists")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DeviceLinkException("HTTP ${resp.code}: ${body.take(200)}")
            return runCatching { json.decodeFromString<Result>(body) }
                .getOrDefault(Result(playlists = playlists.size))
        }
    }
}
