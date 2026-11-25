/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.rab1d.petal.music.model.Playlist
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.model.playable
import java.io.File

private val Context.dataStore by preferencesDataStore("petal_music")

/** A recently opened playlist or album, stored for the merged home shelves. */
@kotlinx.serialization.Serializable
data class RecentEntry(
    /** "playlist" or "album". */
    val type: String,
    val source: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val artUri: String? = null,
)

/** The queue + playhead captured when the app last stopped, so it can resume. */
@kotlinx.serialization.Serializable
data class PlaybackSession(
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val unshuffledQueue: List<Track>? = null,
)

/**
 * Device Link: a direct link to the desktop player that receives this phone's
 * playlists (metadata only). Audio downloading on the desktop is optional.
 */
@kotlinx.serialization.Serializable
data class DeviceLinkSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 9478,
) {
    val configured: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
}

/**
 * On-device processing is opt-in for Apollo. The light DSP-only Enhance path
 * never loads the model. [mode] selects the routing policy: AUTO inspects the
 * spectrum, RESTORE forces Apollo, ENHANCE forces DSP only.
 */
data class UpscaleSettings(
    val onDeviceUnlocked: Boolean = false,
    val width: Float = 0.5f,
    val mode: UpscaleMode = UpscaleMode.AUTO,
    /** Upscale album art 2x for the player and the system media controls. */
    val art2x: Boolean = true,
)

enum class UpscaleMode(val id: Int, val label: String, val description: String) {
    AUTO(0, "Auto", "Analyse the spectrum and choose Restore or Enhance"),
    RESTORE(1, "Restore", "Apollo for band-limited or codec-damaged audio, then finishing DSP"),
    ENHANCE(2, "Enhance", "DSP only for genuine full-band audio; no generative model");

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Mirror of the BridgeController System.Settings keys, so both apps agree. */
object BridgeSettings {
    const val ENABLED = "petal_bridge_enabled"
    const val FADE_MS = "petal_bridge_fade_ms"
    const val STYLE = "petal_bridge_style"
}

enum class AutomixStyle(val id: Int, val label: String) {
    CLEAN(0, "Clean"),
    REVERB(1, "Reverb"),
    STUTTER(2, "Stutter"),
    ADAPTIVE(3, "Adaptive");

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: CLEAN
    }
}

/** Which in-app transition engine renders dual-deck AutoMix. */
enum class AutomixAlgorithm(val id: Int, val label: String, val description: String) {
    V1_BLEND(0, "v1 · Smooth blend", "Live part exchange: held bass/body from one song, melody from the other, gentle hand-off"),
    V2_DJ(1, "v2 · DJ mix", "Phrase-aligned overlays, handoffs and bass swaps that land the next song on the bar line");

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: V1_BLEND
    }
}

