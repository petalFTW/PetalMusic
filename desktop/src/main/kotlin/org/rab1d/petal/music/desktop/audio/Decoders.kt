/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.audio

import com.jcraft.jogg.Page
import com.jcraft.jogg.Packet
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Raw PCM in the file's native rate/channel count, interleaved floats in [-1, 1]. */
interface PcmRawSource : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    /** @return frames placed into [out], 0 when more input is needed, -1 at end of stream. */
    fun read(out: FloatArray, maxFrames: Int): Int
}

/** Decoded PCM resampled to the engine's output format (48 kHz stereo). */
interface PcmSource : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    /** @return frames placed into [out], 0 to retry later, -1 at end of stream. */
    fun read(out: FloatArray, maxFrames: Int): Int
}

object PcmIO {
    val OUTPUT_RATE = 48000
    val OUTPUT_CHANNELS = 2

    /** Opens [file] as a 48 kHz stereo [PcmSource], or null when the format is unsupported. */
    fun open(file: Path): PcmSource? {
        val raw = openRaw(file) ?: return null
        return SourcePipeline(raw, OUTPUT_RATE, OUTPUT_CHANNELS)
    }

    fun openRaw(file: Path): PcmRawSource? = when (file.fileName.toString().lowercase().substringAfterLast('.')) {
        "wav", "aif", "aiff", "au" -> WavRawSource(file)
        "mp3" -> Mp3RawSource(file)
        "ogg", "oga" -> OggRawSource(file)
        else -> null
    }

    /** Cheap header-only duration probe used by the library scan. */
    fun estimateDurationMs(file: Path): Long = try {
        when (file.fileName.toString().lowercase().substringAfterLast('.')) {
            "wav", "aif", "aiff", "au" -> {
                AudioSystem.getAudioFileFormat(file.toFile()).let {
                    it.frameLength * 1000L / it.format.sampleRate.toInt().coerceAtLeast(1)
                }
            }
            "mp3" -> {
                val bytes = Files.size(file)
                val bitrate = Mp3RawSource.probeBitrateKbps(file)
                if (bitrate > 0) bytes * 8_000L / (bitrate * 1000L) else 0L
            }
            "ogg", "oga" -> {
                val rate = OggRawSource.probeSampleRate(file)
                val granule = lastGranulePosition(file)
                if (rate > 0 && granule > 0) granule * 1000L / rate else 0L
            }
            else -> 0L
        }
    } catch (_: Exception) {
        0L
    }

    private fun lastGranulePosition(file: Path): Long {
        java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
            val size = raf.length()
            val window = size.coerceAtMost(128L * 1024L).toInt()
            raf.seek(size - window)
            val buf = ByteArray(window)
            raf.readFully(buf)
            var i = buf.size - 27
            while (i >= 0) {
                if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() &&
                    buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()
                ) {
                    return ByteBuffer.wrap(buf, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
                }
                i--
            }
        }
        return 0L
    }
}

/** Channel count fix-up + linear resample to the output format, streaming. */
internal class SourcePipeline(
    private val raw: PcmRawSource,
    private val outRate: Int,
    private val outChannels: Int,
) : PcmSource {
    override val sampleRate = outRate
    override val channels = outChannels

    private val ratio = raw.sampleRate.toDouble() / outRate
    private val rawChannels = raw.channels
    private val rawBuf = FloatArray(4096 * rawChannels)
    private val convBuf = FloatArray((rawBuf.size / rawChannels) * outChannels)
    private var convFrames = 0
    private var convPos = 0
    private var rawEof = false

    private val prev = FloatArray(outChannels)
    private val cur = FloatArray(outChannels)
    private var havePrev = false
    private var haveCur = false
    private var frac = 0.0
    private var eof = false

    private val passthrough = raw.sampleRate == outRate && rawChannels == outChannels

    override fun read(out: FloatArray, maxFrames: Int): Int {
        if (eof) return -1
        if (passthrough) {
            val n = raw.read(out, maxFrames)
            if (n < 0) eof = true
            return n
        }
        var produced = 0
        while (produced < maxFrames) {
            if (!havePrev) {
                if (!nextFrame(prev)) break
                havePrev = true
            }
            if (!haveCur) {
                if (!nextFrame(cur)) {
                    System.arraycopy(prev, 0, out, produced * outChannels, outChannels)
                    produced++
                    eof = true
                    break
                }
                haveCur = true
            }
            var o = produced * outChannels
            for (c in 0 until outChannels) {
                val p = prev[c]
                out[o + c] = (p + (cur[c] - p) * frac).coerceIn(-1.0, 1.0).toFloat()
            }
            produced++
            frac += ratio
            if (frac >= 1.0) {
                frac -= 1.0
                System.arraycopy(cur, 0, prev, 0, outChannels)
                // If the last frame was consumed, flush it via the haveCur
                // branch on the next iteration instead of dropping it.
                haveCur = nextFrame(cur)
            }
        }
        if (produced == 0) {
            eof = true
            return -1
        }
        return produced
    }

    private fun nextFrame(dst: FloatArray): Boolean {
        while (true) {
            if (convPos < convFrames) {
                System.arraycopy(convBuf, convPos * outChannels, dst, 0, outChannels)
                convPos++
                return true
            }
            if (rawEof) return false
            refill()
        }
    }

    private fun refill() {
        if (rawEof) return
        val n = raw.read(rawBuf, rawBuf.size / rawChannels)
        if (n < 0) {
            rawEof = true
            return
        }
        if (n == 0) return
        var o = 0
        for (f in 0 until n) {
            val src = f * rawChannels
            when {
                rawChannels == outChannels -> System.arraycopy(rawBuf, src, convBuf, o, outChannels)
                rawChannels == 1 -> {
                    val v = rawBuf[src]
                    convBuf[o] = v
                    if (outChannels == 2) convBuf[o + 1] = v
                }
                else -> {
                    convBuf[o] = rawBuf[src]
                    if (outChannels == 2) convBuf[o + 1] = rawBuf[src + 1]
                }
            }
            o += outChannels
        }
        convFrames = n
        convPos = 0
    }

    override fun close() = raw.close()
}

