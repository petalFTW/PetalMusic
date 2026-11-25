/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.sources.SourceProvider

/**
 * Serial download queue with progress. Sources that can fetch offline media
 * implement [DownloadableSource]; everything else is reported as unsupported.
 *
 * Downloads are kept one-at-a-time on purpose: Tidal serves the same CDN, and
 * a serial queue keeps segment tokens fresh and progress meaningful.
 */
class DownloadManager(
    private val context: Context,
    private val store: DownloadStore,
    private val providers: Map<Source, SourceProvider>,
) {
    companion object {
        const val TAG = "PetalMusic/Downloads"
    }

    enum class State { QUEUED, DOWNLOADING, DONE, FAILED, UNSUPPORTED }

    data class Progress(
        val key: String,
        val title: String,
        val state: State,
        val fraction: Float = 0f,
        val quality: String = "",
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    private val queue = ArrayDeque<Track>()

    @Synchronized
    fun enqueue(track: Track) {
        if (store.isDownloaded(track.key())) return
        if (_progress.value[track.key()]?.state in listOf(State.QUEUED, State.DOWNLOADING)) return
        update(track, Progress(track.key(), track.title, State.QUEUED))
        queue.addLast(track)
        scope.launch { drain() }
    }

    fun enqueueAll(tracks: List<Track>) {
        tracks.forEach { enqueue(it) }
    }

    @Synchronized
    fun cancel(track: Track) {
        queue.removeAll { it.key() == track.key() }
        _progress.value = _progress.value - track.key()
    }

    fun delete(track: Track) = store.remove(track.key())

    private suspend fun drain() = queueMutex.withLock {
        while (true) {
            val track = synchronized(this@DownloadManager) { queue.removeFirstOrNull() } ?: break
            val provider = providers[track.source]
            val downloadable = provider as? DownloadableSource
            if (downloadable == null || !provider.isLoggedIn()) {
                update(
                    track,
                    Progress(
                        track.key(), track.title, State.UNSUPPORTED,
                        error = "This source can't download",
                    ),
                )
                continue
            }
            update(track, Progress(track.key(), track.title, State.DOWNLOADING))
            try {
                val entry = downloadable.download(track, store) { fraction, quality ->
                    update(
                        track,
                        Progress(track.key(), track.title, State.DOWNLOADING, fraction, quality),
                    )
                }
                store.put(entry)
                update(
                    track,
                    Progress(track.key(), track.title, State.DONE, 1f, entry.quality),
                )
                Log.i(TAG, "downloaded ${track.title} (${entry.quality}, ${entry.bytes / 1024} KiB)")
            } catch (e: Exception) {
                Log.w(TAG, "download failed for ${track.key()}: ${e.message}")
                update(
                    track,
                    Progress(
                        track.key(), track.title, State.FAILED,
                        error = e.message ?: "download failed",
                    ),
                )
            }
        }
    }

    @Synchronized
    private fun update(track: Track, progress: Progress) {
        _progress.value = _progress.value + (track.key() to progress)
    }
}

/** Implemented by sources that can produce offline files. */
interface DownloadableSource {
    /**
     * Fetch [track] into [store]'s directory at the best quality the account
     * allows. [onProgress] reports 0..1 plus a quality label.
     */
    suspend fun download(
        track: Track,
        store: DownloadStore,
        onProgress: (fraction: Float, quality: String) -> Unit,
    ): DownloadedTrack
}
