/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.spotify

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.spotify.Authentication
import com.spotify.connectstate.Connect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import xyz.gianlu.librespot.audio.MetadataWrapper
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.PlayableId
import xyz.gianlu.librespot.metadata.TrackId
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Unofficial Spotify client on top of librespot (librespot-java): session,
 * credential persistence, and the PCM pump. Audio flows through [PetalSink]
 * into the app's shared audio session, so BridgeFX mixes it with everything
 * else and the AutomixEngine can arm perfect transitions.
 */
class LibrespotBridge(private val context: Context) {

    companion object {
        const val TAG = "PetalMusic/Spotify"
        const val CREDS_FILE = "spotify_session.json"
        private const val DEVICE_ID_FILE = "spotify_device.id"
        const val VOLUME_MAX = 65535

        /**
         * Public librespot OAuth identity (the desktop client id) with its
         * registered loopback redirect. We run the PKCE flow ourselves in the
         * real browser and answer the redirect on a local socket, see
         * [awaitWebLogin].
         */
        private const val OAUTH_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
        private const val OAUTH_PORT = 5588
        private const val OAUTH_REDIRECT = "http://127.0.0.1:$OAUTH_PORT/login"
        private const val OAUTH_RETURN = "petalmusic://spotify-login"
        private const val OAUTH_AUTHORIZE = "https://accounts.spotify.com/authorize"
        private const val OAUTH_TOKEN = "https://accounts.spotify.com/api/token"
        private const val OAUTH_SCOPES =
            "app-remote-control playlist-modify-private playlist-modify-public " +
                "playlist-read-collaborative playlist-read-private streaming " +
                "user-follow-modify user-follow-read user-library-modify user-library-read " +
                "user-modify-playback-state user-read-currently-playing user-read-email " +
                "user-read-playback-state user-read-private user-read-recently-played user-top-read"

        private val http = OkHttpClient()
    }

    private var session: Session? = null
    private var player: Player? = null
    private var pumpListener: PumpListener? = null

    /**
     * librespot defaults to persisting credentials.json and its audio cache
     * relative to the process working directory — on Android that is "/" and
     * writes fail with EROFS. We persist credentials ourselves, so disable
     * both stores.
     */
    private fun sessionConf(): Session.Configuration =
        Session.Configuration.Builder()
            .setStoreCredentials(false)
            .setCacheEnabled(false)
            .setConnectionTimeout(10)
            .build()

    /**
     * Spotify binds reusable credentials (and audio-key grants) to the device
     * id that requested them. A fresh random id on every start makes every
     * restored session fail audio-key requests, so persist one id and reuse it.
     */
    private fun deviceId(): String {
        val f = context.getFileStreamPath(DEVICE_ID_FILE)
        if (f.exists()) {
            runCatching {
                val id = f.readText().trim()
                if (id.length == 40 && id.all { c -> c in '0'..'9' || c in 'a'..'f' }) return id
            }
        }
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        val id = bytes.joinToString("") { b -> "%02x".format(b) }
        runCatching { f.writeText(id) }
        return id
    }

