/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.rab1d.petal.music.model.TrackAnalysis
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Onset-envelope tempo + phase estimation — the analysis half of the DJ
 * automix (the DJtransGAN/BitChord role, shrunken to run on-device in
 * milliseconds). Feed mono PCM at [analysisRate]; [analyze] returns BPM and
 * the phase (ms into the analysed window where beats land).
 */
object BeatAnalyzer {

    const val ANALYSIS_RATE = 11025
    const val MIN_BPM = 60f
    const val MAX_BPM = 200f

    /**
     * [phaseMs] is a beat offset from the envelope origin. [downbeatMs], when
     * present, is a beat whose accents recur every four beats; ties between
     * candidate bar phases (four-on-the-floor kicks, 1/3 vs 2/4) return null.
     */
    data class Result(
        val bpm: Float,
        val phaseMs: Float,
        val strength: Float,
        val downbeatMs: Float? = null,
    )

    /**
     * @param pcm mono float samples at [ANALYSIS_RATE].
     * @return null when the material is too short or too ambient to judge.
     */
    fun analyze(pcm: FloatArray, rate: Int = ANALYSIS_RATE): Result? {
        if (pcm.size < rate * 8) return null

        // Onset envelope: frame RMS (23ms hops), half-wave rectified diff.
        val hop = (rate * 0.023f).toInt().coerceAtLeast(1)
        val frames = pcm.size / hop - 1
        if (frames < 64) return null
        val rms = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0.0
            val start = f * hop
            for (i in start until start + hop) {
                val v = pcm[i]
                acc += v * v
            }
            rms[f] = sqrt(acc / hop).toFloat()
        }
        return analyzeEnvelope(rms, hop * 1000.0 / rate)
    }

    /**
     * Tempo, beat phase and (optionally) bar phase from hop RMS envelopes.
     * [hopMs] is exact; fractional hop lengths must not be rounded or the grid
     * slides across a long window. Positions are relative to [originMs].
     */
    fun analyzeEnvelope(
        rms: FloatArray,
        hopMs: Double,
        low: FloatArray? = null,
        high: FloatArray? = null,
        originMs: Double = 0.0,
    ): Result? {
        if (hopMs <= 0.0 || rms.size * hopMs < 8000.0) return null
        val frames = rms.size
        if (frames < 64) return null
        val onset = normalisedOnset(rms) ?: return null

        // Autocorrelation over beat-period lags (60..200 BPM).
        val minLag = (60000.0 / MAX_BPM / hopMs).toInt()
        val maxLag = (60000.0 / MIN_BPM / hopMs).toInt().coerceAtMost(onset.size / 2)
        if (maxLag <= minLag) return null
        val extendedLag = minOf(maxLag * 8, onset.size / 3)
        val correlations = FloatArray(extendedLag + 1)
        var bestLag = -1
        var bestScore = -1f
        for (lag in minLag..extendedLag) {
            var acc = 0f
            val n = onset.size - lag
            for (f in 0 until n) acc += onset[f] * onset[f + lag]
            // slight preference for the 80..160 BPM band to avoid octave jumps
            val bpm = 60000.0 / (hopMs * lag)
            val weight = if (bpm in 80.0..160.0) 1.15f else 1f
            correlations[lag] = acc / n
            val score = correlations[lag] * weight
            if (lag <= maxLag && score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null
        val peak = correlations[bestLag]
        val left = correlations.getOrElse(bestLag - 1) { peak }
        val right = correlations.getOrElse(bestLag + 1) { peak }
        val curvature = left - 2f * peak + right
        val fractional = if (bestLag > minLag && bestLag < maxLag && curvature < -1e-6f)
            (.5f * (left - right) / curvature).coerceIn(-.5f, .5f) else 0f
        var periods = (bestLag + fractional).toDouble()
        var weights = 1.0
        // Estimate the period over several beats. A one-hop lag error otherwise
        // drifts the grid by hundreds of milliseconds across an ordinary track.
        for (multiple in 2..8) {
            val expected = bestLag * multiple
            val radius = maxOf(2, multiple / 2)
            val lo = maxOf(minLag, expected - radius)
            val hi = minOf(extendedLag - 1, expected + radius)
            if (lo > hi) continue
            val lag = (lo..hi).maxByOrNull { correlations[it] } ?: continue
            val p = correlations[lag]
            if (p < maxOf(.2f, peak * .5f)) continue
            val curve = correlations[lag - 1] - 2f * p + correlations[lag + 1]
            val offset = if (curve < -1e-6f)
                (.5f * (correlations[lag - 1] - correlations[lag + 1]) / curve).coerceIn(-.5f, .5f) else 0f
            periods += multiple * (lag + offset).toDouble()
            weights += multiple * multiple
        }
        val beatFrames = (periods / weights).toFloat()
        val bpm = (60000.0 / (hopMs * beatFrames)).toFloat()
        if (bpm < MIN_BPM || bpm > MAX_BPM) return null

        // Phase: fold the onset envelope over the beat period; the strongest
        // phase bin is a beat offset, not a downbeat/phrase annotation.
        val bins = bestLag.coerceAtLeast(2)
        val fold = FloatArray(bins)
        for (f in onset.indices) {
            val phase = ((f + 1) / beatFrames).mod(1f) * bins
            val bin = phase.toInt().coerceIn(0, bins - 1)
            val fraction = phase - bin
            fold[bin] += onset[f] * (1f - fraction)
            fold[(bin + 1) % bins] += onset[f] * fraction
        }
        var bestBin = 0
        for (b in 1 until bins) if (fold[b] > fold[bestBin]) bestBin = b
        val phaseMs = (bestBin.toDouble() / bins * beatFrames * hopMs).toFloat()

        // Autocorrelation is already normalised by the number of observations.
        // Dividing again made even a perfect steady beat "unreliable".
        val strength = peak.coerceIn(0f, 1f)
        val downbeat = if (strength >= .3f) downbeat(rms, onset, low, high, hopMs,
            beatFrames * hopMs, phaseMs.toDouble()) else null
        return Result(bpm, (originMs + phaseMs).toFloat(), strength,
            downbeat?.let { (originMs + it).toFloat() })
    }

    /** Z-scored half-wave rectified flux of [rms]; null when the envelope is flat. */
    private fun normalisedOnset(rms: FloatArray): FloatArray? {
        val onset = FloatArray(rms.size - 1)
        for (f in 0 until rms.size - 1) onset[f] = (rms[f + 1] - rms[f]).coerceAtLeast(0f)
        var mean = 0f
        for (v in onset) mean += v
        mean /= onset.size
        var variance = 0f
        for (v in onset) variance += (v - mean) * (v - mean)
        val std = sqrt(variance / onset.size)
        if (std < 1e-5f) return null
        for (f in onset.indices) onset[f] = (onset[f] - mean) / std
        return onset
    }

    /**
     * Which of four beats begins the bar. Low-band hits, cymbal/high onsets and
     * beat-to-beat energy changes (section entries) all accent the first beat.
     * Needs at least eight bars, and a clear winner over the runner-up.
     */
    private fun downbeat(
        rms: FloatArray, full: FloatArray, low: FloatArray?, high: FloatArray?,
        hopMs: Double, beatMs: Double, phaseMs: Double,
    ): Double? {
        val beats = ((rms.size * hopMs - phaseMs) / beatMs).toInt()
        if (beats < 32) return null
        val lowOnset = low?.let { normalisedOnset(it) }
        val highOnset = high?.let { normalisedOnset(it) }
        fun peakNear(env: FloatArray, at: Double): Float {
            val centre = (at / hopMs).toInt() - 1
            var best = 0f
            for (i in centre - 1..centre + 1) if (i in env.indices) best = maxOf(best, env[i])
            return best
        }
        fun beatEnergy(k: Int): Double {
            val from = ((phaseMs + k * beatMs) / hopMs).toInt().coerceIn(0, rms.size)
            val to = ((phaseMs + (k + 1) * beatMs) / hopMs).toInt().coerceIn(from, rms.size)
            if (to <= from) return 0.0
            var acc = 0.0
            for (i in from until to) acc += rms[i]
            return acc / (to - from)
        }
        val energies = DoubleArray(beats) { beatEnergy(it) }
        val meanEnergy = energies.average().coerceAtLeast(1e-6)
        val score = DoubleArray(4)
        for (k in 1 until beats) {
            val at = phaseMs + k * beatMs
            var accent = .5 * peakNear(full, at)
            if (lowOnset != null) accent += peakNear(lowOnset, at)
            if (highOnset != null) accent += .5 * peakNear(highOnset, at)
            accent += 2.0 * kotlin.math.abs(energies[k] - energies[k - 1]) / meanEnergy
            score[k % 4] += accent
        }
        val order = (0..3).sortedByDescending { score[it] }
        val best = score[order[0]]
        if (best <= 0.0 || (best - score[order[1]]) / best < .08) return null
        return phaseMs + order[0] * beatMs
    }

    /** Nearest beat-aligned instant ≥ [fromMs], relative to a track's timeline. */
    fun align(fromMs: Long, bpm: Float, phaseMs: Float): Long {
        if (!bpm.isFinite() || bpm <= 0f || !phaseMs.isFinite()) return fromMs
        // Preserve fractional milliseconds; rounding the period accumulates
        // substantial phase error several minutes into a track.
        val beatMs = 60000.0 / bpm
        val phase = phaseMs.toDouble().mod(beatMs)
        val beat = kotlin.math.ceil((fromMs - phase) / beatMs)
        return kotlin.math.ceil(phase + beat * beatMs).toLong().coerceAtLeast(fromMs)
    }

    /**
     * Media position of the last beat of the grid ([phaseMs] + k·[beatMs]) at or
     * before [endMs]. A blend that ends here finishes exactly on a beat, so the
     * EQ handover lands on the outgoing's finishing beat rather than mid-bar.
     * Returns [endMs] when no grid is available.
     */
    fun lastBeat(phaseMs: Float, beatMs: Float, endMs: Long): Float {
        if (!phaseMs.isFinite() || !beatMs.isFinite() || beatMs <= 0f || endMs <= 0) {
            return endMs.toFloat()
        }
        val beats = kotlin.math.floor((endMs - phaseMs) / beatMs)
        return (phaseMs + beats * beatMs).toFloat()
    }

    /** Tempo ratio sanity for beatmatching (DJ ±8%). */
    fun beatmatchRatio(outBpm: Float, inBpm: Float): Float? {
        if (outBpm <= 0f || inBpm <= 0f) return null
        val ratio = outBpm / inBpm
        return if (abs(ratio - 1f) <= 0.08f) ratio else null
    }

    fun energy(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        var acc = 0.0
        for (v in pcm) acc += v * v
        return sqrt(acc / pcm.size).toFloat()
    }
}

/**
 * Analysis cache. Measured grids and intro profiles persist across launches so
 * streamed tracks — which cannot be decoded ahead — keep what an earlier play
 * learned. Metadata refreshes never discard a measured grid that agrees.
 */
object AnalysisCache {
    private const val MAX = 2000
    private const val FILE = "automix-analysis.tsv"
    private val map = object : LinkedHashMap<String, TrackAnalysis>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?) = size > MAX
    }
    private var file: java.io.File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PetalAnalysisStore").apply { isDaemon = true }
    }
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    fun get(key: String): TrackAnalysis? = map[key]

    @Synchronized
    fun put(key: String, a: TrackAnalysis) {
        val merged = a.withMeasurementsFrom(map[key])
        val changed = map[key] != merged
        map[key] = merged
        if (changed && (merged.gridded || merged.introDropMs != null || merged.drumIntro != null)) persist()
    }

    /** Load persisted measurements once; later calls are ignored. */
    fun attach(dir: java.io.File) {
        synchronized(this) {
            if (file != null) return
            file = java.io.File(dir, FILE)
        }
        writer.execute {
            val lines = runCatching { java.io.File(dir, FILE).readLines() }.getOrNull() ?: return@execute
            val loaded = lines.mapNotNull { decode(it) }
            synchronized(this) {
                // Live entries are newer than the file; keep them on top.
                for ((key, value) in loaded) if (key !in map) map[key] = value
            }
        }
    }

    /** Coalesced: live tracking refines grids every few seconds; write rarely. */
    private fun persist() {
        if (file == null || !pending.compareAndSet(false, true)) return
        writer.schedule({
            pending.set(false)
            val target = synchronized(this) { file } ?: return@schedule
            val text = synchronized(this) {
                map.entries.filter { it.value.gridded || it.value.introDropMs != null || it.value.drumIntro != null }
                    .joinToString("\n") { encode(it.key, it.value) }
            }
            runCatching {
                val tmp = java.io.File(target.parentFile, "$FILE.tmp")
                tmp.writeText(text)
                tmp.renameTo(target)
            }
        }, 20, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun encode(key: String, a: TrackAnalysis) = listOf(
        key.replace('\t', ' ').replace('\n', ' '), a.bpm, a.phaseMs, a.strength, a.camelot ?: "",
        a.authoritative, a.minor ?: "", a.downbeatMs ?: "", a.introDropMs ?: "", a.drumIntro ?: "",
    ).joinToString("\t")

    private fun decode(line: String): Pair<String, TrackAnalysis>? {
        val f = line.split('\t')
        if (f.size != 10) return null
        return runCatching {
            f[0] to TrackAnalysis(
                bpm = f[1].toFloat(), phaseMs = f[2].toFloat(), strength = f[3].toFloat(),
                camelot = f[4].toIntOrNull(), authoritative = f[5].toBoolean(),
                minor = f[6].toBooleanStrictOrNull(), downbeatMs = f[7].toFloatOrNull(),
                introDropMs = f[8].toLongOrNull(), drumIntro = f[9].toBooleanStrictOrNull(),
            )
        }.getOrNull()
    }
}