/** PCM WAV/AIFF/AU via Java Sound, converted to 16-bit PCM at the native rate. */
class WavRawSource(file: Path) : PcmRawSource {
    private val stream: AudioInputStream
    override val sampleRate: Int
    override val channels: Int

    init {
        val base = AudioSystem.getAudioInputStream(file.toFile())
        val fmt = base.format
        sampleRate = fmt.sampleRate.toInt()
        channels = fmt.channels
        stream = if (fmt.encoding == AudioFormat.Encoding.PCM_SIGNED && fmt.sampleSizeInBits == 16) base
        else AudioSystem.getAudioInputStream(
            AudioFormat(AudioFormat.Encoding.PCM_SIGNED, fmt.sampleRate, 16, fmt.channels, fmt.channels * 2, fmt.sampleRate, false),
            base,
        )
    }

    private val bytes = ByteArray(64 * 1024)
    private var carry = 0

    override fun read(out: FloatArray, maxFrames: Int): Int {
        val frameBytes = channels * 2
        val want = maxFrames.coerceAtMost(bytes.size / frameBytes) * frameBytes - carry
        if (want <= 0) return 0
        val n = stream.read(bytes, carry, want)
        if (n < 0 && carry == 0) return -1
        val available = if (n < 0) carry else carry + n
        var usable = available - available % frameBytes
        if (usable <= 0) {
            carry = available
            return if (n < 0) -1 else 0
        }
        val buf = ByteBuffer.wrap(bytes, 0, usable).order(ByteOrder.LITTLE_ENDIAN)
        val frames = usable / frameBytes
        var o = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) out[o++] = buf.short / 32768f
        }
        carry = available - usable
        if (carry > 0) System.arraycopy(bytes, usable, bytes, 0, carry)
        return frames
    }

    override fun close() = stream.close()
}

/** MP3 via the JLayer gdx fork (MP3Decoder + 16-bit OutputBuffer). */
class Mp3RawSource(private val file: Path) : PcmRawSource {
    private val input = BufferedInputStream(FileInputStream(file.toFile()), 1 shl 16)
    private val bitstream = javazoom.jl.decoder.Bitstream(input)
    private val decoder = javazoom.jl.decoder.MP3Decoder()
    private val outBuffer: javazoom.jl.decoder.OutputBuffer
    override val sampleRate: Int
    override val channels: Int
    val bitrateKbps: Int

    private val buffer: ByteArray
    private var pendingBytes = 0
    private var pendingPos = 0
    private var eof = false

    init {
        val header = bitstream.readFrame() ?: run {
            bitstream.close()
            throw IllegalArgumentException("empty mp3: $file")
        }
        sampleRate = header.frequency()
        channels = if (header.mode() == javazoom.jl.decoder.Header.SINGLE_CHANNEL) 1 else 2
        bitrateKbps = bitrateKbps(header)
        outBuffer = javazoom.jl.decoder.OutputBuffer(channels, false)
        decoder.setOutputBuffer(outBuffer)
        buffer = outBuffer.buffer
        bitstream.unreadFrame()
    }

    override fun read(out: FloatArray, maxFrames: Int): Int {
        var o = 0
        val frameBytes = channels * 2
        while (o < maxFrames) {
            if (pendingPos >= pendingBytes) {
                val header = bitstream.readFrame()
                if (header == null) {
                    eof = true
                    runCatching { bitstream.close() }
                    break
                }
                decoder.decodeFrame(header, bitstream)
                pendingBytes = outBuffer.reset()
                pendingPos = 0
                bitstream.closeFrame()
                if (pendingBytes < frameBytes) continue
            }
            val n = minOf(maxFrames - o, (pendingBytes - pendingPos) / frameBytes)
            var i = pendingPos
            var w = o * channels
            for (f in 0 until n) {
                for (c in 0 until channels) {
                    val lo = buffer[i++].toInt() and 0xFF
                    val hi = buffer[i++].toInt()
                    out[w++] = ((hi shl 8) or lo) / 32768f
                }
            }
            pendingPos = i
            o += n
        }
        return if (o == 0 && eof) -1 else o
    }

