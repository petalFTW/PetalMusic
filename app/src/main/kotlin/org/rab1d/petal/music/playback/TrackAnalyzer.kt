/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Whole-track analysis tuned for hip-hop, R&B and pop:
 *
 *  1. Onset strength from log-compressed flux in the low (kick), mid (snare
 *     body) and high (snare crack, hats) bands.
 *  2. Tempo from a comb-filtered autocorrelation with a log-normal prior around
 *     95 BPM, so half/double-time ambiguity resolves the way these genres count.
 *  3. Dynamic-programming beat tracking (Ellis 2007), then a robust linear fit of
 *     one constant grid to the tracked beats. Produced records are quantised, so
 *     the fit gives a phase within a few ms; live-feel drift lowers confidence.
 *  4. Bar phase from the backbeat: kick on 1, snare on 2 and 4, plus section
 *     changes on 1.
 *  5. Per-bar energy, vocal presence and drum activity; section boundaries from
 *     4-bar feature novelty; the phrase grid; intro and outro bars.
 *
 * Heuristics on band envelopes, not stem separation: "vocal" means sustained
 * 1–4 kHz content, which is what audibly clashes in a blend.
 */
internal object TrackAnalyzer {
    private const val MIN_BPM = 58.0
    private const val MAX_BPM = 190.0
    private const val PRIOR_BPM = 95.0
    private const val PRIOR_OCTAVES = .85

    data class Grid(val beatMs: Double, val phaseMs: Double, val confidence: Float)

