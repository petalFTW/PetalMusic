/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

// Four warm crossover bands; Media3's integer path keeps this processor plugged in.
class EqBendProcessor : BaseAudioProcessor() {

    @Volatile private var gains = BandGainRamp.Gains()
    private val ramp = BandGainRamp()
    val subGain: Float get() = gains.sub
    val bassGain: Float get() = gains.bass
    val midGain: Float get() = gains.mid
    val highGain: Float get() = gains.high

    /** Publish all four targets atomically to the audio thread. */
    fun setGains(sub: Float, bass: Float, mid: Float, high: Float) {
        gains = BandGainRamp.Gains(sub, bass, mid, high)
    }

    // Dry full/low/high tap every ~23ms; don't block the audio thread here.
    @Volatile var sink: ((Float, Float, Float, Long) -> Unit)? = null

    private val monitor = MixAudioMonitor()
    @Volatile private var monitorOriginMs = 0L
    @Volatile private var monitorReady = false
    @Volatile private var resetPending = true

    /** Set before replacing/seeking this deck; the audio-thread flush resets history. */
    fun beginMonitoring(positionMs: Long) {
        monitorReady = false
        monitorOriginMs = positionMs.coerceAtLeast(0)
        resetPending = true
    }

    internal fun featuresAt(positionMs: Long): MixAudioMonitor.Frame? =
        if (monitorReady) monitor.at(positionMs) else null

    /** Dry band envelopes on this deck's exact media clock: a whole track's worth. */
    internal val envelope = BandEnvelope(BandEnvelope.hopsFor(9 * 60_000.0))
    internal val chroma = ChromaEnvelope(ChromaEnvelope.framesFor(9 * 60_000.0))
    private var vocalBand = VocalBand(48000)

    /**
     * AutoMix v2 arrangement for this deck. While set it owns the band gains and
     * the effects; clearing it hands back to [setGains] from the last applied level.
     */
    @Volatile internal var automation: DeckAutomation? = null
    private var appliedAutomation: DeckAutomation? = null
    private var fx = DjFx(48000, 2)
    private var blockLeft = 0
    // Frames processed since the last seek/item origin: the media clock of this audio.
    private var mediaFrames = 0L
    private var mediaOriginMs = 0.0
    // A seek resumes at a decoded-frame boundary (up to ~93 ms from the target for
    // FLAC), not at the target. The renderer reports each buffer's true time.
    @Volatile private var bufferTimeUs = Long.MIN_VALUE
    private var awaitingOrigin = false
    /** How far the first buffer after the last seek was from the requested position. */
    @Volatile var originCorrectionMs = 0.0
        private set

    /** Presentation time of the buffer about to be queued (playback thread). */
    internal fun noteBufferTime(us: Long) { bufferTimeUs = us }

    private var rate = 48000
    private var channels = 2
    private var cBass = 0f
    private var cMid = 0f
    private var crossover = Array(2) { DjCrossover(rate) }
    private val splitBands = FloatArray(4)
    private var floats = FloatArray(0)