    private suspend fun createSession(creds: Authentication.LoginCredentials): Session {
        repeat(3) { attempt ->
            try {
                return Session.Builder(sessionConf()).setDeviceName("Petal Music")
                    .setDeviceId(deviceId())
                    .setDeviceType(Connect.DeviceType.COMPUTER).credentials(creds).create()
            } catch (e: java.io.IOException) {
                if (attempt == 2) throw e
                Log.w(TAG, "Spotify access point unavailable; trying another", e)
                kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        error("No Spotify access point")
    }

    interface PumpListener {
        fun onTrackChanged(track: Track?)
        fun onPlaybackEnded()
        fun onPlayingChanged(playing: Boolean, positionMs: Long)
        fun onSeeked(positionMs: Long)
        fun onFailed(e: Exception)
    }

    fun setListener(l: PumpListener?) {
        pumpListener = l
    }

    val loggedIn: Boolean get() = session?.isValid == true

    /** True when the signed-in account has no streaming entitlement (Spotify Free). */
    val freeTier: Boolean
        get() = session?.isValid == true &&
            runCatching { session?.getUserAttribute("catalogue", "premium") }.getOrNull() == "free"

    // A closed session still knows its username, don't call that signed in.
    val username: String? get() = session?.takeIf { it.isValid }?.username()

    private fun credsFile() = context.getFileStreamPath(CREDS_FILE)

    /** Username/password login (no 2FA support — that's the protocol's limit). */
    suspend fun login(user: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val s = Session.Builder(sessionConf())
                .setDeviceName("Petal Music")
                .setDeviceType(Connect.DeviceType.COMPUTER)
                .userPass(user, pass)
                .create()
            onSessionReady(s)
            persist(s)
            true
        }.getOrElse {
            Log.w(TAG, "login failed: ${it.message}", it)
            false
        }
    }

    // ---- Web (PKCE) login, Tidal-style ---------------------------------------------

    /** A web login session: load [url], intercept [redirectPrefix], exchange [verifier]. */
    data class WebLoginRequest(
        val url: String,
        val redirectPrefix: String,
        val verifier: String,
        val state: String,
    )

    sealed interface RedirectParse {
        data object Ignore : RedirectParse
        data class GotCode(val code: String) : RedirectParse
        data class Failed(val message: String) : RedirectParse
    }

    private fun b64Url(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP,
        )

    /** Build the Spotify authorize url with a fresh PKCE challenge. */
    fun startWebLogin(): WebLoginRequest {
        val verifierBytes = ByteArray(64)
        SecureRandom().nextBytes(verifierBytes)
        val verifier = b64Url(verifierBytes)
        val challenge = b64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val state = b64Url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val url = "$OAUTH_AUTHORIZE" +
            "?response_type=code" +
            "&state=$state" +
            "&client_id=$OAUTH_CLIENT_ID" +
            "&redirect_uri=${URLEncoder.encode(OAUTH_REDIRECT, "UTF-8")}" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&scope=${URLEncoder.encode(OAUTH_SCOPES, "UTF-8")}"
        return WebLoginRequest(url, OAUTH_REDIRECT, verifier, state)
    }

    /** Inspect an intercepted url: our loopback callback carries ?code= or ?error=. */
    fun parseRedirect(url: String): RedirectParse {
        if (!url.startsWith(OAUTH_REDIRECT)) return RedirectParse.Ignore
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

    /**
     * Listens on the loopback redirect until the browser lands on it. This has
     * to be the real browser: Google refuses sign-in inside a WebView, whatever
     * the user agent says. Nothing else on the phone can finish the login, the
     * code is useless without our PKCE verifier and [WebLoginRequest.state]
     * drops anything that isn't our own redirect.
     */
    suspend fun awaitWebLogin(req: WebLoginRequest): RedirectParse = withContext(Dispatchers.IO) {
        val server = try {
            ServerSocket(OAUTH_PORT, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "web login: can't listen on $OAUTH_PORT", e)
            return@withContext RedirectParse.Failed("couldn't open the sign-in callback")
        }
        server.use {
            // accept() ignores cancellation, so wake up to check for it.
            it.soTimeout = 500
            var result: RedirectParse? = null
            while (result == null) {
                ensureActive()
                val socket = try { it.accept() } catch (_: SocketTimeoutException) { continue }
                result = socket.use { s -> answerRedirect(s, req) }
            }
            result!!
        }
    }

    private fun answerRedirect(socket: Socket, req: WebLoginRequest): RedirectParse? {
        val path = runCatching {
            socket.soTimeout = 2000
            socket.getInputStream().bufferedReader().readLine()?.split(' ')?.getOrNull(1)
        }.getOrNull() ?: return null
        val url = "http://127.0.0.1:$OAUTH_PORT$path"
        val parsed = parseRedirect(url)
        val ours = parsed != RedirectParse.Ignore &&
            android.net.Uri.parse(url).getQueryParameter("state") == req.state
        val body = if (!ours) "" else "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"background:#121214;color:#fff;font:18px sans-serif;text-align:center;padding-top:30vh\">" +
            "<p>You can go back to Petal Music.</p><p><a style=\"color:#ff647c\" href=\"$OAUTH_RETURN\">Open Petal Music</a></p>" +
            "<script>location.href=\"$OAUTH_RETURN\"</script>"
        val bytes = body.toByteArray()
        runCatching {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 ${if (ours) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        }
        return if (ours) parsed else null
    }

    /** Why the last web login failed, for the sign-in dialog. */
    @Volatile var lastLoginError: String = ""
        private set

    /** Exchange the captured code for a token and start the librespot session. */
    suspend fun completeWebLogin(req: WebLoginRequest, code: String): Boolean =
        withContext(Dispatchers.IO) {
            var stage = "token exchange"
            runCatching {
                Log.i(TAG, "web login: exchanging authorization code")
                val body = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", OAUTH_CLIENT_ID)
                    .add("redirect_uri", OAUTH_REDIRECT)
                    .add("code", code)
                    .add("code_verifier", req.verifier)
                    .build()
                val request = Request.Builder().url(OAUTH_TOKEN).post(body).build()
                val token = http.newCall(request).execute().use { resp ->
                    val o = JSONObject(resp.body?.string().orEmpty())
                    if (resp.code != 200 || !o.has("access_token")) {
                        Log.w(TAG, "token exchange failed: $o")
                        lastLoginError = o.optString("error_description").ifBlank { "Spotify rejected the sign-in" }
                        return@withContext false
                    }
                    o.getString("access_token")
                }
                val creds = Authentication.LoginCredentials.newBuilder()
                    .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                    .setAuthData(com.google.protobuf.ByteString.copyFromUtf8(token))
                    .build()
                stage = "session authentication"
                Log.i(TAG, "web login: token exchange succeeded; authenticating session")
                val s = createSession(creds)
                persist(s)
                // Spotify refuses audio-key requests on OAuth-token sessions
                // (code 1), so swap straight to the reusable blob it issued.
                // Keep the token session until that's up: closing it first left
                // a dead session when the reconnect failed, showing "signed in"
                // with nothing loading.
                Log.i(TAG, "web login: re-authenticating with reusable credentials")
                val reusable = runCatching { restoreCredentials()?.let { createSession(it) } }
                    .onFailure { Log.w(TAG, "web login: reusable session failed: ${it.message}", it) }
                    .getOrNull()
                if (reusable == null) Log.w(TAG, "web login: staying on the token session, audio keys may be refused")
                stage = "player initialization"
                shutdown()
                onSessionReady(reusable ?: s)
                if (reusable != null) runCatching { s.close() }
                true
            }.getOrElse {
                Log.w(TAG, "web login failed during $stage: ${it.message}", it)
                lastLoginError = "$stage failed"
                false
            }
        }

    /** Restore a stored session at app start. */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val creds = restoreCredentials() ?: return@withContext false
        runCatching {
            val s = createSession(creds)
            onSessionReady(s)
            true
        }.getOrElse {
            Log.w(TAG, "restore failed: ${it.message}", it)
            // A network/access-point failure must never sign the user out.
            false
        }
    }

    private fun persist(s: Session) {
        val w = s.apWelcome() ?: return
        val json = JsonObject().apply {
            addProperty("username", w.canonicalUsername)
            addProperty("authType", w.reusableAuthCredentialsType.name)
            addProperty(
                "blob",
                Base64.getEncoder().encodeToString(w.reusableAuthCredentials.toByteArray()),
            )
        }
        context.openFileOutput(CREDS_FILE, Context.MODE_PRIVATE).use {
            it.write(json.toString().toByteArray())
        }
    }

    private fun restoreCredentials(): Authentication.LoginCredentials? {
        val f = credsFile()
        if (!f.exists()) return null
        return runCatching {
            val json = f.readText()
            val o = com.google.gson.JsonParser.parseString(json).asJsonObject
            val type = Authentication.AuthenticationType.valueOf(o["authType"].asString)
            val blob = Base64.getDecoder().decode(o["blob"].asString)
            Authentication.LoginCredentials.newBuilder()
                .setUsername(o["username"].asString)
                .setTyp(type)
                .setAuthData(com.google.protobuf.ByteString.copyFrom(blob))
                .build()
        }.getOrNull()
    }

    private fun onSessionReady(s: Session) {
        SpotifyMetadataAdapter.install(s)
        session = s
        val conf = PlayerConfiguration.Builder()
            .setPreferredQuality(xyz.gianlu.librespot.audio.decoders.AudioQuality.VERY_HIGH)
            .setEnableNormalisation(false)
            .setAutoplayEnabled(false)
            .setCrossfadeDuration(0)
            .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
            .setOutputClass(PetalSink::class.java.name)
            .setPreloadEnabled(true)
            .setInitialVolume(VOLUME_MAX)
            .build()
        val p = Player(conf, s)
        p.addEventsListener(events)
        player = p
        Log.i(TAG, "session ready user=${s.username()} catalogue=${s.getUserAttribute("catalogue", "?")} " +
            "country=${s.countryCode()} deviceId=${s.deviceId()}")
    }

    private val events = object : Player.EventsListener {
        override fun onContextChanged(p: Player, contextUri: String) {}
        override fun onTrackChanged(p: Player, id: PlayableId, metadata: MetadataWrapper?, userInitiated: Boolean) {
            pumpListener?.onTrackChanged(metadata?.toTrack(id))
        }
        override fun onPlaybackEnded(p: Player) {
            pumpListener?.onPlaybackEnded()
        }
        override fun onPlaybackPaused(p: Player, positionMs: Long) {
            playingState = false
            pumpListener?.onPlayingChanged(false, positionMs)
        }
        override fun onPlaybackResumed(p: Player, positionMs: Long) {
            playingState = true
            pumpListener?.onPlayingChanged(true, positionMs)
        }
        override fun onPlaybackFailed(p: Player, e: Exception) {
            playingState = false
            Log.e(TAG, "Spotify playback failed", e)
            org.rab1d.petal.music.playback.PlaybackBus.publishError("Spotify playback failed: ${e.message ?: e.javaClass.simpleName}")
            pumpListener?.onFailed(e)
        }
        override fun onTrackSeeked(p: Player, positionMs: Long) {
            pumpListener?.onSeeked(positionMs)
        }
        override fun onMetadataAvailable(p: Player, metadata: MetadataWrapper) {}
        override fun onPlaybackHaltStateChanged(p: Player, halted: Boolean, positionMs: Long) {
            playingState = !halted
            pumpListener?.onPlayingChanged(!halted, positionMs)
        }
        override fun onInactiveSession(p: Player, timeout: Boolean) {}
        override fun onVolumeChanged(p: Player, volume: Float) {}
        override fun onPanicState(p: Player) {
            pumpListener?.onFailed(IllegalStateException("panic state"))
        }
        override fun onStartedLoading(p: Player) {}
        override fun onFinishedLoading(p: Player) {}
    }

    // ---- Search / metadata / radio -------------------------------------------------

    /** Spotify's session-backed catalogue API, used by the desktop/web clients. */
    suspend fun sessionJson(url: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.isValid }
            ?: throw org.rab1d.petal.music.model.SourceException("Sign in to Spotify first")
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${s.tokens().get("user-read-private")}")
            .header("client-token", sessionClientToken(s))
            .header("App-Platform", "WebPlayer").header("Accept", "application/json")
            .header("Origin", "https://open.spotify.com")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }
            .build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "session API ${req.url.encodedPath} failed: HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                Log.w(TAG, "session API rejection: ${text.take(300)}")
                throw org.rab1d.petal.music.model.SourceException("Spotify session API: HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) JsonObject() else com.google.gson.JsonParser.parseString(text).asJsonObject
        }
    }