    fun analyse(
        s: BandEnvelope.Snapshot,
        chroma: ChromaEnvelope.Snapshot? = null,
        complete: Boolean = false,
        key: KeyDetector.Key? = chroma?.let { keyOf(it) },
    ): TrackProfile? {
        if (s.size * s.hopMs < 15_000) return null
        val odf = onsets(s)
        val period = tempo(odf, s.hopMs) ?: return null
        val grid = grid(s, period) ?: return null
        // Beat k of the window sits at phase + k·beat.
        val first = ceil((s.originMs - grid.phaseMs) / grid.beatMs).toInt()
        val count = floor((s.endMs - grid.phaseMs) / grid.beatMs).toInt() - first
        if (count < 16) return null
        fun hopOf(k: Int) = (grid.phaseMs + (first + k) * grid.beatMs - s.originMs) / s.hopMs

        val low = flux(s[BandEnvelope.LOW], s)
        // A snare is a broadband burst: mid and high rise together. Hats are
        // high-only and vocals mostly mid-only, so require both.
        val snare = run {
            val h = flux(s[BandEnvelope.HIGH], s)
            val m = flux(s[BandEnvelope.MID], s)
            val hm = h.average().toFloat().coerceAtLeast(1e-6f)
            val mm = m.average().toFloat().coerceAtLeast(1e-6f)
            FloatArray(h.size) { i ->
                var hi = 0f; var mi = 0f
                for (j in i - 1..i + 1) if (j in h.indices) { hi = maxOf(hi, h[j]); mi = maxOf(mi, m[j]) }
                sqrt((hi / hm) * (mi / mm))
            }
        }
        fun peak(a: FloatArray, at: Double): Float {
            val c = at.roundToInt()
            var best = 0f
            for (i in c - 2..c + 2) if (i in a.indices) best = maxOf(best, a[i])
            return best
        }
        val kick = FloatArray(count) { peak(low, hopOf(it)) }
        val back = FloatArray(count) { peak(snare, hopOf(it)) }
        val beatFeatures = Array(count) { k ->
            val from = hopOf(k).toInt().coerceIn(0, s.size)
            val to = hopOf(k + 1).toInt().coerceIn(from, s.size)
            FloatArray(4) { b ->
                val band = s[intArrayOf(BandEnvelope.LOW, BandEnvelope.MID, BandEnvelope.HIGH, BandEnvelope.VOCAL)[b]]
                var acc = 0.0
                for (i in from until to) acc += band[i]
                ln(1e-4 + acc / (to - from).coerceAtLeast(1)).toFloat()
            }
        }
        val change = FloatArray(count) { k ->
            if (k == 0) 0f else (0..3).sumOf { abs(beatFeatures[k][it] - beatFeatures[k - 1][it]).toDouble() }.toFloat()
        }
        val harmony = chroma?.let { harmonicChange(it, count) { k -> grid.phaseMs + (first + k) * grid.beatMs } }
        val (bar, barConfidence) = barPhase(kick, back, change, harmony)
        val downbeat = bar?.let { grid.phaseMs + (first + it) * grid.beatMs }
        val firstBar = (downbeat ?: (grid.phaseMs + first * grid.beatMs))
        val barMs = grid.beatMs * 4
        val bars = floor((s.endMs - firstBar) / barMs).toInt()
        if (bars < 4) return null

        val energy = FloatArray(bars)
        val lowE = FloatArray(bars)
        val vocal = FloatArray(bars)
        val drums = FloatArray(bars)
        val vocalLevel = FloatArray(bars)
        val sustain = FloatArray(bars)
        val values = FloatArray(((barMs / s.hopMs).toInt() + 2).coerceAtLeast(1))
        for (b in 0 until bars) {
            val from = ((firstBar + b * barMs - s.originMs) / s.hopMs).toInt().coerceIn(1, s.size)
            val to = ((firstBar + (b + 1) * barMs - s.originMs) / s.hopMs).toInt().coerceIn(from, s.size)
            val n = to - from
            if (n <= 0) continue
            var e = 0.0; var l = 0.0; var d = 0.0; var v = 0.0
            for (i in from until to) {
                e += s[BandEnvelope.FULL][i]; l += s[BandEnvelope.LOW][i]
                d += low[i] + snare[i]
                v += s[BandEnvelope.VOCAL][i]
                values[i - from] = s[BandEnvelope.VOCAL][i]
            }
            energy[b] = (e / n).toFloat()
            lowE[b] = (l / n).toFloat()
            drums[b] = (d / n).toFloat()
            vocalLevel[b] = (v / n).toFloat()
            // Sustained content keeps a high floor between hits; drums do not.
            val sorted = values.copyOf(n).also { it.sort() }
            sustain[b] = (sorted[n / 5] / vocalLevel[b].coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        }
        val vocalMedian = median(vocalLevel).coerceAtLeast(1e-6f)
        val drumMedian = median(drums).coerceAtLeast(1e-6f)
        for (b in 0 until bars) {
            val level = (vocalLevel[b] / vocalMedian).coerceIn(0f, 2f)
            // Presence relative to the record's own level, weighted by sustain.
            vocal[b] = ((level * sustain[b] - .25f) / .45f).coerceIn(0f, 1f)
            drums[b] = (drums[b] / drumMedian).coerceIn(0f, 3f) / 3f
        }

        val (sections, phrase) = sections(energy, lowE, vocal, drums)
        val body = median(energy.copyOfRange(bars / 5, maxOf(bars / 5 + 1, bars * 4 / 5)))
        val intro = sections.firstOrNull { b ->
            b > 0 && avg(energy, b, b + 4) >= .8f * body && avg(energy, 0, b) < .7f * body
        } ?: 0
        val outro = sections.lastOrNull { b ->
            b > intro && b < bars && avg(energy, b, bars) < .7f * body && avg(energy, b - 4, b) >= .8f * body
        } ?: bars
        return TrackProfile(
            grid.beatMs, grid.phaseMs, grid.confidence, downbeat, barConfidence, firstBar,
            energy, lowE, vocal, drums, sections, phrase, intro, outro,
            key?.camelot, key?.minor, key?.confidence ?: 0f, s.originMs, s.endMs, complete,
        )
    }

    // ---- onsets ------------------------------------------------------------------

    private fun flux(env: FloatArray, s: BandEnvelope.Snapshot): FloatArray {
        val ref = median(sample(env)).coerceAtLeast(1e-6f)
        val out = FloatArray(env.size)
        var prev = ln(1f + 50f * env[0] / ref)
        for (i in 1 until env.size) {
            val c = ln(1f + 50f * env[i] / ref)
            out[i] = (c - prev).coerceAtLeast(0f)
            prev = c
        }
        return out
    }

    /** Combined onset strength, local mean removed, unit variance. */
    fun onsets(s: BandEnvelope.Snapshot): FloatArray {
        val n = s.size
        val odf = FloatArray(n)
        for ((band, w) in listOf(BandEnvelope.LOW to 1f, BandEnvelope.MID to .6f,
                BandEnvelope.HIGH to 1f, BandEnvelope.FULL to .4f)) {
            val f = flux(s[band], s)
            for (i in 0 until n) odf[i] += w * f[i]
        }
        val radius = (250 / s.hopMs).toInt().coerceAtLeast(1)
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + odf[i]
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - radius); val hi = minOf(n, i + radius + 1)
            out[i] = (odf[i] - ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()).coerceAtLeast(0f)
        }
        var sum = 0.0; var sq = 0.0
        for (v in out) { sum += v; sq += v.toDouble() * v }
        val mean = sum / n
        val std = sqrt((sq / n - mean * mean).coerceAtLeast(1e-12)).toFloat()
        for (i in out.indices) out[i] /= std
        return out
    }

    // ---- tempo -------------------------------------------------------------------

    /** Beat period in hops (fractional), or null when nothing periodic was found. */
    fun tempo(odf: FloatArray, hopMs: Double): Double? {
        val window = minOf(odf.size, (90_000 / hopMs).toInt())
        val start = (odf.size - window) / 2
        val minLag = floor(60000 / MAX_BPM / hopMs).toInt()
        val maxLag = ceil(60000 / MIN_BPM / hopMs).toInt()
        val top = minOf(maxLag * 4 + 2, window / 2)
        if (top <= maxLag) return null
        val acf = FloatArray(top + 1)
        for (lag in minLag..top) {
            var acc = 0.0
            for (i in start until start + window - lag) acc += odf[i] * odf[i + lag]
            acf[lag] = (acc / (window - lag)).toFloat()
        }
        fun at(x: Double): Double {
            val i = x.toInt()
            if (i + 1 > top) return 0.0
            val f = x - i
            return acf[i] * (1 - f) + acf[i + 1] * f
        }
        var best = -1.0
        var bestLag = 0.0
        var lag = minLag.toDouble()
        while (lag <= maxLag) {
            // Comb: a true beat period also explains its multiples.
            var comb = 0.0
            for (k in 1..4) comb += at(lag * k) / k
            val bpm = 60000 / (lag * hopMs)
            val octaves = ln(bpm / PRIOR_BPM) / ln(2.0)
            val score = comb * exp(-.5 * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
            if (score > best) { best = score; bestLag = lag }
            lag += .25
        }
        return if (best > 0) bestLag else null
    }

    // ---- beat grid ---------------------------------------------------------------

    /** Onsets that define beats: kick and snare body first, hats much less (they fill off-beats). */
    private fun beatOnsets(s: BandEnvelope.Snapshot): FloatArray {
        val out = FloatArray(s.size)
        for ((band, w) in listOf(BandEnvelope.LOW to 1f, BandEnvelope.MID to .8f, BandEnvelope.HIGH to .35f)) {
            val f = flux(s[band], s)
            for (i in out.indices) out[i] += w * f[i]
        }
        return out
    }

    /**
     * One constant grid for a quantised record: the period (±0.6 %) and phase
     * that maximise kick/snare onset support across the whole track. Confidence
     * combines how far that phase stands out from every other phase with
     * whether four separate segments agree on it; drift or a live feel disagrees.
     * Beat trackers wander between on- and off-beats in syncopated, hat-dense
     * hip-hop; a global fit does not.
     */
    fun grid(s: BandEnvelope.Snapshot, period: Double): Grid? {
        val odf = beatOnsets(s)
        val n = odf.size
        fun support(p: Double, phase: Double, from: Int = 0, to: Int = n): Double {
            var acc = 0.0
            var count = 0
            var t = phase + ceil((from - phase) / p) * p
            while (t < to - 1) {
                val i = t.toInt()
                val f = t - i
                acc += odf[i] * (1 - f) + odf[i + 1] * f
                count++
                t += p
            }
            return if (count == 0) 0.0 else acc / count
        }
        var best = -1.0
        var bp = period
        var bphi = 0.0
        var p = period * .994
        while (p <= period * 1.006) {
            var phi = 0.0
            while (phi < p) {
                val v = support(p, phi)
                if (v > best) { best = v; bp = p; bphi = phi }
                phi += .5
            }
            p += period * .0005
        }
        // Two finer passes: a period error of 0.005 % still drifts a hop over a
        // few hundred beats, and drift trades against the phase at the start.
        // Pivot on a beat mid-track so a period change does not drag the phase.
        for ((pStep, phiStep) in listOf(period * .0001 to .25, period * .00002 to .1, period * .000004 to .04)) {
            val p0 = bp
            val k = ((n / 2 - bphi) / p0).roundToInt()
            val pivot = bphi + k * p0
            for (dp in -10..10) for (dphi in -10..10) {
                val pp = p0 + dp * pStep
                val ph = (pivot + dphi * phiStep - k * pp).mod(pp)
                val v = support(pp, ph)
                if (v > best) { best = v; bp = pp; bphi = ph }
            }
        }
        if (best <= 0) return null
        // How far the winning phase stands out from all others.
        val curve = ArrayList<Double>()
        var phi = 0.0
        while (phi < bp) { curve += support(bp, phi); phi += 1.0 }
        val meanPhase = curve.average()
        val ratio = best / meanPhase.coerceAtLeast(1e-9)
        // Excluding the peak's own neighbourhood, how many deviations above the
        // other phases the winner sits. The best of thousands of noisy candidates
        // reaches ~3–4; a real grid stands far above.
        val others = curve.filterIndexed { i, _ ->
            val d = abs(i - bphi).let { minOf(it, bp - it) }
            d > 3
        }
        val mean = others.average()
        val sd = sqrt(others.map { (it - mean) * (it - mean) }.average()).coerceAtLeast(1e-9)
        val z = (best - mean) / sd
        // Quantised records keep the same phase in every segment.
        var agree = 0
        var valid = 0
        for (seg in 0 until 4) {
            val from = n * seg / 4
            val to = n * (seg + 1) / 4
            if (support(bp, bphi, from, to) <= 0.0) continue
            valid++
            var segBest = -1.0
            var segPhase = 0.0
            var d = -bp / 2
            while (d < bp / 2) {
                val v = support(bp, (bphi + d).mod(bp), from, to)
                if (v > segBest) { segBest = v; segPhase = d }
                d += .5
            }
            // Syncopated sections may favour an off-beat locally; the grid holds if
            // its own phase is nearly as well supported there.
            if (abs(segPhase) * s.hopMs <= 15) agree += 2
            else if (support(bp, bphi, from, to) >= .75 * segBest) agree++
        }
        // Dense hat-driven production keeps the contrast modest even when exact.
        val stand = ((z - 3.5) / 3.0).toFloat().coerceIn(0f, 1f)
        val consistency = if (valid == 0) 0f else agree.toFloat() / (2 * valid)
        val beatMs = bp * s.hopMs
        if (60000 / beatMs !in MIN_BPM..MAX_BPM) return null
        // Every segment agreeing is the strongest evidence of a quantised grid.
        return Grid(beatMs, s.originMs + bphi * s.hopMs, stand * consistency * consistency)
    }

    /**
     * Which beat of four starts the bar. Backbeat genres put the kick on 1 and the
     * snare on 2 and 4; sections change on 1. Null when the winner is unclear.
     */
    /**
     * Per-beat harmonic change: 1 − cosine between the chroma of beat k and beat
     * k−1. Chords and 808 notes move on bar lines.
     */
    fun harmonicChange(c: ChromaEnvelope.Snapshot, beats: Int, beatStart: (Int) -> Double): FloatArray {
        val vectors = Array(beats) { DoubleArray(ChromaEnvelope.BINS) }
        for (k in 0 until beats) {
            val from = beatStart(k)
            val to = beatStart(k + 1)
            val a = ((from - c.originMs) / c.hopMs - .5).let { ceil(it).toInt() }.coerceAtLeast(0)
            val b = ((to - c.originMs) / c.hopMs - .5).let { ceil(it).toInt() }.coerceAtMost(c.frames)
            for (f in a until b) for (bin in 0 until ChromaEnvelope.BINS) vectors[k][bin] += c.get(f, bin).toDouble()
        }
        fun cos(x: DoubleArray, y: DoubleArray): Double {
            var d = 0.0; var nx = 0.0; var ny = 0.0
            for (i in x.indices) { d += x[i] * y[i]; nx += x[i] * x[i]; ny += y[i] * y[i] }
            return if (nx <= 0 || ny <= 0) 1.0 else d / sqrt(nx * ny)
        }
        return FloatArray(beats) { k -> if (k == 0) 0f else (1 - cos(vectors[k], vectors[k - 1])).toFloat() }
    }

    /** Key from a whole snapshot's chroma, each frame normalised. */
    fun keyOf(c: ChromaEnvelope.Snapshot): KeyDetector.Key? {
        val sum = DoubleArray(ChromaEnvelope.BINS)
        for (f in 0 until c.frames) {
            var t = 0.0
            for (b in 0 until ChromaEnvelope.BINS) t += c.get(f, b)
            if (t <= 1e-9) continue
            for (b in 0 until ChromaEnvelope.BINS) sum[b] += c.get(f, b) / t
        }
        return KeyDetector.fromChroma(sum)
    }

    fun barPhase(kick: FloatArray, snare: FloatArray, change: FloatArray, harmony: FloatArray? = null): Pair<Int?, Float> {
        if (kick.size < 32) return null to 0f
        fun norm(a: FloatArray): FloatArray {
            val m = a.average().toFloat().coerceAtLeast(1e-6f)
            return FloatArray(a.size) { a[it] / m }
        }
        val k = norm(kick); val sn = norm(snare); val c = norm(change)
        val score = DoubleArray(4)
        for (d in 0..3) {
            var acc = 0.0
            for (i in k.indices) {
                acc += when ((i - d).mod(4)) {
                    0 -> k[i] - .5 * sn[i]
                    1 -> sn[i] - .3 * k[i]
                    2 -> .5 * k[i] - .5 * sn[i]
                    else -> sn[i] - .3 * k[i]
                }
            }
            score[d] = acc / k.size
        }
        // Sections and phrases change on "1". The largest beat-to-beat changes vote
        // for their bar position, which settles 1-versus-3 when kick and snare tie.
        val top = c.indices.sortedByDescending { c[it] }.take(maxOf(4, c.size / 32))
        val votes = DoubleArray(4)
        for (i in top) votes[i.mod(4)] += c[i].toDouble()
        val total = votes.sum().coerceAtLeast(1e-9)
        for (d in 0..3) score[d] += 1.2 * votes[d] / total
        // Harmonic changes vote the same way, and they separate 1 from 3.
        if (harmony != null && harmony.size == k.size) {
            val h = DoubleArray(4)
            for (i in harmony.indices) h[i.mod(4)] += harmony[i].toDouble()
            val ht = h.sum().coerceAtLeast(1e-9)
            for (d in 0..3) score[d] += 2.0 * h[d] / ht
        }
        val order = (0..3).sortedByDescending { score[it] }
        val best = score[order[0]]
        val margin = ((best - score[order[1]]) / (abs(best) + .5)).toFloat()
        return if (margin >= .06f) order[0] to margin.coerceIn(0f, 1f) else null to margin.coerceAtLeast(0f)
    }

    // ---- structure ---------------------------------------------------------------

    /** Section starts from 4-bar novelty, snapped to the phrase grid; and that grid's offset. */
    fun sections(vararg features: FloatArray): Pair<IntArray, Int> {
        val bars = features[0].size
        val z = features.map { f ->
            val m = f.average().toFloat()
            val sd = sqrt(f.map { (it - m) * (it - m) }.average().toFloat()).coerceAtLeast(1e-4f)
            FloatArray(bars) { (f[it] - m) / sd }
        }
        val novelty = FloatArray(bars)
        for (b in 2 until bars - 1) {
            var acc = 0.0
            for (f in z) {
                val before = avg(f, b - 4, b)
                val after = avg(f, b, b + 4)
                acc += (after - before) * (after - before)
            }
            novelty[b] = sqrt(acc).toFloat()
        }
        val phrase = (0..3).maxByOrNull { o ->
            (0 until bars).filter { (it - o).mod(4) == 0 && it >= 4 }.sumOf { novelty[it].toDouble() }
        } ?: 0
        val mean = novelty.average().toFloat()
        val sd = sqrt(novelty.map { (it - mean) * (it - mean) }.average().toFloat())
        val found = sortedSetOf(0)
        for (b in 2 until bars - 1) {
            if (novelty[b] < mean + .5f * sd) continue
            if (novelty[b] < novelty[b - 1] || novelty[b] < novelty[b + 1]) continue
            // Section changes land on phrase starts; move a near miss onto it.
            val snapped = (-1..1).map { b + it }.firstOrNull { it in 1 until bars && (it - phrase).mod(4) == 0 } ?: b
            if (found.none { abs(it - snapped) < 4 && it != 0 }) found += snapped
        }
        return found.toIntArray() to phrase
    }

    // ---- helpers -----------------------------------------------------------------

    private fun avg(a: FloatArray, from: Int, to: Int): Float {
        val lo = from.coerceIn(0, a.size); val hi = to.coerceIn(lo, a.size)
        if (hi <= lo) return 0f
        var acc = 0.0
        for (i in lo until hi) acc += a[i]
        return (acc / (hi - lo)).toFloat()
    }

    private fun sample(a: FloatArray): FloatArray {
        if (a.size <= 4000) return a.copyOf()
        val step = a.size / 4000.0
        return FloatArray(4000) { a[(it * step).toInt()] }
    }

    private fun median(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf().also { it.sort() }
        return s[s.size / 2]
    }
}
