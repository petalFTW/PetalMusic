/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.audio

import org.rab1d.petal.music.desktop.library.DesktopTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * The Windows playback engine: two decks and the WinMixer tail ring in one
 * 48 kHz stereo output line. Queue-owner semantics mirror BridgeFXLink on the
 * ROM — arm a countdown, fire the boundary at the perfect instant, blend the
 * incoming track over the retained tail — all in software, no HAL needed.
 */
class BridgeFXWin(
    outputRate: Int = PcmIO.OUTPUT_RATE,
    outputChannels: Int = PcmIO.OUTPUT_CHANNELS,
) : AutoCloseable {

    @Volatile var fadeMs: Int = 6000
        set(value) {
            field = value.coerceIn(MIN_FADE_MS, MAX_FADE_MS)
            mixer.fadeMs = field
        }
    @Volatile var style: Int = STYLE_CLEAN
        set(value) {
            field = value.coerceIn(0, 3)
            mixer.style = field
        }
    @Volatile var automix: Boolean = true
    @Volatile var volume: Float = 1f

    var onEvent: ((Event) -> Unit)? = null

    private val mixer = WinMixer(outputRate, outputChannels)
    private val rate = outputRate
    private val channels = outputChannels
    private val lock = Any()

    private var line: SourceDataLine? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var loop: Thread? = null

    private var tracks: List<DesktopTrack> = emptyList()
    private var index = -1
    private var deck: Deck? = null
    private var positionFrames = 0L
    private var armAtFrame = Long.MAX_VALUE
    @Volatile private var boundaryArmed = false

    val engineName: String = NAME
    val currentTrack: DesktopTrack?
        get() = tracks.getOrNull(index)
    val positionMs: Long
        get() = positionFrames * 1000L / rate
    val durationMs: Long
        get() = currentTrack?.durationMs ?: 0L
    val isMixing: Boolean
        get() = mixer.mixing
    val isPlaying: Boolean
        get() = running && !paused && deck != null

    fun start() {
        synchronized(lock) {
            if (running) return
            val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
            val l = AudioSystem.getSourceDataLine(format)
            l.open(format, BUFFER_BYTES)
            l.start()
            line = l
            mixer.fadeMs = fadeMs
            mixer.style = style
            mixer.configure(rate, channels)
            running = true
            loop = thread(isDaemon = true, name = "BridgeFXWin") { run() }
        }
    }

    /** Replace the play queue and start [startIndex] immediately (hard cut). */
    fun setProgram(program: List<DesktopTrack>, startIndex: Int) {
        synchronized(lock) {
            tracks = program
            openLocked(startIndex)
        }
    }

    fun playNow(i: Int) {
        val program = tracks
        if (i !in program.indices) return
        if (running) transitionTo(i) else synchronized(lock) { openLocked(i) }
    }

    fun next() {
        val i = index + 1
        if (i < tracks.size) transitionTo(i) else stopPlayback()
    }

    fun previous() {
        if (positionMs > 5000) {
            seekTo(0)
            return
        }
        val i = index - 1
        if (i >= 0) transitionTo(i) else seekTo(0)
    }

    fun togglePause(): Boolean {
        synchronized(lock) {
            if (deck == null) return false
            paused = !paused
            if (paused) line?.stop() else line?.start()
            return !paused
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!paused) {
                paused = true
                line?.stop()
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            if (paused && deck != null) {
                paused = false
                line?.start()
            }
        }
    }

    /** Queue-owner parity with BridgeFXLink: schedule a boundary countdown. */
    fun arm(countdownMs: Int) {
        synchronized(lock) {
            armAtFrame = positionFrames + countdownMs.coerceIn(0, MAX_FADE_MS).toLong() * rate / 1000
        }
    }

    fun abort() {
        synchronized(lock) {
            armAtFrame = Long.MAX_VALUE
            boundaryArmed = false
            mixer.abort()
        }
    }

    /** Crossfade into the next queue item right now (boundary op). */
    fun boundary() {
        transitionTo(index + 1)
    }

    fun seekTo(ms: Long) {
        val track = currentTrack ?: return
        val opened = Deck.open(track) ?: return
        val target = (ms * rate / 1000).coerceAtLeast(0)
        val scratch = FloatArray(2048 * channels)
        var skipped = 0L
        while (skipped < target) {
            val n = opened.pull(scratch, 2048)
            if (n < 0) break
            skipped += n
        }
        synchronized(lock) {
            deck?.dispose()
            deck = opened
            positionFrames = skipped
            armAtFrame = Long.MAX_VALUE
            boundaryArmed = false
            mixer.abort()
        }
    }

    override fun close() {
        synchronized(lock) {
            running = false
            loop?.interrupt()
            loop = null
            deck?.dispose()
            deck = null
            tracks = emptyList()
            index = -1
            line?.let { runCatching { it.drain() } }
            line?.close()
            line = null
        }
    }

    fun stopPlayback() {
        synchronized(lock) {
            deck?.dispose()
            deck = null
            index = -1
            positionFrames = 0
            mixer.abort()
            paused = false
            onEvent?.invoke(Event.PlaybackStopped)
        }
    }

    private fun openLocked(startIndex: Int) {
        index = startIndex
        positionFrames = 0
        armAtFrame = Long.MAX_VALUE
        boundaryArmed = false
        mixer.abort()
        deck?.dispose()
        deck = null
        if (startIndex !in tracks.indices) return
        val opened = Deck.open(tracks[startIndex])
        if (opened == null) {
            onEvent?.invoke(Event.Error("cannot decode ${tracks[startIndex].path.fileName}"))
            return
        }
        deck = opened
        onEvent?.invoke(Event.TrackStarted(tracks[startIndex], startIndex))
    }

    /** Crossfade (or hard-cut when idle) into [newIndex]. */
    private fun transitionTo(newIndex: Int) {
        try {
            if (newIndex !in tracks.indices) {
                if (running) stopPlayback()
                return
            }
            val opened = Deck.open(tracks[newIndex])
            if (opened == null) {
                onEvent?.invoke(Event.Error("cannot decode ${tracks[newIndex].path.fileName}"))
                return
            }
            opened.prebuffer(PREBUFFER_MS)
            synchronized(lock) {
                if (!running) {
                    opened.dispose()
                    return
                }
                val fade = deck != null
                if (fade) mixer.markBoundary() else mixer.abort()
                armAtFrame = Long.MAX_VALUE
                val from = currentTrack
                deck?.dispose()
                deck = opened
                index = newIndex
                positionFrames = 0
                paused = false
                line?.start()
                onEvent?.invoke(Event.TransitionStarted(from, tracks[newIndex], if (fade) fadeMs else 0, style))
                onEvent?.invoke(Event.TrackStarted(tracks[newIndex], newIndex))
            }
        } finally {
            boundaryArmed = false
        }
    }

    private fun run() {
        val chunk = FloatArray(CHUNK * channels)
        var tick = 0
        while (running) {
            if (paused) {
                Thread.sleep(20)
                continue
            }
            val active = synchronized(lock) { deck }
            if (active == null) {
                Thread.sleep(40)
                continue
            }
            val n = active.pull(chunk, CHUNK)
            if (n < 0) {
                onDeckEnd(active)
                continue
            }
            if (n == 0) {
                Thread.sleep(5)
                continue
            }
            val blended = mixer.process(chunk, n)
            write(blended, n)
            positionFrames += n
            if (++tick % 8 == 0) checkArm()
        }
    }

    private fun onDeckEnd(finished: Deck) {
        synchronized(lock) {
            if (deck !== finished) return
            val nextIndex = index + 1
            if (nextIndex < tracks.size) {
                // AutoMix normally crossfades a few seconds before the end. If
                // it never armed (unknown/short duration), fall back to a
                // gapless handover so the queue keeps playing instead of
                // draining after every track.
                transitionToGaplessLocked(nextIndex)
            } else {
                deck = null
                positionFrames = 0
                onEvent?.invoke(Event.PlaybackEnded(tracks.getOrNull(index)))
            }
        }
    }

    private fun transitionToGaplessLocked(nextIndex: Int) {
        val opened = Deck.open(tracks[nextIndex])
        mixer.abort()
        deck?.dispose()
        deck = opened
        index = nextIndex
        positionFrames = 0
        armAtFrame = Long.MAX_VALUE
        if (opened == null) {
            onEvent?.invoke(Event.Error("cannot decode ${tracks[nextIndex].path.fileName}"))
        } else {
            onEvent?.invoke(Event.TrackStarted(tracks[nextIndex], nextIndex))
        }
    }

    /** The auto DJ: fire the boundary just before the track runs dry. */
    private fun checkArm() {
        val nextIndex = index + 1
        if (boundaryArmed || mixer.mixing || !automix || nextIndex >= tracks.size || deck == null) return
        val armedByUser = positionFrames >= armAtFrame
        val duration = durationMs
        val threshold = duration - fadeMs - LEAD_MS
        val autoBoundary = duration > 0 && threshold > 0 && positionMs >= threshold
        if (!armedByUser && !autoBoundary) return
        boundaryArmed = true
        thread(isDaemon = true, name = "BridgeFXWin/Boundary") { transitionTo(nextIndex) }
    }

    private fun write(samples: FloatArray, frames: Int) {
        val l = line ?: return
        val bytes = ByteBuffer.wrap(writeBuf).order(ByteOrder.LITTLE_ENDIAN)
        val vol = volume.coerceIn(0f, 1f)
        val count = frames * channels
        for (i in 0 until count) {
            val v = (samples[i] * vol).coerceIn(-1f, 1f)
            bytes.putShort((v * 32767f).toInt().toShort())
        }
        l.write(writeBuf, 0, count * 2)
    }

    private val writeBuf = ByteArray(CHUNK * channels * 2)

    /** One deck: streaming decoder feeding a small FIFO from a feeder thread. */
    private class Deck private constructor(private val source: PcmSource, val track: DesktopTrack) {
        private val fifo = FloatArray(FIFO_FRAMES * channels)
        private var head = 0
        private var tail = 0
        private var count = 0
        @Volatile private var srcEof = false
        @Volatile private var disposed = false

        private val feeder = thread(isDaemon = true, name = "DeckFeeder") {
            val tmp = FloatArray(2048 * channels)
            try {
                while (!disposed) {
                    val n = source.read(tmp, 2048)
                    if (n < 0) {
                        synchronized(this) {
                            srcEof = true
                            (this as java.lang.Object).notifyAll()
                        }
                        break
                    }
                    if (n == 0) continue
                    synchronized(this) {
                        while (count + n > FIFO_FRAMES && !disposed) {
                            (this as java.lang.Object).wait(50)
                        }
                        if (!disposed) {
                            var f = 0
                            while (f < n) {
                                val take = minOf(n - f, FIFO_FRAMES - count, FIFO_FRAMES - tail)
                                System.arraycopy(tmp, f * channels, fifo, tail * channels, take * channels)
                                tail = (tail + take) % FIFO_FRAMES
                                count += take
                                f += take
                            }
                            (this as java.lang.Object).notifyAll()
                        }
                    }
                }
            } finally {
                runCatching { source.close() }
            }
        }

        /** @return frames read, or -1 once the decoder is drained. */
        fun pull(dst: FloatArray, frames: Int): Int {
            synchronized(this) {
                if (count == 0) {
                    return if (srcEof) -1 else 0
                }
                val n = minOf(frames, count)
                var f = 0
                while (f < n) {
                    val take = minOf(n - f, FIFO_FRAMES - head)
                    System.arraycopy(fifo, head * channels, dst, f * channels, take * channels)
                    head = (head + take) % FIFO_FRAMES
                    count -= take
                    f += take
                }
                (this as java.lang.Object).notifyAll()
                return n
            }
        }

        fun prebuffer(ms: Int) {
            val deadline = System.currentTimeMillis() + 2000
            synchronized(this) {
                while (count < ms * rateFrames / 1000 && !srcEof && !disposed) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    (this as java.lang.Object).wait(left)
                }
            }
        }

        fun dispose() {
            disposed = true
            synchronized(this) { (this as java.lang.Object).notifyAll() }
            feeder.interrupt()
        }

        companion object {
            fun open(track: DesktopTrack): Deck? {
                val source = PcmIO.open(track.path) ?: return null
                return Deck(source, track)
            }
        }
    }

    sealed interface Event {
        data class TrackStarted(val track: DesktopTrack, val index: Int) : Event
        data class TransitionStarted(
            val from: DesktopTrack?,
            val to: DesktopTrack,
            val fadeMs: Int,
            val style: Int,
        ) : Event

        data class PlaybackEnded(val track: DesktopTrack?) : Event
        data object PlaybackStopped : Event
        data class Error(val message: String) : Event
    }

    companion object {
        const val NAME = "BridgeFXWin"

        /** DSP styles — values mirror bridge::Style / BridgeFXLink. */
        const val STYLE_CLEAN = 0
        const val STYLE_REVERB = 1
        const val STYLE_STUTTER = 2
        const val STYLE_ADAPTIVE = 3

        const val MIN_FADE_MS = 250
        const val MAX_FADE_MS = 12000

        private const val CHUNK = 1024
        private const val FIFO_FRAMES = 48000
        private const val PREBUFFER_MS = 120
        private const val LEAD_MS = 350
        private const val BUFFER_BYTES = 1 shl 15

        private val rateFrames = PcmIO.OUTPUT_RATE
        private val channels = PcmIO.OUTPUT_CHANNELS
    }
}
