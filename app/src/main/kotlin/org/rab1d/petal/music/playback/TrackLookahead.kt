/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder

/**
 * Decodes a whole local or downloaded file ahead of its mix (a few hundred ms
 * of CPU for a typical track) and analyses it like a DJ listening through:
 * grid, bar phase, sections, vocals, key. Streams cannot be read ahead; they
 * are measured while they play or by a silent prelisten.
 */
internal object TrackLookahead {
    private const val TAG = "PetalMusic/Lookahead"
    private const val MAX_MS = 12 * 60_000L

    fun supports(uri: Uri?): Boolean = uri?.scheme == "file" || uri?.scheme == "content"

    fun analyse(context: Context, uri: Uri): TrackProfile? {
        val started = android.os.SystemClock.elapsedRealtime()
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
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000 else MAX_MS
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val envelope = BandEnvelope(BandEnvelope.hopsFor(minOf(durationMs + 5000, MAX_MS).toDouble()))
            var crossover = DjCrossover(rate)
            var vocal = VocalBand(rate)
            val chroma = ChromaEnvelope(ChromaEnvelope.framesFor(minOf(durationMs + 5000, MAX_MS).toDouble()))
            envelope.reset(0.0, rate)
            chroma.reset(0.0, rate)
            val bands = FloatArray(4)
            // ExoPlayer trims encoder priming; the media clock must match playback.
            var skip = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY))
                format.getInteger(MediaFormat.KEY_ENCODER_DELAY).toLong() else 0L
            var samples = FloatArray(1 shl 15)
            var frames = 0L
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
                    val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (newRate != rate && frames == 0L) {
                        // HE-AAC containers can describe the core rate, not the output.
                        rate = newRate
                        crossover = DjCrossover(rate)
                        vocal = VocalBand(rate)
                        envelope.reset(0.0, rate)
                        chroma.reset(0.0, rate)
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
                            if (skip > 0) { skip--; continue }
                            var mono = 0f
                            for (c in 0 until channels) mono += samples[f * channels + c]
                            mono /= channels
                            crossover.split(mono, bands)
                            envelope.frame(mono, bands[0] + bands[1], bands[2], bands[3], vocal.process(mono))
                            chroma.sample(mono)
                            frames++
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val snapshot = envelope.snapshot() ?: return null
            val profile = TrackAnalyzer.analyse(snapshot, chroma.snapshot(), complete = true)
            Log.i(TAG, "analysed $uri in ${android.os.SystemClock.elapsedRealtime() - started}ms: " +
                describe(profile))
            return profile
        } catch (e: Exception) {
            Log.w(TAG, "lookahead failed for $uri: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    fun describe(p: TrackProfile?): String = if (p == null) "no grid" else
        "bpm=${"%.2f".format(p.bpm)} grid=${"%.2f".format(p.gridConfidence)} " +
            "downbeat=${p.downbeatMs?.let { "%.0f".format(it) }}(${"%.2f".format(p.downbeatConfidence)}) " +
            "bars=${p.bars} sections=${p.sections.joinToString(",")} phrase=${p.phraseOffset} " +
            "intro=${p.introEndBar} outro=${p.outroStartBar} key=${p.camelot}${if (p.minor == true) "A" else "B"}" +
            "(${"%.2f".format(p.keyConfidence)}) vocalBars=${p.barVocal.count { it > .5f }} complete=${p.complete}"
}