    override fun close() {
        runCatching { bitstream.close() }
    }

    companion object {
        private val BITRATES_MPEG1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATES_MPEG2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

        private fun bitrateKbps(header: javazoom.jl.decoder.Header): Int {
            val table = if (header.version() == javazoom.jl.decoder.Header.MPEG1) BITRATES_MPEG1 else BITRATES_MPEG2
            val idx = header.bitrate_index()
            return if (idx in table.indices) table[idx] else 0
        }

        fun probeBitrateKbps(file: Path): Int = runCatching {
            BufferedInputStream(FileInputStream(file.toFile()), 1 shl 16).use { input ->
                val bitstream = javazoom.jl.decoder.Bitstream(input)
                val rate = bitstream.readFrame()?.let { bitrateKbps(it) } ?: 0
                bitstream.close()
                rate
            }
        }.getOrDefault(0)
    }
}

/** Ogg Vorbis via jorbis (port of the DecoderExample pull loop). */
class OggRawSource(private val file: Path) : PcmRawSource {
    private val input = DataInputStream(BufferedInputStream(FileInputStream(file.toFile()), 1 shl 15))
    private val sync = SyncState()
    private val stream = StreamState()
    private val info = Info()
    private val comment = Comment()
    private val dsp = DspState()
    private val block: Block
    override val sampleRate: Int
    override val channels: Int

    private val page = Page()
    private val packet = Packet()
    private val pcmOut = arrayOfNulls<Array<FloatArray>>(1)
    private val pcmIndex = IntArray(1)
    private var conv = FloatArray(0)
    private var convLen = 0
    private var convOff = 0
    private var headersDone = false
    private var eof = false

    init {
        sync.init()
        readHeaders()
        sampleRate = info.rate
        channels = info.channels
        dsp.synthesis_init(info)
        block = Block(dsp)
        block.init(dsp)
    }

    private fun fill(): Int {
        val offset = sync.buffer(FILL_CHUNK)
        val n = input.read(sync.data, offset, FILL_CHUNK)
        if (n <= 0) return -1
        sync.wrote(n)
        return n
    }

    private fun readHeaders() {
        var packets = 0
        var streamReady = false
        while (packets < 3) {
            when (sync.pageout(page)) {
                0 -> if (fill() == -1) throw IllegalArgumentException("truncated ogg: $file")
                -1 -> throw IllegalArgumentException("not ogg: $file")
                else -> {
                    if (!streamReady) {
                        stream.init(page.serialno())
                        streamReady = true
                    }
                    stream.pagein(page)
                    while (packets < 3 && stream.packetout(packet) != 0) {
                        if (info.synthesis_headerin(comment, packet) != 0 && packets == 0) {
                            throw IllegalArgumentException("not vorbis: $file")
                        }
                        packets++
                    }
                }
            }
        }
        headersDone = true
    }

    override fun read(out: FloatArray, maxFrames: Int): Int {
        if (eof && convLen == 0) return -1
        while (convLen == 0 && !eof) decodePacket()
        if (convLen == 0) return -1
        val ch = channels
        val n = minOf(maxFrames, convLen)
        var o = 0
        var s = convOff
        for (f in 0 until n) {
            for (c in 0 until ch) out[o++] = conv[s++]
        }
        convOff = s
        convLen -= n
        if (convLen == 0) {
            convOff = 0
        }
        return n
    }

    private fun decodePacket() {
        while (true) {
            if (headersDone && stream.packetout(packet) > 0) {
                synth(packet)
                return
            }
            when (sync.pageout(page)) {
                0 -> {
                    if (fill() == -1) {
                        eof = true
                        return
                    }
                }
                -1 -> Unit
                else -> {
                    if (!headersDone) stream.init(page.serialno())
                    stream.pagein(page)
                }
            }
        }
    }

    private fun synth(packet: Packet) {
        if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block)
        val samples = dsp.synthesis_pcmout(pcmOut, pcmIndex)
        if (samples <= 0) return
        val pcm = pcmOut[0] ?: return
        val idx = pcmIndex[0]
        val ch = channels
        ensureConv(samples * ch)
        var o = convLen * ch
        for (f in 0 until samples) {
            for (c in 0 until ch) conv[o++] = pcm[c][idx + f]
        }
        convLen += samples
        dsp.synthesis_read(samples)
    }

    private fun ensureConv(framesTimesChannels: Int) {
        val needed = convOff + convLen * channels + framesTimesChannels
        if (conv.size < needed) {
            conv = conv.copyOf(maxOf(needed, conv.size * 2))
        }
    }

    override fun close() {
        runCatching { stream.clear() }
        runCatching { sync.clear() }
    }

    companion object {
        private const val FILL_CHUNK = 8192

        fun probeSampleRate(file: Path): Int = runCatching {
            OggRawSource(file).use { it.sampleRate }
        }.getOrDefault(0)
    }
}
