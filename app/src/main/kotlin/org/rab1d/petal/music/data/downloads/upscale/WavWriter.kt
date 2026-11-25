/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Minimal 16-bit PCM WAV writer. The model emits float samples; we quantise to
 * 16-bit because the reconstruction benefit lives in the high band, which
 * survives 16-bit precision, and because WAV keeps the result lossless and
 * playable by ExoPlayer with no extra encoder. Sizes are patched on [close].
 */
class WavWriter(
    private val file: File,
    private val channels: Int,
    private val sampleRate: Int,
) : Closeable {

    companion object {
        const val HEADER_BYTES = 44
    }

    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bytes = ByteArray(1 shl 16)
    private var cached = 0
    private var dataBytes = 0L
    private var closed = false

    init {
        writeHeader(0L)
    }

    /** Write [frames] samples from per-channel arrays (each >= frames long). */
    fun writeChannels(channelData: Array<FloatArray>, frames: Int) {
        if (frames <= 0) return
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                if (cached + 2 > bytes.size) flushBytes()
                val v = (channelData[c][i] * 32767f).toInt().coerceIn(-32768, 32767)
                bytes[cached++] = (v and 0xFF).toByte()
                bytes[cached++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        flushBytes()
        dataBytes += frames.toLong() * channels * 2
    }

    private fun flushBytes() {
        if (cached > 0) {
            out.write(bytes, 0, cached)
            cached = 0
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        flushBytes()
        out.flush()
        out.close()
        patchSizes()
    }

    private fun writeHeader(dataSize: Long) {
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(36L + dataSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(16))
        out.write(le16(1)) // PCM
        out.write(le16(channels))
        out.write(le32(sampleRate.toLong()))
        out.write(le32(sampleRate.toLong() * channels * 2))
        out.write(le16(channels * 2))
        out.write(le16(16))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(dataSize))
    }

    private fun patchSizes() {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(le32(36L + dataBytes))
            raf.seek(40)
            raf.write(le32(dataBytes))
        }
    }

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun le16(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )
}
