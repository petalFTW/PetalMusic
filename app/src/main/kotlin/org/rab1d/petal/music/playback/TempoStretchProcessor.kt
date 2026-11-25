/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessorChain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tempo without pitch for a deck, in place of Media3's Sonic stage (WSOLA).
 *
 * The sink drains its processors on every speed change. Sonic ends a drain by padding its
 * input with silence, so each step of a tempo ride dipped the audio for ~30 ms: a loop ride
 * clicked constantly and its first step left a gap on the loop seam. This stretcher ends a
 * drain by copying its unstretched remainder straight through, and the fresh one after the
 * flush starts on the very next input frame, so a speed change joins sample to sample.
 *
 * 16-bit PCM only (the integer path the decks use). Inactive at 1x, like Sonic.
 */
class TempoStretchProcessor : AudioProcessor {
    /** Written by the player thread (glides) and read on the playback thread. */
    @Volatile var speed = 1f
        private set
    private var pendingFormat = AudioFormat.NOT_SET
    private var format = AudioFormat.NOT_SET
    private var channels = 0
    /** Overlap (half a segment) and search radius, in frames. */
    private var hop = 0
    private var radius = 0
    private var fadeIn = FloatArray(0)

    // Input not yet consumed, interleaved; positions below are frames into it.
    private var fifo = ShortArray(0)
    private var fifoFrames = 0
    /** Where the previous segment's tail (the part still to be overlapped) starts. */
    private var tail = 0
    /** Ideal start of the next segment: advances [hop] × speed per output hop. */
    private var ideal = 0.0
    private var dropped = 0L

    private var out = ShortArray(0)
    private var outFrames = 0
    private var outBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputTotal = 0L
    private var inputEnded = false

    fun setSpeed(value: Float) {
        speed = value
    }

    /** Media time played in [playoutUs] of output since the last flush (cf. Sonic). */
    fun getMediaDuration(playoutUs: Long): Long {
        if (outputTotal < 1024) return (speed.toDouble() * playoutUs).toLong()
        val media = dropped + tail
        return (playoutUs.toDouble() * media / outputTotal).toLong()
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = pendingFormat.sampleRate != androidx.media3.common.Format.NO_VALUE &&
        abs(speed - 1f) >= 1e-4f

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val shorts = inputBuffer.asShortBuffer()
        val count = shorts.remaining()
        val frames = count / channels
        ensureFifo(fifoFrames + frames)
        shorts.get(fifo, fifoFrames * channels, frames * channels)
        fifoFrames += frames
        inputBuffer.position(inputBuffer.position() + frames * channels * 2)
        process()
    }

    override fun queueEndOfStream() {
        // Stretch as far as the input reaches (a narrower search at the very end), so only
        // the last hop or so plays unstretched.
        process(final = true)
        // The rest goes out as it is: the tail was fading into exactly these frames.
        val rest = fifoFrames - tail
        if (rest > 0) {
            ensureOut(outFrames + rest)
            System.arraycopy(fifo, tail * channels, out, outFrames * channels, rest * channels)
            outFrames += rest
        }
        dropped += fifoFrames
        fifoFrames = 0
        tail = 0
        ideal = 0.0
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        if (outFrames == 0) return AudioProcessor.EMPTY_BUFFER
        val bytes = outFrames * channels * 2
        if (outBuffer.capacity() < bytes) outBuffer = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        else outBuffer.clear()
        outBuffer.asShortBuffer().put(out, 0, outFrames * channels)
        outBuffer.limit(bytes)
        outputTotal += outFrames
        outFrames = 0
        return outBuffer
    }

    override fun isEnded(): Boolean = inputEnded && outFrames == 0

    override fun flush() {
        if (isActive()) {
            format = pendingFormat
            // 25 ms overlap and a 12.5 ms search: at 20/8 ms a bass cycle (20+ ms) didn't fit the
            // match window, so bass seams missed and a held stretch sounded grumbly and choppy.
            if (channels != format.channelCount || hop != format.sampleRate / 40) {
                channels = format.channelCount
                hop = (format.sampleRate / 40).coerceAtLeast(64)
                radius = (format.sampleRate / 80).coerceAtLeast(16)
                fadeIn = FloatArray(hop) { t -> sin(PI / 2 * (t + .5) / hop).let { (it * it).toFloat() } }
            }
        }
        fifoFrames = 0
        outFrames = 0
        outBuffer = AudioProcessor.EMPTY_BUFFER
        outputTotal = 0
        dropped = 0
        inputEnded = false
        // A virtual segment ending at frame 0 + hop: its tail is the first hop of input, so the
        // first output frame is the first input frame.
        tail = 0
        ideal = -hop + hop * speed.toDouble()
    }

