/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Half
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.max

/**
 * CPU FFTs and spectral heads around six BSNet blocks that may run on Qualcomm
 * HTP. Only one HTP block session is resident at a time: this device is memory
 * tight and holding all six contexts concurrently triggers the low-memory
 * killer. Each block's compiled context is reused from disk, so no compilation
 * happens in the player process.
 */
internal class OnnxUpscalePipeline(
    modelFile: File,
    private val files: UpscalePipelineFiles?,
    private val nativeLibraryDir: String?,
    private val appContext: Context?,
) : Closeable {
    companion object {
        /**
         * Direct QNN/HTP execution is not viable for this model on current
         * hardware: Apollo's band attention materialises intermediates far
         * larger than the Hexagon's on-DSP memory, so blocks compile but fail
         * at graph execution with QNN error 1003. CPU is the shipped path; the
         * mechanism remains for future models or a rewritten attention.
         */
        const val ENABLE_NPU = false
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = mutableListOf<OrtSession.SessionOptions>()
    private val sessions = mutableListOf<OrtSession>()
    private val cpu: OrtSession
    private val frontend: OrtSession?
    private val backend: OrtSession?
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var npuEnabled = false
    private var probed = false
    var backendDescription: String = "CPU"
        private set
    var onBackendChanged: () -> Unit = {}
    var shouldCancel: () -> Boolean = { false }

    private fun describeBackend(value: String) {
        backendDescription = value
        onBackendChanged()
    }

    private fun checkCancelled() {
        if (shouldCancel()) throw UpscaleCancelledException()
    }

    init {
        try {
            cpu = createSession(files?.core ?: modelFile)
            frontend = files?.let { createSession(it.frontend) }
            backend = files?.let { createSession(it.backend) }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun createSession(file: File, qnn: Boolean = false): OrtSession {
        val opts = OrtSession.SessionOptions()
        options.add(opts)
        opts.setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
        if (qnn) {
            check(files?.npuBlocks?.any { it.cache == file } == true) {
                "refusing uncached NPU compilation in the player process"
            }
            QnnSessionOptions.configure(opts, nativeLibraryDir!!)
        }
        return env.createSession(file.absolutePath, opts).also { sessions.add(it) }
    }

    private fun run(session: OrtSession, input: OnnxTensor): OrtSession.Result =
        session.run(mapOf(session.inputNames.first() to input))

    private fun toFp16(input: OnnxTensor): ShortBuffer {
        val values = input.floatBuffer
        val halves = ByteBuffer.allocateDirect(values.remaining() * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        while (values.hasRemaining()) halves.put(Half.toHalf(values.get()))
        halves.rewind()
        return halves
    }

    /** Copies an fp16 tensor into a standalone owner so its session can close. */
    private fun copyHalf(tensor: OnnxTensor): OnnxTensor =
        OnnxTensor.createTensor(env, tensor.shortBuffer, tensor.info.shape, OnnxJavaType.FLOAT16)

    /**
     * Run the waveform through the HTP blocks, keeping a single block session
     * resident at a time. Each block's inputs/outputs are copied so the session
     * and its native allocations are released before the next block opens.
     */
    private fun runNpu(input: OnnxTensor): OrtSession.Result {
        checkCancelled()
        val blocks = files!!.npuBlocks
        run(encoder!!, input).use { features ->
            val latent = features[0] as OnnxTensor
            var current = OnnxTensor.createTensor(env, toFp16(latent), latent.info.shape, OnnxJavaType.FLOAT16)
            try {
                for (block in blocks) {
                    checkCancelled()
                    val session = createSession(block.cache, qnn = true)
                    val next = try {
                        run(session, current).use { result -> copyHalf(result[0] as OnnxTensor) }
                    } finally {
                        sessions.remove(session)
                        runCatching { session.close() }
                    }
                    current.close()
                    current = next
                }
                OnnxTensor.createTensor(env, current.floatBuffer, current.info.shape).use { floats ->
                    return run(decoder!!, floats)
                }
            } finally {
                current.close()
            }
        }
    }

    private fun probe(input: OnnxTensor) {
        if (probed) return
        probed = true
        if (!ENABLE_NPU || files == null || nativeLibraryDir == null || appContext == null ||
            OrtProvider.QNN !in OrtEnvironment.getAvailableProviders() ||
            !File(nativeLibraryDir, "libQnnHtp.so").isFile ||
            files.npuBlocks.any { !it.cache.isFile }
        ) return
        try {
            checkCancelled()
            NpuPreparationClient.prepare(appContext, files, shouldCancel, ::describeBackend)
            checkCancelled()
            describeBackend("Loading NPU contexts")
            encoder = encoder ?: createSession(files.encoder)
            decoder = decoder ?: createSession(files.decoder)
            describeBackend("Comparing CPU/NPU")
            var matches = false
            run(cpu, input).use { reference ->
                runNpu(input).use { result ->
                    matches = AccelerationCheck.matches(
                        (reference[0] as OnnxTensor).floatBuffer,
                        (result[0] as OnnxTensor).floatBuffer,
                    )
                }
            }
            if (!matches) {
                Log.w(OnnxAudioUpscaler.TAG, "QNN rejected: output differs from CPU")
                return
            }
            describeBackend("Benchmarking CPU/NPU")
            var cpuNanos = 0L
            var npuNanos = 0L
            repeat(2) {
                checkCancelled()
                var start = System.nanoTime()
                run(cpu, input).use { (it[0] as OnnxTensor).floatBuffer }
                cpuNanos += System.nanoTime() - start
                start = System.nanoTime()
                runNpu(input).use { (it[0] as OnnxTensor).floatBuffer }
                npuNanos += System.nanoTime() - start
            }
            Log.i(
                OnnxAudioUpscaler.TAG,
                "spectral benchmark: CPU=${cpuNanos / 2_000_000}ms NPU=${npuNanos / 2_000_000}ms",
            )
            if (AccelerationCheck.isFaster(cpuNanos, npuNanos)) {
                npuEnabled = true
                describeBackend("NPU · Qualcomm HTP")
            } else {
                Log.i(OnnxAudioUpscaler.TAG, "QNN rejected: less than 15% speedup")
            }
        } catch (e: UpscaleCancelledException) {
            probed = false
            throw e
        } catch (e: Exception) {
            Log.w(OnnxAudioUpscaler.TAG, "QNN unavailable; using CPU", e)
        } finally {
            if (!npuEnabled) describeBackend("CPU")
        }
    }

    private fun core(input: OnnxTensor): OrtSession.Result {
        probe(input)
        checkCancelled()
        if (!npuEnabled) return run(cpu, input)
        return try {
            runNpu(input)
        } catch (e: UpscaleCancelledException) {
            throw e
        } catch (e: Exception) {
            // A DSP restart must not discard a nearly completed track.
            Log.w(OnnxAudioUpscaler.TAG, "QNN inference failed; reverting to CPU", e)
            npuEnabled = false
            describeBackend("CPU")
            run(cpu, input)
        }
    }

    fun runWaveform(input: OnnxTensor): Array<FloatArray> {
        val front = frontend
        return if (front == null) reconstruct(input) else {
            run(front, input).use { result -> reconstruct(result[0] as OnnxTensor) }
        }
    }

    private fun reconstruct(input: OnnxTensor): Array<FloatArray> = core(input).use { result ->
        val back = backend
        if (back == null) channels(result[0] as OnnxTensor) else {
            val prediction = result[0] as OnnxTensor
            if (prediction.info.type == OnnxJavaType.FLOAT16) {
                OnnxTensor.createTensor(env, prediction.floatBuffer, prediction.info.shape).use { floats ->
                    run(back, floats).use { channels(it[0] as OnnxTensor) }
                }
            } else run(back, prediction).use { channels(it[0] as OnnxTensor) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun channels(tensor: OnnxTensor): Array<FloatArray> =
        (tensor.value as Array<Array<FloatArray>>)[0]

    override fun close() {
        sessions.asReversed().forEach { runCatching { it.close() } }
        sessions.clear()
        options.asReversed().forEach { runCatching { it.close() } }
        options.clear()
    }
}
