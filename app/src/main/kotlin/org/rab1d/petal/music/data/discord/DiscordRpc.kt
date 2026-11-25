/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.discord

import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Discord Rich Presence over the user-token gateway — the kizzy/BitChord
 * approach. There is no official API for a third-party app to set a user's
 * presence, so this socket identifies as Discord Android (see
 * [SuperProperties]) and sends PRESENCE_UPDATE with a LISTENING activity:
 *
 *   Listening to Petal Music
 *   [art] Song title
 *         Artist
 *         ▁▁▁ 1:04 / 3:47   ← from the start/end timestamps
 *   [ Listen on Tidal ]
 */
object SuperProperties {
    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013

    private val props: JSONObject by lazy {
        JSONObject().apply {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", Build.DEVICE)
            put("system_locale", Locale.getDefault().toString())
            put("client_version", CLIENT_VERSION)
            put("release_channel", "googleRelease")
            put("device_vendor_id", UUID.randomUUID().toString())
            put("client_uuid", UUID.randomUUID().toString())
            put("client_launch_id", UUID.randomUUID().toString())
            put("os_version", Build.VERSION.RELEASE)
            put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", JSONObject.NULL)
            put("design_id", 0)
        }
    }

    val base64: String by lazy {
        Base64.encodeToString(props.toString().toByteArray(), Base64.NO_WRAP)
    }

    val userAgent: String = "Discord-Android/$CLIENT_BUILD_NUMBER;RNA"
}

class DiscordRpc(private val token: String) {

    companion object {
        const val TAG = "PetalMusic/Discord"
        private const val GATEWAY = "wss://gateway.discord.gg/?v=9&encoding=json"
        private const val API = "https://discord.com/api/v9"

        /** Presence activities need an application id (kizzy's public default). */
        const val APPLICATION_ID = "105075425444823041"
        private val JSON = "application/json".toMediaType()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    @Volatile private var lastSeq: Long? = null
    @Volatile private var ready = false
    @Volatile private var closed = false

    /** Activity waiting for READY so the first push after connect lands. */
    @Volatile private var pendingActivity: JSONObject? = null

    /** external asset cache: artwork url -> mp:external/... path */
    private val assetCache = HashMap<String, String>()

    val isReady: Boolean get() = ready

    fun connect() {
        if (ws != null || closed) return
        val request = Request.Builder()
            .url(GATEWAY)
            .header("User-Agent", SuperProperties.userAgent)
            .build()
        ws = http.newWebSocket(request, listener)
    }

    fun close() {
        closed = true
        heartbeatJob?.cancel()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        ready = false
        scope.cancel()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "gateway open")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val payload = JSONObject(text)
                when (payload.optInt("op")) {
                    10 -> { // HELLO
                        val interval = payload.getJSONObject("d").optLong("heartbeat_interval")
                        sendIdentify()
                        startHeartbeat(interval)
                    }
                    0 -> { // DISPATCH
                        lastSeq = if (payload.isNull("s")) null else payload.optLong("s")
                        if (payload.optString("t") == "READY") {
                            ready = true
                            Log.i(TAG, "gateway ready as @" + payload.optJSONObject("d")
                                ?.optJSONObject("user")?.optString("username"))
                            pendingActivity?.let { setActivity(it) }
                        }
                    }
                    11 -> { /* HEARTBEAT_ACK */ }
                    7, 9 -> { // RECONNECT / INVALID_SESSION
                        Log.w(TAG, "op=${payload.optInt("op")}, reconnecting")
                        reset()
                        connect()
                    }
                }
            }.onFailure { Log.w(TAG, "onMessage: ${it.message}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "gateway failure: ${t.message}")
            reset()
            if (!closed) {
                scope.launch {
                    delay(5000)
                    connect()
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "gateway closed")
            reset()
        }
    }

    private fun reset() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        ws = null
        ready = false
    }

