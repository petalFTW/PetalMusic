/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.data.downloads.DownloadedTrack
import org.rab1d.petal.music.data.downloads.upscale.ChannelMixer
import org.rab1d.petal.music.data.downloads.upscale.AccelerationCheck
import org.rab1d.petal.music.data.downloads.upscale.HighBandEnhancer
import org.rab1d.petal.music.data.downloads.upscale.HifiMastering
import org.rab1d.petal.music.data.downloads.upscale.Pcm16Io
import org.rab1d.petal.music.data.downloads.upscale.PcmSegmentProcessor
import org.rab1d.petal.music.data.downloads.upscale.StreamResampler
import org.rab1d.petal.music.data.downloads.upscale.UpscaleConfig
import org.rab1d.petal.music.data.downloads.upscale.WavWriter
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.FloatBuffer

class UpscaleTest {

    private val jsonReader = Json { ignoreUnknownKeys = true }

    private fun entry(upscaled: Boolean) = DownloadedTrack(
        id = "123",
        source = "tidal",
        title = "Song",
        artist = "Artist",
        album = "Album",
        file = "/tmp/orig.m4a",
        quality = "AAC · 320 kbps",
        upscaledFile = "/tmp/up.wav",
        upscaledQuality = "Upscaled · 44.1 kHz WAV",
        upscaleEnabled = upscaled,
    )

    @Test
    fun `active file follows the upscale toggle and reverts`() {
        val off = entry(upscaled = false)
        assertEquals("/tmp/orig.m4a", off.activeFile)
        assertEquals("AAC · 320 kbps", off.activeQuality)
        assertEquals("/tmp/orig.m4a", off.toTrack().localPath)

        val on = off.copy(upscaleEnabled = true)
        assertEquals("/tmp/up.wav", on.activeFile)
        assertEquals("Upscaled · 44.1 kHz WAV", on.activeQuality)
        assertEquals("/tmp/up.wav", on.toTrack().localPath)

        // Disabling is just a flag flip: the original download is intact.
        assertEquals("/tmp/orig.m4a", on.copy(upscaleEnabled = false).activeFile)
    }

    @Test
    fun `enabling without a generated copy stays on the original`() {
        val noCopy = entry(false).copy(upscaledFile = null, upscaleEnabled = true)
        assertEquals("/tmp/orig.m4a", noCopy.activeFile)
        assertEquals("AAC · 320 kbps", noCopy.activeQuality)
    }

    @Test
    fun `config parses the upstream snake_case keys`() {
        val json = """
            {
              "sample_rate": 44100,
              "segment_length": 131072,
              "in_channels": 2,
              "base_channels": 32,
              "channel_multipliers": [1,2,4,8,16,16]
            }
        """.trimIndent()
        val cfg = jsonReader.decodeFromString(UpscaleConfig.serializer(), json)
        assertEquals(44_100, cfg.sampleRate)
        assertEquals(131_072, cfg.segmentLength)
        assertEquals(2, cfg.inChannels)
    }

    @Test
    fun `wav writer emits a valid header and interleaved samples`() {
        val file = File.createTempFile("petal-upscale", ".wav")
        file.deleteOnExit()
        val frames = 5
        WavWriter(file, channels = 2, sampleRate = 44_100).use { w ->
            w.writeChannels(
                arrayOf(FloatArray(frames) { 0.5f }, FloatArray(frames) { -0.5f }),
                frames,
            )
        }
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(frames * 2 * 2, bytes.size - WavWriter.HEADER_BYTES)
        assertEquals(36L + frames * 2 * 2, le32(bytes, 4))
        assertEquals((frames * 2 * 2).toLong(), le32(bytes, 40))
        // first interleaved sample ~ +0.5, second ~ -0.5
        val first = ((bytes[44].toInt() and 0xFF) or (bytes[45].toInt() shl 8)).toShort()
        assertTrue(first > 16_000)
    }

    @Test
    fun `resampler preserves a constant and hits the target length`() {
        val resampler = StreamResampler(48_000, 44_100, 2)
        val inFrames = 4_800
        val input = FloatArray(inFrames * 2) { 0.5f }
        val out = ArrayList<Float>()
        val sink: (FloatArray, Int) -> Unit = { block, frames ->
            for (i in 0 until frames * 2) out.add(block[i])
        }
        resampler.process(input, inFrames, sink)
        resampler.flush(sink)
        val outFrames = out.size / 2
        assertEquals(4_410, outFrames)
        val avg = out.average()
        assertTrue("average=$avg", Math.abs(avg - 0.5) < 0.01)
    }

