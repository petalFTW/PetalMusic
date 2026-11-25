/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

/**
 * The "listen to the record" half of the automix. While a track plays we tap
 * its decoded PCM (via [EqBendProcessor]'s band filters) and keep a rolling,
 * downsampled energy profile: full-band RMS, a bass fraction, a top fraction
 * and an onset density. From that we find where the **instrumental outro**
 * actually starts — the stretch at the very end where the track drops its
 * energy / strips its low end / stops hitting, and never comes back.
 *
 * Deliberately cheap: no FFT, no ML, a few hundred floats per track, and it
 * runs on the audio thread as a by-product of the EQ bend.
 */
object StructureAnalyzer {

    private const val WINDOW_MS = 250L
    private const val MAX_WINDOWS = 1440 // ~6 minutes of history
    private const val MIN_RUN_MS = 6_000L
    /** Phrase length the mix start snaps to (8 bars = 32 beats). */
    private const val PHRASE_BARS = 8
    private const val TAIL_MIN_MS = 25_000L
    private const val TAIL_MAX_MS = 120_000L

    private class Win(
        val atMs: Long,
        val full: Float,
        val low: Float,
        val high: Float,
        val onset: Float,
    )

    private class Profile {
        val windows = ArrayList<Win>()
        var curBucket = Long.MIN_VALUE
        var sumFull = 0.0
        var sumLow = 0.0
        var sumHigh = 0.0
        var sumOnset = 0.0
        var n = 0
        var prevFull = -1f
    }

    private val profiles = HashMap<String, Profile>()

    /** One ~23 ms hop of band RMS from the audio thread. */
    @Synchronized
    fun feed(trackKey: String, full: Float, low: Float, high: Float, atMs: Long) {
        if (atMs < 0 || !full.isFinite() || !low.isFinite() || !high.isFinite()) return
        val p = profiles.getOrPut(trackKey) { Profile() }
        val bucket = atMs / WINDOW_MS
        if (bucket != p.curBucket) {
            flush(p)
            p.curBucket = bucket
        }
        p.sumFull += full.toDouble() * full
        p.sumLow += low.toDouble() * low
        p.sumHigh += high.toDouble() * high
        if (p.prevFull >= 0f) p.sumOnset += (full - p.prevFull).coerceAtLeast(0f).toDouble()
        p.prevFull = full
        p.n++
    }

    @Synchronized
    fun reset(trackKey: String) {
        profiles.remove(trackKey)
    }

    /**
     * Instrumental-outro start in the track's own timeline, or -1 when there
     * isn't enough signal to judge. [bpm]/[phaseMs] snap the answer to the grid.
     */
    @Synchronized
    fun detectOutro(trackKey: String, bpm: Float, phaseMs: Float, durationMs: Long? = null): Long {
        val w = profiles[trackKey]?.windows?.toList() ?: return -1
        if (w.size < 24) return -1
        val last = w.last().atMs
        // A breakdown halfway through a record isn't an ending. Future audio is
        // unknown; only make an outro claim inside the actual closing horizon.
        if (durationMs != null && durationMs - last > 45_000) return -1
        val first = w.first().atMs
        val span = last - first
        if (span < 40_000) return -1

        val tailLen = (span * 0.45).toLong().coerceIn(TAIL_MIN_MS, TAIL_MAX_MS)
        val tailStart = last - tailLen
        val body = w.filter { it.atMs in first..tailStart && it.full > 1e-6f }
        if (body.size < 12) return -1

        val bodyFull = median(body.map { it.full })
        if (bodyFull <= 1e-5f) return -1
        val bodyBassFrac = median(body.map { if (it.full > 1e-6f) it.low / it.full else 0f })
        val bodyOnset = median(body.map { it.onset })

        val fullFloor = bodyFull * 0.6f
        val bassFloor = bodyBassFrac * 0.5f
        val onsetFloor = bodyOnset * 0.5f

        val tail = w.filter { it.atMs >= tailStart }
        if (tail.size < 8) return -1

        fun dropped(win: Win): Boolean {
            val bassFrac = if (win.full > 1e-6f) win.low / win.full else 0f
            val energy = win.full < fullFloor
            val bass = bodyBassFrac > 0.05f && bassFrac < bassFloor && win.full < bodyFull * .9f
            // No drums/vocals hitting any more is another sign of an
            // instrumental tail — but only alongside *some* energy loss, so a
            // sustained full-power section is never mistaken for the outro.
            val calm = bodyOnset > 1e-4f && win.onset < onsetFloor && win.full < bodyFull * 0.85f
            return energy || bass || calm
        }

        // The outro must run to the very end of what we've heard. Walk back
        // from the last window, tolerating a brief fill but no real return to
        // the song's body.
        if (!dropped(tail.last())) return -1
        var startIdx = tail.size - 1
        var misses = 0
        for (i in tail.size - 2 downTo 0) {
            if (dropped(tail[i])) {
                startIdx = i
                misses = 0
            } else {
                misses++
                if (misses > 4) break
            }
        }

        val runMs = tail.last().atMs - tail[startIdx].atMs
        if (runMs < MIN_RUN_MS) return -1
        val start = tail[startIdx].atMs
        if (durationMs != null && durationMs - start > 45_000) return -1
        // Beat phase is NOT a phrase/downbeat annotation. Inventing a phrase
        // origin at file position zero moved cues up to eight seconds too early.
        return BeatAnalyzer.align(start, bpm, phaseMs)
    }

