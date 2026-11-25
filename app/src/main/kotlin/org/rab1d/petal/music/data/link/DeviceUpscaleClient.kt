/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.link

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.rab1d.petal.music.data.downloads.upscale.UpscaleCancelledException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Off-device upscaling over Device Link: decode a downloaded file to 16-bit PCM,
 * POST it to the linked desktop, and stream the upscaled WAV back.
 *
 * The decoded PCM's true rate/channels come from the decoder's **output**
 * format, not the container's. HE-AAC (SBR) reports 22.05 kHz in the extractor
 * but decodes to 44.1 kHz; using the input rate made the returned WAV play at
 * half speed.
 */
object DeviceUpscaleClient {
    private const val CONNECT_TIMEOUT_MS = 5000

    /**
     * The desktop may spend a long time before it sends the first response
     * byte. The watchdog below still aborts promptly when the user cancels.
     */
    private const val READ_TIMEOUT_MS = 4 * 60 * 60 * 1000

    class RemoteException(message: String) : Exception(message)

    data class UpscaleResult(val bytes: Long, val engine: String?)

    private data class PcmFormat(val rate: Int, val channels: Int)

    fun upscale(
        source: File,
        outPart: File,
        host: String,
        port: Int,
        width: Float,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): UpscaleResult {
        val pcm = File(outPart.parentFile, outPart.nameWithoutExtension + ".pcm")
        try {
            val format = decodeToPcm(source, pcm, shouldCancel) { onProgress(it * 0.45f) }
            if (shouldCancel()) throw UpscaleCancelledException()
            return post(
                host, port, pcm, format, width.coerceIn(0f, 1.5f), outPart,
                shouldCancel,
            ) {
                onProgress(0.45f + it * 0.55f)
            }
        } finally {
            runCatching { pcm.delete() }
        }
    }

    private fun post(
        host: String,
        port: Int,
        pcm: File,
        format: PcmFormat,
        width: Float,
        out: File,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): UpscaleResult {
        val widthParam = String.format(java.util.Locale.US, "%.2f", width)
        val url = "http://$host:$port/v1/upscale?rate=${format.rate}" +
            "&channels=${format.channels}&bits=16&width=$widthParam"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("User-Agent", "PetalMusic/2.0")
        }
        var finished = false
        val watchdog = Thread {
            try {
                while (!finished) {
                    if (shouldCancel()) {
                        runCatching { conn.disconnect() }
                        break
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
            }
        }.apply {
            name = "DeviceUpscale-cancel"
            isDaemon = true
            start()
        }
        try {
            val totalIn = pcm.length().coerceAtLeast(1L)
            conn.setFixedLengthStreamingMode(pcm.length())
            conn.outputStream.use { outStream ->
                pcm.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var sent = 0L
                    while (true) {
                        if (shouldCancel()) throw UpscaleCancelledException()
                        val n = input.read(buf)
                        if (n <= 0) break
                        outStream.write(buf, 0, n)
                        sent += n
                        onProgress(0.5f * (sent.toDouble() / totalIn).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val msg = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                throw RemoteException("desktop HTTP $code${msg?.let { ": ${it.take(200)}" } ?: ""}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        if (shouldCancel()) throw UpscaleCancelledException()
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            onProgress(0.5f + 0.5f * (read.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
            return UpscaleResult(out.length(), conn.getHeaderField("X-Petal-Engine"))
        } finally {
            finished = true
            watchdog.interrupt()
            conn.disconnect()
        }
    }

    private fun decodeToPcm(
        source: File,
        out: File,
        shouldCancel: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): PcmFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (e: Exception) {
            throw RemoteException("could not open ${source.name}: ${e.message}")
        }
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw RemoteException("no audio track in ${source.name}")
        }
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw RemoteException("unknown audio format")
        // Provisional; overwritten by the decoder's real output format.
        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val codec = MediaCodec.createDecoderByType(mime)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val scratch = ByteArray(1 shl 16)
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                while (!sawOutputEos) {
                    if (shouldCancel()) throw UpscaleCancelledException()
                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                                extractor.advance()
                                if (durationUs > 0) onProgress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        else -> if (outIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                while (buffer.hasRemaining()) {
                                    val n = minOf(buffer.remaining(), scratch.size)
                                    buffer.get(scratch, 0, n)
                                    sink.write(scratch, 0, n)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
        return PcmFormat(rate, channels)
    }
}