    private fun sendIdentify() {
        val identify = JSONObject()
            .put("token", token)
            .put("capabilities", 16381)
            .put("compress", false)
            .put("large_threshold", 100)
            .put(
                "properties",
                JSONObject()
                    .put("os", "Android")
                    .put("browser", "Discord Android")
                    .put("device", Build.DEVICE),
            )
            .put("shard", JSONArray().put(0).put(1))
            .put(
                "client_state",
                JSONObject()
                    .put("guild_versions", JSONObject())
                    .put("highest_last_message_id", "0")
                    .put("read_state_version", 0)
                    .put("user_guild_settings_version", -1)
                    .put("user_settings_version", -1),
            )
            .put(
                "presence",
                JSONObject()
                    .put("status", "online")
                    .put("since", 0)
                    .put("activities", JSONArray())
                    .put("afk", false),
            )
        send(JSONObject().put("op", 2).put("d", identify))
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay((intervalMs - 3000).coerceAtLeast(5000))
                send(JSONObject().put("op", 1).put("d", lastSeq ?: JSONObject.NULL))
            }
        }
    }

    private fun send(json: JSONObject) {
        runCatching { ws?.send(json.toString()) }
    }

    /** Push the presence update; call with null to clear the activity. */
    fun setActivity(activity: JSONObject?) {
        val presence = JSONObject()
            .put("since", 0)
            .put(
                "activities",
                if (activity == null) JSONArray() else JSONArray().put(activity),
            )
            .put("status", "online")
            .put("afk", false)
        send(JSONObject().put("op", 3).put("d", presence))
    }

    /**
     * Presence for [track]. Timestamps are start/end instants (Discord draws
     * the countdown itself), adjusted for playback speed like BitChord does.
     */
    fun updateSong(track: Track, positionMs: Long, durationMs: Long, playing: Boolean) {
        scope.launch {
            val now = System.currentTimeMillis()
            val timestamps = JSONObject().put("start", now - positionMs.coerceAtLeast(0))
            if (playing && durationMs > positionMs) {
                timestamps.put("end", now + (durationMs - positionMs))
            }

            val largeImage = track.artUri?.takeIf { it.startsWith("http") }
                ?.let { fetchExternalAsset(it) }

            val listenUrl = listenUrl(track)
            val buttons = if (listenUrl != null) {
                JSONArray().put("Listen")
            } else null

            val activity = JSONObject()
                .put("name", "Petal Music")
                .put("type", 2) // LISTENING
                .put("details", track.title.ifEmpty { "Unknown" })
                .put("state", track.artist.ifEmpty { track.source.label })
                .put("timestamps", timestamps)
                .put(
                    "assets",
                    JSONObject()
                        .put("large_image", largeImage ?: "mp:app-icons/${APPLICATION_ID}")
                        .put("large_text", "Petal Music · petalOS"),
                )
                .put("application_id", APPLICATION_ID)
            listenUrl?.let { activity.put("details_url", it) }
            buttons?.let {
                activity.put("buttons", it)
                activity.put(
                    "metadata",
                    JSONObject().put("button_urls", JSONArray().put(listenUrl)),
                )
            }
            if (ready) {
                setActivity(activity)
            } else {
                pendingActivity = activity
                connect()
            }
            Log.i(TAG, "presence: ${track.title} (${if (playing) "playing" else "paused"})")
        }
    }

    /** Register external artwork with Discord so the presence can display it. */
    private fun fetchExternalAsset(imageUrl: String): String? {
        assetCache[imageUrl]?.let { return it }
        return runCatching {
            // Discord expects {"urls":[url]} — a bare array is a 400.
            val body = JSONObject().put("urls", JSONArray().put(imageUrl))
                .toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$API/applications/$APPLICATION_ID/external-assets")
                .header("Authorization", token)
                .header("User-Agent", SuperProperties.userAgent)
                .header("X-Super-Properties", SuperProperties.base64)
                .post(body)
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "external-assets ${resp.code}")
                    return null
                }
                val arr = JSONArray(resp.body?.string().orEmpty())
                val path = arr.optJSONObject(0)?.optString("external_asset_path")
                if (path != null) {
                    assetCache[imageUrl] = "mp:$path"
                    "mp:$path"
                } else null
            }
        }.getOrNull()
    }

    private fun listenUrl(track: Track): String? = when (track.source) {
        Source.TIDAL -> "https://tidal.com/browse/track/${track.id}"
        else -> null
    }

    /** Validate the token; returns the account username. */
    fun fetchUsername(): String? = runCatching {
        val request = Request.Builder()
            .url("$API/users/@me")
            .header("Authorization", token)
            .header("User-Agent", SuperProperties.userAgent)
            .header("X-Super-Properties", SuperProperties.base64)
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty()).optString("username")
        }
    }.getOrNull()
}
