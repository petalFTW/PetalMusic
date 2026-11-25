/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Separates the media ranges a composed mix asked for ([DjComposer.Mix.outStemsMs],
 * [DjComposer.Mix.inStemsMs], [DjComposer.Mix.clipMs]) on one low-priority thread
 * and attaches the results to the mix. A mix whose stems are not ready when it
 * fires plays its stem-free [DjComposer.Mix.fallback] instead.
 *
 * Only files can be read ahead (local and downloaded tracks); streams get no stems.
 */
internal class StemWorker(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PetalStems").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var job: Future<*>? = null
    private var jobMix: DjComposer.Mix? = null
    @Volatile private var cancelled: DjComposer.Mix? = null
    private val separator: StemSeparator by lazy { StemModels.separator(context) }

    fun supports(uri: Uri?) = TrackLookahead.supports(uri)

    /** Start separating for [mix] unless already running for it. */
    fun prepare(mix: DjComposer.Mix, outUri: Uri?, inUri: Uri?, onReady: () -> Unit) {
        if (!mix.needsStems || mix.stemsReady || jobMix === mix) return
        jobMix?.let { cancelled = it }
        job?.cancel(false)
        jobMix = mix
        job = executor.submit {
            val started = android.os.SystemClock.elapsedRealtime()
            val stop = { cancelled === mix }
            try {
                mix.outStemsMs?.let { r -> outUri?.let { window(it, r, stop)?.let { w -> mix.outStems = w } } }
                mix.inStemsMs?.let { r -> inUri?.let { window(it, r, stop)?.let { w -> mix.inStems = w } } }
                mix.clipMs?.let { r ->
                    inUri?.let { window(it, r, stop) }?.let { w ->
                        mix.clip = StemClip(w.rate, w.channels, w.frames, w.stems[StemWindow.VOCALS], w.source)
                    }
                }
                AutomixLog.i(TAG, "stems for ${mix.label}: ready=${mix.stemsReady} by ${separator.name} in " +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms out=${mix.outStemsMs} in=${mix.inStemsMs} clip=${mix.clipMs}")
            } catch (e: Throwable) {
                AutomixLog.i(TAG, "stems failed for ${mix.label}: ${e.message}")
            }
            handler.post(onReady)
        }
    }

    private fun window(uri: Uri, range: LongRange, stop: () -> Boolean): StemWindow? {
        val pcm = decode(uri, range.first, range.last) ?: return null
        if (stop()) return null
        val stems = separator.separate(pcm.samples, pcm.frames, pcm.rate, pcm.channels, stop) ?: return null
        return StemWindow(pcm.startMs, pcm.rate, pcm.channels, pcm.frames, Array(4) { toShorts(stems[it]) }, separator.name)
    }

    private class Pcm(val startMs: Double, val rate: Int, val channels: Int, val frames: Int, val samples: FloatArray)

    /**
     * Decode media [fromMs, toMs) on the same clock as [TrackLookahead] and ExoPlayer:
     * from the file start with the encoder delay trimmed.
     */
    private fun decode(uri: Uri, fromMs: Long, toMs: Long): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var skip = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY))
                format.getInteger(MediaFormat.KEY_ENCODER_DELAY).toLong() else 0L
            var first = (fromMs * rate / 1000).coerceAtLeast(0)
            var last = toMs * rate / 1000
            var out = FloatArray(0)
            var frames = 0L
            var kept = 0
            var samples = FloatArray(1 shl 15)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (frames < last) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    channels = srcChannels.coerceIn(1, 2)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (newRate != rate && frames == 0L) {
                        rate = newRate
                        first = (fromMs * rate / 1000).coerceAtLeast(0)
                        last = toMs * rate / 1000
                    }
                } else if (outIndex >= 0) {
                    if (out.isEmpty()) out = FloatArray(((last - first).coerceAtLeast(1) * channels).toInt())
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        if (samples.size < info.size) samples = FloatArray(info.size)
                        val count = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> buffer.asFloatBuffer().let {
                                val n = it.remaining(); it.get(samples, 0, n); n
                            }
                            AudioFormat.ENCODING_PCM_16BIT -> buffer.asShortBuffer().let {
                                val n = it.remaining()
                                for (i in 0 until n) samples[i] = it.get(i) / 32768f
                                n
                            }
                            else -> 0
                        }
                        for (f in 0 until count / srcChannels) {
                            if (skip > 0) { skip--; continue }
                            if (frames >= first && frames < last) {
                                for (c in 0 until channels) out[kept * channels + c] = samples[f * srcChannels + c]
                                kept++
                            }
                            frames++
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (kept < rate) return null
            return Pcm(first * 1000.0 / rate, rate, channels, kept, out)
        } catch (e: Exception) {
            AutomixLog.i(TAG, "stem decode failed for $uri: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    companion object { private const val TAG = "PetalStems" }
}