    /** Fold [ms] onto the nearest beat so the mix starts on the grid. */
    fun snapToBeat(ms: Long, bpm: Float, phaseMs: Float): Long {
        if (bpm <= 0f) return ms
        val beatMs = (60000.0 / bpm).toLong().coerceAtLeast(60)
        val phase = (phaseMs % beatMs).toLong()
        val since = (ms - phase).mod(beatMs)
        return if (since <= beatMs / 2) ms - since else ms + (beatMs - since)
    }

    /**
     * Fold [ms] onto the nearest [bars]-bar phrase. A transition that starts on
     * a phrase line never lands mid-chorus, which is the whole point.
     */
    fun snapToPhrase(ms: Long, bpm: Float, phaseMs: Float, bars: Int = PHRASE_BARS): Long {
        if (bpm <= 0f) return ms
        val phraseMs = (60000.0 / bpm * 4.0 * bars).toLong().coerceAtLeast(240)
        val phase = (phaseMs % phraseMs).toLong()
        val since = (ms - phase).mod(phraseMs)
        return if (since <= phraseMs / 2) ms - since else ms + (phraseMs - since)
    }

    /** Downsampled full-band envelope for [trackKey], normalised to 0..1. */
    @Synchronized
    fun envelope(trackKey: String, points: Int = 160): FloatArray {
        val w = profiles[trackKey]?.windows ?: return FloatArray(0)
        if (w.isEmpty()) return FloatArray(0)
        val n = points.coerceAtLeast(8)
        val out = FloatArray(n)
        var maxV = 1e-6f
        for (i in 0 until n) {
            val idx = (i.toLong() * w.size / n).toInt().coerceIn(0, w.size - 1)
            out[i] = w[idx].full
            if (out[i] > maxV) maxV = out[i]
        }
        for (i in out.indices) out[i] = (out[i] / maxV).coerceIn(0f, 1f)
        return out
    }

    private fun flush(p: Profile) {
        if (p.n == 0) return
        val fin = p.n.toDouble()
        p.windows.add(
            Win(
                atMs = p.curBucket * WINDOW_MS,
                full = kotlin.math.sqrt(p.sumFull / fin).toFloat(),
                low = kotlin.math.sqrt(p.sumLow / fin).toFloat(),
                high = kotlin.math.sqrt(p.sumHigh / fin).toFloat(),
                onset = (p.sumOnset / fin).toFloat(),
            ),
        )
        if (p.windows.size > MAX_WINDOWS) p.windows.subList(0, p.windows.size - MAX_WINDOWS).clear()
        p.sumFull = 0.0
        p.sumLow = 0.0
        p.sumHigh = 0.0
        p.sumOnset = 0.0
        p.n = 0
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
