/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import android.content.Context
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Runs a music bandwidth-extension ONNX graph over a whole audio file.
 *
 * The graph consumes one stereo waveform segment at a time
 * (`[1, channels, segmentLength]` float32) and returns the reconstructed
 * waveform for that segment. The file is decoded to 16-bit PCM once, optionally
 * peak-normalised and streamed through the graph with short crossfaded overlaps,
 * so memory stays roughly one segment regardless of track length.
 */
class OnnxAudioUpscaler(
    modelFile: File,
    private val config: UpscaleConfig,
    pipelineFiles: UpscalePipelineFiles? = null,
    nativeLibraryDir: String? = null,
    appContext: Context? = null,
) : RestoreEngine {

    companion object {
        const val TAG = "PetalMusic/Upscale"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val pipeline = OnnxUpscalePipeline(modelFile, pipelineFiles, nativeLibraryDir, appContext)
    private val enhancer = HighBandEnhancer(config.sampleRate)
    override val backendDescription: String get() = pipeline.backendDescription

    private val channels = config.inChannels
    private val segment = config.segmentLength

    init {
        Log.i(
            TAG,
            "model loaded: segment=$segment sr=${config.sampleRate} spectral=${pipelineFiles != null}",
        )
    }

    /** Run one segment. [input] holds [channels] arrays of exactly [segment] samples. */
    fun runSegment(input: Array<FloatArray>): Array<FloatArray> {
        val buf = FloatBuffer.allocate(channels * segment)
        for (c in 0 until channels) buf.put(input[c], 0, segment)
        buf.rewind()
        OnnxTensor.createTensor(
            env,
            buf,
            longArrayOf(1L, channels.toLong(), segment.toLong()),
        ).use { tensor ->
            return pipeline.runWaveform(tensor)
        }
    }

    /**
     * Decode [input], upscale it and write a 16-bit WAV to [output].
     * [onProgress] reports 0..1 over the inference pass. [shouldCancel] is
     * polled once per segment; when it turns true the pass aborts and the
     * partial output is deleted. Returns output bytes.
     */
    fun upscale(
        input: File,
        output: File,
        shouldCancel: () -> Boolean = { false },
        masteringWidth: Float = 0.5f,
        onProgress: (Float) -> Unit,
    ): Long {
        val temp = File(output.parentFile, output.name + ".pcm")
        val mastering = HifiMastering(config.sampleRate, channels, masteringWidth)
        var progress = 0f
        pipeline.shouldCancel = shouldCancel
        pipeline.onBackendChanged = { onProgress(progress) }
        try {
            val decoded = PcmDecoder.decode(input, temp, config.sampleRate, channels)
            if (decoded.frames <= 0) throw java.io.IOException("no audio decoded from ${input.name}")
            if (temp.length() != decoded.frames * channels * 2L) {
                throw java.io.IOException("decoded PCM length does not match frame count")
            }
            // Apollo feeds raw audio; EchoVault normalises to full scale first.
            val scale = if (config.normalize) 1f / max(peak(temp), 1e-4f) else 1f

            WavWriter(output, channels, config.sampleRate).use { writer ->
                BufferedInputStream(temp.inputStream(), 1 shl 16).use { ins ->
                    PcmSegmentProcessor.process(
                        input = ins,
                        frames = decoded.frames,
                        channels = channels,
                        segment = segment,
                        // 100 ms overlap avoids hard joins with ~5% extra inference
                        // for the two-second Apollo export.
                        overlap = minOf(config.sampleRate / 10, segment / 2),
                        scale = scale,
                        infer = { data ->
                            if (data.all { channel -> channel.all { abs(it) < 1e-6f } }) {
                                Array(data.size) { data[it].copyOf() }
                            } else enhancer.enhance(data, runSegment(data))
                        },
                        write = { data, count ->
                            mastering.process(data, count)
                            writer.writeChannels(data, count)
                        },
                        shouldCancel = shouldCancel,
                        onProgress = { fraction -> progress = fraction; onProgress(fraction) },
                    )
                }
            }
            if (output.length() != WavWriter.HEADER_BYTES + decoded.frames * channels * 2L) {
                throw java.io.IOException("upscaled WAV length does not match decoded audio")
            }
            return output.length()
        } catch (e: Throwable) {
            runCatching { output.delete() }
            throw e
        } finally {
            pipeline.onBackendChanged = {}
            pipeline.shouldCancel = { false }
            runCatching { temp.delete() }
        }
    }

    /**
     * Run Apollo on an already-decoded PCM file and write the deficit-gated
     * result. The caller has analysed the same PCM, so no second decode and no
     * full-spectrum model blend is needed.
     */
    override fun upscaleDecoded(
        pcm: File,
        frames: Long,
        output: File,
        profile: AudioProfile,
        shouldCancel: () -> Boolean,
        masteringWidth: Float,
        onProgress: (Float) -> Unit,
    ): Long {
        if (frames <= 0) throw java.io.IOException("no decoded audio to process")
        val enhancer = HighBandEnhancer(config.sampleRate, profile.apolloCutoffHz, amount = 0.8f)
        val mastering = HifiMastering(config.sampleRate, channels, masteringWidth, profile)
        var progress = 0f
        pipeline.shouldCancel = shouldCancel
        pipeline.onBackendChanged = { onProgress(progress) }
        try {
            WavWriter(output, channels, config.sampleRate).use { writer ->
                BufferedInputStream(pcm.inputStream(), 1 shl 16).use { ins ->
                    PcmSegmentProcessor.process(
                        input = ins,
                        frames = frames,
                        channels = channels,
                        segment = segment,
                        overlap = minOf(config.sampleRate / 10, segment / 2),
                        scale = 1f,
                        infer = { data ->
                            if (data.all { channel -> channel.all { abs(it) < 1e-6f } }) {
                                Array(data.size) { data[it].copyOf() }
                            } else enhancer.enhance(data, runSegment(data))
                        },
                        write = { data, count ->
                            mastering.process(data, count)
                            writer.writeChannels(data, count)
                        },
                        shouldCancel = shouldCancel,
                        onProgress = { fraction -> progress = fraction; onProgress(fraction) },
                    )
                }
            }
            if (output.length() != WavWriter.HEADER_BYTES + frames * channels * 2L) {
                throw java.io.IOException("upscaled WAV length does not match decoded audio")
            }
            return output.length()
        } catch (e: Throwable) {
            runCatching { output.delete() }
            throw e
        } finally {
            pipeline.onBackendChanged = {}
            pipeline.shouldCancel = { false }
        }
    }

    private fun peak(pcm: File): Float {
        var peak = 0f
        BufferedInputStream(pcm.inputStream(), 1 shl 16).use { ins ->
            val b = ByteArray(1 shl 16)
            var carry = -1
            while (true) {
                val n = ins.read(b)
                if (n <= 0) break
                var i = 0
                if (carry >= 0) {
                    val s = (((b[0].toInt() and 0xFF) shl 8) or carry).toShort()
                    peak = max(peak, abs(s.toInt()) / 32768f)
                    carry = -1
                    i = 1
                }
                while (i + 1 < n) {
                    val s = (((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)).toShort()
                    peak = max(peak, abs(s.toInt()) / 32768f)
                    i += 2
                }
                if (i < n) carry = b[i].toInt() and 0xFF
            }
        }
        return peak
    }

    override fun close() {
        pipeline.close()
    }
}
