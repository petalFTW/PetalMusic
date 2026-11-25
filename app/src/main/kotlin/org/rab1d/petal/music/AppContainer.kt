/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import android.content.Context
import kotlinx.coroutines.launch
import org.rab1d.petal.music.data.MediaStoreLibrary
import org.rab1d.petal.music.data.SettingsRepository
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.equivalentTrack
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.sources.LocalSource
import org.rab1d.petal.music.sources.SourceProvider
import org.rab1d.petal.music.sources.deezer.DeezerSource
import org.rab1d.petal.music.sources.spotify.LibrespotBridge
import org.rab1d.petal.music.sources.spotify.SpotifySource
import org.rab1d.petal.music.sources.tidal.TidalSource
import org.rab1d.petal.music.sources.ytmusic.YtMusicSource

/** Manual dependency container — ROM apps stay lean. */
class AppContainer(val app: Context) {
    val settings = SettingsRepository(app)
    val library = MediaStoreLibrary(app)

    val spotify = LibrespotBridge(app)
    val ytMusic = YtMusicSource(app).also { it.init() }
    val deezer = DeezerSource(app)
    val tidal = TidalSource(app)
    val local = LocalSource(library)

    val providers: Map<Source, SourceProvider> = mapOf(
        Source.LOCAL to local,
        Source.SPOTIFY to SpotifySource(spotify),
        Source.YT_MUSIC to ytMusic,
        Source.DEEZER to deezer,
        Source.TIDAL to tidal,
    )

    /** Offline library + serial download queue. */
    val downloads = org.rab1d.petal.music.data.downloads.DownloadStore(app)
    val downloadManager = org.rab1d.petal.music.data.downloads.DownloadManager(
        app, downloads, providers,
    )

    /** On-device bandwidth extension for downloaded tracks. */
    val upscaleModel = org.rab1d.petal.music.data.downloads.upscale.UpscaleModelRepository(app)
    val upscaleManager = org.rab1d.petal.music.data.downloads.upscale.UpscaleManager(
        app, downloads, upscaleModel,
    )

    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    init {
        org.rab1d.petal.music.data.ArtUpscaler.attach(app)
        bgScope.launch {
            settings.upscale.collect { org.rab1d.petal.music.data.ArtUpscaler.enabled = it.art2x }
        }
        // Device Link doubles as the off-device upscaler endpoint.
        bgScope.launch {
            settings.deviceLink.collect { upscaleManager.external = it }
        }
        bgScope.launch {
            settings.upscale.collect {
                upscaleManager.onDeviceUnlocked = it.onDeviceUnlocked
                upscaleManager.masteringWidth = it.width
                upscaleManager.mode = it.mode
            }
        }
    }

    /** Mirrors the upscale queue into an ongoing progress notification. */
    val upscaleNotifier = org.rab1d.petal.music.data.downloads.upscale.UpscaleNotifier(
        app, upscaleManager,
    ).also { it.start() }

    /** Mirrors the queue into an ongoing progress notification. */
    val downloadNotifier = org.rab1d.petal.music.data.downloads.DownloadNotifier(
        app, downloadManager,
    ).also { it.start() }

    private val _offlineReady = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True once downloads and the device library are loaded. */
    val offlineReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _offlineReady

    private val _servicesReady = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True once the streaming sessions have been restored. */
    val servicesReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _servicesReady

    init {
        downloads.load()
        // Offline first: scan the device library and downloads, publish them, and
        // only then connect to any streaming service. Boot stays usable (and fast)
        // even when the network is slow, flaky or absent.
        Thread {
            runCatching { library.refresh() }
            _offlineReady.value = true
            kotlinx.coroutines.runBlocking {
                runCatching { spotify.restore() }
                runCatching { deezer.restore() }
                runCatching { tidal.restore() }
                runCatching { ytMusic.restore() }
            }
            _servicesReady.value = true
            // Downloads saved before explicit/artist id were recorded: refetch
            // their albums once so badges and artist links appear offline too.
            if (tidal.isLoggedIn()) bgScope.launch {
                for (albumId in downloads.albumsMissingMetadata(Source.TIDAL)) {
                    runCatching { downloads.backfillMetadata(tidal.album(albumId)) }
                }
            }
        }.start()
    }

