/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Windowed-sinc streaming resampler. Reads interleaved float PCM at the source
 * rate and emits interleaved float PCM at the target rate, keeping only a small
 * history window in memory so multi-minute tracks never need to be held whole.
 */
class StreamResampler(
    private val srcRate: Int,
    private val dstRate: Int,
    private val channels: Int,
) {
    private val ratio = srcRate.toDouble() / dstRate
    private val half = 12
    private val cutoff = min(1.0, dstRate.toDouble() / srcRate)

    private var pending = FloatArray(1 shl 16)
    private var pendingFrames = 0
    private var pendingStart = 0L
    private var nextOut = 0L

    private val outBlock = FloatArray(4096 * channels)
    private var outFrames = 0
    private val frame = FloatArray(channels)

    fun process(input: FloatArray, frames: Int, sink: (FloatArray, Int) -> Unit) {
        ensure(frames)
        System.arraycopy(input, 0, pending, pendingFrames * channels, frames * channels)
        pendingFrames += frames
        emit(sink)
    }

    /** Drain the tail. Call once, with no further [process]. */
    fun flush(sink: (FloatArray, Int) -> Unit) {
        while (true) {
            val globalEnd = pendingStart + pendingFrames
            val pos = nextOut * ratio
            // Frames cover [0, globalEnd), including fractional positions in
            // the last input frame when upsampling.
            if (pos >= globalEnd) break
            computeFrame(pos, globalEnd)
            append(sink)
            nextOut++
        }
        flushBlock(sink)
    }

    private fun emit(sink: (FloatArray, Int) -> Unit) {
        while (true) {
            val globalEnd = pendingStart + pendingFrames
            val pos = nextOut * ratio
            val center = floor(pos).toLong()
            if (center + half >= globalEnd) break
            computeFrame(pos, globalEnd)
            append(sink)
            nextOut++
        }
        flushBlock(sink)
        compact()
    }

    private fun computeFrame(pos: Double, globalEnd: Long) {
        val center = floor(pos).toLong()
        val lo = center - half + 1
        val hi = center + half
        java.util.Arrays.fill(frame, 0f)
        var norm = 0.0
        var t = lo
        while (t <= hi) {
            val w = kernel(pos - t)
            norm += w
            val idx = (t - pendingStart).toInt()
            if (t < globalEnd && idx in 0 until pendingFrames) {
                val base = idx * channels
                val wf = w.toFloat()
                for (c in 0 until channels) frame[c] += pending[base + c] * wf
            }
            t++
        }
        if (norm != 0.0) {
            val inv = (1.0 / norm).toFloat()
            for (c in 0 until channels) frame[c] *= inv
        }
    }

    private fun kernel(d: Double): Double {
        val x = cutoff * d
        val s = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
        val n = d / half
        if (abs(n) >= 1.0) return 0.0
        val w = 0.42 + 0.5 * cos(PI * n) + 0.08 * cos(2 * PI * n)
        return cutoff * s * w
    }

    private fun append(sink: (FloatArray, Int) -> Unit) {
        val base = outFrames * channels
        for (c in 0 until channels) outBlock[base + c] = frame[c]
        outFrames++
        if (outFrames == outBlock.size / channels) flushBlock(sink)
    }

    private fun flushBlock(sink: (FloatArray, Int) -> Unit) {
        if (outFrames > 0) {
            sink(outBlock, outFrames)
            outFrames = 0
        }
    }

    private fun compact() {
        val keep = (nextOut * ratio).toLong() - half
        if (keep > pendingStart) {
            val drop = (keep - pendingStart).toInt().coerceAtMost(pendingFrames)
            if (drop > 0) {
                System.arraycopy(
                    pending, drop * channels, pending, 0, (pendingFrames - drop) * channels,
                )
                pendingFrames -= drop
                pendingStart += drop
            }
        }
    }

    private fun ensure(frames: Int) {
        val needed = (pendingFrames + frames) * channels
        if (needed > pending.size) {
            var n = pending.size
            while (n < needed) n = n shl 1
            pending = pending.copyOf(n)
        }
    }
}

