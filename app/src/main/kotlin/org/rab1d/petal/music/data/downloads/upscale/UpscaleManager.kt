/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

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
import org.rab1d.petal.music.data.downloads.DownloadStore
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import java.io.File

/** Thrown internally when a running upscale is cancelled. */
class UpscaleCancelledException : Exception("upscale cancelled")

/**
 * Serial queue that bandwidth-extends downloaded tracks on device.
 *
 * Upscaling is heavy (a ~107 MB model, per-segment inference) so jobs run one
 * at a time with progress, exactly like [DownloadStore]'s download queue. A
 * finished upscale is written beside the original download and flagged as the
 * active file; disabling it only flips that flag, so reverting is instant and
 * the original download is never touched.
 */
class UpscaleManager(
    private val context: Context,
    private val store: DownloadStore,
    private val repository: UpscaleModelRepository,
) {
    companion object {
        const val TAG = "PetalMusic/Upscale"

        /** Bumped whenever the Apollo + mastering pipeline changes. */
        const val PROCESSING_REVISION = 2
    }

    enum class State { QUEUED, MODEL, PROCESSING, DONE, FAILED }

    data class Progress(
        val key: String,
        val title: String,
        val state: State,
        val fraction: Float = 0f,
        val stage: String = "",
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val queue = ArrayDeque<Track>()

    /** Keys the user asked to stop; polled between segments/downloads. */
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    @Volatile
    private var engine: RestoreEngine? = null

    /**
     * Device Link settings. When configured, upscaling is offloaded to the
     * linked desktop (its GPU/CPU) instead of the on-device model.
     */
    @Volatile
    var external: org.rab1d.petal.music.data.DeviceLinkSettings =
        org.rab1d.petal.music.data.DeviceLinkSettings()

    /** On-device inference is locked until the user deliberately opts in. */
    @Volatile
    var onDeviceUnlocked: Boolean = false

    /** Unlocked and actually shipped in this build (not in the F-Droid flavor). */
    private val apolloOnDevice: Boolean get() = onDeviceUnlocked && OnDeviceApollo.AVAILABLE

    /** Adaptive stereo width for the finishing stage (0..1.5). */
    @Volatile
    var masteringWidth: Float = 0.5f

    /** AUTO routes by measured spectrum; RESTORE forces Apollo; ENHANCE is DSP only. */
    @Volatile
    var mode: org.rab1d.petal.music.data.UpscaleMode = org.rab1d.petal.music.data.UpscaleMode.AUTO

    /** Ready to upscale: either a local model, or a configured Device Link. */
    fun modelReady(): Boolean = external.configured || (apolloOnDevice && repository.isReady())

    fun hasRetainedUpscale(track: Track): Boolean =
        store.entry(track.key())?.retainedUpscaleFile() != null

    /**
     * Turn the upscale on or off for [track]. Turning it off is instant and
     * reverts playback to the original download. Turning it on uses an existing
     * upscale immediately, or queues one.
     *
     * @return true when the request was accepted. False means a new on-device
     * pass was requested while the feature is still locked; the caller should
     * route the user through the unlock gesture.
     */
    fun setEnabled(track: Track, enabled: Boolean): Boolean {
        if (!enabled) {
            cancelled.add(track.key())
            queue.removeAll { it.key() == track.key() }
            _progress.value = _progress.value - track.key()
            store.setUpscaleEnabled(track.key(), false)
            return true
        }
        cancelled.remove(track.key())
        val entry = store.entry(track.key()) ?: return false
        if (entry.retainedUpscaleFile() != null) {
            _progress.value = _progress.value - track.key()
            store.setUpscaleEnabled(track.key(), true)
            return true
        }
        // AUTO/ENHANCE may finish with the light DSP path without unlocking
        // Apollo. RESTORE is the only mode that always needs the model.
        if (!external.configured && !apolloOnDevice &&
            mode == org.rab1d.petal.music.data.UpscaleMode.RESTORE
        ) return false
        store.setUpscaleEnabled(track.key(), false)
        enqueue(track)
        return true
    }

    private fun enqueue(track: Track) {
        if (!store.isDownloaded(track.key())) return
        val active = _progress.value[track.key()]?.state
        if (active == State.QUEUED || active == State.MODEL || active == State.PROCESSING) return
        cancelled.remove(track.key())
        update(track, Progress(track.key(), track.title, State.QUEUED))
        queue.addLast(track)
        scope.launch { drain() }
    }

    /** Stop a running/queued upscale. Partial output is discarded. */
    fun cancel(track: Track) = cancelKey(track.key())

    /** Stop a running/queued upscale by key (used by the notification action). */
    fun cancelKey(key: String) {
        cancelled.add(key)
        queue.removeAll { it.key() == key }
        _progress.value = _progress.value - key
    }

    /** Delete the upscaled copy and revert to the original download. */
    fun deleteUpscale(track: Track) {
        cancelled.add(track.key())
        queue.removeAll { it.key() == track.key() }
        _progress.value = _progress.value - track.key()
        store.clearUpscale(track.key())
    }

    private suspend fun drain() = mutex.withLock {
        while (queue.isNotEmpty()) {
            process(queue.removeFirst())
        }
    }

    private suspend fun process(track: Track) {
        val key = track.key()
        val entry = store.entry(key) ?: return
        val source = File(entry.file)
        if (!source.isFile) {
            fail(track, "downloaded file is missing")
            return
        }
        val shouldCancel: () -> Boolean = { cancelled.contains(key) }
        val outDir = store.dirFor(Source.fromId(entry.source))
        val out = File(outDir, "${entry.id}.upscaled.wav")
        val part = File(outDir, "${entry.id}.upscaled.part.wav")
        try {
            val worker = external
            val result = if (worker.configured) {
                // Off-device: the linked desktop does the heavy lifting.
                update(track, Progress(key, track.title, State.PROCESSING, 0f, "device link · ${worker.host}"))
                try {
                    val result = org.rab1d.petal.music.data.link.DeviceUpscaleClient.upscale(
                        source, part, worker.host, worker.port, masteringWidth, shouldCancel,
                    ) { f ->
                        if (!shouldCancel()) {
                            // The desktop may spend a long time processing
                            // before it starts streaming the response.
                            val stage = if (f > 0.72f) {
                                "processing on ${worker.host}"
                            } else {
                                "device link · ${worker.host}"
                            }
                            update(track, Progress(key, track.title, State.PROCESSING, f, stage))
                        }
                    }
                    org.rab1d.petal.music.data.link.DeviceLinkStatus.set(true)
                    val engine = result.engine?.takeIf { it.isNotBlank() } ?: "Device Link"
                    result.bytes to "Hi-Fi · $engine (${worker.host})"
                } catch (e: Exception) {
                    // Latch the link as down so it isn't retried automatically.
                    org.rab1d.petal.music.data.link.DeviceLinkStatus.set(false)
                    throw e
                }
            } else {
                // Analyse first: a FLAC that is really a transcoded low-bandwidth
                // source must be routed like lossy audio. Full-band audio never
                // needs a generative model.
                val config = repository.readConfig()
                update(track, Progress(key, track.title, State.PROCESSING, 0f, "analysing"))
                val tempPcm = File.createTempFile("petal-${entry.id}-", ".pcm", context.cacheDir)
                try {
                    val decoded = PcmDecoder.decode(source, tempPcm, config.sampleRate, config.inChannels)
                    if (decoded.frames <= 0) throw java.io.IOException("no audio decoded")
                    val profile = AudioAnalyzer.analyze(
                        tempPcm, decoded.frames, config.sampleRate, config.inChannels, shouldCancel,
                    )
                    if (shouldCancel()) throw UpscaleCancelledException()
                    Log.i(TAG, "profile $key: ${profile.summary()}")
                    var restore = when (mode) {
                        org.rab1d.petal.music.data.UpscaleMode.ENHANCE -> false
                        org.rab1d.petal.music.data.UpscaleMode.RESTORE -> true
                        org.rab1d.petal.music.data.UpscaleMode.AUTO -> !profile.isFullBand
                    }
                    if (profile.isSilent) restore = false
                    val apolloLocked = restore && !apolloOnDevice && !external.configured
                    if (apolloLocked) {
                        // Keep the light finishing path usable without the
                        // deliberate Apollo unlock. The label explains the fallback.
                        restore = false
                        Log.i(TAG, "Apollo locked; DSP-only finishing for $key")
                    }
                    if (!restore) {
                        update(track, Progress(key, track.title, State.PROCESSING, 0f, "enhancing · DSP"))
                        val bytes = DspAudioUpscaler.process(
                            tempPcm, decoded.frames, part, profile, masteringWidth, shouldCancel,
                        ) { f ->
                            if (!shouldCancel()) {
                                update(track, Progress(key, track.title, State.PROCESSING, f, "enhancing · DSP"))
                            }
                        }
                        val suffix = when {
                            !apolloLocked -> ""
                            OnDeviceApollo.AVAILABLE -> ", Apollo locked"
                            else -> ", Apollo via Device Link only"
                        }
                        bytes to "Enhanced · DSP (${profile.character.name.lowercase().replace('_', ' ')}$suffix)"
                    } else {
                        update(track, Progress(key, track.title, State.MODEL, stage = "model"))
                        val apollo = repository.ensure(shouldCancel) { f ->
                            update(track, Progress(key, track.title, State.MODEL, f, "model"))
                        }
                        if (shouldCancel()) throw UpscaleCancelledException()
                        val current = engine ?: OnDeviceApollo.create(context, repository, apollo).also {
                            engine = it
                        }
                        update(track, Progress(key, track.title, State.PROCESSING, 0f, "restoring · Apollo + DSP"))
                        val bytes = current.upscaleDecoded(
                            tempPcm, decoded.frames, part, profile, shouldCancel, masteringWidth,
                        ) { f ->
                            if (!shouldCancel()) {
                                update(
                                    track,
                                    Progress(
                                        key, track.title, State.PROCESSING, f,
                                        "restoring · Apollo + DSP · ${current.backendDescription}",
                                    ),
                                )
                            }
                        }
                        bytes to "Restored · Apollo + DSP"
                    }
                } finally {
                    runCatching { tempPcm.delete() }
                }
            }
            if (shouldCancel()) {
                runCatching { part.delete() }
                return
            }
            if (!part.renameTo(out)) {
                part.delete()
                throw java.io.IOException("could not publish upscaled audio")
            }
            store.setUpscaled(
                key, out.absolutePath, result.second, result.first,
                PROCESSING_REVISION, masteringWidth,
            )
            update(track, Progress(key, track.title, State.DONE, 1f, "upscaling"))
            Log.i(TAG, "upscaled ${track.title} -> ${result.first / 1024} KiB")
        } catch (e: UpscaleCancelledException) {
            Log.i(TAG, "upscale cancelled for $key")
        } catch (e: Exception) {
            if (!cancelled.contains(key)) {
                Log.w(TAG, "upscale failed for $key", e)
                fail(track, e.message ?: "upscale failed")
            }
        } finally {
            cancelled.remove(key)
        }
    }

    private fun fail(track: Track, message: String) {
        update(track, Progress(track.key(), track.title, State.FAILED, error = message))
    }

    private fun update(track: Track, progress: Progress) {
        _progress.value = _progress.value + (track.key() to progress)
    }
}