    // ---- analysis (dry, mono) — kept separate from the bend filters above ----
    private var hopSize = 1024
    private var hopMs = 23L
    private var aLow = 0f
    private var aHigh = 0f
    private var sumFull = 0.0
    private var sumLow = 0.0
    private var sumHigh = 0.0
    private var n = 0
    private var hopIndex = 0L
    @Volatile private var anchorMs = -1L
    @Volatile private var anchorHop = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (bytesPerSample(inputAudioFormat.encoding) == null) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Same PCM format? Leave the filters alone or tempo changes click.
        if (rate == inputAudioFormat.sampleRate && channels == inputAudioFormat.channelCount &&
            inputAudioFormat == this.inputAudioFormat) return inputAudioFormat
        resetPending = true
        rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        monitor.configure(rate, channels)
        cBass = coeff(300.0)
        cMid = coeff(3000.0)
        crossover = Array(channels) { DjCrossover(rate) }
        fx = DjFx(rate, channels)
        vocalBand = VocalBand(rate)
        appliedAutomation = null
        hopSize = (rate * 0.023).toInt().coerceAtLeast(1)
        hopMs = (hopSize * 1000.0 / rate).toLong().coerceAtLeast(1)
        return inputAudioFormat
    }

    private fun coeff(hz: Double): Float =
        (1.0 - exp(-2.0 * PI * hz / rate)).toFloat()

    /** Re-anchor the hop clock to the player's position (called from the engine). */
    fun notePosition(ms: Long) {
        anchorMs = ms
        anchorHop = hopIndex
    }

    fun resetStructure() {
        aLow = 0f
        aHigh = 0f
        sumFull = 0.0
        sumLow = 0.0
        sumHigh = 0.0
        n = 0
        hopIndex = 0L
        anchorMs = -1L
        anchorHop = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val bytesPerSample = bytesPerSample(encoding) ?: return
        val count = inputBuffer.remaining() / bytesPerSample
        val frames = count / channels
        if (frames <= 0) return
        if (floats.size < count) floats = FloatArray(count)
        if (awaitingOrigin) {
            awaitingOrigin = false
            val us = bufferTimeUs
            val actual = us / 1000.0
            // Stale values (no MediaCodec renderer, or another stream) are ignored.
            if (us != Long.MIN_VALUE && abs(actual - mediaOriginMs) < 500) {
                originCorrectionMs = actual - mediaOriginMs
                mediaOriginMs = actual
                envelope.reset(actual, rate)
                chroma.reset(actual, rate)
                monitor.rebase(actual.toLong())
            }
        }

        readToFloat(inputBuffer, floats, count, encoding)
        val auto = automation
        if (auto !== appliedAutomation) {
            val previous = appliedAutomation
            appliedAutomation = auto
            // Continue from the level the arrangement left, never from a stale ramp.
            if (auto == null && previous != null)
                ramp.reset(BandGainRamp.Gains(fx.gain[0], fx.gain[1], fx.gain[2], fx.gain[3]))
            fx.reset()
            blockLeft = 0
        }
        ramp.setTarget(gains, rate)
        val analysis = sink != null
        // Stems and a clip only when they were cut from this deck's own PCM format.
        val stems = auto?.stems?.takeIf { it.rate == rate && it.channels == channels }
        val clip = auto?.clip?.takeIf { it.rate == rate && it.channels == channels && auto.deck.sampleAt >= 0f }
        val stemBase = stems?.let { Math.round((mediaOriginMs - it.startMs) * rate / 1000.0) } ?: 0L
        val clipBase = if (clip != null) Math.round((mediaOriginMs - (auto!!.originMs + auto.deck.sampleAt * auto.barMs)) *
            rate / 1000.0) else 0L
        // Keep the filters warm at unity, cold starts made the mix click.
        run {
            var i = 0
            for (f in 0 until frames) {
                val g = if (auto != null) {
                    if (blockLeft == 0) {
                        fx.beginBlock(auto, mediaFrames, mediaOriginMs)
                        blockLeft = DjFx.BLOCK
                    }
                    fx.gain
                } else {
                    ramp.advance()
                    ramp.current
                }
                val si = stemBase + mediaFrames
                val inStems = stems != null && si >= 0 && si < stems.frames
                val ci = clipBase + mediaFrames
                val inClip = clip != null && ci >= 0 && ci < clip.frames && fx.sample > 1e-4f
                var mono = 0f
                var lowMono = 0f
                var midMono = 0f
                var highMono = 0f
                for (c in 0 until channels) {
                    var x = floats[i + c]
                    if (inStems) {
                        // original + Σ (gain − 1) · stem: unity gains leave the deck untouched.
                        val at = (si * channels + c).toInt()
                        for (k in 0..3) {
                            val d = fx.stem[k] - 1f
                            if (d > 1e-4f || d < -1e-4f) x += d * stems!!.stems[k][at] * (1f / 32768f)
                        }
                    }
                    if (inClip) x += fx.sample * clip!!.pcm[(ci * channels + c).toInt()] * (1f / 32768f)
                    mono += x
                    crossover[c].split(x, splitBands)
                    val sub = splitBands[0]
                    val bassBand = splitBands[1]
                    val midBand = splitBands[2]
                    val highBand = splitBands[3]
                    lowMono += sub + bassBand
                    midMono += midBand
                    highMono += highBand
                    monitor.sample(x, sub, bassBand, midBand, highBand)
                    val y = sub * g[0] + bassBand * g[1] + midBand * g[2] + highBand * g[3]
                    floats[i + c] = if (auto != null) fx.process(c, y, mediaFrames) else y
                }
                if (auto != null) {
                    fx.endFrame(mediaFrames)
                    blockLeft--
                }
                val dry = mono / channels
                envelope.frame(dry, lowMono / channels, midMono / channels, highMono / channels,
                    vocalBand.process(dry))
                chroma.sample(dry)
                mediaFrames++
                monitor.endFrame()
                if (analysis) {
                    mono /= channels
                    aLow += cBass * (mono - aLow)
                    aHigh += cMid * (mono - aHigh)
                    val top = mono - aHigh
                    sumFull += mono.toDouble() * mono
                    sumLow += aLow.toDouble() * aLow
                    sumHigh += top.toDouble() * top
                    n++
                    if (n >= hopSize) emitHop()
                }
                i += channels
            }
        }

        val out = replaceOutputBuffer(count * bytesPerSample)
        writeFromFloat(floats, count, out, encoding)
        out.position(count * bytesPerSample)
        out.flip()
    }

    private fun emitHop() {
        val d = n.toDouble()
        val full = kotlin.math.sqrt(sumFull / d).toFloat()
        val lowR = kotlin.math.sqrt(sumLow / d).toFloat()
        val highR = kotlin.math.sqrt(sumHigh / d).toFloat()
        val at = if (anchorMs < 0) -1L else anchorMs + (hopIndex - anchorHop) * hopMs
        sink?.invoke(full, lowR, highR, at)
        hopIndex++
        sumFull = 0.0
        sumLow = 0.0
        sumHigh = 0.0
        n = 0
    }

    override fun onFlush() {
        // Tempo flushes aren't seeks; keep the history unless the track actually jumped.
        if (!resetPending) return
        resetPending = false
        awaitingOrigin = true
        monitor.reset(monitorOriginMs)
        mediaFrames = 0
        mediaOriginMs = monitorOriginMs.toDouble()
        envelope.reset(mediaOriginMs, rate)
        chroma.reset(mediaOriginMs, rate)
        vocalBand.reset()
        fx.reset()
        blockLeft = 0
        monitorReady = true
        ramp.reset(gains)
        crossover.forEach { it.reset() }
        aLow = 0f
        aHigh = 0f
        sumFull = 0.0
        sumLow = 0.0
        sumHigh = 0.0
        n = 0
    }

    override fun onReset() {
        resetPending = true
        onFlush()
        // Decoder resets aren't allowed to unmute this damn deck.
        ramp.reset(gains)
        monitorReady = false
    }

    private fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> null
    }

    private fun readToFloat(input: ByteBuffer, out: FloatArray, count: Int, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = input.asShortBuffer()
                for (i in 0 until count) out[i] = sb.get(i) / 32768f
                input.position(input.position() + count * 2)
            }
            C.ENCODING_PCM_FLOAT -> {
                input.asFloatBuffer().get(out, 0, count)
                input.position(input.position() + count * 4)
            }
            C.ENCODING_PCM_32BIT -> {
                val ib = input.asIntBuffer()
                for (i in 0 until count) out[i] = ib.get(i) / 2147483648f
                input.position(input.position() + count * 4)
            }
            C.ENCODING_PCM_24BIT -> {
                val pos = input.position()
                for (i in 0 until count) {
                    val b = pos + i * 3
                    val v = (input.get(b + 2).toInt() shl 16) or
                        ((input.get(b + 1).toInt() and 0xFF) shl 8) or
                        (input.get(b).toInt() and 0xFF)
                    out[i] = ((v shl 8) shr 8) / 8388608f
                }
                input.position(pos + count * 3)
            }
        }
    }

    private fun writeFromFloat(inF: FloatArray, count: Int, out: ByteBuffer, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = out.asShortBuffer()
                for (i in 0 until count) {
                    sb.put((inF[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> out.asFloatBuffer().put(inF, 0, count)
            C.ENCODING_PCM_32BIT -> {
                val ib = out.asIntBuffer()
                for (i in 0 until count) {
                    ib.put((inF[i].coerceIn(-1f, 1f) * 2147483647f).toLong().toInt())
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until count) {
                    val v = (inF[i].coerceIn(-1f, 1f) * 8388607f).toInt()
                    out.put((v and 0xFF).toByte())
                    out.put(((v shr 8) and 0xFF).toByte())
                    out.put(((v shr 16) and 0xFF).toByte())
                }
            }
        }
    }
}