    /** Resolve a Track into a MediaItem; null (plus an error toast) on failure. */
    suspend fun resolveTrack(track: org.rab1d.petal.music.model.Track): androidx.media3.common.MediaItem? =
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { buildItem(track) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: org.rab1d.petal.music.model.SourceException) {
            org.rab1d.petal.music.playback.PlaybackBus.publishError("${track.title}: ${e.message}")
            null
        } catch (e: Exception) {
            org.rab1d.petal.music.playback.PlaybackBus.publishError("Couldn't load ${track.title}")
            null
        }

    /**
     * Spotify Free accounts can browse but not stream, so playback falls back
     * to the same recording on YT Music. [equivalentTrack] guards against
     * covers/remixes; the result keeps the Spotify origin for the UI.
     */
    private val ytMatches = java.util.concurrent.ConcurrentHashMap<String, org.rab1d.petal.music.model.Track>()

    suspend fun resolveYtMusicEquivalent(track: org.rab1d.petal.music.model.Track):
        org.rab1d.petal.music.model.Track? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ytMatches[track.key()]?.let { return@withContext it }
        val cacheKey = "yt-match-${track.key()}"
        org.rab1d.petal.music.data.TrackListCache.read(app.filesDir, cacheKey)
            ?.firstOrNull()?.let {
                ytMatches[track.key()] = it
                return@withContext it
            }
        val ytm = providers[Source.YT_MUSIC] ?: return@withContext null
        val results = runCatching { ytm.search("${track.title} ${track.artist}") }
            .onFailure { android.util.Log.w("PetalFallback", "ytm search failed", it) }
            .getOrNull()?.tracks ?: return@withContext null
        val match = org.rab1d.petal.music.model.equivalentTrack(track, results, durationToleranceMs = 8_000)
        android.util.Log.i("PetalFallback",
            "resolve '${track.title}' results=${results.size} " +
                "top=${results.take(3).joinToString { "'${it.title}' by '${it.artist}' ${it.durationMs}ms" }} match=${match?.id}")
        match?.copy(originSource = org.rab1d.petal.music.model.Source.SPOTIFY)
            ?.also {
                ytMatches[track.key()] = it
                org.rab1d.petal.music.data.TrackListCache.write(app.filesDir, cacheKey, listOf(it))
            }
    }

    fun usesSpotifyPump(track: org.rab1d.petal.music.model.Track?): Boolean =
        track?.source == Source.SPOTIFY && !spotify.freeTier &&
            downloads.entry(track.key())?.playbackFile() == null

    private suspend fun buildItem(track: org.rab1d.petal.music.model.Track): androidx.media3.common.MediaItem =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val t = track
            // Offline first: a downloaded file always wins over streaming, so
            // the same Track object plays with no network.
            val downloaded = downloads.entry(t.key())
            val downloadedFile = downloaded?.playbackFile()
            if (downloaded != null && downloadedFile == null) {
                android.util.Log.w(
                    "PetalOffline",
                    "download index has ${t.key()} but no usable file: ${downloaded.activeFile}; falling back to source",
                )
                downloadManager.enqueue(t)
                org.rab1d.petal.music.playback.PlaybackBus.publishError(
                    "${t.title}: downloaded audio is missing — re-downloading",
                )
            }
            if (downloaded != null && downloadedFile != null) {
                // activeFile is the upscaled copy when the user enabled it,
                // otherwise the original download.
                val file = downloadedFile
                val activePath = file.path
                val isWav = activePath.endsWith(".wav", ignoreCase = true)
                // Repair files downloaded before the sidx fix so they seek too.
                // A WAV needs no sidx.
                if (!isWav) {
                    org.rab1d.petal.music.data.downloads.Mp4Index
                        .ensureSeekable(file, downloaded.durationMs)
                }
                return@withContext androidx.media3.common.MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(file))
                    .setMediaId(t.key())
                    .setMimeType(if (isWav) "audio/wav" else "audio/mp4")
                    .setTag(t)
                    .build()
                    .buildUpon()
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(t.title)
                            .setArtist(t.artist)
                            .setAlbumTitle(t.album)
                            .setArtworkUri(art2x(downloaded.artFile ?: t.artUri))
                            .build(),
                    )
                    .build()
            }
            when (t.source) {
                Source.LOCAL -> {
                    // DATA is flaky on newer Android; fall back to the MediaStore
                    // content uri instead of throwing the song away.
                    val uri = t.localPath?.let { android.net.Uri.fromFile(java.io.File(it)) }
                        ?: android.net.Uri.withAppendedPath(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id,
                        )
                    androidx.media3.common.MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(t.key())
                        .setTag(t)
                        .build()
                }
                Source.SPOTIFY -> {
                    if (!spotify.freeTier) throw IllegalStateException("spotify tracks never enter ExoPlayer")
                    val match = resolveYtMusicEquivalent(t)
                        ?: throw org.rab1d.petal.music.model.SourceException("No YT Music match")
                    // Keep the original queue identity and Spotify cover/metadata.
                    // Only the audio is supplied by YouTube, resolved one song ahead.
                    buildItem(match).buildUpon().setMediaId(t.key()).setTag(t).build()
                }
                else -> {
                    val provider = providers[t.source]
                        ?: throw IllegalStateException("no provider for ${t.source}")
                    when (val handle = provider.stream(t)
                        ?: throw IllegalStateException("unresolvable $t")) {
                        is org.rab1d.petal.music.sources.StreamHandle.Url -> {
                            org.rab1d.petal.music.sources.PetalStreamResolver.register(t.key(), handle)
                            androidx.media3.common.MediaItem.Builder()
                                .setUri(org.rab1d.petal.music.sources.PetalStreamResolver.uri(t.key()))
                                .setMediaId(t.key())
                                .setMimeType(handle.mimeType)
                                .setTag(t)
                                .build()
                        }
                        is org.rab1d.petal.music.sources.StreamHandle.Blowfish -> {
                            org.rab1d.petal.music.sources.PetalStreamResolver.register(t.key(), handle)
                            androidx.media3.common.MediaItem.Builder()
                                .setUri(org.rab1d.petal.music.sources.PetalStreamResolver.uri(t.key()))
                                .setMediaId(t.key())
                                .setMimeType("audio/mpeg")
                                .setTag(t)
                                .build()
                        }
                        is org.rab1d.petal.music.sources.StreamHandle.Mpd -> {
                            org.rab1d.petal.music.sources.PetalStreamResolver.register(t.key(), handle)
                            androidx.media3.common.MediaItem.Builder()
                                .setUri(org.rab1d.petal.music.sources.PetalStreamResolver.uri(t.key()))
                                .setMediaId(t.key())
                                .setMimeType("application/dash+xml")
                                .setTag(t)
                                .build()
                        }
                    }
                }
            }.buildUpon().setMediaMetadata(metadataFor(t)).build()
        }

    /**
     * Artwork for the media session (notification, lock screen, island): the 2x
     * copy when it is ready within a moment, otherwise the original while the 2x
     * copy is produced for next time.
     */
    private fun art2x(source: String?): android.net.Uri? {
        if (source == null) return null
        org.rab1d.petal.music.data.ArtUpscaler.await(source, 2500)?.let { return it }
        return if (source.startsWith("/")) android.net.Uri.fromFile(java.io.File(source)) else android.net.Uri.parse(source)
    }

    private fun metadataFor(track: org.rab1d.petal.music.model.Track): androidx.media3.common.MediaMetadata =
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(art2x(track.artUri))
            .setTrackNumber(track.trackNumber)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
}