data class PlaybackSettings(
    val automixEnabled: Boolean = true,
    val fadeMs: Int = 6000,
    val style: AutomixStyle = AutomixStyle.CLEAN,
    val beatAlign: Boolean = true,
    val preferSystemBridge: Boolean = true,
    val preserveAlbum: Boolean = true,
    /** Spotify-style: start the next track before this one physically ends. */
    val earlyExit: Boolean = false,
    /** App owns the mix: true 4-band EQ crossfade, not the ROM BridgeFX loop. */
    val inAppMixer: Boolean = true,
    val dynamicColor: Boolean = false,
    /** ThemeMode name: SYSTEM, NIGHT or PAPER. */
    val themeMode: String = "SYSTEM",
    /** Dye name (SLEEVE, ORCHID, AMETHYST, ROSE, TWILIGHT, JADE, SYSTEM). */
    val dye: String = "SLEEVE",
    val normaliseLoudness: Boolean = true,
    /** Choose each overlap from the song pair; fadeMs is the manual budget. */
    val autoFade: Boolean = true,
    val algorithm: AutomixAlgorithm = AutomixAlgorithm.V1_BLEND,
    /** v2: plain phrase-aligned blends (true) or every effect family (false). */
    val cleanMix: Boolean = true,
    /** v2: stretch the next song to this one's tempo; the playing song keeps its speed. */
    val conformIncoming: Boolean = true,
    /** v2: after a mix, glide a stretched song slowly back to its own tempo. */
    val glideBack: Boolean = true,
    /** v2: prefer dropping the next song exactly where this one's outro ends. */
    val letOutrosPlay: Boolean = true,
    /** v2: match pairs up to 8 % apart by riding both decks' tempo, half each. */
    val tempoRide: Boolean = true,
    /** v2: separate stems for local files so a mix can move drums, bass and vocals on their own. */
    val stemMoves: Boolean = true,
    /** v2 with shuffle: order the next few songs so neighbours share a tempo and key. */
    val smoothShuffle: Boolean = true,
    /** v2: mix out after a song's second chorus, like a DJ, instead of at its outro. */
    val djPacing: Boolean = true,
    /** When a source has no lyrics, look the song up on LRCLIB (sends title, artist, album, length). */
    val lrclibLyrics: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val AUTOMIX = booleanPreferencesKey("automix_enabled")
        val FADE = intPreferencesKey("fade_ms")
        val AUTO_FADE = booleanPreferencesKey("auto_fade")
        val STYLE = intPreferencesKey("style")
        val ALGORITHM = intPreferencesKey("automix_algorithm")
        val CLEAN_MIX = booleanPreferencesKey("automix_clean_mix")
        val CONFORM_INCOMING = booleanPreferencesKey("automix_conform_incoming")
        val GLIDE_BACK = booleanPreferencesKey("automix_glide_back")
        val LET_OUTROS_PLAY = booleanPreferencesKey("automix_let_outros_play")
        val TEMPO_RIDE = booleanPreferencesKey("automix_tempo_ride")
        val STEM_MOVES = booleanPreferencesKey("automix_stem_moves")
        val SMOOTH_SHUFFLE = booleanPreferencesKey("automix_smooth_shuffle")
        val DJ_PACING = booleanPreferencesKey("automix_dj_pacing")
        val LRCLIB_LYRICS = booleanPreferencesKey("lrclib_lyrics")
        val BEAT_ALIGN = booleanPreferencesKey("beat_align")
        val PREFER_BRIDGE = booleanPreferencesKey("prefer_system_bridge")
        val PRESERVE_ALBUM = booleanPreferencesKey("preserve_album")
        val EARLY_EXIT = booleanPreferencesKey("early_exit")
        val IN_APP_MIXER = booleanPreferencesKey("in_app_mixer")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYE = stringPreferencesKey("dye")
        val NORMALISE = booleanPreferencesKey("normalise")
        val RECENTS = stringPreferencesKey("recents")
        val FAVORITES = stringPreferencesKey("favorites")
        val PLAYLISTS = stringPreferencesKey("playlists")
        val SPOTIFY_CREDS = stringPreferencesKey("spotify_creds")
        val SPOTIFY_USER = stringPreferencesKey("spotify_user")
        val DEEZER_ARL = stringPreferencesKey("deezer_arl")
        val TIDAL_TOKEN = stringPreferencesKey("tidal_token")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val DISCORD_USER = stringPreferencesKey("discord_user")
        val DISCORD_ENABLED = booleanPreferencesKey("discord_rpc_enabled")
        val LAST_KEY = longPreferencesKey("last_key")
        val RECENT_MEDIA = stringPreferencesKey("recent_media")
        val DEVICE_LINK_ENABLED = booleanPreferencesKey("device_link_enabled")
        val DEVICE_LINK_HOST = stringPreferencesKey("device_link_host")
        val DEVICE_LINK_PORT = intPreferencesKey("device_link_port")
        val ON_DEVICE_UPSCALE_UNLOCKED = booleanPreferencesKey("on_device_upscale_unlocked")
        val UPSCALE_WIDTH = floatPreferencesKey("upscale_width")
        val UPSCALE_MODE = intPreferencesKey("upscale_mode")
        val ART_2X = booleanPreferencesKey("art_2x")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val recentMedia: Flow<List<RecentEntry>> = context.dataStore.data.map { p ->
        p[Keys.RECENT_MEDIA]?.let { raw ->
            runCatching { json.decodeFromString<List<RecentEntry>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun addRecentMedia(entry: RecentEntry) = edit { p ->
        val current = p[Keys.RECENT_MEDIA]?.let { raw ->
            runCatching { json.decodeFromString<List<RecentEntry>>(raw) }.getOrNull()
        } ?: emptyList()
        val next = (listOf(entry) + current.filterNot {
            it.type == entry.type && it.source == entry.source && it.id == entry.id
        }).take(20)
        p[Keys.RECENT_MEDIA] = json.encodeToString(next)
    }

    suspend fun updateRecentPlaylist(source: Source, id: String, name: String? = null) = edit { p ->
        val current = p[Keys.RECENT_MEDIA]?.let {
            runCatching { json.decodeFromString<List<RecentEntry>>(it) }.getOrNull()
        }.orEmpty()
        p[Keys.RECENT_MEDIA] = json.encodeToString(current.mapNotNull { entry ->
            if (entry.type != "playlist" || entry.source != source.id || entry.id != id) entry
            else name?.let { entry.copy(title = it) }
        })
    }

    val settings: Flow<PlaybackSettings> = context.dataStore.data.map { p ->
        PlaybackSettings(
            automixEnabled = p[Keys.AUTOMIX] ?: true,
            fadeMs = p[Keys.FADE] ?: 6000,
            autoFade = p[Keys.AUTO_FADE] ?: true,
            style = AutomixStyle.fromId(p[Keys.STYLE] ?: 0),
            algorithm = AutomixAlgorithm.fromId(p[Keys.ALGORITHM] ?: 0),
            cleanMix = p[Keys.CLEAN_MIX] ?: true,
            conformIncoming = p[Keys.CONFORM_INCOMING] ?: true,
            glideBack = p[Keys.GLIDE_BACK] ?: true,
            letOutrosPlay = p[Keys.LET_OUTROS_PLAY] ?: true,
            tempoRide = p[Keys.TEMPO_RIDE] ?: true,
            stemMoves = p[Keys.STEM_MOVES] ?: true,
            smoothShuffle = p[Keys.SMOOTH_SHUFFLE] ?: true,
            djPacing = p[Keys.DJ_PACING] ?: true,
            lrclibLyrics = p[Keys.LRCLIB_LYRICS] ?: true,
            beatAlign = p[Keys.BEAT_ALIGN] ?: true,
            preferSystemBridge = p[Keys.PREFER_BRIDGE] ?: true,
            preserveAlbum = p[Keys.PRESERVE_ALBUM] ?: true,
            earlyExit = p[Keys.EARLY_EXIT] ?: false,
            inAppMixer = p[Keys.IN_APP_MIXER] ?: true,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
            themeMode = p[Keys.THEME_MODE] ?: "SYSTEM",
            // Anyone who had Material You on keeps it as the System dye.
            dye = p[Keys.DYE] ?: if (p[Keys.DYNAMIC_COLOR] == true) "SYSTEM" else "SLEEVE",
            normaliseLoudness = p[Keys.NORMALISE] ?: true,
        )
    }.distinctUntilChanged()

    val deviceLink: Flow<DeviceLinkSettings> = context.dataStore.data.map { p ->
        DeviceLinkSettings(
            enabled = p[Keys.DEVICE_LINK_ENABLED] ?: false,
            host = p[Keys.DEVICE_LINK_HOST] ?: "",
            port = p[Keys.DEVICE_LINK_PORT] ?: 9478,
        )
    }

    suspend fun setDeviceLinkEnabled(v: Boolean) = edit { it[Keys.DEVICE_LINK_ENABLED] = v }

    suspend fun setDeviceLinkEndpoint(host: String, port: Int) = edit {
        it[Keys.DEVICE_LINK_HOST] = host.trim()
        it[Keys.DEVICE_LINK_PORT] = port.coerceIn(1, 65535)
    }

    val upscale: Flow<UpscaleSettings> = context.dataStore.data.map { p ->
        UpscaleSettings(
            onDeviceUnlocked = p[Keys.ON_DEVICE_UPSCALE_UNLOCKED] ?: false,
            width = (p[Keys.UPSCALE_WIDTH] ?: 0.5f).coerceIn(0f, 1.5f),
            mode = UpscaleMode.fromId(p[Keys.UPSCALE_MODE] ?: 0),
            art2x = p[Keys.ART_2X] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun setOnDeviceUpscaleUnlocked(v: Boolean) =
        edit { it[Keys.ON_DEVICE_UPSCALE_UNLOCKED] = v }

    suspend fun setUpscaleWidth(v: Float) =
        edit { it[Keys.UPSCALE_WIDTH] = v.coerceIn(0f, 1.5f) }

    suspend fun setUpscaleMode(mode: UpscaleMode) =
        edit { it[Keys.UPSCALE_MODE] = mode.id }

    suspend fun setArt2x(v: Boolean) = edit { it[Keys.ART_2X] = v }

    val discordToken: Flow<String?> = context.dataStore.data.map { it[Keys.DISCORD_TOKEN] }
    val discordUser: Flow<String?> = context.dataStore.data.map { it[Keys.DISCORD_USER] }
    val discordEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.DISCORD_ENABLED] ?: false }

    suspend fun setDiscordLogin(token: String?, user: String?) = edit {
        if (token.isNullOrBlank()) {
            it.remove(Keys.DISCORD_TOKEN)
            it.remove(Keys.DISCORD_USER)
            it[Keys.DISCORD_ENABLED] = false
        } else {
            it[Keys.DISCORD_TOKEN] = token.trim()
            user?.let { u -> it[Keys.DISCORD_USER] = u }
            it[Keys.DISCORD_ENABLED] = true
        }
    }

    suspend fun setDiscordEnabled(v: Boolean) = edit { it[Keys.DISCORD_ENABLED] = v }

    val recents: Flow<List<String>> = context.dataStore.data.map { p ->
        p[Keys.RECENTS]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    val playlists: Flow<List<Playlist>> = context.dataStore.data.map { p -> decodePlaylists(p[Keys.PLAYLISTS]) }

    private fun decodePlaylists(raw: String?): List<Playlist> =
        raw?.let { runCatching { json.decodeFromString<List<Playlist>>(it) }.getOrNull() } ?: emptyList()

    /** Create a playlist from [tracks] and return it. */
    suspend fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val clean = tracks.playable().distinctBy { it.key() }
        val playlist = Playlist(
            id = "pl_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "New playlist" },
            trackKeys = clean.map { it.key() }.toMutableList(),
            tracks = clean.toMutableList(),
        )
        edit { it[Keys.PLAYLISTS] = json.encodeToString(decodePlaylists(it[Keys.PLAYLISTS]) + playlist) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TrackMetadataCache.remember(context.filesDir, clean)
        }
        return playlist
    }

    /** Append [tracks] to an existing playlist (idempotent per track key). */
    suspend fun addToPlaylist(playlistId: String, tracks: List<Track>) {
        val clean = tracks.playable()
        if (clean.isEmpty()) return
        edit { p ->
            val next = decodePlaylists(p[Keys.PLAYLISTS]).map { pl ->
                if (pl.id != playlistId) return@map pl
                val keys = (pl.trackKeys + clean.map { it.key() }).distinct().toMutableList()
                val merged = (pl.tracks + clean).distinctBy { it.key() }.toMutableList()
                pl.copy(trackKeys = keys, tracks = merged)
            }
            p[Keys.PLAYLISTS] = json.encodeToString(next)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TrackMetadataCache.remember(context.filesDir, clean)
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, trackKey: String) = edit { p ->
        val next = decodePlaylists(p[Keys.PLAYLISTS]).map { pl ->
            if (pl.id != playlistId) pl
            else pl.copy(
                trackKeys = pl.trackKeys.filterNot { it == trackKey }.toMutableList(),
                tracks = pl.tracks.filterNot { it.key() == trackKey }.toMutableList(),
            )
        }
        p[Keys.PLAYLISTS] = json.encodeToString(next)
    }

    suspend fun deletePlaylist(playlistId: String) = edit { p ->
        p[Keys.PLAYLISTS] = json.encodeToString(decodePlaylists(p[Keys.PLAYLISTS]).filterNot { it.id == playlistId })
    }

    suspend fun renamePlaylist(playlistId: String, name: String) = edit { p ->
        val next = decodePlaylists(p[Keys.PLAYLISTS]).map {
            if (it.id == playlistId) it.copy(name = name.trim().ifBlank { it.name }) else it
        }
        p[Keys.PLAYLISTS] = json.encodeToString(next)
    }

    /** Resolve a playlist's entries, filling legacy key-only rows from cache. */
    fun resolvePlaylist(playlist: Playlist): List<Track> {
        if (playlist.tracks.isNotEmpty()) return playlist.playable()
        return playlist.trackKeys.mapNotNull { k -> TrackMetadataCache.get(context.filesDir, k) }.playable()
    }

    val favorites: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[Keys.FAVORITES]?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun toggleFavorite(key: String) = edit { p ->
        val cur = p[Keys.FAVORITES]?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val next = if (cur.contains(key)) cur - key else cur + key
        p[Keys.FAVORITES] = next.joinToString("\n")
    }

    val spotifyUser: Flow<String?> = context.dataStore.data.map { it[Keys.SPOTIFY_USER] }
    val spotifyCreds: Flow<String?> = context.dataStore.data.map { it[Keys.SPOTIFY_CREDS] }
    val deezerArl: Flow<String?> = context.dataStore.data.map { it[Keys.DEEZER_ARL] }
    val tidalToken: Flow<String?> = context.dataStore.data.map { it[Keys.TIDAL_TOKEN] }

    suspend fun setAutomixEnabled(v: Boolean) = edit { it[Keys.AUTOMIX] = v }
    suspend fun toggleAutomix() = edit { it[Keys.AUTOMIX] = !(it[Keys.AUTOMIX] ?: true) }
    suspend fun setFadeMs(v: Int) = edit {
        it[Keys.FADE] = v.coerceIn(0, 12000)
        it[Keys.AUTO_FADE] = false
    }
    suspend fun setAutoFade(v: Boolean) = edit { it[Keys.AUTO_FADE] = v }
    suspend fun setStyle(v: AutomixStyle) = edit { it[Keys.STYLE] = v.id }
    suspend fun setAlgorithm(v: AutomixAlgorithm) = edit { it[Keys.ALGORITHM] = v.id }
    suspend fun setCleanMix(v: Boolean) = edit { it[Keys.CLEAN_MIX] = v }
    suspend fun setConformIncoming(v: Boolean) = edit { it[Keys.CONFORM_INCOMING] = v }
    suspend fun setGlideBack(v: Boolean) = edit { it[Keys.GLIDE_BACK] = v }
    suspend fun setLetOutrosPlay(v: Boolean) = edit { it[Keys.LET_OUTROS_PLAY] = v }
    suspend fun setTempoRide(v: Boolean) = edit { it[Keys.TEMPO_RIDE] = v }
    suspend fun setStemMoves(v: Boolean) = edit { it[Keys.STEM_MOVES] = v }
    suspend fun setSmoothShuffle(v: Boolean) = edit { it[Keys.SMOOTH_SHUFFLE] = v }
    suspend fun setDjPacing(v: Boolean) = edit { it[Keys.DJ_PACING] = v }
    suspend fun setLrclibLyrics(v: Boolean) = edit { it[Keys.LRCLIB_LYRICS] = v }
    suspend fun setBeatAlign(v: Boolean) = edit { it[Keys.BEAT_ALIGN] = v }
    suspend fun setPreferSystemBridge(v: Boolean) = edit { it[Keys.PREFER_BRIDGE] = v }
    suspend fun setPreserveAlbum(v: Boolean) = edit { it[Keys.PRESERVE_ALBUM] = v }
    suspend fun setEarlyExit(v: Boolean) = edit { it[Keys.EARLY_EXIT] = v }
    suspend fun setInAppMixer(v: Boolean) = edit { it[Keys.IN_APP_MIXER] = v }
    suspend fun setDynamicColor(v: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = v }
    suspend fun setThemeMode(v: String) = edit { it[Keys.THEME_MODE] = v }
    suspend fun setDye(v: String) = edit { it[Keys.DYE] = v }
    suspend fun setNormalise(v: Boolean) = edit { it[Keys.NORMALISE] = v }

    suspend fun addRecent(key: String) = edit { p ->
        val cur = p[Keys.RECENTS]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        p[Keys.RECENTS] = (listOf(key) + cur.filter { it != key }).take(30).joinToString("\n")
    }

    suspend fun setPlaylists(list: List<Playlist>) = edit { it[Keys.PLAYLISTS] = json.encodeToString(list) }

    suspend fun setSpotifyLogin(user: String, credsJson: String?) = edit {
        it[Keys.SPOTIFY_USER] = user
        if (credsJson != null) it[Keys.SPOTIFY_CREDS] = credsJson else it.remove(Keys.SPOTIFY_CREDS)
    }

    suspend fun setDeezerArl(arl: String?) = edit {
        if (arl.isNullOrBlank()) it.remove(Keys.DEEZER_ARL) else it[Keys.DEEZER_ARL] = arl.trim()
    }

    suspend fun setTidalToken(token: String?) = edit {
        if (token.isNullOrBlank()) it.remove(Keys.TIDAL_TOKEN) else it[Keys.TIDAL_TOKEN] = token.trim()
    }

    val credsFile: File get() = File(context.filesDir, "librespot_creds.json")

    // Last selected bottom-nav tab — read synchronously so the app can start
    // straight on it without a flash of Home.
    private val uiPrefs = context.getSharedPreferences("petal_ui", Context.MODE_PRIVATE)

    fun lastTab(): String? = uiPrefs.getString("last_tab", null)

    fun setLastTab(route: String) {
        uiPrefs.edit().putString("last_tab", route).apply()
    }

    // Shuffle / repeat survive relaunches, like a real player.
    fun shuffleEnabled(): Boolean = uiPrefs.getBoolean("shuffle", false)

    fun setShuffleEnabled(value: Boolean) {
        uiPrefs.edit().putBoolean("shuffle", value).apply()
    }

    fun repeatMode(): Int = uiPrefs.getInt("repeat_mode", 0)

    fun setRepeatMode(value: Int) {
        uiPrefs.edit().putInt("repeat_mode", value).apply()
    }

    // The last queue + playhead, so relaunching can pick the song back up.
    // The playhead lives in its own file: SharedPreferences rewrites a whole file per change,
    // and the session file holds the entire queue.
    private val positionPrefs = context.getSharedPreferences("petal_position", Context.MODE_PRIVATE)

    fun playbackSession(): PlaybackSession? =
        uiPrefs.getString("playback_session", null)?.let {
            runCatching { json.decodeFromString<PlaybackSession>(it) }.getOrNull()
        }?.let { s ->
            val pos = positionPrefs.getLong("position_ms", -1)
            val at = positionPrefs.getInt("position_index", -1)
            if (pos >= 0 && at == s.index) s.copy(positionMs = pos) else s
        }

    fun setPlaybackSession(session: PlaybackSession?) {
        uiPrefs.edit().apply {
            if (session == null || session.queue.isEmpty()) remove("playback_session")
            else putString("playback_session", json.encodeToString(session))
        }.apply()
        session?.let { setPlaybackPosition(it.index, it.positionMs) }
    }

    /** Cheap: a few bytes, written as playback runs. */
    fun setPlaybackPosition(index: Int, positionMs: Long) {
        positionPrefs.edit().putInt("position_index", index).putLong("position_ms", positionMs).apply()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    fun loginState(sources: List<Source>): Map<Source, Boolean> = buildMap {
        put(Source.LOCAL, true)
        put(Source.SPOTIFY, credsFile.exists())
        put(Source.DEEZER, File(context.filesDir, "deezer_state.json").exists())
        put(Source.TIDAL, File(context.filesDir, "tidal_state.json").exists())
        put(Source.YT_MUSIC, true)
        sources.forEach { putIfAbsent(it, false) }
    }
}
