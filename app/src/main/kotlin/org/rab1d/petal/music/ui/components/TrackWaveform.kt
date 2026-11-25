/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The song's real waveform, decoded from the file itself: [BINS] slots, each the loudness
 * (RMS) of its slice of the song, scaled so the loudest slot is 1. Only files on the phone
 * (local or downloaded) can be read ahead; streams keep the heard waveform.
 */
object TrackWaveform {
    const val BINS = 120
    /** RMS windows of 50 ms, folded into [BINS] slots once the length is known. */
    private const val WINDOW_MS = 50
    private const val MAX_MS = 15 * 60_000L

    private val memory = LruCache<String, FloatArray>(64)

    fun cached(key: String): FloatArray? = memory.get(key)

    /** Decodes [file] (or reads the disk cache). Call off the main thread. */
    fun load(context: Context, key: String, file: File): FloatArray? {
        memory.get(key)?.let { return it }
        val dir = File(context.cacheDir, "waveforms").apply { mkdirs() }
        val cache = File(dir, "${key.hashCode().toUInt()}-${file.length()}.bin")
        readCache(cache)?.let { memory.put(key, it); return it }
        val bins = runCatching { decode(file) }.getOrNull() ?: return null
        runCatching { writeCache(cache, bins) }
        memory.put(key, bins)
        return bins
    }

    private fun readCache(f: File): FloatArray? {
        if (!f.isFile || f.length() != BINS * 4L) return null
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(BINS) { b.getFloat() }
    }

    private fun writeCache(f: File, bins: FloatArray) {
        val b = ByteBuffer.allocate(BINS * 4).order(ByteOrder.LITTLE_ENDIAN)
        bins.forEach { b.putFloat(it) }
        f.writeBytes(b.array())
    }

    private fun decode(file: File): FloatArray? {
        if (!file.isFile) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val windows = ArrayList<Float>(4096)
            var windowFrames = rate * WINDOW_MS / 1000
            var sum = 0.0
            var inWindow = 0
            var frames = 0L
            var samples = FloatArray(1 shl 15)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (frames < rate * MAX_MS / 1000) {
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
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (newRate != rate && frames == 0L) {
                        // HE-AAC containers can describe the core rate, not the output.
                        rate = newRate
                        windowFrames = rate * WINDOW_MS / 1000
                    }
                } else if (outIndex >= 0) {
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
                        for (f in 0 until count / channels) {
                            var mono = 0f
                            for (c in 0 until channels) mono += samples[f * channels + c]
                            mono /= channels
                            sum += mono * mono
                            if (++inWindow >= windowFrames) {
                                windows += sqrt(sum / inWindow).toFloat()
                                sum = 0.0
                                inWindow = 0
                            }
                            frames++
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (inWindow > 0) windows += sqrt(sum / inWindow).toFloat()
            if (windows.size < BINS) return null
            // Each slot is the loudest window in its slice, so transients survive the fold.
            val bins = FloatArray(BINS) { b ->
                val from = b * windows.size / BINS
                val to = ((b + 1) * windows.size / BINS).coerceAtLeast(from + 1)
                var m = 0f
                for (i in from until to) if (windows[i] > m) m = windows[i]
                m
            }
            val peak = bins.max().takeIf { it > 0f } ?: return null
            for (i in bins.indices) bins[i] /= peak
            return bins
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

/** The real waveform for [key], decoded from [path] in the background; null for streams. */
@Composable
fun rememberTrackWaveform(key: String?, path: String?): FloatArray? {
    val context = LocalContext.current
    var bins by remember(key) { mutableStateOf(key?.let { TrackWaveform.cached(it) }) }
    LaunchedEffect(key, path) {
        if (key == null || path == null || bins != null) return@LaunchedEffect
        bins = withContext(Dispatchers.IO) { TrackWaveform.load(context.applicationContext, key, File(path)) }
    }
    return bins
}
