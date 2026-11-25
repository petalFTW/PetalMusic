/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.rab1d.petal.music.PetalApp
import org.rab1d.petal.music.data.DeviceLinkSettings
import org.rab1d.petal.music.data.MediaStoreLibrary
import org.rab1d.petal.music.data.link.DeviceLinkClient
import org.rab1d.petal.music.data.link.DeviceLinkStatus
import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.data.SettingsRepository
import org.rab1d.petal.music.data.TrackMetadataCache
import org.rab1d.petal.music.data.UpscaleMode
import org.rab1d.petal.music.data.UpscaleSettings
import org.rab1d.petal.music.data.downloads.upscale.OnDeviceApollo
import org.rab1d.petal.music.model.Playlist
import org.rab1d.petal.music.model.PlaylistSummary
import org.rab1d.petal.music.model.SourceException
import org.rab1d.petal.music.model.equivalentTrack
import org.rab1d.petal.music.model.Album
import org.rab1d.petal.music.model.Artist
import org.rab1d.petal.music.model.SearchResults
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.isPlayable
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.model.playable
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.playback.PlaybackBus
import org.rab1d.petal.music.playback.PlaybackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

class PetalViewModel(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val library: MediaStoreLibrary,
) : ViewModel() {
    companion object {
        const val ON_DEVICE_UNLOCK_TAPS = 10
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val playbackState = PlaybackBus.state.stateIn(scope, SharingStarted.Eagerly, PlaybackBus.State())

    val settings = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, PlaybackSettings())

    val recents = settingsRepo.recents.stateIn(scope, SharingStarted.Eagerly, emptyList<String>())

    val favorites = settingsRepo.favorites.stateIn(scope, SharingStarted.Eagerly, emptySet<String>())

    fun toggleFavorite(track: Track) = scope.launch { settingsRepo.toggleFavorite(track.key()) }

    /** Bottom-nav tab the app last showed, restored on next launch. */
    fun lastTab(): String? = settingsRepo.lastTab()

    fun setLastTab(route: String) = settingsRepo.setLastTab(route)

    // ---- offline downloads -----------------------------------------------------

    val downloads = (context.applicationContext as PetalApp).container.downloads.downloads
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Set of downloaded track keys, reactive — drives "all downloaded" pills. */
    val downloadedKeys = downloads
        .map { list -> list.filter { it.playbackFile() != null }.map { "${it.source}/${it.id}" }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val downloadProgress = (context.applicationContext as PetalApp).container.downloadManager.progress
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun downloadTrack(track: Track) {
        if (!track.isPlayable) return
        (context.applicationContext as PetalApp).container.downloadManager.enqueue(track)
    }

    fun downloadAll(tracks: List<Track>) {
        (context.applicationContext as PetalApp).container.downloadManager.enqueueAll(tracks.playable())
    }

    fun deleteDownload(track: Track) {
        (context.applicationContext as PetalApp).container.downloadManager.delete(track)
    }

    // ---- on-device upscaling of downloads --------------------------------------

    sealed interface UpscaleRequest {
        data object Started : UpscaleRequest
        data object Ignored : UpscaleRequest
        data class Locked(val taps: Int, val required: Int) : UpscaleRequest
    }

    val upscaleProgress =
        (context.applicationContext as PetalApp).container.upscaleManager.progress
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val upscaleSettings = settingsRepo.upscale
        .stateIn(scope, SharingStarted.Eagerly, UpscaleSettings())

    private val _upscaleUnlockTaps = MutableStateFlow(0)
    val upscaleUnlockTaps: StateFlow<Int> = _upscaleUnlockTaps

    private val _showUpscaleWarning = MutableStateFlow(false)
    val showUpscaleWarning: StateFlow<Boolean> = _showUpscaleWarning

    /**
     * Turn the upscaled copy of a downloaded track on (or revert to the original).
     * A new on-device pass is refused until the deliberate unlock gesture has
     * been completed; the caller counts the tap and eventually shows the warning.
     */
    fun toggleUpscale(track: Track, enabled: Boolean): UpscaleRequest {
        val manager = (context.applicationContext as PetalApp).container.upscaleManager
        // A dropped link is never retried automatically; surface it instead of
        // silently hammering the (unreachable) desktop.
        if (enabled && !manager.hasRetainedUpscale(track) &&
            deviceLink.value.configured && DeviceLinkStatus.connected.value == false
        ) {
            PlaybackBus.publishError("Device Link disconnected — tap Link to reconnect")
            return UpscaleRequest.Ignored
        }
        val accepted = manager.setEnabled(track, enabled)
        if (accepted) return UpscaleRequest.Started
        if (!OnDeviceApollo.AVAILABLE) {
            // Nothing to unlock: this build only restores over Device Link.
            PlaybackBus.publishError("Restore needs Device Link in this build — or pick Auto / Enhance")
            return UpscaleRequest.Ignored
        }
        val taps = countUnlockTap()
        return UpscaleRequest.Locked(taps, ON_DEVICE_UNLOCK_TAPS)
    }

    /** A tap on the locked "upscale on device" control. Returns the new count. */
    fun tapOnDeviceUpscale(): Int = countUnlockTap()

    private fun countUnlockTap(): Int {
        val next = (_upscaleUnlockTaps.value + 1).coerceAtMost(ON_DEVICE_UNLOCK_TAPS)
        _upscaleUnlockTaps.value = next
        if (next >= ON_DEVICE_UNLOCK_TAPS) _showUpscaleWarning.value = true
        return next
    }

    fun dismissUpscaleWarning() {
        _showUpscaleWarning.value = false
        _upscaleUnlockTaps.value = 0
    }

    fun confirmOnDeviceUpscale() {
        val container = (context.applicationContext as PetalApp).container
        container.upscaleManager.onDeviceUnlocked = true
        _showUpscaleWarning.value = false
        _upscaleUnlockTaps.value = 0
        scope.launch { settingsRepo.setOnDeviceUpscaleUnlocked(true) }
    }

    fun setUpscaleWidth(value: Float) {
        val clean = value.coerceIn(0f, 1.5f)
        (context.applicationContext as PetalApp).container.upscaleManager.masteringWidth = clean
        scope.launch { settingsRepo.setUpscaleWidth(clean) }
    }

    fun setArt2x(v: Boolean) {
        org.rab1d.petal.music.data.ArtUpscaler.enabled = v
        scope.launch { settingsRepo.setArt2x(v) }
    }

    fun setUpscaleMode(mode: UpscaleMode) {
        (context.applicationContext as PetalApp).container.upscaleManager.mode = mode
        scope.launch { settingsRepo.setUpscaleMode(mode) }
    }

    fun cancelUpscale(track: Track) {
        (context.applicationContext as PetalApp).container.upscaleManager.cancel(track)
    }

    fun deleteUpscale(track: Track) {
        (context.applicationContext as PetalApp).container.upscaleManager.deleteUpscale(track)
    }

    fun upscaleModelReady(): Boolean =
        (context.applicationContext as PetalApp).container.upscaleManager.modelReady()

    fun isDownloaded(track: Track): Boolean =
        (context.applicationContext as PetalApp).container.downloads.isDownloaded(track.key())

    fun downloadedTracks(): List<Track> =
        (context.applicationContext as PetalApp).container.downloads.tracks()

    private val _offline = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True when there is no default network — the app falls back to offline. */
    val offline: kotlinx.coroutines.flow.StateFlow<Boolean> = _offline

    init {
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            // Callbacks only fire on change, so seed the current state or a cold
            // boot with no network looks online forever.
            _offline.value = cm?.activeNetwork == null
            cm?.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        _offline.value = false
                    }

                    override fun onLost(network: android.net.Network) {
                        _offline.value = true
                    }
                },
            )
        }
    }

    fun downloadSizeBytes(): Long =
        (context.applicationContext as PetalApp).container.downloads.totalBytes()

    val playlists = settingsRepo.playlists.stateIn(scope, SharingStarted.Eagerly, emptyList<Playlist>())

    val deviceLink = settingsRepo.deviceLink
        .stateIn(scope, SharingStarted.Eagerly, DeviceLinkSettings())

    fun setDeviceLinkEnabled(v: Boolean) = scope.launch { settingsRepo.setDeviceLinkEnabled(v) }

    fun setDeviceLinkEndpoint(host: String, port: Int) =
        scope.launch { settingsRepo.setDeviceLinkEndpoint(host, port) }

    /** Link connection state (null = unknown). */
    val linkConnected: kotlinx.coroutines.flow.StateFlow<Boolean?> = DeviceLinkStatus.connected

    /** Probe the linked desktop; returns its name or null. */
    suspend fun probeDeviceLink(host: String, port: Int): String? {
        val name = kotlinx.coroutines.withContext(Dispatchers.IO) { DeviceLinkClient.probe(host, port) }
        DeviceLinkStatus.set(name != null)
        return name
    }

    /**
     * Push every saved playlist's metadata to the linked desktop. Only names,
     * artists and source ids cross the link; the PC stores the metadata and
     * may optionally download the audio.
     */
    fun sendPlaylistsToDevice() {
        val cfg = deviceLink.value
        if (!cfg.configured) {
            PlaybackBus.publishError("Device Link is not configured")
            return
        }
        if (DeviceLinkStatus.connected.value == false) {
            // Don't retry a dropped link; the user re-links deliberately.
            PlaybackBus.publishError("Device Link disconnected — tap Link to reconnect")
            return
        }
        scope.launch(Dispatchers.IO) {
            PlaybackBus.publishError("Device Link: gathering playlists…")
            val payloads = LinkedHashMap<String, DeviceLinkClient.Playlist>()
            try {
                // Locally saved playlists.
                playlists.value.forEach { pl ->
                    val tracks = playlistTracks(pl)
                    android.util.Log.i("PetalDeviceLink", "local '${pl.name}' tracks=${tracks.size}")
                    if (tracks.isNotEmpty()) payloads[pl.name] = toPayload(pl.name, tracks)
                }

                val container = appContainer().container

                // Tidal: use the loaded summaries + cache-first track resolution so
                // the link doesn't hammer the network for big playlists.
                if (runCatching { container.tidal.isLoggedIn() }.getOrDefault(false)) {
                    val summaries = if (tidalLibrary.playlists.isNotEmpty()) {
                        tidalLibrary.playlists
                    } else {
                        runCatching { container.tidal.playlists() }
                            .onFailure { android.util.Log.w("PetalDeviceLink", "tidal playlists failed", it) }
                            .getOrDefault(emptyList())
                    }
                    android.util.Log.i("PetalDeviceLink", "tidal playlists=${summaries.size}")
                    for (summary in summaries) {
                        val tracks = runCatching { sourcePlaylistTracks(Source.TIDAL, summary.id) }
                            .onFailure { android.util.Log.w("PetalDeviceLink", "tidal '${summary.name}' failed", it) }
                            .getOrDefault(emptyList())
                            .playable()
                        android.util.Log.i("PetalDeviceLink", "tidal '${summary.name}' tracks=${tracks.size}")
                        if (tracks.isNotEmpty()) payloads[summary.name] = toPayload(summary.name, tracks)
                    }
                }

                // Any other signed-in source with a playlist surface.
                container.providers.filterKeys { it != Source.LOCAL && it != Source.TIDAL }
                    .forEach { (source, provider) ->
                        if (!runCatching { provider.isLoggedIn() }.getOrDefault(false)) return@forEach
                        val summaries = runCatching { provider.playlists() }
                            .getOrDefault(emptyList())
                        android.util.Log.i("PetalDeviceLink", "$source playlists=${summaries.size}")
                        for (summary in summaries) {
                            val tracks = runCatching { provider.playlistTracks(summary.id) }
                                .getOrDefault(emptyList())
                                .playable()
                            if (tracks.isNotEmpty()) {
                                payloads[summary.name] = toPayload(summary.name, tracks)
                            }
                        }
                    }

                android.util.Log.i("PetalDeviceLink", "payloads=${payloads.size}")
                if (payloads.isEmpty()) {
                    PlaybackBus.publishError("No playlists found — save one or sign in to a source")
                    return@launch
                }
                val list = payloads.values.toList()
                val res = DeviceLinkClient.sendPlaylists(cfg.host, cfg.port, list)
                DeviceLinkStatus.set(true)
                PlaybackBus.publishError(
                    "Device Link: sent ${res.playlists} playlists · ${res.accepted} tracks to ${cfg.host}",
                )
            } catch (e: Exception) {
                android.util.Log.w("PetalDeviceLink", "send failed", e)
                DeviceLinkStatus.set(false)
                PlaybackBus.publishError("Device Link failed: ${e.message}")
            }
        }
    }

    private fun toPayload(name: String, tracks: List<Track>): DeviceLinkClient.Playlist =
        DeviceLinkClient.Playlist(
            name = name,
            tracks = tracks.map { t ->
                DeviceLinkClient.Track(
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                    source = t.source.id,
                    durationMs = t.durationMs,
                    key = t.key(),
                )
            },
        )

    // ---- playlists ---------------------------------------------------------------

    /**
     * Resolve a saved playlist's tracks. New playlists carry full metadata;
     * legacy key-only playlists fall back to the metadata cache, downloads and
     * the device library so they never render empty.
     */
    fun playlistTracks(playlist: Playlist): List<Track> {
        if (playlist.tracks.isNotEmpty()) return playlist.playable()
        val container = (context.applicationContext as PetalApp).container
        val local = library.all()
        return playlist.trackKeys.mapNotNull { k ->
            TrackMetadataCache.get(context.filesDir, k)
                ?: container.downloads.entry(k)?.toTrack()
                ?: local.firstOrNull { it.key() == k }
        }.playable()
    }

    fun addToPlaylist(playlistId: String, track: Track) =
        scope.launch { settingsRepo.addToPlaylist(playlistId, listOf(track)) }

    fun addToPlaylist(playlistId: String, tracks: List<Track>) =
        scope.launch { settingsRepo.addToPlaylist(playlistId, tracks) }

    /** Create a playlist containing [tracks] (used by the picker and "new"). */
    fun createPlaylist(name: String, tracks: List<Track> = emptyList()) =
        scope.launch { settingsRepo.createPlaylist(name, tracks) }

    fun removeFromPlaylist(playlistId: String, track: Track) =
        scope.launch { settingsRepo.removeFromPlaylist(playlistId, track.key()) }

    fun deletePlaylist(playlistId: String) = scope.launch { settingsRepo.deletePlaylist(playlistId) }

    fun renamePlaylist(playlistId: String, name: String) =
        scope.launch { settingsRepo.renamePlaylist(playlistId, name) }

    fun playlistSources(): List<Source> = listOf(Source.LOCAL) + if (offline.value) emptyList() else
        appContainer().container.providers.values.filter { it.supportsPlaylistEditing && it.isLoggedIn() }.map { it.source }

    fun sourceLoggedIn(source: Source): Boolean = appContainer().container.providers[source]?.isLoggedIn() == true

    fun libraryFor(source: Source): TidalLibrary =
        if (source == Source.TIDAL) tidalLibrary else sourceLibraries[source] ?: TidalLibrary()

    fun playlistSummaries(source: Source): List<PlaylistSummary> = if (source == Source.LOCAL) {
        playlists.value.map { PlaylistSummary(it.id, source, it.name,
            it.tracks.size.coerceAtLeast(it.trackKeys.size), it.tracks.firstOrNull()?.artUri, editable = true) }
    } else libraryFor(source).playlists

    private fun playlistProvider(source: Source) = appContainer().container.providers[source]
        ?.takeIf { it.isLoggedIn() && it.supportsPlaylistEditing }
        ?: throw SourceException("Sign in to ${source.label} to edit playlists")

    private suspend fun destinationTracks(source: Source, tracks: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        if (source == Source.LOCAL) return@withContext tracks.playable()
        val provider = playlistProvider(source)
        tracks.playable().map { track ->
            if (track.source == source) track else {
                val candidates = provider.search("${track.title} ${track.artist}").tracks
                equivalentTrack(track, candidates)
                    ?: throw SourceException("No matching ${source.label} recording for ${track.title} — ${track.artist}")
            }
        }
    }

    var playlistRevision by mutableStateOf(0)
        private set

    private fun playlistChanged(source: Source) {
        playlistRevision++
        if (source == Source.YT_MUSIC) {
            // YouTube's library index briefly lags successful playlist writes.
            scope.launch {
                kotlinx.coroutines.delay(1500)
                playlistRevision++
                loadSourceLibrary(source, force = true)
            }
        } else if (source != Source.LOCAL) loadSourceLibrary(source, force = true)
    }

    private fun updatePlaylistSummaries(source: Source, update: (List<PlaylistSummary>) -> List<PlaylistSummary>) {
        val library = libraryFor(source)
        val changed = library.copy(playlists = update(library.playlists))
        if (source == Source.TIDAL) tidalLibrary = changed
        else sourceLibraries = sourceLibraries + (source to changed)
    }

    suspend fun addTracksToPlaylist(source: Source, id: String, tracks: List<Track>) {
        val resolved = destinationTracks(source, tracks)
        if (source == Source.LOCAL) settingsRepo.addToPlaylist(id, resolved)
        else playlistProvider(source).addPlaylistTracks(id, resolved)
        if (source != Source.LOCAL) invalidatePlaylist(source, id)
        playlistChanged(source)
    }

    suspend fun createSourcePlaylist(source: Source, name: String, tracks: List<Track> = emptyList()) {
        val cleanName = name.trim().takeIf { it.isNotBlank() } ?: throw SourceException("Enter a playlist name")
        val resolved = destinationTracks(source, tracks)
        if (source == Source.LOCAL) settingsRepo.createPlaylist(cleanName, resolved)
        else {
            val provider = playlistProvider(source)
            val created = provider.createPlaylist(cleanName)
            updatePlaylistSummaries(source) { list -> list.filterNot { it.id == created.id } + created }
            try { if (resolved.isNotEmpty()) provider.addPlaylistTracks(created.id, resolved) }
            finally { playlistChanged(source) }
            return
        }
        playlistChanged(source)
    }

    suspend fun renameSourcePlaylist(source: Source, id: String, name: String) {
        val cleanName = name.trim().takeIf { it.isNotBlank() } ?: throw SourceException("Enter a playlist name")
        if (source == Source.LOCAL) settingsRepo.renamePlaylist(id, cleanName)
        else playlistProvider(source).renamePlaylist(id, cleanName)
        if (source != Source.LOCAL) updatePlaylistSummaries(source) { list ->
            list.map { if (it.id == id) it.copy(name = cleanName) else it }
        }
        settingsRepo.updateRecentPlaylist(source, id, cleanName)
        playlistChanged(source)
    }

    suspend fun deleteSourcePlaylist(source: Source, id: String) {
        if (source == Source.LOCAL) settingsRepo.deletePlaylist(id)
        else playlistProvider(source).deletePlaylist(id)
        if (source != Source.LOCAL) updatePlaylistSummaries(source) { it.filterNot { playlist -> playlist.id == id } }
        settingsRepo.updateRecentPlaylist(source, id)
        invalidatePlaylist(source, id)
        playlistChanged(source)
    }

    suspend fun removeSourcePlaylistTrack(source: Source, id: String, track: Track) {
        if (source == Source.LOCAL) settingsRepo.removeFromPlaylist(id, track.key())
        else playlistProvider(source).removePlaylistTrack(id, track)
        invalidatePlaylist(source, id)
        playlistChanged(source)
    }

    private fun invalidatePlaylist(source: Source, id: String) {
        org.rab1d.petal.music.data.TrackListCache.invalidate(context.filesDir, "playlist-${source.id}-$id")
    }

    fun refreshPlaylist(source: Source, id: String) {
        invalidatePlaylist(source, id)
        playlistRevision++
        loadSourceLibrary(source, force = true)
    }

    var client: PlaybackClient? = null
        private set

    var libraryReady by mutableStateOf(false)
        private set

    var searchResults by mutableStateOf(SearchResults())
        private set

    var searching by mutableStateOf(false)
        private set
    var searchErrors by mutableStateOf<Map<Source, String>>(emptyMap())
        private set
    private var searchJob: Job? = null

    data class TidalLibrary(
        val loading: Boolean = false,
        val playlists: List<org.rab1d.petal.music.model.PlaylistSummary> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val error: String? = null,
    )

    var tidalLibrary by mutableStateOf(TidalLibrary())
        private set
    var sourceLibraries by mutableStateOf<Map<Source, TidalLibrary>>(emptyMap())
        private set
    private val pendingLibraryRefresh = mutableSetOf<Source>()

    var searchSources: Set<Source> by mutableStateOf(emptySet())
        private set
    private var sourcesCustomized = false

    /** True once the streaming sessions have connected (offline loads first). */
    var servicesReady by mutableStateOf(false)
        private set

    init {
        val container = (context.applicationContext as PetalApp).container
        PlaybackClient.connect(context) { c ->
            client = c
        }
        // The container loads downloads + the device library before it touches a
        // network service; mirror that so the UI never waits on a stream.
        scope.launch {
            container.offlineReady.collect { if (it) libraryReady = true }
        }
        scope.launch {
            container.servicesReady.collect { ready ->
                servicesReady = ready
                if (ready) loadTidalLibrary()
            }
        }
    }

    // ---- library -----------------------------------------------------------------

    fun loadTidalLibrary(force: Boolean = false) = loadSourceLibrary(Source.TIDAL, force)

    fun loadSourceLibrary(source: Source, force: Boolean = false) {
        if (source == Source.LOCAL || offline.value) return
        val previous = libraryFor(source)
        if (previous.loading) {
            if (force) pendingLibraryRefresh += source
            return
        }
        if (!force && (previous.playlists.isNotEmpty() || previous.tracks.isNotEmpty())) return
        val provider = appContainer().container.providers[source]?.takeIf { it.isLoggedIn() } ?: return
        fun update(value: TidalLibrary) {
            if (source == Source.TIDAL) tidalLibrary = value
            else sourceLibraries = sourceLibraries + (source to value)
        }
        update(previous.copy(loading = true, error = null))
        val revision = playlistRevision
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var error: String? = null
                fun <T> load(fallback: List<T>, block: suspend () -> List<T>): suspend () -> List<T> = {
                    try { block() } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        android.util.Log.w("PetalLibrary", "${source.label} library failed", e)
                        if (error == null) error = e.message
                        fallback
                    }
                }
                val pl = load(previous.playlists) { provider.playlists() }()
                val tr = load(previous.tracks) { provider.favoriteTracks() }()
                val al = load(previous.albums) { provider.favoriteAlbums() }()
                val ar = load(previous.artists) { provider.favoriteArtists() }()
                TidalLibrary(false, pl, tr, al, ar, error)
            }
            update(if (revision == playlistRevision) result else libraryFor(source).copy(loading = false))
            if (pendingLibraryRefresh.remove(source)) loadSourceLibrary(source, force = true)
        }
    }

    private fun appContainer(): PetalApp = context.applicationContext as PetalApp

    suspend fun sourceAlbumTracks(source: Source, id: String): List<Track> {
        val app = context.applicationContext as PetalApp
        val cacheKey = "album-${source.id}-$id"
        // Offline: the album's downloads are all we have. Do NOT fall through to
        // the provider — DNS just hangs and the tap looks dead.
        if (offline.value) {
            val dl = app.container.downloads.tracksForAlbum(id)
            if (dl.isNotEmpty()) return dl
            return org.rab1d.petal.music.data.TrackListCache.read(app.filesDir, cacheKey).orEmpty()
        }
        // Cache first so reopening an album is instant, then quietly refresh it
        // for next time in the background.
        val cached = org.rab1d.petal.music.data.TrackListCache.read(app.filesDir, cacheKey)
        if (cached != null) {
            scope.launch(Dispatchers.IO) {
                val fresh = runCatching {
                    app.container.providers[source]?.album(id)
                }.getOrNull().orEmpty()
                if (fresh.isNotEmpty()) {
                    org.rab1d.petal.music.data.TrackListCache.write(app.filesDir, cacheKey, fresh)
                }
            }
            return cached
        }
        val fresh = kotlinx.coroutines.withContext(Dispatchers.IO) {
            (runCatching { app.container.providers[source]?.album(id) }
                .onFailure { android.util.Log.w("PetalLibrary", "${source.label} album $id failed", it) }
                .getOrNull()).orEmpty()
        }
        if (fresh.isNotEmpty()) {
            org.rab1d.petal.music.data.TrackListCache.write(app.filesDir, cacheKey, fresh)
        }
        return fresh
    }

    suspend fun sourceArtistTracks(source: Source, id: String): List<Track> {
        if (offline.value) return emptyList()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            (runCatching { appContainer().container.providers[source]?.artistTopTracks(id) }
                .getOrNull()).orEmpty()
        }
    }

    suspend fun sourcePlaylistTracks(source: Source, id: String, force: Boolean = false): List<Track> {
        val app = context.applicationContext as PetalApp
        val cacheKey = "playlist-${source.id}-$id"
        // Offline has no provider to ask, the cache is the whole show. Covers and
        // shuffle both need this to survive without a network.
        val cached = org.rab1d.petal.music.data.TrackListCache.read(app.filesDir, cacheKey)
        if (offline.value) return cached.orEmpty()
        // Cache first, refresh quietly for next time. Makes reopening a playlist
        // and shuffle-tapping it feel instant instead of a network round trip.
        if (cached != null && !force) {
            val revision = playlistRevision
            scope.launch(Dispatchers.IO) {
                try {
                    val fresh = app.container.providers.getValue(source).playlistTracks(id)
                    if (revision == playlistRevision) org.rab1d.petal.music.data.TrackListCache.write(app.filesDir, cacheKey, fresh)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("PetalLibrary", "Playlist refresh failed", e) }
            }
            return cached
        }
        val fresh = try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                app.container.providers.getValue(source).playlistTracks(id)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (cached != null) return cached
            throw e
        }
        org.rab1d.petal.music.data.TrackListCache.write(app.filesDir, cacheKey, fresh)
        return fresh
    }

    /** Play a remote album by resolving it first (click handlers are not suspend). */
    fun playSourceAlbum(source: Source, albumId: String) = scope.launch {
        val tracks = sourceAlbumTracks(source, albumId)
        if (tracks.isNotEmpty()) playTracks(tracks, 0) else complainEmpty()
    }

    /** Say something instead of eating the tap when there is nothing to play. */
    private fun complainEmpty() {
        PlaybackBus.publishError(
            if (offline.value) "Offline — only your downloads can play" else "Couldn't load those tracks",
        )
    }

    fun allTracks(): List<Track> = library.all()
    fun albums(): List<Album> = library.albumList()
    fun artists(): List<Artist> = library.artistList()
    fun albumTracks(albumId: String): List<Track> = library.albumTracks(albumId)
    fun artistTracks(artistId: String): List<Track> = library.artistTracks(artistId)
    fun recentsTracks(): List<Track> = recents.value.mapNotNull { library.track(it) }

    /** Recently opened playlists/albums (drives the merged home). */
    val recentMedia: kotlinx.coroutines.flow.StateFlow<List<org.rab1d.petal.music.data.RecentEntry>> =
        settingsRepo.recentMedia.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addRecentMedia(
        type: String,
        source: Source,
        id: String,
        title: String,
        subtitle: String,
        artUri: String?,
    ) {
        scope.launch {
            settingsRepo.addRecentMedia(
                org.rab1d.petal.music.data.RecentEntry(type, source.id, id, title, subtitle, artUri),
            )
        }
    }

    fun playRecent(entry: org.rab1d.petal.music.data.RecentEntry, shuffle: Boolean = false) = scope.launch {
        val src = Source.fromId(entry.source)
        val list = when (entry.type) {
            "album" -> sourceAlbumTracks(src, entry.id)
            "playlist" -> if (src == Source.LOCAL) playlists.value.firstOrNull { it.id == entry.id }
                ?.let { playlistTracks(it) }.orEmpty() else sourcePlaylistTracks(src, entry.id)
            else -> recentsTracks().filter { it.key() == "${entry.source}/${entry.id}" }
        }
        if (list.isNotEmpty()) playTracks(list, 0, shuffle) else complainEmpty()
    }

    /** Best-known display name for a recent entry (repairs stale "Playlist"). */
    fun recentTitle(entry: org.rab1d.petal.music.data.RecentEntry): String {
        if (entry.title.isNotBlank() && entry.title != "Playlist" && entry.title != "Album") {
            return entry.title
        }
        return when (entry.type) {
            "playlist" -> playlistSummaries(Source.fromId(entry.source)).firstOrNull { it.id == entry.id }?.name
                ?: playlists.value.firstOrNull { it.id == entry.id }?.name
                ?: entry.title
            "album" -> albums().firstOrNull { it.id == entry.id }?.title ?: entry.title
            else -> entry.title
        }
    }

    // ---- search ------------------------------------------------------------------

    fun updateSearchSources(sources: Set<Source>) {
        sourcesCustomized = true
        searchSources = sources
    }

    /** All providers that can actually serve results right now. */
    fun activeSearchSources(): Set<Source> {
        if (sourcesCustomized) return searchSources.filterNot { it == Source.DEEZER }.toSet()
        val container = (context.applicationContext as PetalApp).container
        // Deezer is disabled for now — keep it out of search entirely.
        return container.providers.filterValues { it.isLoggedIn() }.keys
            .filterNot { it == Source.DEEZER }
            .toSet()
            .ifEmpty { setOf(Source.LOCAL) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        searchErrors = emptyMap()
        if (query.isBlank()) {
            searchResults = SearchResults()
            searching = false
            return
        }
        searching = true
        val sources = activeSearchSources()
        val container = (context.applicationContext as PetalApp).container
        searchJob = scope.launch {
            try {
                val results = coroutineScope {
                    sources.map { source -> async(Dispatchers.IO) {
                        val provider = container.providers[source]
                        try {
                            if (provider == null || !provider.isLoggedIn())
                                throw SourceException("Sign in to ${source.label} first")
                            Triple(source, provider.search(query), null as String?)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            android.util.Log.w("PetalSearch", "${source.label} search failed", e)
                            Triple(source, SearchResults(), e.message ?: "Search failed")
                        }
                    } }.awaitAll()
                }
                searchResults = SearchResults(
                    results.flatMap { it.second.tracks }.distinctBy { it.key() },
                    results.flatMap { it.second.albums }.distinctBy { "${it.source}/${it.id}" },
                    results.flatMap { it.second.artists }.distinctBy { "${it.source}/${it.id}" },
                    results.flatMap { it.second.playlists }.distinctBy { "${it.source}/${it.id}" },
                )
                searchErrors = results.mapNotNull { (source, _, error) -> error?.let { source to it } }.toMap()
            } finally { if (isActive) searching = false }
        }
    }

    // ---- transport ---------------------------------------------------------------

    fun playTracks(tracks: List<Track>, start: Int = 0, shuffle: Boolean? = null) {
        // Unknown / unavailable entries never reach the queue or the player.
        val requested = tracks.getOrNull(start)
        val list = tracks.playable()
        if (list.isEmpty()) {
            complainEmpty()
            return
        }
        val safeStart = requested?.let { r ->
            list.indexOfFirst { it.key() == r.key() }.takeIf { it >= 0 }
        } ?: start.coerceIn(0, list.lastIndex)
        // Cache off the main thread — writing the whole list synchronously here
        // was what made "play"/"shuffle" feel slow on big playlists.
        scope.launch(Dispatchers.IO) { TrackMetadataCache.remember(context.filesDir, list) }
        val c = client
        if (c == null || !c.controller.isConnected) {
            // Controller never bound (or got dropped): bind it and retry the tap
            // instead of silently eating it.
            android.util.Log.w("PetalMusic", "playTracks before client ready; retrying after connect")
            PlaybackClient.connect(context) { ready ->
                client = ready
                ready.playTracks(list, safeStart, shuffle)
            }
            return
        }
        c.playTracks(list, safeStart, shuffle)
        scope.launch { settingsRepo.addRecent(list.getOrNull(safeStart)?.key() ?: return@launch) }
    }

    private fun withPlayback(action: (PlaybackClient) -> Unit) {
        PlaybackClient.connect(context) { ready ->
            client = ready
            action(ready)
        }
    }

    fun playPause() = withPlayback { it.playPause() }
    fun next() = withPlayback { it.next() }
    fun previous() = withPlayback { it.previous() }
    fun seekTo(ms: Long) = withPlayback { it.seekTo(ms) }
    fun toggleShuffle() = withPlayback { it.toggleShuffle() }
    fun cycleRepeat() = withPlayback { it.cycleRepeat() }

    /** 0 = off, 1 = loop list, 2 = loop one. */
    fun setRepeat(mode: Int) = withPlayback { it.setRepeat(mode) }
    fun playFromQueue(index: Int) = withPlayback { it.playFromQueue(index) }
    fun removeQueueItem(index: Int) = client?.removeQueueItem(index)
    fun playNext(tracks: List<Track>) = withPlayback { it.playNext(tracks) }
    fun addToQueue(tracks: List<Track>) = withPlayback { it.addToQueue(tracks) }

    // ---- lyrics, credits, artists ----
    private val lyricsRepo by lazy {
        org.rab1d.petal.music.data.LyricsRepository { appContainer().container.providers }
    }
    fun cachedLyrics(track: Track) = lyricsRepo.cached(track)
    suspend fun lyrics(track: Track): org.rab1d.petal.music.model.Lyrics? =
        lyricsRepo.lyrics(track, settings.value.lrclibLyrics && !offline.value)
    fun setLrclibLyrics(v: Boolean) = scope.launch { settingsRepo.setLrclibLyrics(v) }

    /** Credits: the source's, plus the writer from a device file's tags. */
    suspend fun credits(track: Track): List<org.rab1d.petal.music.model.Credit> {
        val fromSource = if (offline.value) emptyList() else
            runCatching { appContainer().container.providers[track.source]?.credits(track) }.getOrNull().orEmpty()
        val writer = track.composer?.let { listOf(org.rab1d.petal.music.model.Credit("Written by", it.split(";", "/").map(String::trim))) }
        return fromSource.ifEmpty { writer.orEmpty() }
    }

    /** Whether [source] can show an artist page right now. */
    fun hasArtistPages(source: Source): Boolean =
        !offline.value && appContainer().container.providers[source]?.hasArtistPages == true ||
            source == Source.LOCAL
    fun moveQueueItem(from: Int, to: Int) = client?.moveQueueItem(from, to)

    // ---- settings ----------------------------------------------------------------

    fun setAutomixEnabled(v: Boolean) = scope.launch { settingsRepo.setAutomixEnabled(v) }
    fun toggleAutomix() = scope.launch { settingsRepo.toggleAutomix() }
    fun setFadeMs(v: Int) = scope.launch { settingsRepo.setFadeMs(v) }
    fun setAutoFade(v: Boolean) = scope.launch { settingsRepo.setAutoFade(v) }
    fun setStyle(v: org.rab1d.petal.music.data.AutomixStyle) = scope.launch { settingsRepo.setStyle(v) }
    fun setAlgorithm(v: org.rab1d.petal.music.data.AutomixAlgorithm) = scope.launch { settingsRepo.setAlgorithm(v) }
    fun setCleanMix(v: Boolean) = scope.launch { settingsRepo.setCleanMix(v) }
    fun setConformIncoming(v: Boolean) = scope.launch { settingsRepo.setConformIncoming(v) }
    fun setGlideBack(v: Boolean) = scope.launch { settingsRepo.setGlideBack(v) }
    fun setLetOutrosPlay(v: Boolean) = scope.launch { settingsRepo.setLetOutrosPlay(v) }
    fun setTempoRide(v: Boolean) = scope.launch { settingsRepo.setTempoRide(v) }
    fun setStemMoves(v: Boolean) = scope.launch { settingsRepo.setStemMoves(v) }
    fun setSmoothShuffle(v: Boolean) = scope.launch { settingsRepo.setSmoothShuffle(v) }
    fun setDjPacing(v: Boolean) = scope.launch { settingsRepo.setDjPacing(v) }
    fun setPreferSystemBridge(v: Boolean) = scope.launch { settingsRepo.setPreferSystemBridge(v) }
    fun setPreserveAlbum(v: Boolean) = scope.launch { settingsRepo.setPreserveAlbum(v) }
    fun setEarlyExit(v: Boolean) = scope.launch { settingsRepo.setEarlyExit(v) }
    fun setInAppMixer(v: Boolean) = scope.launch { settingsRepo.setInAppMixer(v) }
    fun setDynamicColor(v: Boolean) = scope.launch { settingsRepo.setDynamicColor(v) }
    fun setThemeMode(v: String) = scope.launch { settingsRepo.setThemeMode(v) }
    fun setDye(v: String) = scope.launch { settingsRepo.setDye(v) }

    suspend fun spotifyLogin(user: String, pass: String): Boolean {
        val container = context.applicationContext as PetalApp
        val ok = container.container.spotify.login(user, pass)
        return ok
    }

    fun spotifyStartWebLogin(): org.rab1d.petal.music.sources.spotify.LibrespotBridge.WebLoginRequest =
        (context.applicationContext as PetalApp).container.spotify.startWebLogin()

    suspend fun spotifyAwaitWebLogin(
        req: org.rab1d.petal.music.sources.spotify.LibrespotBridge.WebLoginRequest,
    ): org.rab1d.petal.music.sources.spotify.LibrespotBridge.RedirectParse =
        (context.applicationContext as PetalApp).container.spotify.awaitWebLogin(req)

    fun spotifyLoginError(): String =
        (context.applicationContext as PetalApp).container.spotify.lastLoginError

    suspend fun spotifyCompleteWebLogin(
        req: org.rab1d.petal.music.sources.spotify.LibrespotBridge.WebLoginRequest,
        code: String,
    ): Boolean = (context.applicationContext as PetalApp).container.spotify.completeWebLogin(req, code)

    fun spotifyLogout() = (context.applicationContext as PetalApp).container.spotify.logout()

    // ---- YT Music ----------------------------------------------------------------

    fun ytMusicLoggedIn(): Boolean =
        (context.applicationContext as PetalApp).container.ytMusic.isLoggedIn()

    fun ytMusicLogin(cookieHeader: String): Boolean =
        (context.applicationContext as PetalApp).container.ytMusic.login(cookieHeader)

    fun ytMusicLogout() = (context.applicationContext as PetalApp).container.ytMusic.logout()

    suspend fun deezerLogin(arl: String): Boolean =
        (context.applicationContext as PetalApp).container.deezer.login(arl)

    fun tidalStartWebLogin(): org.rab1d.petal.music.sources.tidal.TidalSource.WebLoginRequest =
        (context.applicationContext as PetalApp).container.tidal.startWebLogin()

    fun tidalParseRedirect(url: String): org.rab1d.petal.music.sources.tidal.TidalSource.RedirectParse =
        runCatching {
            (context.applicationContext as PetalApp).container.tidal.parseRedirect(url)
        }.getOrDefault(org.rab1d.petal.music.sources.tidal.TidalSource.RedirectParse.Ignore)

    suspend fun tidalCompleteWebLogin(
        req: org.rab1d.petal.music.sources.tidal.TidalSource.WebLoginRequest,
        code: String,
    ): Boolean = (context.applicationContext as PetalApp).container.tidal.completeWebLogin(req, code)

    suspend fun tidalStartPairing(): org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairing? =
        (context.applicationContext as PetalApp).container.tidal.startPairing()

    suspend fun tidalPollPairing(
        p: org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairing,
    ): org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult =
        (context.applicationContext as PetalApp).container.tidal.pollPairing(p)

    fun spotifyUsername(): String? =
        (context.applicationContext as PetalApp).container.spotify.username

    fun deezerLoggedIn(): Boolean =
        (context.applicationContext as PetalApp).container.deezer.isLoggedIn()

    fun tidalLoggedIn(): Boolean =
        (context.applicationContext as PetalApp).container.tidal.isLoggedIn()

    val discordUser = settingsRepo.discordUser.stateIn(scope, SharingStarted.Eagerly, null as String?)
    val discordEnabled = settingsRepo.discordEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun discordLogin(token: String): Boolean {
        val rpc = org.rab1d.petal.music.data.discord.DiscordRpc(token.trim())
        val username = kotlinx.coroutines.withContext(Dispatchers.IO) { rpc.fetchUsername() }
        rpc.close()
        if (username.isNullOrBlank()) return false
        settingsRepo.setDiscordLogin(token.trim(), username)
        return true
    }

    fun discordLogout() = scope.launch { settingsRepo.setDiscordLogin(null, null) }

    fun setDiscordEnabled(v: Boolean) = scope.launch { settingsRepo.setDiscordEnabled(v) }

    fun tidalLogout() =
        (context.applicationContext as PetalApp).container.tidal.logout()

    fun tidalSetCredentials(id: String, secret: String) {
        val t = (context.applicationContext as PetalApp).container.tidal
        t.overrideClientId = id.trim().ifEmpty { null }
        t.overrideClientSecret = secret.trim().ifEmpty { null }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext as PetalApp
            return PetalViewModel(context, app.container.settings, app.container.library) as T
        }
    }
}