    private fun sessionClientToken(s: Session): String {
        val api = s.api()
        val field = api.javaClass.getDeclaredField("clientToken").apply { isAccessible = true }
        (field.get(api) as? String)?.let { return it }
        // The bundled API exposes setClientToken, but not its fetch method.
        val result = api.javaClass.getDeclaredMethod("clientToken").apply { isAccessible = true }.invoke(api)
            as com.spotify.clienttoken.http.v0.ClientToken.ClientTokenResponse
        if (!result.hasGrantedToken()) throw org.rab1d.petal.music.model.SourceException("Spotify client token was not granted")
        val token = result.grantedToken.token
        api.setClientToken(token)
        return token
    }

    suspend fun graphql(operation: String, hash: String, variables: JsonObject): JsonObject {
        val result = sessionJson("https://api-partner.spotify.com/pathfinder/v2/query", JsonObject().apply {
            addProperty("operationName", operation)
            add("variables", variables)
            add("extensions", JsonObject().apply { add("persistedQuery", JsonObject().apply {
                addProperty("version", 1); addProperty("sha256Hash", hash)
            }) })
        })
        result.arr("errors")?.takeIf { it.isNotEmpty() }?.let {
            throw org.rab1d.petal.music.model.SourceException("Spotify: ${it.first().asObj().str("message") ?: "Catalogue request failed"}")
        }
        return result.obj("data") ?: throw org.rab1d.petal.music.model.SourceException("Spotify returned no data")
    }

