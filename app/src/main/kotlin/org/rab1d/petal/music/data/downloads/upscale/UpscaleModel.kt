/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Describes the ONNX upscaler's expected tensor layout. Mirrors the
 * `config.json` shipped alongside the model: stereo waveform in, stereo
 * waveform out, processed in fixed-length segments.
 */
@Serializable
data class UpscaleConfig(
    @SerialName("sample_rate") val sampleRate: Int = 44_100,
    /** Fixed input length the ONNX graph was exported with. */
    @SerialName("segment_length") val segmentLength: Int = 88_200,
    @SerialName("in_channels") val inChannels: Int = 2,
    /**
     * Whether the model expects peak-normalised input (EchoVault's CLI does;
     * Apollo's own inference feeds the raw waveform and would clip otherwise).
     */
    @SerialName("normalize") val normalize: Boolean = false,
)

data class UpscalePipelineFiles(
    val frontend: File,
    val core: File,
    val backend: File,
    val encoder: File,
    val decoder: File,
    val npuBlocks: List<UpscaleNpuBlock>,
)

data class UpscaleNpuBlock(val model: File, val sha256: String) {
    val cache: File get() = File(model.parentFile, "qnn-block-${sha256.take(16)}-2.44.0.onnx")
}

@Serializable
private data class BundledModelFile(val bytes: Long, val sha256: String)

@Serializable
private data class BundledModelManifest(val version: String, val files: Map<String, BundledModelFile>)

/**
 * Prepares the bundled Apollo spectral pipeline, verified by size + SHA-256.
 * The original waveform model remains supported by builds without bundled assets.
 */
class UpscaleModelRepository(private val context: Context) {

    companion object {
        const val TAG = "PetalMusic/Upscale"

        const val MODEL_FILE = "model.onnx"
        const val CONFIG_FILE = "config.json"

        /**
         * Apollo (JusperLee/Apollo, ICASSP'25) exported to ONNX and patched so
         * the STFT/iSTFT overlap-add's int32 ScatterND indices are int64.
         * 75 MB, opset 18, `audio` [1,2,88200] -> `audio_out`. Hosted by the
         * ROM owner; also accepted from a locally pushed copy of this size.
         */
        private const val MODEL_URL =
            "https://github.com/JusperLee/Apollo/releases/download/onnx/apollo.onnx"

        private const val MODEL_SHA256 =
            "0ea8a886b006b36bc74aba33c657c7ba2d09be8cd9e1cbce90e0a35d55aaf265"
        private const val MODEL_BYTES = 78_889_067L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val dir: File get() = File(context.filesDir, "upscale").apply { mkdirs() }
    val modelFile: File get() = File(dir, MODEL_FILE)
    val configFile: File get() = File(dir, CONFIG_FILE)

    private val bundled by lazy {
        runCatching {
            context.assets.open("upscale/manifest.json").bufferedReader().use {
                json.decodeFromString(BundledModelManifest.serializer(), it.readText())
            }
        }.getOrNull()
    }

    private val pipelineDir: File get() = File(dir, "apollo-spectral-v2-blocks")

    fun pipelineFiles(): UpscalePipelineFiles? = if (bundled != null) {
        UpscalePipelineFiles(
            File(pipelineDir, "frontend.onnx"), File(pipelineDir, "core.onnx"),
            File(pipelineDir, "backend.onnx"),
            File(pipelineDir, "encoder.onnx"), File(pipelineDir, "decoder.onnx"),
            (0 until 6).map { index ->
                val name = "block-$index-fp16.onnx"
                UpscaleNpuBlock(File(pipelineDir, name), bundled!!.files.getValue(name).sha256)
            },
        )
    } else null

    /** Bundled models can be prepared offline on the first use. */
    fun isReady(): Boolean = bundled != null || modelFile.length() == MODEL_BYTES

    /**
     * The model's tensor layout. An optional local `config.json` overrides the
     * baked-in defaults (matching the Apollo export).
     */
    fun readConfig(): UpscaleConfig =
        if (configFile.isFile) {
            runCatching {
                json.decodeFromString(UpscaleConfig.serializer(), configFile.readText())
            }.getOrDefault(UpscaleConfig())
        } else {
            UpscaleConfig()
        }

    /**
     * Ensure the model is on disk, downloading and verifying it if needed.
     * Even this heavy path is cancellable: [shouldCancel] is polled per chunk
     * of downloaded bytes and the partial file is discarded.
     */
    suspend fun ensure(
        shouldCancel: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): UpscaleConfig = withContext(Dispatchers.IO) {
        val manifest = bundled
        if (manifest != null) {
            installBundled(manifest, shouldCancel, onProgress)
            // The bundled graphs have a fixed, verified stereo two-second layout.
            return@withContext UpscaleConfig()
        }
        if (modelFile.length() != MODEL_BYTES) {
            download(MODEL_URL, modelFile, MODEL_BYTES, MODEL_SHA256, 0f, 1f, shouldCancel, onProgress)
        }
        onProgress(1f)
        readConfig()
    }

    private fun installBundled(
        manifest: BundledModelManifest,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        require(manifest.version == "apollo-spectral-v2-blocks") { "unsupported bundled model version" }
        val names = listOf("frontend.onnx", "core.onnx", "backend.onnx", "encoder.onnx", "decoder.onnx") +
            (0 until 6).map { "block-$it-fp16.onnx" }
        pipelineDir.mkdirs()
        for ((index, name) in names.withIndex()) {
            if (shouldCancel()) throw UpscaleCancelledException()
            val expected = manifest.files.getValue(name)
            val dest = File(pipelineDir, name)
            if (dest.length() != expected.bytes || sha256(dest) != expected.sha256) {
                val part = File(pipelineDir, "$name.part")
                try {
                    context.assets.open("upscale/$name").use { input ->
                        part.outputStream().buffered().use { out ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                if (shouldCancel()) throw UpscaleCancelledException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    check(part.length() == expected.bytes && sha256(part) == expected.sha256) {
                        "bundled model checksum mismatch: $name"
                    }
                    if (!part.renameTo(dest)) throw java.io.IOException("could not install $name")
                } finally {
                    part.delete()
                }
            }
            onProgress((index + 1f) / names.size)
        }
    }

    private fun download(
        url: String,
        dest: File,
        expectedBytes: Long,
        expectedSha: String,
        from: Float,
        to: Float,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val part = File(dest.parentFile, dest.name + ".part")
        runCatching { part.delete() }
        try {
            val request = Request.Builder().url(url).header("User-Agent", "PetalMusic/2.0").build()
            client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("model download HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("empty model body")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes
            var read = 0L
            body.byteStream().use { input ->
                java.io.BufferedOutputStream(java.io.FileOutputStream(part), 1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (shouldCancel()) throw UpscaleCancelledException()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(from + (to - from) * (read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            }
        } catch (e: Throwable) {
            runCatching { part.delete() }
            throw e
        }
        if (part.length() != expectedBytes) {
            part.delete()
            throw java.io.IOException("model size mismatch: ${part.length()} != $expectedBytes")
        }
        val sha = sha256(part)
        if (!sha.equals(expectedSha, ignoreCase = true)) {
            part.delete()
            throw java.io.IOException("model checksum mismatch")
        }
        if (!part.renameTo(dest)) {
            dest.delete()
            if (!part.renameTo(dest)) throw java.io.IOException("could not move model into place")
        }
        Log.i(TAG, "verified ${dest.name} (${expectedBytes} bytes)")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