    @Test
    fun `PCM frame counts and segment output preserve mono and stereo duration`() {
        for (channels in listOf(1, 2)) {
            val frames = 1_003 // Multiple segments, including a short final segment.
            val segment = 128
            val pcm = ByteArrayOutputStream()
            var decodedFrames = 0L
            for (start in 0 until frames step 71) {
                val count = minOf(71, frames - start)
                val samples = FloatArray(count * channels) { i ->
                    val frame = start + i / channels
                    val sign = if (i % channels == 0) 1f else -1f
                    sign * (0.1f + frame * 0.0005f)
                }
                decodedFrames += Pcm16Io.write(pcm, samples, count, channels)
            }
            assertEquals(frames.toLong(), decodedFrames)
            assertEquals(decodedFrames * channels * 2, pcm.size().toLong())
            // Deliberately short stream reads must also be assembled correctly.
            val input = object : ByteArrayInputStream(pcm.toByteArray()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, minOf(len, 3))
            }
            val file = File.createTempFile("petal-pcm-duration", ".wav")
            try {
                WavWriter(file, channels, 44_100).use { writer ->
                    PcmSegmentProcessor.process(
                        input, decodedFrames, channels, segment, 16, 1f,
                        infer = { it.map { channel -> channel.copyOf() }.toTypedArray() },
                        write = writer::writeChannels,
                        shouldCancel = { false },
                        onProgress = {},
                    )
                }
                assertEquals(-1, input.read())
                val wav = file.readBytes()
                assertEquals(frames * channels * 2L, le32(wav, 40))
                assertEquals(WavWriter.HEADER_BYTES + frames * channels * 2L, file.length())
                for (i in 0 until frames) {
                    for (c in 0 until channels) {
                        val offset = WavWriter.HEADER_BYTES + (i * channels + c) * 2
                        val sample = ((wav[offset].toInt() and 0xFF) or
                            (wav[offset + 1].toInt() shl 8)).toShort() / 32768f
                        val sign = if (c == 0) 1f else -1f
                        assertEquals(sign * (0.1f + i * 0.0005f), sample, 0.0001f)
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test(expected = EOFException::class)
    fun `truncated PCM rejects stale segment data`() {
        val bytes = ByteArray(16) { 0x7F }
        val data = Array(2) { FloatArray(4) { 0.75f } }
        Pcm16Io.readChannels(ByteArrayInputStream(ByteArray(7)), bytes, data, 4, 1f)
    }

    @Test
    fun `overlapping segments cover boundary lengths without extra audio or inference`() {
        val segment = 128
        val overlap = 16
        for (frames in listOf(1, 15, 16, 17, 127, 128, 129, 239, 240, 241, 1_003)) {
            val pcm = ByteArrayOutputStream()
            Pcm16Io.write(pcm, FloatArray(frames) { 0.5f }, frames, 1)
            val output = ArrayList<Float>()
            var calls = 0
            var progress = 0f
            PcmSegmentProcessor.process(
                ByteArrayInputStream(pcm.toByteArray()), frames.toLong(), 1, segment, overlap, 1f,
                infer = { data ->
                    calls++
                    data.map { it.copyOf() }.toTypedArray()
                },
                write = { data, count -> for (i in 0 until count) output.add(data[0][i]) },
                shouldCancel = { false },
                onProgress = { assertTrue(it >= progress); progress = it },
            )
            assertEquals(frames, output.size)
            val expectedCalls = if (frames <= segment) 1 else
                1 + (frames - segment + (segment - overlap) - 1) / (segment - overlap)
            assertEquals(expectedCalls, calls)
            assertEquals(1f, progress, 0f)
            for (value in output) assertEquals(0.5f, value, 0.0001f)
        }
    }

    @Test
    fun `segment seams blend model edge differences instead of hard cutting`() {
        val pcm = ByteArrayOutputStream()
        Pcm16Io.write(pcm, FloatArray(12), 12, 1)
        val output = ArrayList<Float>()
        var calls = 0
        PcmSegmentProcessor.process(
            ByteArrayInputStream(pcm.toByteArray()), 12, 1, 8, 4, 1f,
            infer = { arrayOf(FloatArray(8) { calls.toFloat() }).also { calls++ } },
            write = { data, count -> for (i in 0 until count) output.add(data[0][i]) },
            shouldCancel = { false },
            onProgress = {},
        )
        assertEquals(12, output.size)
        for (i in 0 until 4) assertEquals(0f, output[i], 0f)
        for (i in 4 until 8) assertEquals((i - 3) / 5f, output[i], 0.00001f)
        for (i in 8 until 12) assertEquals(1f, output[i], 0f)
    }

    @Test
    fun `enhancement preserves original low band while adding high frequency detail`() {
        val rate = 44_100
        val frames = 4_410
        val original = FloatArray(frames) { i ->
            (0.2 * kotlin.math.sin(2 * Math.PI * 440 * i / rate)).toFloat()
        }
        val prediction = FloatArray(frames) { i ->
            // The model alters the rhythmic core and also reconstructs high-band detail.
            (0.2 * kotlin.math.sin(2 * Math.PI * 600 * i / rate) +
                0.1 * kotlin.math.sin(2 * Math.PI * 12_000 * i / rate)).toFloat()
        }
        val output = HighBandEnhancer(rate).enhance(arrayOf(original), arrayOf(prediction))[0]
        for (i in 64 until frames - 64) {
            val expected = original[i] + (0.05 * kotlin.math.sin(2 * Math.PI * 12_000 * i / rate)).toFloat()
            assertEquals(expected, output[i], 0.0001f)
        }
    }

    @Test
    fun `enhancement neither shifts rhythmic pulses nor leaks the other channel`() {
        val pulse = FloatArray(4_410) { i ->
            if (i in 1_200..1_500) {
                (0.4 * kotlin.math.sin(Math.PI * (i - 1_200) / 300)).toFloat()
            } else 0f
        }
        val source = arrayOf(pulse, FloatArray(pulse.size))
        val prediction = Array(2) { FloatArray(pulse.size) }
        val result = HighBandEnhancer(44_100).enhance(source, prediction)
        val peak = result[0].indices.maxBy { result[0][it] }
        assertEquals(1_350, peak)
        for (i in result[1].indices) assertEquals(0f, result[1][i], 0f)
        assertEquals(0.4f, pulse[1_350], 0.00001f) // Input was not modified.
        assertEquals(pulse.size, result[0].size)
    }

    @Test
    fun `mastering keeps in-phase bass centred and widens only the side band`() {
        val rate = 44_100
        val chunk = 2_048
        val chunks = 8
        val bass = FloatArray(chunk * chunks) { i ->
            (0.20 * kotlin.math.sin(2 * Math.PI * 55 * i / rate)).toFloat()
        }
        val side = FloatArray(chunk * chunks) { i ->
            (0.02 * kotlin.math.sin(2 * Math.PI * 7_000 * i / rate)).toFloat()
        }
        val left = FloatArray(bass.size) { bass[it] + side[it] }
        val right = FloatArray(bass.size) { bass[it] - side[it] }
        val mastering = HifiMastering(rate, 2, width = 1.2f)
        for (offset in 0 until bass.size step chunk) {
            val data = arrayOf(
                left.copyOfRange(offset, offset + chunk),
                right.copyOfRange(offset, offset + chunk),
            )
            mastering.process(data, chunk)
            for (i in 0 until chunk) {
                left[offset + i] = data[0][i]
                right[offset + i] = data[1][i]
            }
        }
        val last = (chunks - 1) * chunk
        var inputSide = 0.0
        var outputSide = 0.0
        for (i in 0 until chunk) {
            val index = last + i
            val inSide = (bass[index] + side[index] - (bass[index] - side[index])) / 2.0
            val outSide = (left[index] - right[index]) / 2.0
            inputSide += inSide * inSide
            outputSide += outSide * outSide
        }
        assertTrue("output side should be wider", outputSide > inputSide * 1.01)

        val monoBass = FloatArray(chunk) { i ->
            (0.25 * kotlin.math.sin(2 * Math.PI * 60 * i / rate)).toFloat()
        }
        val mono = arrayOf(monoBass.copyOf(), monoBass.copyOf())
        HifiMastering(rate, 2, width = 1.5f).process(mono, chunk)
        for (i in 0 until chunk) {
            assertEquals(mono[0][i], mono[1][i], 0.000001f)
        }
    }

    @Test
    fun `mastering limiter keeps peaks below the ceiling and output finite`() {
        val rate = 44_100
        val frames = 8_192
        val loud = FloatArray(frames) { i ->
            (0.995 * kotlin.math.sin(2 * Math.PI * 1_000 * i / rate)).toFloat()
        }
        val data = arrayOf(loud.copyOf(), loud.copyOf())
        HifiMastering(rate, 2, width = 0.8f).process(data, frames)
        for (c in 0 until 2) {
            var peak = 0f
            for (i in 0 until frames) {
                assertTrue(data[c][i].isFinite())
                peak = maxOf(peak, kotlin.math.abs(data[c][i]))
            }
            assertTrue("peak=$peak", peak <= 0.9701f)
        }
    }

    @Test
    fun `deficit gate rejects residual below the detected cutoff`() {
        val rate = 44_100
        val frames = rate
        val original = FloatArray(frames) { i ->
            (0.1 * kotlin.math.sin(2 * Math.PI * 1_000 * i / rate)).toFloat()
        }
        val prediction = FloatArray(frames) { i ->
            original[i] +
                (0.1 * kotlin.math.sin(2 * Math.PI * 12_000 * i / rate)).toFloat() +
                (0.1 * kotlin.math.sin(2 * Math.PI * 20_000 * i / rate)).toFloat()
        }
        val output = HighBandEnhancer(rate, cutoffHz = 16_000.0, amount = 0.8f)
            .enhance(arrayOf(original), arrayOf(prediction))[0]
        val correction = FloatArray(frames) { output[it] - original[it] }
        fun amplitude(hz: Int): Double {
            var re = 0.0
            var im = 0.0
            for (i in 0 until frames) {
                val phase = 2.0 * Math.PI * hz * i / rate
                re += correction[i] * kotlin.math.cos(phase)
                im += correction[i] * kotlin.math.sin(phase)
            }
            return kotlin.math.sqrt(re * re + im * im) / frames
        }
        val below = amplitude(12_000)
        val above = amplitude(20_000)
        assertTrue("below=$below above=$above", above > below * 4)
    }

    @Test
    fun `accelerator selection rejects inaccurate nonfinite and slower results`() {
        val reference = FloatBuffer.wrap(floatArrayOf(0.1f, 0.2f, -0.3f, 0.4f))
        assertTrue(AccelerationCheck.matches(reference, FloatBuffer.wrap(floatArrayOf(0.10001f, 0.2f, -0.3f, 0.4f))))
        assertTrue(!AccelerationCheck.matches(reference, FloatBuffer.wrap(floatArrayOf(0.1f, 0.2f, -0.1f, 0.4f))))
        assertTrue(!AccelerationCheck.matches(reference, FloatBuffer.wrap(floatArrayOf(0.1f, Float.NaN, -0.3f, 0.4f))))
        assertTrue(!AccelerationCheck.matches(reference, FloatBuffer.wrap(floatArrayOf(0.1f))))
        assertEquals(0, reference.position())
        assertTrue(AccelerationCheck.isFaster(1_000, 800))
        assertTrue(!AccelerationCheck.isFaster(1_000, 900))
        assertTrue(!AccelerationCheck.isFaster(1_000, 2_000))
    }

    @Test
    fun `resampling across codec blocks preserves timing and stereo alignment`() {
        for ((srcRate, dstRate) in listOf(48_000 to 44_100, 22_050 to 44_100)) {
            val frames = srcRate * 2
            val signal = FloatArray(frames * 2) { i ->
                val value = kotlin.math.sin(2.0 * Math.PI * 440 * (i / 2) / srcRate).toFloat()
                if (i % 2 == 0) value else -value
            }
            fun resample(blockSize: Int): FloatArray {
                val resampler = StreamResampler(srcRate, dstRate, 2)
                val result = ArrayList<Float>()
                val sink: (FloatArray, Int) -> Unit = { block, count ->
                    for (i in 0 until count * 2) result.add(block[i])
                }
                for (start in 0 until frames step blockSize) {
                    val count = minOf(blockSize, frames - start)
                    resampler.process(signal.copyOfRange(start * 2, (start + count) * 2), count, sink)
                }
                resampler.flush(sink)
                return result.toFloatArray()
            }
            val whole = resample(frames)
            val chunked = resample(1_024)
            assertEquals(dstRate * 2, chunked.size / 2)
            assertEquals(whole.size, chunked.size)
            for (i in chunked.indices) assertEquals(whole[i], chunked[i], 0.00001f)
            for (i in 24 until chunked.size / 2 - 24) {
                val expected = kotlin.math.sin(2.0 * Math.PI * 440 * i / dstRate).toFloat()
                assertEquals(expected, chunked[i * 2], 0.002f)
                assertEquals(-chunked[i * 2], chunked[i * 2 + 1], 0.00001f)
            }
        }
    }

    @Test
    fun `channel mixer duplicates mono and trims surround`() {
        val mono = ChannelMixer(1, 2)
        val twoOut = FloatArray(6)
        mono.mix(floatArrayOf(0.1f, 0.2f, 0.3f), 3, twoOut)
        assertEquals(listOf(0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.3f), twoOut.toList())

        val surround = ChannelMixer(6, 2)
        val stereoOut = FloatArray(4)
        surround.mix(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f), 2, stereoOut)
        assertEquals(listOf(1f, 2f, 7f, 8f), stereoOut.toList())
    }

    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF)) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
}
