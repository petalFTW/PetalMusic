/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import java.io.File

/** A track that lives on disk, with the facts worth showing offline. */
@Serializable
data class DownloadedTrack(
    val id: String,
    val source: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val file: String,
    val artFile: String? = null,
    val quality: String = "",
    val bytes: Long = 0,
    val downloadedAt: Long = 0,
    /** Bandwidth-extended copy of [file], when one has been generated. */
    val upscaledFile: String? = null,
    /** Quality label of [upscaledFile] (e.g. "Hi-Fi · 44.1 kHz WAV"). */
    val upscaledQuality: String? = null,
    val upscaledBytes: Long = 0,
    /** Processing revision, so older generated copies can be rebuilt on enable. */
    val upscaleRevision: Int = 0,
    /** Stereo-width setting used when [upscaledFile] was generated (-1 = old/unknown). */
    val upscaleWidth: Float = -1f,
    /**
     * When true, playback uses [upscaledFile] in place of [file]. Disabling it
     * reverts the entry to the original download without deleting the upscale.
     */
    val upscaleEnabled: Boolean = false,
    val explicit: Boolean = false,
    val artistId: String? = null,
    val composer: String? = null,
) {
    /** The file ExoPlayer should actually play right now. */
    val activeFile: String
        get() = if (upscaleEnabled && upscaledFile != null) upscaledFile else file

    /** Prefer the selected copy, but never hand playback a missing/empty file. */
    fun playbackFile(): File? = listOf(activeFile, file).distinct()
        .map(::File).firstOrNull { it.isFile && it.length() > 0 }

    /** A retained copy is a playback choice, regardless of current processing settings. */
    fun retainedUpscaleFile(): File? = upscaledFile?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0 }

    val hasUpscale: Boolean get() = upscaledFile != null

    /** Quality label reflecting whether the upscale is currently active. */
    val activeQuality: String
        get() = if (upscaleEnabled && upscaledFile != null) {
            upscaledQuality ?: quality
        } else {
            quality
        }

    fun toTrack(): Track = Track(
        id = id,
        source = Source.fromId(source),
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        trackNumber = trackNumber,
        artUri = artFile?.let { if (it.startsWith("http")) it else "file://$it" },
        localPath = activeFile,
        explicit = explicit,
        artistId = artistId,
        composer = composer,
    )
}

/**
 * Persisted index of downloaded tracks. This is the offline library: playback
 * prefers these files, so downloaded Tidal content plays with no network (and
 * no Tidal session) at all.
 */
class DownloadStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val indexFile: File get() = File(context.filesDir, "downloads/index.json")

    private val _downloads = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads

    /** Root directory for a source's audio files. */
    fun dirFor(source: Source): File =
        File(context.filesDir, "downloads/${source.id}").apply { mkdirs() }

    @Synchronized
    fun load() {
        _downloads.value = runCatching {
            val index = android.util.AtomicFile(indexFile)
            val input = try { index.openRead() }
            catch (e: java.io.FileNotFoundException) { return@runCatching emptyList<DownloadedTrack>() }
            input.bufferedReader().use {
                json.decodeFromString<List<DownloadedTrack>>(it.readText())
            }
        }.onFailure { android.util.Log.w("PetalOffline", "download index read failed", it) }
            .getOrDefault(emptyList())
    }

    private fun persist(list: List<DownloadedTrack>) {
        indexFile.parentFile?.mkdirs()
        // Closing/killing the app mid-write must not erase the offline library.
        val index = android.util.AtomicFile(indexFile)
        var output: java.io.FileOutputStream? = null
        try {
            val bytes = json.encodeToString(list).toByteArray(Charsets.UTF_8)
            output = index.startWrite()
            output.write(bytes)
            index.finishWrite(output)
        } catch (e: Exception) {
            index.failWrite(output)
            android.util.Log.w("PetalOffline", "download index write failed", e)
            throw e
        }
        _downloads.value = list
    }

    @Synchronized
    fun put(entry: DownloadedTrack) {
        val next = _downloads.value.filterNot { it.key() == entry.key() } + entry
        persist(next)
    }

    @Synchronized
    fun remove(trackKey: String) {
        val entry = _downloads.value.firstOrNull { it.key() == trackKey }
        entry?.let { runCatching { File(it.file).delete() } }
        entry?.artFile?.let { runCatching { File(it).delete() } }
        entry?.upscaledFile?.let { runCatching { File(it).delete() } }
        persist(_downloads.value.filterNot { it.key() == trackKey })
    }

    @Synchronized
    fun clearAll() {
        _downloads.value.forEach { entry ->
            runCatching { File(entry.file).delete() }
            entry.artFile?.let { runCatching { File(it).delete() } }
            entry.upscaledFile?.let { runCatching { File(it).delete() } }
        }
        persist(emptyList())
    }

    /** Record a finished upscale and switch playback over to it. */
    @Synchronized
    fun setUpscaled(
        trackKey: String,
        file: String,
        quality: String,
        bytes: Long,
        revision: Int = 0,
        width: Float = -1f,
    ) {
        val entry = _downloads.value.firstOrNull { it.key() == trackKey } ?: return
        replace(
            entry.copy(
                upscaledFile = file,
                upscaledQuality = quality,
                upscaledBytes = bytes,
                upscaleRevision = revision,
                upscaleWidth = width,
                upscaleEnabled = true,
            ),
        )
    }

    /** Flip playback between the upscaled copy and the original download. */
    @Synchronized
    fun setUpscaleEnabled(trackKey: String, enabled: Boolean) {
        val entry = _downloads.value.firstOrNull { it.key() == trackKey } ?: return
        if (entry.upscaleEnabled == enabled) return
        replace(entry.copy(upscaleEnabled = enabled && entry.upscaledFile != null))
    }

    /** Drop the upscaled copy and revert to the original download. */
    @Synchronized
    fun clearUpscale(trackKey: String) {
        val entry = _downloads.value.firstOrNull { it.key() == trackKey } ?: return
        entry.upscaledFile?.let { runCatching { File(it).delete() } }
        replace(
            entry.copy(
                upscaledFile = null,
                upscaledQuality = null,
                upscaledBytes = 0,
                upscaleRevision = 0,
                upscaleWidth = -1f,
                upscaleEnabled = false,
            ),
        )
    }

    private fun replace(entry: DownloadedTrack) {
        persist(_downloads.value.filterNot { it.key() == entry.key() } + entry)
    }

    /** Entries saved before explicit/artist id were kept, grouped by album to refetch. */
    fun albumsMissingMetadata(source: Source): Set<String> = _downloads.value
        .filter { it.source == source.id && it.artistId == null }
        .mapNotNull { it.albumId }
        .toSet()

    /**
     * Fill explicit / artist id / composer on older entries from fresh source
     * metadata. Only missing facts are added; nothing already stored changes.
     */
    @Synchronized
    fun backfillMetadata(tracks: List<Track>) {
        val fresh = tracks.associateBy { it.key() }
        var changed = false
        val next = _downloads.value.map { entry ->
            val t = fresh[entry.key()] ?: return@map entry
            if (entry.artistId != null || t.artistId == null) return@map entry
            changed = true
            entry.copy(
                explicit = entry.explicit || t.explicit,
                artistId = t.artistId,
                composer = entry.composer ?: t.composer,
            )
        }
        if (changed) persist(next)
    }

    fun entry(trackKey: String): DownloadedTrack? = _downloads.value.firstOrNull { it.key() == trackKey }

    fun isDownloaded(trackKey: String): Boolean = entry(trackKey)?.playbackFile() != null

    fun tracks(): List<Track> = _downloads.value.map { it.toTrack() }

    fun tracksForAlbum(albumId: String): List<Track> =
        _downloads.value.filter { it.albumId == albumId }.sortedBy { it.trackNumber }.map { it.toTrack() }

    fun totalBytes(): Long = _downloads.value.sumOf { it.bytes }

    private fun DownloadedTrack.key(): String = "${source}/${id}"
}
