/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.data.downloads.Mp4Index
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Mp4IndexTest {
    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()

    private fun fragmentedAudio(): Pair<ByteArray, ByteArray> {
        val mdhd = box("mdhd", ByteBuffer.allocate(24)
            .putInt(0).putInt(0).putInt(0).putInt(48_000).putInt(48_000).putInt(0).array())
        val init = box("ftyp", ByteArray(8)) + box("moov", box("trak", box("mdia", mdhd)))
        val media = box("moof", box("traf", box("tfdt", ByteArray(8)))) +
            box("mdat", ByteArray(1 shl 20) { (it % 251).toByte() })
        return init to media
    }

    @Test fun concurrentRepairsKeepAudioAndInsertOnlyOneIndex() {
        val dir = Files.createTempDirectory("petal-sidx").toFile()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val (init, media) = fragmentedAudio()
            repeat(8) { round ->
                val audio = dir.resolve("$round.m4a").apply { writeBytes(init + media) }
                val barrier = CyclicBarrier(8)
                val repairs = (0 until 8).map {
                    pool.submit<Boolean> {
                        barrier.await(10, TimeUnit.SECONDS)
                        Mp4Index.ensureSeekable(audio, 1000)
                    }
                }
                repairs.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
                val result = audio.readBytes()
                assertArrayEquals(init, result.copyOfRange(0, init.size))
                val indexSize = ByteBuffer.wrap(result, init.size, 4).int
                assertEquals("sidx", String(result, init.size + 4, 4, Charsets.US_ASCII))
                assertArrayEquals(media, result.copyOfRange(init.size + indexSize, result.size))
                assertTrue(Mp4Index.ensureSeekable(audio, 1000))
                assertArrayEquals(result, audio.readBytes())
            }
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test fun unrepairableFileIsLeftIntact() {
        val dir = Files.createTempDirectory("petal-sidx-invalid").toFile()
        try {
            val bytes = box("ftyp", ByteArray(8))
            val audio = dir.resolve("unrepairable.m4a").apply { writeBytes(bytes) }
            assertFalse(Mp4Index.ensureSeekable(audio, 1000))
            assertArrayEquals(bytes, audio.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
