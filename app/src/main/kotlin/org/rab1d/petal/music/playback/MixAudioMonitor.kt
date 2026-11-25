/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.exp
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicReferenceArray

/** Immutable four-band value used for both measurements and gain commands. */
data class BandLevels(val sub: Float, val bass: Float, val mid: Float, val high: Float) {
    operator fun get(band: Int): Float = when (band) {
        0 -> sub; 1 -> bass; 2 -> mid; 3 -> high
        else -> error("Invalid band $band")
    }
    fun energy() = sub * sub + bass * bass + mid * mid + high * high

    companion object {
        val ZERO = BandLevels(0f, 0f, 0f, 0f)
        val ONE = BandLevels(1f, 1f, 1f, 1f)
        fun of(values: FloatArray) = BandLevels(values[0], values[1], values[2], values[3])
    }
}

/**
 * Dry, pre-gain PCM measurements. One audio-thread writer; readers see immutable
 * snapshots. No locks or allocations per sample. Stereo energy is measured per
 * channel, so opposite-phase channels cannot disappear through mono cancellation.
 */
internal class MixAudioMonitor {
    data class Frame(
        val positionMs: Long,
        val observedMs: Long,
        val rms: Float,
        val bands: BandLevels,
        val baseline: BandLevels,
        val onset: BandLevels,
    ) {
        val confidence: Float get() = (observedMs / 240f).coerceIn(0f, 1f)
        fun share(band: Int) = bands[band] * bands[band] / bands.energy().coerceAtLeast(1e-10f)
    }

    private class History {
        val frames = AtomicReferenceArray<Frame>(400)
        @Volatile var count = 0
        fun add(frame: Frame) {
            frames.set(count % frames.length(), frame)
            count++
        }
    }
    @Volatile private var history = History()
    private val energy = DoubleArray(5)
    private val smooth = FloatArray(5)
    private val baseline = FloatArray(4)
    private val previous = FloatArray(4)
    private val onset = FloatArray(4)
    private var rate = 48000
    private var channels = 2
    private var windowFrames = 1920
    private var count = 0
    private var totalFrames = 0L
    private var originMs = 0L
    private var windows = 0

    fun configure(sampleRate: Int, channelCount: Int) {
        rate = sampleRate
        channels = channelCount
        windowFrames = (rate * .04f).toInt().coerceAtLeast(1)
        reset(0)
    }

    /** Move the timeline origin before any frame was measured (exact first-buffer time). */
    fun rebase(positionMs: Long) {
        if (totalFrames == 0L) originMs = positionMs.coerceAtLeast(0)
    }

    /** Called by the audio processor on flush, not concurrently from the UI. */
    fun reset(positionMs: Long) {
        history = History()
        energy.fill(0.0)
        smooth.fill(0f)
        baseline.fill(0f)
        previous.fill(0f)
        onset.fill(0f)
        count = 0
        totalFrames = 0
        windows = 0
        originMs = positionMs.coerceAtLeast(0)
    }

    fun sample(full: Float, sub: Float, bass: Float, mid: Float, high: Float) {
        energy[0] += sub.toDouble() * sub
        energy[1] += bass.toDouble() * bass
        energy[2] += mid.toDouble() * mid
        energy[3] += high.toDouble() * high
        energy[4] += full.toDouble() * full
    }

    fun endFrame() {
        totalFrames++
        if (++count < windowFrames) return
        val seconds = count.toFloat() / rate
        val fast = 1f - exp(-seconds / .12f)
        val slow = 1f - exp(-seconds / 1.5f)
        for (b in 0..4) {
            val rms = sqrt(energy[b] / (count * channels)).toFloat()
            if (b < 4) {
                // Relative positive spectral flux; startup is not a drum hit.
                val rise = if (windows == 0) 0f else
                    ((rms - previous[b]) / maxOf(rms, previous[b], .002f)).coerceIn(0f, 1f)
                onset[b] += fast * (rise - onset[b])
                baseline[b] = if (windows == 0) rms else baseline[b] + slow * (rms - baseline[b])
                previous[b] = rms
            }
            smooth[b] = if (windows == 0) rms else smooth[b] + fast * (rms - smooth[b])
            energy[b] = 0.0
        }
        windows++
        val frame = Frame(
            originMs + totalFrames * 1000 / rate, totalFrames * 1000 / rate,
            smooth[4], BandLevels.of(smooth), BandLevels.of(baseline), BandLevels.of(onset),
        )
        // 16 seconds cover normal decoder/audio-sink lookahead without unbounded growth.
        history.add(frame)
        count = 0
    }

    /** Follow the playhead, not the newest decoded buffer (which can be seconds ahead). */
    fun at(positionMs: Long): Frame? {
        val h = history
        val count = h.count
        for (offset in 1..minOf(count, h.frames.length())) {
            val frame = h.frames.get((count - offset) % h.frames.length()) ?: continue
            if (frame.positionMs <= positionMs + 80)
                return frame.takeIf { positionMs - it.positionMs <= 400 }
        }
        return null
    }
}