    fun radioForTrack(trackId: String): JsonObject? = runCatching {
        session?.api()?.getRadioForTrack(TrackId.fromBase62(trackId))
    }.getOrNull()

    fun sessionApiAlbum(albumId: String) =
        session?.api()?.getMetadata4Album(xyz.gianlu.librespot.metadata.AlbumId.fromBase62(albumId))!!

    fun metadata(trackId: String) = runCatching {
        session?.api()?.getMetadata4Track(TrackId.fromBase62(trackId))
    }.getOrNull()

    // ---- Pump control ----------------------------------------------------------------

    fun play(track: Track, play: Boolean) {
        val p = player ?: run {
            org.rab1d.petal.music.playback.PlaybackBus.publishError("Spotify player is not ready")
            Log.w(TAG, "play requested before Spotify player was ready")
            return
        }
        Log.i(TAG, "load track=${track.id} play=$play")
        runCatching { p.load("spotify:track:${track.id}", play, false) }.onFailure {
            Log.e(TAG, "Spotify load failed", it)
            org.rab1d.petal.music.playback.PlaybackBus.publishError("Spotify playback failed: ${it.message}")
        }
    }

    fun playPause() {
        player?.playPause()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seek(ms: Int) {
        player?.seek(ms)
    }

    /** librespot mixes the transition internally; true when accepted. */
    fun crossfadeTo(track: Track, fadeMs: Int): Boolean {
        val p = player ?: return false
        return runCatching {
            // Same-session crossfade: load while playing engages the mixing line.
            p.load("spotify:track:${track.id}", true, false)
            true
        }.getOrDefault(false)
    }

    fun fadeOutAndStop(fadeMs: Int, onDone: () -> Unit) {
        val p = player ?: run { onDone(); return }
        val sink = SpotifySessionBridge.activeSink
        if (sink == null) {
            p.pause()
            onDone()
            return
        }
        val steps = 20
        val stepMs = (fadeMs / steps).coerceAtLeast(20)
        var i = 0
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        fun step() {
            i++
            val v = 1f - i.toFloat() / steps
            if (i >= steps || v <= 0.02f) {
                p.pause()
                sink.setVolume(1f)
                onDone()
            } else {
                sink.setVolume(v)
                h.postDelayed(::step, stepMs.toLong())
            }
        }
        h.postDelayed(::step, stepMs.toLong())
    }

    fun position(): Long = runCatching { player?.time()?.toLong() ?: 0 }.getOrDefault(0)

    fun duration(): Long = runCatching {
        val m = player?.currentMetadata() ?: return@runCatching 0L
        if (m.isTrack) m.track.duration.toLong() else m.duration().toLong()
    }.getOrDefault(0)

    @Volatile private var playingState = false

    fun isPlaying(): Boolean = playingState && player?.currentMetadata() != null

    fun logout() {
        shutdown()
        credsFile().delete()
    }

    fun shutdown() {
        runCatching { player?.close() }
        runCatching { session?.close() }
        player = null
        session = null
    }
}

/** MetadataWrapper → app Track model. */
fun MetadataWrapper.toTrack(id: PlayableId?): Track? {
    val t = track ?: return null
    val hex = t.gid.toByteArray().joinToString("") { "%02x".format(it) }
    val artist = t.artistList.joinToString(", ") { it.name }
    val cover = t.album.coverGroup.imageList.lastOrNull()?.fileId
        ?.let { fid -> "https://i.scdn.co/image/$fid" }
    return Track(
        id = TrackId.fromHex(hex).toSpotifyUri().substringAfterLast(':'),
        source = Source.SPOTIFY,
        title = t.name,
        artist = artist,
        album = t.album.name,
        albumId = t.album.gid?.let { hex2(it.toByteArray()) },
        durationMs = t.duration.toLong(),
        trackNumber = t.number,
        discNumber = t.discNumber,
        year = t.album.date.year,
        artUri = cover,
    )
}

private fun hex2(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
