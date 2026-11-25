/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Raw little-endian PCM I/O. A frame contains one sample per channel. */
internal object Pcm16Io {
    /** Write interleaved samples, returning frames rather than sample count. */
    fun write(out: OutputStream, samples: FloatArray, frames: Int, channels: Int): Long {
        require(channels > 0 && frames >= 0 && frames <= samples.size / channels)
        val count = frames * channels
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        return frames.toLong()
    }

    /** Read exactly [frames], padding only the unused portion of the segment. */
    fun readChannels(
        input: InputStream,
        bytes: ByteArray,
        channelData: Array<FloatArray>,
        frames: Int,
        scale: Float,
    ) {
        val channels = channelData.size
        require(channels > 0 && frames >= 0 && channelData.all { it.size >= frames })
        val count = frames * channels * 2
        require(bytes.size >= count)
        var offset = 0
        while (offset < count) {
            val n = input.read(bytes, offset, count - offset)
            if (n <= 0) throw EOFException("truncated decoded PCM: expected $count bytes, read $offset")
            offset += n
        }
        for (data in channelData) java.util.Arrays.fill(data, 0f)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val base = (i * channels + c) * 2
                val lo = bytes[base].toInt() and 0xFF
                val hi = bytes[base + 1].toInt()
                channelData[c][i] = ((hi shl 8) or lo).toShort() / 32768f * scale
            }
        }
    }
}