/** Mixes [srcChannels] interleaved float frames down/up to [dstChannels]. */
class ChannelMixer(private val srcChannels: Int, private val dstChannels: Int) {
    fun mix(src: FloatArray, frames: Int, dst: FloatArray) {
        when {
            srcChannels == dstChannels ->
                System.arraycopy(src, 0, dst, 0, frames * srcChannels)
            srcChannels == 1 ->
                for (i in 0 until frames) {
                    val v = src[i]
                    val base = i * dstChannels
                    for (c in 0 until dstChannels) dst[base + c] = v
                }
            else ->
                for (i in 0 until frames) {
                    val sBase = i * srcChannels
                    val dBase = i * dstChannels
                    for (c in 0 until dstChannels) {
                        dst[dBase + c] = src[sBase + (c % srcChannels)]
                    }
                }
        }
    }
}

/** Decoded PCM facts. */
data class DecodedPcm(val frames: Long, val sampleRate: Int, val channels: Int)

/**
 * Decodes a compressed audio file to raw 16-bit little-endian interleaved PCM
 * at [targetSampleRate]/[targetChannels] using the platform MediaCodec stack.
 * Output is a headerless temp file so the upscaler can stream it.
 */
object PcmDecoder {
    private const val TAG = "PetalMusic/Upscale"

    fun decode(input: File, output: File, targetSampleRate: Int, targetChannels: Int): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw java.io.IOException("no audio track in ${input.name}")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw java.io.IOException("no mime for audio track")
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var mixer = ChannelMixer(srcChannels, targetChannels)
            var resampler = if (srcRate != targetSampleRate) {
                StreamResampler(srcRate, targetSampleRate, targetChannels)
            } else null
            var framesWritten = 0L

            FileOutputStream(output).use { fos ->
                BufferedOutputStream(fos, 1 shl 16).use { out ->
                    val sink: (FloatArray, Int) -> Unit = { block, frames ->
                        framesWritten += Pcm16Io.write(out, block, frames, targetChannels)
                    }
                    var decoded = FloatArray(1 shl 16)
                    var mixed = FloatArray((1 shl 16) * targetChannels)
                    var receivedAudio = false
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false

                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val inBuf = codec.getInputBuffer(inIndex)!!
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val of = codec.outputFormat
                                val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val channelCount = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (rate <= 0 || channelCount <= 0) {
                                    throw java.io.IOException("invalid decoder PCM format")
                                }
                                if (rate != srcRate || channelCount != srcChannels) {
                                    // Container metadata can describe the AAC core rather than
                                    // the actual decoded rate/channel layout (e.g. HE-AAC).
                                    if (receivedAudio) {
                                        throw java.io.IOException("decoder PCM format changed mid-stream")
                                    }
                                    srcRate = rate
                                    srcChannels = channelCount
                                    mixer = ChannelMixer(srcChannels, targetChannels)
                                    resampler = if (srcRate != targetSampleRate) {
                                        StreamResampler(srcRate, targetSampleRate, targetChannels)
                                    } else null
                                }
                                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                }
                            }
                            outIndex >= 0 -> {
                                if (info.size > 0) {
                                    val outBuf = codec.getOutputBuffer(outIndex)!!
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    outBuf.order(ByteOrder.nativeOrder())
                                    // Size for even 8-bit output; codecs need not use a fixed
                                    // output block size across devices.
                                    if (decoded.size < info.size) decoded = FloatArray(info.size)
                                    val frames = readFloats(outBuf, pcmEncoding, srcChannels, decoded)
                                    if (frames > 0) {
                                        receivedAudio = true
                                        if (mixed.size < frames * targetChannels) {
                                            mixed = FloatArray(frames * targetChannels)
                                        }
                                        mixer.mix(decoded, frames, mixed)
                                        val activeResampler = resampler
                                        if (activeResampler == null) sink(mixed, frames)
                                        else activeResampler.process(mixed, frames, sink)
                                    }
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                    resampler?.flush(sink)
                }
            }
            Log.i(TAG, "decoded ${input.name}: $framesWritten frames @ $targetSampleRate")
            return DecodedPcm(framesWritten, targetSampleRate, targetChannels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Read the codec's output buffer into [dst] as interleaved float; returns frames. */
    private fun readFloats(src: ByteBuffer, encoding: Int, channels: Int, dst: FloatArray): Int {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = src.asFloatBuffer()
                val n = fb.remaining()
                fb.get(dst, 0, n)
                n / channels
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = src.remaining()
                for (i in 0 until n) {
                    dst[i] = ((src.get(src.position() + i).toInt() and 0xFF) - 128) / 128f
                }
                n / channels
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                val sb = src.asShortBuffer()
                val n = sb.remaining()
                for (i in 0 until n) dst[i] = sb.get(i) / 32768f
                n / channels
            }
            else -> throw java.io.IOException("unsupported decoder PCM encoding: $encoding")
        }
    }
}