    override fun reset() {
        flush()
        speed = 1f
        pendingFormat = AudioFormat.NOT_SET
        format = AudioFormat.NOT_SET
        channels = 0
        hop = 0
        fifo = ShortArray(0)
        out = ShortArray(0)
    }

    private fun process(final: Boolean = false) {
        if (channels == 0) return
        val h = hop
        while (true) {
            val center = Math.round(ideal).toInt()
            if (tail + h > fifoFrames) break
            val lo = maxOf(0, center - radius)
            val hi = if (final) minOf(center + radius, fifoFrames - h) else center + radius
            if (hi < lo || (!final && center + radius + 2 * h > fifoFrames)) break
            val k = bestMatch(lo, hi, center)
            // Overlap-add: the previous tail fades out as the matched segment fades in.
            ensureOut(outFrames + h)
            var o = outFrames * channels
            for (t in 0 until h) {
                val w = fadeIn[t]
                val a = (tail + t) * channels
                val b = (k + t) * channels
                for (c in 0 until channels) {
                    val v = fifo[a + c] * (1f - w) + fifo[b + c] * w
                    out[o++] = v.toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            outFrames += h
            tail = k + h
            ideal += h * speed.toDouble()
            compact()
        }
    }

    /**
     * The segment start in [lo, hi] whose first hop best continues the tail. Equal matches
     * (a steady tone repeats every period) go to the one nearest [center], or the stretch
     * would drift early and come out at the wrong speed.
     */
    private fun bestMatch(lo: Int, hi: Int, center: Int): Int {
        if (hi <= lo) return lo
        fun score(k: Int, step: Int): Double {
            var xy = 0.0
            var yy = 0.0
            var t = 0
            while (t < hop) {
                val a = (tail + t) * channels
                val b = (k + t) * channels
                var x = 0
                var y = 0
                for (c in 0 until channels) { x += fifo[a + c]; y += fifo[b + c] }
                xy += x.toDouble() * y
                yy += y.toDouble() * y
                t += step
            }
            val d = (k - center).toDouble() / radius
            val s = xy / sqrt(yy + 1.0)
            return s * (if (s > 0) 1 - .1 * d * d else 1 + .1 * d * d)
        }
        // Coarse every 4th lag on every 4th frame, then refine around the best at full rate.
        var best = lo
        var bestScore = Double.NEGATIVE_INFINITY
        var k = lo
        while (k <= hi) {
            val s = score(k, 4)
            if (s > bestScore) { bestScore = s; best = k }
            k += 4
        }
        val from = maxOf(lo, best - 3)
        val to = minOf(hi, best + 3)
        bestScore = Double.NEGATIVE_INFINITY
        for (j in from..to) {
            val s = score(j, 2)
            if (s > bestScore) { bestScore = s; best = j }
        }
        return best
    }

    /** Drop input no later segment can reach. */
    private fun compact() {
        val drop = minOf(tail, Math.floor(ideal).toInt() - radius)
        if (drop < 4 * hop) return
        System.arraycopy(fifo, drop * channels, fifo, 0, (fifoFrames - drop) * channels)
        fifoFrames -= drop
        tail -= drop
        ideal -= drop
        dropped += drop
    }

    private fun ensureFifo(frames: Int) {
        if (fifo.size < frames * channels) fifo = fifo.copyOf(maxOf(frames * channels, fifo.size * 2))
    }

    private fun ensureOut(frames: Int) {
        if (out.size < frames * channels) out = out.copyOf(maxOf(frames * channels, out.size * 2))
    }
}

/** A deck's processing: the EQ/effects stage, then [tempo] in place of Sonic. No silence skipping. */
class DeckAudioChain(private val bend: AudioProcessor, private val tempo: TempoStretchProcessor) : AudioProcessorChain {
    private val processors = arrayOf(bend, tempo)
    override fun getAudioProcessors(): Array<AudioProcessor> = processors
    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        tempo.setSpeed(playbackParameters.speed)
        // Tempo only: the decks never bend pitch.
        return PlaybackParameters(playbackParameters.speed, 1f)
    }
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean) = false
    override fun getMediaDuration(playoutDuration: Long) = tempo.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount() = 0L
}
