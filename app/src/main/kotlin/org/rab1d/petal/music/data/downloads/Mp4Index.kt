/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * TIDAL offline files are DASH fragments concatenated into one fragmented MP4
 * (`ftyp+moov` + `[moof+mdat]×N`). ExoPlayer's FragmentedMp4Extractor only
 * exposes a seek map when the file carries a `sidx`, so such a file plays but
 * cannot be scrubbed (and "previous", which restarts at 0, does nothing).
 *
 * [ensureSeekable] inserts a standard `sidx` box between the init segment and
 * the first fragment, idempotently. Reference sizes are the exact fragment byte
 * lengths and durations come from each fragment's `tfdt` — precisely the layout
 * `FragmentedMp4Extractor.parseSidx` expects.
 */
object Mp4Index {

    /** Make [file] seekable. Returns true when it has (or now has) a sidx. */
    @Synchronized
    fun ensureSeekable(file: File, durationMs: Long): Boolean {
        if (!file.isFile) return false
        return runCatching { insertSidx(file, durationMs) }.getOrDefault(false)
    }

    private fun insertSidx(file: File, durationMs: Long): Boolean {
        val fileLength = file.length()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            var firstMoof = -1L
            var sidxBeforeMoof = false
            val moofs = ArrayList<Long>()
            while (offset + 8 <= fileLength) {
                val header = readHeader(raf, offset, fileLength)
                if (header.size < 8) break
                when (header.type) {
                    "sidx" -> if (firstMoof < 0) sidxBeforeMoof = true
                    "moof" -> {
                        if (firstMoof < 0) firstMoof = offset
                        moofs.add(offset)
                    }
                }
                offset += header.size
            }
            if (sidxBeforeMoof) return true
            if (firstMoof < 0 || moofs.isEmpty()) return false

            val timescale = readTimescale(raf) ?: return false
            val tfdt = LongArray(moofs.size)
            for (i in moofs.indices) {
                tfdt[i] = readTfdt(raf, moofs[i]) ?: if (i == 0) 0L else tfdt[i - 1]
            }

            val count = moofs.size
            val sizes = IntArray(count)
            for (i in 0 until count) {
                val end = if (i + 1 < count) moofs[i + 1] else fileLength
                sizes[i] = (end - moofs[i]).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            val durations = IntArray(count)
            for (i in 0 until count - 1) {
                durations[i] = (tfdt[i + 1] - tfdt[i])
                    .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            durations[count - 1] = lastDuration(
                durationMs, timescale, tfdt[count - 1],
                if (count > 1) durations[count - 2] else timescale / 25,
            )

            val sidx = buildSidx(timescale, tfdt[0], sizes, durations)

            val tmp = File.createTempFile(file.name + ".sidx-", ".tmp", file.parentFile)
            try {
                RandomAccessFile(file, "r").use { input ->
                    BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
                        copyRange(input, out, 0L, firstMoof)
                        out.write(sidx)
                        copyRange(input, out, firstMoof, fileLength)
                    }
                }
                // Same-directory rename atomically replaces the original on Android.
                // If it fails, leave the original playable file intact. Deleting
                // it first can lose audio when another repair moved the temp file.
                if (!tmp.renameTo(file)) return false
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            return true
        }
    }

    private fun lastDuration(durationMs: Long, timescale: Int, lastTfdt: Long, fallback: Int): Int {
        if (durationMs > 0) {
            val totalSamples = Math.round(durationMs / 1000.0 * timescale)
            val last = totalSamples - lastTfdt
            if (last in 1..Int.MAX_VALUE.toLong()) return last.toInt()
        }
        return fallback.coerceAtLeast(1)
    }

    /** ISO-BMFF `sidx`, version 0. */
    private fun buildSidx(timescale: Int, earliest: Long, sizes: IntArray, durations: IntArray): ByteArray {
        val count = sizes.size
        val total = 8 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + count * 12
        val b = ByteArray(total)
        var i = 0
        i = putU32(b, i, total.toLong())
        i = putType(b, i, "sidx")
        i = putU32(b, i, 0)              // version 0, flags 0
        i = putU32(b, i, 1)              // reference_ID (ignored by the extractor)
        i = putU32(b, i, timescale.toLong())
        i = putU32(b, i, earliest)
        i = putU32(b, i, 0)              // first_offset: sidx ends at the first moof
        i = putU16(b, i, 0)              // reserved
        i = putU16(b, i, count.toLong())
        for (j in 0 until count) {
            i = putU32(b, i, sizes[j].toLong() and 0x7FFFFFFFL)
            i = putU32(b, i, durations[j].toLong())
            i = putU32(b, i, 0)          // no SAP flags needed for audio
        }
        return b
    }

    private data class Header(val size: Long, val type: String)

    private fun readHeader(raf: RandomAccessFile, pos: Long, length: Long): Header {
        val hb = ByteArray(16)
        raf.seek(pos)
        raf.readFully(hb, 0, 8)
        var size = u32(hb, 0)
        val type = String(hb, 4, 4, Charsets.US_ASCII)
        if (size == 1L) {
            raf.readFully(hb, 8, 8)
            size = u64(hb, 8)
        } else if (size == 0L) {
            size = length - pos
        }
        return Header(size, type)
    }

    private fun readTimescale(raf: RandomAccessFile): Int? {
        val moov = readBox(raf, "moov") ?: return null
        val mdhd = findBox(moov, 0, moov.size, listOf("moov", "trak", "mdia", "mdhd")) ?: return null
        val p = mdhd.offset + mdhd.headerSize
        val end = mdhd.offset + mdhd.size
        val version = moov[p].toInt() and 0xFF
        val tsOffset = if (version == 1) p + 4 + 8 + 8 else p + 4 + 4 + 4
        if (tsOffset + 4 > end) return null
        return u32(moov, tsOffset).toInt().takeIf { it > 0 }
    }

    /** Reads the top-level [type] box into memory (init boxes are tiny). */
    private fun readBox(raf: RandomAccessFile, type: String): ByteArray? {
        val length = raf.length()
        var offset = 0L
        while (offset + 8 <= length) {
            val header = readHeader(raf, offset, length)
            if (header.size < 8 || offset + header.size > length) return null
            if (header.type == type) {
                val bytes = ByteArray(header.size.toInt())
                raf.seek(offset)
                raf.readFully(bytes)
                return bytes
            }
            offset += header.size
        }
        return null
    }

    private fun readTfdt(raf: RandomAccessFile, moofOffset: Long): Long? {
        val header = readHeader(raf, moofOffset, raf.length())
        val bytes = ByteArray(header.size.toInt().coerceAtMost(8192))
        raf.seek(moofOffset)
        raf.readFully(bytes)
        return findTfdt(bytes)
    }

    private fun findTfdt(data: ByteArray): Long? {
        var i = 8 // skip the moof header
        while (i + 8 <= data.size) {
            val size = u32(data, i)
            if (size < 8 || i + size > data.size) break
            if (String(data, i + 4, 4, Charsets.US_ASCII) == "traf") {
                return findTfdtInTraf(data, i + 8, (i + size).toInt())
            }
            i += size.toInt()
        }
        return null
    }

    private fun findTfdtInTraf(data: ByteArray, start: Int, end: Int): Long? {
        var i = start
        while (i + 8 <= end) {
            val size = u32(data, i).toInt()
            if (size < 8 || i + size > end) return null
            if (String(data, i + 4, 4, Charsets.US_ASCII) == "tfdt") {
                val version = data[i + 8].toInt() and 0xFF
                return if (version == 1) u64(data, i + 12) else u32(data, i + 12)
            }
            i += size
        }
        return null
    }

    private data class BoxInfo(val offset: Int, val headerSize: Int, val size: Int)

    /** Finds a nested box and returns its offset, header size and total size. */
    private fun findBox(data: ByteArray, start: Int, end: Int, path: List<String>): BoxInfo? {
        var i = start
        while (i + 8 <= end) {
            var size = u32(data, i)
            var headerSize = 8
            if (size == 1L) {
                if (i + 16 > end) return null
                size = u64(data, i + 8)
                headerSize = 16
            }
            if (size < headerSize || i + size > end) return null
            if (String(data, i + 4, 4, Charsets.US_ASCII) == path[0]) {
                if (path.size == 1) return BoxInfo(i, headerSize, size.toInt())
                return findBox(data, i + headerSize, (i + size).toInt(), path.drop(1))
            }
            i += size.toInt()
        }
        return null
    }

    private fun copyRange(input: RandomAccessFile, out: OutputStream, from: Long, to: Long) {
        input.seek(from)
        val buf = ByteArray(1 shl 16)
        var remaining = to - from
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    private fun putU16(b: ByteArray, o: Int, v: Long): Int {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
        return o + 2
    }

    private fun putU32(b: ByteArray, o: Int, v: Long): Int {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
        return o + 4
    }

    private fun putType(b: ByteArray, o: Int, type: String): Int {
        for (k in type.indices) b[o + k] = type[k].code.toByte()
        return o + 4
    }
}
