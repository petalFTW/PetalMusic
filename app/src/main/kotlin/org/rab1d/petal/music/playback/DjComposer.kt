/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.rab1d.petal.music.model.TrackAnalysis
import org.rab1d.petal.music.playback.DjTransitionPlan.Deck
import org.rab1d.petal.music.playback.DjTransitionPlan.P
import org.rab1d.petal.music.playback.DjTransitionPlan.RollStep
import org.rab1d.petal.music.playback.DjTransitionPlan.Spin
import org.rab1d.petal.music.playback.DjTransitionPlan.SpinKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * AutoMix v2 composer, tuned for hip-hop, R&B and pop. Chooses where the
 * outgoing leaves (a phrase boundary, ideally where its vocal line ends or its
 * outro starts), where the incoming enters (its start, or a section so its verse
 * or hook lands on the swap), how (blend families when both grids are confident
 * and tempos sit within a few percent; cut families otherwise) and which fill
 * leads into the swap. Every candidate is scored from both tracks' profiles;
 * a stable per-pair seed varies near-equal choices.
 */
object DjComposer {

    enum class Family(val label: String, val blend: Boolean) {
        BASS_SWAP("Bass-swap blend", true),
        BEAT_UNDER("New beat under the vocal", true),
        FILTER_BLEND("Filter blend", true),
        HAT_TEASE("Hi-hat tease → drop", true),
        ECHO_THROW("Echo throw → cut", false),
        BACKSPIN("Backspin → cut", false),
        BRAKE("Tape stop → cut", false),
        STUTTER("Stutter → cut", false),
        FILTER_FADE("Filter fade → cut", false),
        /** A beatless incoming intro washes in under the outgoing's build; nothing can clash. */
        INTRO_WASH("Intro wash → echo out", false),
        /**
         * Outro over intro: both decks at full volume, the incoming untouched, the
         * outgoing's highs dipped and restored; the incoming's first full bar lands
         * where the outgoing ends.
         */
        OVERLAY("Overlay", true),
        /**
         * Handoff by the key bar: the outgoing loses its low end and highs on bar 0,
         * the incoming's low end ramps in, and the outgoing fades out just before
         * the key bar so the new track is alone from there.
         */
        HANDOFF("Handoff", true),
        /**
         * Context: the incoming's instrumental intro plays at its own tempo under the
         * outgoing's last bars (one low end at a time), and its first full bar lands on a
         * phrase line of the outgoing. For pairs that cannot be beat-matched. Not a
         * beat-synced blend, so [blend] is false.
         */
        BED_UNDER("Bed under", false),
        /**
         * Context with stems, beat-matched: only the incoming's drums come in (hats and
         * snare first, the kick on the halfway line, replacing the outgoing's), then its
         * bass line, then the rest on the key bar while the outgoing vocal rides the new beat.
         */
        DRUM_TEASE("Drum tease", true),
        /**
         * Context with stems, any tempo: the outgoing's instrumental drops out on a phrase
         * line and its last vocal line carries alone; the incoming drops in as the line ends.
         * Nothing rhythmic overlaps, so the tempos never meet.
         */
        ACAPELLA_OUT("Acapella out", false),
        /**
         * Context with stems, into a quieter or slower record: the incoming's hook vocal
         * (its stem only) sings over the outgoing's build, then the incoming starts from
         * its beginning on the cut.
         */
        VOCAL_PREVIEW("Vocal preview", false),
        /**
         * Context, distant tempos: the outgoing loops a clean bar or two, lets it play, then its
         * tempo is ridden audibly to the incoming's over 8-16 bars (key held) and the incoming
         * comes in beat-matched underneath on a phrase line, bass swapped halfway. The way DJs
         * bridge big tempo jumps with a pitch fader set wide.
         */
        LOOP_RIDE("Loop ride", false),
        /**
         * Creative, beat-matched, incoming stems: the incoming's vocal alone (its acapella)
         * comes in over the outgoing's beat; its own beat drops under it on the phrase line.
         */
        MASHUP("Acapella over the beat", true),
        /** Creative, beat-matched: both records' drops land on the same bar, one bass, then the outgoing leaves. */
        DOUBLE_DROP("Double drop", true),
    }

    /** Clean: plain phrase-aligned blends, no effects. Creative: every family, fill and exit. */
    enum class MixStyle { CLEAN, CREATIVE }

    data class Options(
        val style: MixStyle = MixStyle.CLEAN,
        /** Stretch the incoming to the outgoing's tempo; the song already playing keeps its speed. */
        val conformIncoming: Boolean = true,
        /** After the mix, glide a stretched incoming back to its native tempo. */
        val glideBack: Boolean = true,
        /** Prefer overlays that let the outgoing play to its own last bar. */
        val letOutrosPlay: Boolean = true,
        /** The outgoing deck's current playback speed (a held stretch from the last mix). */
        val currentSpeed: Float = 1f,
        /**
         * Ride the tempo like a DJ on the pitch fader: pairs up to [RIDE_STRETCH] apart are
         * matched by gliding the outgoing halfway over the bars before the mix and stretching
         * the incoming the rest, so neither deck moves more than ~4 %.
         */
        val tempoRide: Boolean = true,
        /**
         * Mix out like a DJ: once the outgoing's second chorus has paid off, not at its outro.
         * Needs a whole-file profile (streams play to their outro).
         */
        val djPacing: Boolean = true,
    )

    private val CLEAN_FAMILIES = setOf(Family.OVERLAY, Family.HANDOFF, Family.BASS_SWAP, Family.DRUM_TEASE)
    /** Blends that may loop the outgoing's clean bar 0 instead of running its vocal over the incoming. */
    private val LOOP_FAMILIES = setOf(Family.BASS_SWAP, Family.FILTER_BLEND, Family.HANDOFF)
    /** Creative's big moves, played when they fit and not twice in a row. */
    private val SHOWPIECES = setOf(Family.MASHUP, Family.DOUBLE_DROP)
    private const val SHOWPIECE_MARGIN = 3.0

    enum class Fill(val label: String) {
        NONE(""), ROLL("roll fill"), GATE("gate chop"), DROPOUT("bass drop-out"), THROW("echo throw"),
    }

    /** A deck's beat grid in its own media time. */
    data class Grid(val beatMs: Double, val firstBarMs: Double, val confidence: Float, val downbeat: Boolean) {
        val barMs: Double get() = beatMs * 4
        val bpm: Double get() = 60000 / beatMs
        fun barStart(i: Int) = firstBarMs + i * barMs
    }

    class Side(
        val profile: TrackProfile?,
        val analysis: TrackAnalysis?,
        val durationMs: Long,
        /** Stable identity for seeding (track key). */
        val id: String = "",
        /** Stems can be separated for this record (a readable file), so stem moves may use it. */
        val stems: Boolean = false,
    ) {
        fun withoutStems() = Side(profile, analysis, durationMs, id, false)
    }

    /** A fully arranged mix, ready for the renderer. */
    class Mix(
        val family: Family,
        val fill: Fill,
        /** Total length in bars; the incoming owns the mix at this bar. */
        val bars: Int,
        val swapBar: Int,
        /** Bar at which the incoming deck starts playing (0 for blends). */
        val entryBar: Int,
        /** Outgoing media position of bar 0 (a measured bar line). */
        val outStartMs: Long,
        /** Outgoing bar length in its own media time. */
        val outBarMs: Double,
        /** Outgoing playback speed for the whole mix (incoming stays native). */
        val speed: Float,
        val wallBarMs: Double,
        /** Incoming media position where it starts (its bar line). */
        val incomingCueMs: Long,
        val outgoing: Deck,
        val incoming: Deck,
        /** Keep the decks' beats locked with small tempo nudges while they overlap. */
        val sync: Boolean,
        /** Incoming enters at full volume on its bar (no fade-in ramp). */
        val hardEntry: Boolean,
        val bpmOut: Float?,
        val bpmIn: Float?,
        val notes: String,
        /**
         * The incoming is stretched to the outgoing's tempo instead of the other way
         * round; the outgoing keeps the speed it is already playing at.
         */
        val stretchIncoming: Boolean = false,
        /** Keep the outgoing at its current speed ([speed] is then only the composer's estimate). */
        val holdOutgoing: Boolean = false,
        /** Incoming bar length in its own media time. */
        val incomingBarMs: Double = wallBarMs,
        /** Glide a stretched incoming back to native tempo after the handover. */
        val glideBack: Boolean = false,
        /** Outgoing bar index of mix bar 0, and the incoming bar index at its cue. */
        val exitBar: Int = 0,
        val incomingBar: Int = 0,
        /** Glide the outgoing to this speed over the [rideBars] bars before bar 0 (null: no ride). */
        val rideSpeed: Float? = null,
        val rideBars: Int = 0,
        /** Media ranges (each deck's own clock) to separate before the mix; null when not needed. */
        val outStemsMs: LongRange? = null,
        val inStemsMs: LongRange? = null,
        /** Incoming media range whose vocal stem is voiced on the outgoing deck from [Deck.sampleAt]. */
        val clipMs: LongRange? = null,
        /**
         * In-mix tempo ride (Loop ride): the renderer moves the outgoing from its current speed
         * to [rampSpeed] between outgoing mix bars [rampFromBar] and [rampToBar].
         */
        val rampSpeed: Float? = null,
        val rampFromBar: Float = 0f,
        val rampToBar: Float = 0f,
    ) {
        val durationMs: Int get() = (bars * wallBarMs).roundToInt()

        @Volatile var outStems: StemWindow? = null
        @Volatile var inStems: StemWindow? = null
        @Volatile var clip: StemClip? = null
        /** The same pair composed without stems: played when separation is not ready in time. */
        var fallback: Mix? = null
        val needsStems: Boolean get() = outStemsMs != null || inStemsMs != null || clipMs != null
        val stemsReady: Boolean get() = (outStemsMs == null || outStems != null) &&
            (inStemsMs == null || inStems != null) && (clipMs == null || clip != null)

        /**
         * The incoming starts this much before its cue, muted, so the renderer can
         * measure and correct its timing before it is heard. Up to a bar.
         */
        val prerollMs: Long get() = minOf(maxOf(wallBarMs.toLong(), 1800L), incomingCueMs).takeIf { it >= 300 } ?: 0L
        val label: String get() = family.label + (if (fill != Fill.NONE) " · ${fill.label}" else "") + " · $bars bars"
    }

    private const val MIN_CONFIDENCE = .45f
    private const val MAX_STRETCH = .04
    /** With [Options.tempoRide], pairs up to this far apart are matched (half on each deck). */
    private const val RIDE_STRETCH = .08
    /** The outgoing is never ridden further than this from its native tempo. */
    private const val RIDE_MAX_OUT = .045
    private const val RIDE_BARS = 16
    /** Before a pair settles for a context move, a longer ride matches it up to this far apart. */
    private const val RIDE_FAR = .12
    private const val RIDE_MAX_OUT_FAR = .06
    private const val RIDE_BARS_FAR = 32
    /**
     * Loop ride: the outgoing's loop may be ridden this far from native (key held). Only the
     * loop is stretched, and the ride is meant to be heard, like a pitch fader set to wide.
     */
    private const val LOOP_RIDE_MAX = .30
    /** A bed may carry this much drum activity at an unrelated tempo; more needs the incoming's stems. */
    private const val BED_MAX_DRUMS = .12
    private const val OVERLAY_STRETCH = .06
    private const val INSTANT_MS = 6.0
    private const val MIN_PREROLL_MS = 1800.0
    /** An incoming intro is a bed for [Family.BED_UNDER] only below this average vocal. */
    private const val BED_MAX_VOCAL = .25
    /** One-bar-half 16th gate patterns ('.' = muted), read on the grid. */
    private val GATES = listOf("x.x.xx.x", "xx.xx.x.", "x..xx.xx")

    fun grid(side: Side): Grid? {
        // Even a weak grid places a cut on the beat better than a timed fade.
        side.profile?.takeIf { it.gridConfidence >= .05f }?.let {
            return Grid(it.beatMs, it.firstBarMs, it.gridConfidence, it.hasDownbeat)
        }
        val a = side.analysis?.takeIf { it.gridded && it.bpm in 40f..250f } ?: return null
        val beat = 60000.0 / a.bpm
        val origin = (a.downbeatMs ?: a.phaseMs).toDouble()
        return Grid(beat, origin.mod(beat * 4), a.strength.coerceAtMost(.6f), a.downbeatMs != null)
    }

    private fun camelot(side: Side): Pair<Int, Boolean?>? {
        val a = side.analysis
        val p = side.profile
        a?.camelot?.takeIf { it in 1..12 && a.authoritative }?.let { return it to a.minor }
        p?.takeIf { it.keyConfidence >= .08f }?.camelot?.let { return it to p.minor }
        return a?.camelot?.takeIf { it in 1..12 }?.let { it to a.minor }
    }

    /** Distant Camelot families, or a mode change between non-identical numbers. */
    fun clash(a: Pair<Int, Boolean?>?, b: Pair<Int, Boolean?>?): Boolean {
        if (a == null || b == null) return false
        val d = abs(a.first - b.first).let { minOf(it, 12 - it) }
        return d > 1 || (d == 1 && a.second != null && b.second != null && a.second != b.second)
    }

    private class Candidate(
        val family: Family, val exit: Int, val length: Int, val entry: Int, val fill: Fill,
        val exitStyle: Int, val score: Double, val why: String,
        /** The decks are tempo-matched (one is stretched) and beat-synced. */
        val stretch: Boolean = family.blend,
        /** Overlay only: which deck's low end is silenced over the overlap. */
        val killOutgoingLow: Boolean = false,
        /** Loop the outgoing's (vocal-free) bar 0 over the rest of the overlap. */
        val loopOut: Boolean = false,
    )

    /** The incoming's hook: its first sustained, full, sung stretch, preferably on a section line. */
    fun hook(p: TrackProfile?): Int? {
        p ?: return null
        val c = (maxOf(p.introEndBar, 2) until minOf(p.bars - 4, 64)).filter { h ->
            (h until h + 2).all { p.vocal(it) >= .6f } && p.energy(h) >= .8f * p.bodyEnergy
        }
        return c.firstOrNull { it in p.sections } ?: c.firstOrNull()
    }

    private fun meanEnergy(p: TrackProfile, from: Int, to: Int) =
        (from.coerceAtLeast(0) until to.coerceAtMost(p.bars)).map { p.energy(it) }.filter { it >= 0f }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0

    /** Loudness step from the outgoing's last body phrase to the incoming's opening, in dB (negative: a drop). */
    /** A drop: a section start where the energy or the drums jump in. */
    fun isDrop(p: TrackProfile?, b: Int): Boolean {
        p ?: return false
        if (b < 1 || b >= p.bars) return false
        val body = p.bodyEnergy
        return (p.energy(b) >= .95f * body && p.energy(b - 1) in 0f..(.8f * body)) ||
            (p.drums(b) >= .4f && p.drums(b - 1) in 0f..(.2f))
    }

    /**
     * Where a DJ would mix out: the end of the second chorus (a sung section at body energy),
     * so the crowd has had the payoff; the last chorus when there is only one late in the
     * record. Null when the profile is partial or no chorus is found.
     */
    fun payoffBar(p: TrackProfile?): Int? {
        p ?: return null
        if (!p.complete) return null
        val bounds = (p.sections.toList() + p.bars).distinct().sorted()
        // Sung sections (≥ 4 bars) with their mean energy; choruses are the loudest of them.
        val sung = ArrayList<Pair<Int, Double>>()
        for (k in 0 until bounds.size - 1) {
            val a = bounds[k]
            val b = bounds[k + 1]
            if (b - a < 4) continue
            val v = (a until b).map { p.vocal(it).coerceAtLeast(0f) }.average()
            val e = (a until b).map { p.energy(it).coerceAtLeast(0f) }.average()
            if (v >= .5) sung += b to e
        }
        val peak = sung.maxOfOrNull { it.second } ?: return null
        val choruses = sung.filter { it.second >= .97 * peak && it.second >= .95 * p.bodyEnergy }.map { it.first }
        val end = when {
            choruses.size >= 2 -> choruses[1]
            choruses.size == 1 && choruses[0] >= p.bars * .5 -> choruses[0]
            else -> return null
        }
        // Never before ~45 % of the record, and never inside its last 8 bars anyway.
        return end.coerceAtLeast((p.bars * .45).toInt()).takeIf { it < p.bars - 8 }
    }

    /**
     * Energy flow at the handover (Mixed In Key's rules): a high section into an intro drops
     * the floor too fast, a breakdown into a breakdown stalls, a breakdown straight into a peak
     * gives no time to rise.
     */
    private fun energyFlow(op: TrackProfile?, outBar: Int, ip: TrackProfile?, inBar: Int): Double {
        if (op == null || ip == null) return 0.0
        val o = op.energy(outBar).takeIf { it >= 0f }?.div(op.bodyEnergy.coerceAtLeast(1e-6f)) ?: return 0.0
        val i = ip.energy(inBar).takeIf { it >= 0f }?.div(ip.bodyEnergy.coerceAtLeast(1e-6f)) ?: return 0.0
        return when {
            o >= .95f && i < .6f -> -1.0
            o < .6f && i < .6f -> -.8
            o < .6f && i >= 1.1f -> -.5
            // Rising or holding: normal or high into normal or high.
            o >= .8f && i >= .8f -> .3
            else -> 0.0
        }
    }

    fun dropDb(op: TrackProfile, ip: TrackProfile): Double {
        val outEnd = op.outroStartBar.coerceIn(minOf(8, op.bars), op.bars)
        val outE = meanEnergy(op, outEnd - 16, outEnd)
        val inE = meanEnergy(ip, 0, 8)
        return if (outE > 0 && inE > 0) 10 * log10(inE / outE) else 0.0
    }

    /** First bar at or after [from] where the record is fully in: drums and body energy. */
    private fun firstFullBar(p: TrackProfile?, from: Int, span: Int = 24): Int? {
        p ?: return null
        val body = p.bodyEnergy
        for (b in from until minOf(p.bars, from + span)) {
            if (p.drums(b) >= .25f && p.energy(b) >= .9f * body) return b
        }
        return null
    }

    private fun lowAt(p: TrackProfile?, i: Int): Float = p?.barLow?.let { if (i in it.indices) it[i] else -1f } ?: -1f

    private fun avg(from: Int, to: Int, f: (Int) -> Float): Float? {
        if (to <= from) return null
        val v = (from until to).map(f)
        if (v.any { it < 0f }) return null
        return v.average().toFloat()
    }

    /**
     * @param earliestStartMs outgoing media position before which the mix cannot
     *   start (preparation and control latency).
     */
    /** A transition the engine already played, newest first in [compose]'s list. */
    data class Played(val family: Family, val bars: Int, val fill: Fill = Fill.NONE)

    fun compose(
        out: Side, inc: Side, earliestStartMs: Long, recent: List<Played>, seed: Int, beatAlign: Boolean = true,
        options: Options = Options(),
    ): Mix? {
        val mix = composeWith(out, inc, earliestStartMs, recent, seed, beatAlign, options, RIDE_STRETCH) ?: return null
        // A stem move needs separation to finish before it fires; the same pair without
        // stems is ready to play instead.
        if (mix.needsStems && (out.stems || inc.stems))
            mix.fallback = composeWith(out.withoutStems(), inc.withoutStems(), earliestStartMs, recent, seed, beatAlign,
                options, RIDE_STRETCH)
        return mix
    }

    private fun composeWith(
        out: Side, inc: Side, earliestStartMs: Long, recent: List<Played>, seed: Int, beatAlign: Boolean,
        options: Options, rideLimit: Double,
    ): Mix? {
        // No usable grid or no move that fits: v2 has nothing musical to offer, so the
        // engine plays the pair with v1's smooth blend instead (null).
        val og = grid(out) ?: return null
        val ig = grid(inc)
        val op = out.profile
        val ip = inc.profile
        val clean = options.style == MixStyle.CLEAN
        // The outgoing may still be held at the tempo it was stretched to last mix.
        val s0 = if (options.conformIncoming) options.currentSpeed.coerceIn(.9f, 1.1f).toDouble() else 1.0
        // Incoming tempo relative to the outgoing as it is heard now.
        val speed = ig?.let { it.bpm / (og.bpm * s0) }
        // Riding the tempo splits the stretch between the decks, so it reaches further.
        val ride = options.tempoRide && options.conformIncoming
        val matched = beatAlign && speed != null && abs(speed - 1) <= (if (ride) rideLimit else MAX_STRETCH) &&
            og.confidence >= MIN_CONFIDENCE && ig.confidence >= MIN_CONFIDENCE
        // An overlay stretches only the beatless side's partner, so it tolerates more.
        val looseMatch = beatAlign && speed != null && abs(speed - 1) <= OVERLAY_STRETCH &&
            og.confidence >= MIN_CONFIDENCE && ig.confidence >= MIN_CONFIDENCE
        val keyClash = clash(camelot(out), camelot(inc))
        // What each deck is stretched by: half each when the outgoing rides.
        val stretchCost = speed?.let { if (ride && abs(it - 1) > .02) abs(kotlin.math.sqrt(it) - 1) else abs(it - 1) } ?: 0.0
        val hookBar = if (clean) null else hook(ip)
        // Grid-locked repeats and chops need a grid that is right to the beat.
        val tight = og.confidence >= .6f
        val recentQuick = recent.take(2).count { it.bars <= 3 }
        val pulseMatch = speed != null && listOf(1.0 to .1, .5 to .05, 2.0 to .1).any { (r, tol) -> abs(speed / r - 1) <= tol }
        val lastBar = floor((out.durationMs - 400 - og.firstBarMs) / og.barMs).toInt() - 1
        val firstBar = ceil((earliestStartMs - og.firstBarMs) / og.barMs).toInt().coerceAtLeast(0)
        if (lastBar < firstBar + 1) return null
        fun jitter(vararg parts: Any): Double {
            var h = seed
            for (p in parts) h = h * 31 + p.hashCode()
            h = h xor (h ushr 15)
            h *= -0x7a143595
            h = h xor (h ushr 13)
            return ((h ushr 8) and 0xFFFF) / 65536.0
        }
        fun boundary(p: TrackProfile?, i: Int): Double = when {
            p == null -> if (i % 4 == 0) .5 else 0.0
            i in p.sections -> 2.0
            p.isPhraseStart(i, 8) -> 1.0
            p.isPhraseStart(i, 4) -> .5
            else -> 0.0
        }
        fun vocal(p: TrackProfile?, i: Int) = p?.vocal(i)?.takeIf { it >= 0f } ?: .5f
        fun avgVocal(p: TrackProfile?, from: Int, to: Int) =
            if (to <= from) 0f else (from until to).map { vocal(p, it) }.average().toFloat()
        fun skippedOutS(exitBar: Int) = ((out.durationMs - og.barStart(exitBar)) / 1000).coerceAtLeast(0.0)
        val payoff = if (options.djPacing) payoffBar(op) else null
        /** What leaving at outgoing bar [end] costs: before the payoff a lot; after it, a little per bar. */
        fun skipCost(end: Int): Double = if (payoff != null) {
            if (end < payoff) 3.0 else ((end - payoff - 16).coerceAtLeast(0) * .03).coerceAtMost(1.5)
        } else ((skippedOutS(end) - 15).coerceAtLeast(0.0) * .05).coerceAtMost(3.0)
        // Blends may start early so a section lands on the swap; a cut starts the
        // incoming exactly at the bar it enters on.
        fun inEntries(swap: Int, blend: Boolean): List<Int> {
            if (ig == null) return listOf(0)
            val set = sortedSetOf(0)
            if (ip != null) {
                for (b in ip.sections.filter { it <= 40 } + ip.introEndBar) {
                    if (b in 0..40) set += b
                    if (blend && b - swap in 0..40) set += b - swap
                }
            }
            // Where the incoming starts singing: an acapella-over entry point.
            if (blend && ip != null) for (b in 1..40) if (ip.vocal(b) >= .5f && ip.vocal(b - 1) in 0f..(.5f)) set += b
            // Creative cuts may drop straight onto the incoming's hook, like a DJ cutting to the chorus.
            if (!blend && hookBar != null && ig.barStart(hookBar) <= 50_000) set += hookBar
            // Never start the incoming deep in its song: at most ~16 s in, unless
            // everything before that point is near-silent (a long quiet intro).
            return set.filter { j ->
                ig.barStart(j) >= 0 && (ig.barStart(j) <= 16_000 || (!blend && j == hookBar) ||
                    (ip != null && (0 until j).all { ip.energy(it) < .3f * ip.bodyEnergy }))
            }
        }
        fun entryScore(j: Int, swap: Int, blend: Boolean): Pair<Double, String> {
            if (ig == null || ip == null) return (if (j == 0) .4 else -1.0) to ""
            var s = 0.0
            // Starting the incoming late skips its song; a short intro skip is fine,
            // a whole verse is not.
            val skipped = ig.barStart(j) / 1000
            val onHook = !blend && j == hookBar
            s -= ((skipped - 6).coerceAtLeast(0.0) * .14).coerceAtMost(4.5) * if (onHook) .3 else 1.0
            if (onHook) s += 1.0
            val landing = j + swap
            val landsOnSection = landing in ip.sections || landing == ip.introEndBar
            if (blend) {
                if (landsOnSection && landing > 0) s += 1.2
                if (ip.isPhraseStart(j)) s += .4
                if (avgVocal(ip, j, j + swap) < .35f) s += .6
            } else {
                if ((j in ip.sections || j == ip.introEndBar) && ip.energy(j) >= .8f * ip.bodyEnergy) s += 1.2
                if (j == 0) s += if (ip.introEndBar <= 2) .6 else .2
                // A cut must land on something: never drop into a quiet intro.
                val lands = ip.energy(j) >= .6f * ip.bodyEnergy && (ip.drums(j) >= .25f || ip.vocal(j) >= .5f)
                // Near-silence is never a landing; a soft but musical intro is a weak one.
                if (!lands) s -= if (ip.energy(j) >= .3f * ip.bodyEnergy) 1.5 else 3.0
            }
            return s to "in@$j${if (landsOnSection) "(section)" else ""}"
        }

        /** How a blend's outgoing leaves, or a cut's breath, from what is playing there. */
        fun exitBy(family: Family, i: Int, length: Int, j: Int): Int {
            val end = i + length
            val lineEnds = vocal(op, end - 1) >= .5f && vocal(op, end) < .5f
            return if (family.blend) when {
                lineEnds -> 0 // throw the last word into the echo
                op != null && op.energy(end - 1) >= op.bodyEnergy -> 2 // busy to the end: filter it away
                else -> 1
            } else when {
                ip != null && ip.energy(j) >= .9f * ip.bodyEnergy && ip.drums(j) >= .5f -> 2 // a breath before a big drop
                lineEnds -> 1
                else -> 0
            }
        }
        /** The fill into a blend's swap, from what is playing there (none when nothing calls for one). */
        fun fillBy(i: Int, swap: Int, j: Int): Fill {
            val at = i + swap
            // Every fill the music calls for, strongest reason first.
            val fits = ArrayList<Fill>(4)
            if (vocal(op, at - 1) >= .5f && vocal(op, at) < .5f) fits += Fill.THROW
            if (ip != null && (j + swap) in ip.sections && ip.energy(j + swap) >= ip.bodyEnergy) fits += Fill.ROLL
            if (lowAt(op, at - 1) >= .6f) fits += Fill.DROPOUT
            if ((op?.drums(at - 1) ?: 0f) >= .5f) fits += Fill.GATE
            if (!tight) fits.removeAll(listOf(Fill.ROLL, Fill.GATE))
            if (fits.isEmpty()) return Fill.NONE
            // A set does not repeat its last two fills when another one fits as well; among
            // fills that all suit the music, the pair decides.
            val used = recent.map { it.fill }.filter { it != Fill.NONE }.take(2)
            val fresh = fits.filter { it !in used }.ifEmpty { fits }
            return fresh[(jitter("fill", i, j) * fresh.size).toInt().coerceIn(0, fresh.size - 1)]
        }
        val inBpmRatio = ig?.let { it.bpm / (og.bpm * s0) }

        val candidates = ArrayList<Candidate>()
        val exitRange = maxOf(firstBar, minOf(lastBar - 48, (payoff ?: lastBar) - 4))..lastBar
        for (family in Family.entries) {
            if (clean && family !in CLEAN_FAMILIES) continue
            // Cuts stop one record before the next starts; mixes keep both playing. A pair that
            // cannot blend gets a context move instead (contextMove), never a cut.
            if (!family.blend && family != Family.INTRO_WASH) continue
            // Chosen by context after the search (contextMove), never scored here.
            if (family == Family.BED_UNDER || family == Family.ACAPELLA_OUT || family == Family.VOCAL_PREVIEW) continue
            if (family == Family.DRUM_TEASE && (!inc.stems || ig == null || ip == null)) continue
            if (family == Family.MASHUP && (!inc.stems || keyClash || ig == null || ip == null)) continue
            if (family == Family.DOUBLE_DROP && (keyClash || ig == null || ip == null)) continue
            // With the incoming's stems the drum tease is the same move without the EQ guesswork.
            if (family == Family.BEAT_UNDER && inc.stems) continue
            // An overlay over beatless material needs no tempo match; it decides per entry.
            if (family.blend && !matched && family != Family.OVERLAY) continue
            if (family == Family.OVERLAY && (ig == null || ip == null)) continue
            // Hip-hop and R&B blends are short; only uptempo pop earns 16 bars.
            val long = (ig?.bpm ?: 0.0) >= 110
            val lengths = when (family) {
                Family.BASS_SWAP -> if (long) intArrayOf(4, 8, 16) else intArrayOf(4, 8)
                Family.BEAT_UNDER -> intArrayOf(4, 8)
                Family.FILTER_BLEND -> if (long) intArrayOf(8, 16) else intArrayOf(4, 8)
                Family.HAT_TEASE -> intArrayOf(4)
                // An overlay spans the incoming intro; a handoff is the overlap before the key bar.
                Family.OVERLAY -> intArrayOf(4, 8, 16)
                Family.HANDOFF -> intArrayOf(4)
                Family.DRUM_TEASE -> intArrayOf(4, 8)
                Family.MASHUP -> intArrayOf(4, 8)
                Family.DOUBLE_DROP -> intArrayOf(4)
                // A beat repeat imposes the outgoing's pulse right up to the drop; only
                // when the incoming moves at (nearly) the same pulse.
                Family.STUTTER -> if (tight && pulseMatch) intArrayOf(1, 2, 4) else intArrayOf()
                Family.INTRO_WASH -> intArrayOf(4, 8)
                // Cuts carry a build of 1, 2, 4 or 8 bars before the drop.
                else -> intArrayOf(1, 2, 4, 8)
            }
            for (length in lengths) {
                val tail = when (family) {
                    Family.BEAT_UNDER, Family.HAT_TEASE, Family.MASHUP -> 1
                    Family.DOUBLE_DROP -> 6
                    Family.BASS_SWAP, Family.FILTER_BLEND, Family.OVERLAY, Family.HANDOFF, Family.DRUM_TEASE -> 0
                    // Cuts let the outgoing's echo ring two bars over the incoming.
                    else -> 2
                }
                // The bar where the mix's key moment lands: the bass swap, the
                // vocal handoff, the drop or the cut.
                val swap = when (family) {
                    Family.BASS_SWAP, Family.FILTER_BLEND -> length / 2
                    else -> length
                }
                for (i in exitRange) {
                    if (i + length + tail > lastBar + 1) continue
                    // Score the outgoing side.
                    var s = when (family) {
                        Family.BASS_SWAP -> 2.0
                        Family.BEAT_UNDER -> 2.0
                        Family.FILTER_BLEND -> 1.4
                        Family.HAT_TEASE -> 1.5
                        // Cuts start level; evidence and the pair seed pick between them.
                        Family.ECHO_THROW -> 1.0
                        Family.STUTTER -> 1.1
                        Family.BACKSPIN -> 1.1
                        Family.FILTER_FADE -> .9
                        Family.BRAKE -> 1.05
                        Family.INTRO_WASH -> 2.0
                        Family.OVERLAY -> 2.4
                        Family.HANDOFF -> 2.6
                        Family.DRUM_TEASE -> 2.8
                        // Showpieces: chosen when the music allows, kept rare by the set's memory.
                        Family.MASHUP -> 3.0
                        Family.DOUBLE_DROP -> 3.0
                        Family.LOOP_RIDE -> 0.0
                        Family.BED_UNDER, Family.ACAPELLA_OUT, Family.VOCAL_PREVIEW -> 0.0
                    }
                    // A beat-matched pair should blend; cuts are for pairs that cannot.
                    if (!family.blend && matched) s -= 2.2
                    // Echo repeats, rolls and spins are timed from the outgoing grid; on a
                    // weak grid they sound off-tempo, so prefer moves that need no beat.
                    if (og.confidence < .35f && family in setOf(Family.ECHO_THROW, Family.STUTTER,
                            Family.BACKSPIN, Family.BRAKE)) s -= 2.0
                    if (family.blend && family != Family.OVERLAY) s -= ((stretchCost - .02).coerceAtLeast(0.0) * 40)
                    // Variety: the last transition, and less so the two before it. Clean
                    // mixes repeat a good blend freely; only cuts need to vary.
                    if (!clean || !family.blend) {
                        recent.take(3).forEachIndexed { k, r -> if (r.family == family) s -= if (k == 0) 1.2 else .6 }
                    }
                    if (!family.blend) {
                        // Pacing: quick cuts are seasoning, builds are the meal.
                        if (length == 1 && recentQuick >= 1) s -= 1.5
                        // An 8-bar build when it starts on an 8-bar line, otherwise four.
                        val eight = op?.isPhraseStart(i, 8) ?: (i % 8 == 0)
                        s += when (length) { 1 -> -.5; 2 -> .2; 4 -> if (eight) .9 else 1.3; 8 -> if (eight) 1.3 else .7; else -> .7 }
                        // Each move where it makes musical sense.
                        val cutAt = i + length
                        val lineEnds = vocal(op, cutAt - 1) >= .5f && vocal(op, cutAt) < .5f
                        s += when (family) {
                            Family.ECHO_THROW -> if (lineEnds) .5 else 0.0
                            Family.BRAKE -> if (inBpmRatio != null && inBpmRatio < .87) .8 else -.2
                            Family.BACKSPIN -> if (inBpmRatio != null && inBpmRatio >= .95) .4 else 0.0
                            Family.FILTER_FADE -> if (op != null && op.energy(cutAt - 1) < .7f * op.bodyEnergy) .6 else 0.0
                            Family.STUTTER -> if ((op?.drums(cutAt - 1) ?: 0f) >= .5f) .4 else 0.0
                            else -> 0.0
                        }
                    }
                    val cut = i + length // exit bar for cuts / handover for blends
                    if (family.blend) {
                        s += boundary(op, i) + .8 * boundary(op, i + swap)
                        if (op != null && op.outroStartBar < op.bars && i >= op.outroStartBar - 1) s += 1.0
                    } else {
                        // What matters for a cut is where it drops, not where the build starts.
                        s += .3 * boundary(op, i) + 1.8 * boundary(op, cut)
                        if (op != null && op.outroStartBar < op.bars && cut >= op.outroStartBar) s += 1.0
                    }
                    s -= skipCost(cut + tail)
                    // DJ pacing: mix out of the chorus, handing over within 8 bars of its end.
                    if (payoff != null && (i + swap) in payoff..payoff + 8) s += 2.0
                    // No stems: loop the outgoing's clean bar 0 so its vocal does not run over the incoming.
                    val loopOut = !out.stems && family in LOOP_FAMILIES && vocal(op, i) < .3f &&
                        avgVocal(op, i + 1, i + length) >= .45f && og.barMs <= 4800
                    val overlapVocal = if (loopOut) vocal(op, i) else avgVocal(op, i, i + length)
                    when (family) {
                        Family.BEAT_UNDER -> {
                            if (overlapVocal < .45f) continue
                            s += 1.0
                            // The outgoing line ends exactly at the handoff.
                            if (vocal(op, cut - 1) >= .5f && vocal(op, cut) < .5f) s += 1.0
                        }
                        Family.BASS_SWAP, Family.FILTER_BLEND, Family.HANDOFF -> s += 1.0 * (1 - overlapVocal)
                        // The incoming sings alone over the outgoing's beat: the outgoing must not sing too.
                        Family.MASHUP -> if (!out.stems && overlapVocal >= .3f) continue
                        // Both drops must land on the swap bar.
                        Family.DOUBLE_DROP -> if (!isDrop(op, i + length)) continue
                        // The outgoing vocal rides the new drums; best when its line ends on the key bar.
                        Family.DRUM_TEASE -> {
                            s += .6
                            if (vocal(op, cut - 1) >= .5f && vocal(op, cut) < .5f) s += 1.0
                        }
                        Family.ECHO_THROW -> {
                            // Throwing the last word is the move, but vocals are nearly
                            // everywhere in this music: a modest nudge, not a default.
                            if (vocal(op, cut - 1) >= .5f && vocal(op, cut) < .5f) s += .5
                        }
                        Family.HAT_TEASE, Family.INTRO_WASH, Family.OVERLAY -> Unit
                        else -> if (vocal(op, cut - 1) >= .5f && vocal(op, cut) >= .5f) s -= .8
                    }
                    s += when {
                        family == Family.BASS_SWAP && length == 8 -> .6
                        family == Family.BASS_SWAP && length == 4 -> .2
                        family == Family.BEAT_UNDER && length == 8 -> .4
                        family == Family.BEAT_UNDER -> .3
                        family == Family.FILTER_BLEND && length == 8 -> .5
                        family == Family.HANDOFF && length == 4 -> .6
                        family == Family.HANDOFF -> .3
                        else -> 0.0
                    }
                    for (j in inEntries(swap, family.blend)) {
                        var total = s
                        // A key clash matters as much as the overlap is tonal: a sparse,
                        // beatless outro barely clashes; two sung choruses do.
                        if (keyClash) {
                            val density = maxOf(avgVocal(op, i, i + length), avgVocal(ip, j, j + length)).coerceIn(0f, 1f)
                            total -= density * if (family == Family.FILTER_BLEND || family == Family.DRUM_TEASE) .4
                                else if (family.blend) 1.0 else 0.0
                        }
                        var stretch = family.blend
                        var killOutLow = false
                        if (family == Family.OVERLAY || family == Family.HANDOFF) {
                            val outDrums = avg(i, i + length) { op?.drums(it) ?: -1f }
                            val inDrums = avg(j, j + length) { ip?.drums(it) ?: -1f }
                            val outLow = avg(i, i + length) { lowAt(op, it) }
                            val inLow = avg(j, j + length) { lowAt(ip, it) }
                            val outBeatless = outDrums != null && outDrums < .25f && (outLow ?: 1f) < .35f
                            val inBeatless = inDrums != null && inDrums < .25f
                            if (family == Family.OVERLAY) {
                                if (!outBeatless && !inBeatless) continue
                                // Tempo only matters if a beat can be heard against the other deck.
                                stretch = looseMatch
                                if (!stretch && !(outBeatless && inBeatless)) continue
                                if (stretch) total -= ((abs(speed!! - 1) - .02).coerceAtLeast(0.0) * 25)
                                // The quieter low end over the overlap is the one to silence.
                                killOutLow = (outLow ?: 0f) <= (inLow ?: 1f)
                                // The incoming's first full bar should be the outgoing's last.
                                if (firstFullBar(ip, j)?.minus(j) != length) total -= 1.0
                            } else if (inBeatless || (outDrums ?: 1f) < .25f) continue
                        }
                        if (clean && family.blend) {
                            // Score where the incoming arrives: on the key bar, on a phrase line.
                            val rel = firstFullBar(ip, j)?.minus(j)
                            val key = i + swap
                            val lands = rel == swap || (rel == 0 && family != Family.OVERLAY)
                            if (lands) total += when {
                                key == lastBar + 1 && options.letOutrosPlay && payoff == null -> 3.0
                                op != null && op.isPhraseStart(key, 8) &&
                                    (op.outroStartBar >= op.bars || key >= op.outroStartBar) -> 2.5
                                else -> 0.0
                            } else if (rel != null && rel in 1 until swap) total -= 1.5
                        }
                        // A wash enters quietly; what counts is where the incoming goes full.
                        val (es, ewhy) = entryScore(j, swap, family.blend || family == Family.INTRO_WASH)
                        total += es
                        if (family.blend && length == 16 &&
                            (overlapVocal >= .3f || avgVocal(ip, j, j + length) >= .3f)) total -= 1.5
                        if (family == Family.BEAT_UNDER) {
                            if (avgVocal(ip, j, j + length) > .4f) continue
                            // ...and the incoming vocal starts on it: a clean handoff.
                            if (vocal(ip, j + length) >= .5f) total += 1.0
                        }
                        if (family.blend && ig != null && ig.barStart(j) < 0) continue
                        if (family == Family.MASHUP) {
                            // Its vocal must carry the whole overlap, ideally entering right on bar 0.
                            if (vocal(ip, j) < .5f || avgVocal(ip, j, j + length) < .5f) continue
                            if (vocal(ip, j - 1) < .5f) total += .8
                        }
                        if (family == Family.DOUBLE_DROP && !isDrop(ip, j + length)) continue
                        if (family.blend) total += energyFlow(op, i + swap - 1, ip, j + swap)
                        if (family == Family.INTRO_WASH) {
                            // Only over an incoming stretch with no drums that is still music.
                            if (ip == null) continue
                            val drums = (j until j + length).map { ip.drums(it) }.average()
                            val energy = (j until j + length).map { ip.energy(it) }.average()
                            if (drums >= .25 || energy < .15 * ip.bodyEnergy) continue
                            if (avgVocal(ip, j, j + length) < .4f) total += .5
                        }
                        // A long build pays off when the incoming drops in on its hook.
                        if (!family.blend && length >= 4 && ip != null && ip.energy(j) >= .9f * ip.bodyEnergy) total += .6
                        // Clean mixes let the seed break ties only.
                        total += jitter(family.ordinal) * if (clean) .25 else .4
                        // Fills and exits follow the music at the swap; only near-ties are left to the seed.
                        val fill = if (!clean && family.blend &&
                            family !in setOf(Family.BEAT_UNDER, Family.OVERLAY, Family.HANDOFF, Family.DRUM_TEASE,
                                Family.MASHUP, Family.DOUBLE_DROP)) {
                            fillBy(i, swap, j)
                        } else Fill.NONE
                        // Clean: blends fade plainly, cuts take no breath.
                        val style = if (clean) (if (family.blend) 1 else 0) else exitBy(family, i, length, j)
                        candidates += Candidate(family, i, length, j, fill, style, total,
                            "exit@$i len=$length $ewhy${if (loopOut) " loop" else ""} score=${"%.2f".format(total)}",
                            stretch, killOutLow, loopOut)
                    }
                }
            }
        }
        // A beat-matched pair blends whenever any blend fits; cuts are for pairs that cannot.
        val top = (candidates.filter { it.family.blend }.takeIf { matched && it.isNotEmpty() } ?: candidates)
            .maxByOrNull { it.score }
        // A showpiece when the music allows one and the set has not just had one: pacing and
        // contrast, not points, decide when a DJ does the big move.
        val showpiece = if (!clean && matched && top != null && recent.take(2).none { it.family in SHOWPIECES })
            candidates.filter { it.family in SHOWPIECES }.maxByOrNull { it.score }
                ?.takeIf { it.score >= top.score - SHOWPIECE_MARGIN } else null
        val best = showpiece ?: top
        // Context: a pair with no blend overlaps on what each record offers (an instrumental
        // bed, a lone vocal line, a hook to preview) before falling back to a cut.
        // The far ride: a pair up to RIDE_FAR apart blends beat-matched over a 32-bar ride.
        val farRide = {
            val far = ride && rideLimit < RIDE_FAR && beatAlign && speed != null && abs(speed - 1) <= RIDE_FAR &&
                og.confidence >= MIN_CONFIDENCE && (ig?.confidence ?: 0f) >= MIN_CONFIDENCE
            if (far) composeWith(out, inc, earliestStartMs, recent, seed, beatAlign, options, RIDE_FAR)?.takeIf { it.family.blend }
            else null
        }
        if (best == null || !best.family.blend) contextMove(out, inc, og, ig, op, ip, firstBar, lastBar, options,
            s0.toFloat(), keyClash, "matched=$matched speed=${speed?.let { "%.4f".format(it) }}", farRide, recent)?.let { return it }
        best ?: return null
        val notes = "${best.why} matched=$matched speed=${speed?.let { "%.4f".format(it) }} clash=$keyClash " +
            "outConf=${"%.2f".format(og.confidence)} inConf=${ig?.confidence?.let { "%.2f".format(it) }} " +
            "candidates=${candidates.size}"
        return build(best, og, ig, op, ip, speed?.toFloat() ?: 1f, notes, options, s0.toFloat(), out.stems)
    }

    private fun build(
        c: Candidate, og: Grid, ig: Grid?, op: TrackProfile?, ip: TrackProfile?, matchSpeed: Float, notes: String,
        options: Options, currentSpeed: Float, outStems: Boolean = false,
    ): Mix {
        val blend = c.stretch
        val conform = options.conformIncoming
        // Riding: the outgoing glides halfway to the incoming before the mix (never more
        // than RIDE_MAX_OUT from native) and the incoming is stretched the rest.
        val farRide = abs(matchSpeed - 1) > RIDE_STRETCH
        val cap = (if (farRide) RIDE_MAX_OUT_FAR else RIDE_MAX_OUT).toFloat()
        val rideTo = if (conform && blend && options.tempoRide && abs(matchSpeed - 1) > .02f)
            (currentSpeed * kotlin.math.sqrt(matchSpeed)).coerceIn(1 - cap, 1 + cap)
                .takeIf { abs(it - currentSpeed) > .002f } else null
        // Conforming: the outgoing keeps playing as it is; the incoming is stretched
        // onto it. Otherwise (v2 before Clean) the outgoing bends to the incoming.
        val speed = rideTo ?: if (conform) currentSpeed else if (blend) matchSpeed else 1f
        // The old outgoing bend keeps the incoming's exact bar; otherwise the outgoing's bar at its speed.
        val wallBar = if (!conform && blend) ig!!.barMs else og.barMs / speed
        val stretchIncoming = conform && blend
        val clean = options.style == MixStyle.CLEAN
        val e = (INSTANT_MS / wallBar).toFloat()
        val out = Deck.of(1f)
        // Silent until its entry: a cut deck starts a latency early, inaudibly.
        val inc = Deck.of(0f)
        val l = c.length.toFloat()
        // A looped outgoing repeats its bar 0, so that is what sings over the rest.
        fun vocalOut(b: Int) = op?.vocal(c.exit + if (c.loopOut) 0 else b)?.takeIf { it >= 0f } ?: .5f
        fun vocalIn(b: Int) = ip?.vocal(c.entry + b)?.takeIf { it >= 0f } ?: .5f
        fun clashBar(b: Int) = vocalOut(b) >= .5f && vocalIn(b) >= .5f
        fun instant(d: Deck, p: P, at: Float, to: Float) { d.lane(p).ramp(at, at + e, to) }
        /** Mute the outgoing on the '.' sixteenths of [pattern] from [from] (bars). */
        fun gate(from: Float, pattern: String) {
            for ((k, step) in pattern.withIndex()) {
                if (step != '.') continue
                val t = from + k * .0625f
                instant(out, P.DRY, t, 0f)
                instant(out, P.DRY, t + .0625f - e, 1f)
            }
        }
        /**
         * Tension over [b] bars into the drop at [m]: a rising resonant high-pass
         * (or a falling low-pass), the low end thinning, echo feedback swelling in
         * the last bar. Returns nothing; writes the outgoing lanes.
         */
        fun build(m: Float, b: Float, lowPass: Boolean) {
            val start = m - b
            if (lowPass) out.lane(P.LPF).ramp(start, m - .25f, (.55f + .06f * b).coerceAtMost(.8f), accelerate = true)
            else out.lane(P.HPF).ramp(start, m - .25f, (.3f + .08f * b).coerceAtMost(.62f), accelerate = true)
            if (b >= 2) {
                out.lane(P.SUB).ramp(m - b / 2, m - .25f, .3f)
                out.lane(P.BASS).ramp(m - b / 2, m - .25f, .4f)
                // Clean builds thin and filter; only Creative swells an echo.
                if (!clean) {
                    out.lane(P.FEEDBACK).ramp(m - 1f, m - .25f, .64f)
                    out.lane(P.SEND).ramp(m - 1f, m - .25f, .35f)
                }
            }
        }
        fun applyFill(fill: Fill, s: Float) {
            when (fill) {
                Fill.ROLL -> out.rolls += listOf(RollStep(s - .5f, 2), RollStep(s - .25f, 4), RollStep(s, 0))
                Fill.GATE -> gate(s - .5f, GATES[c.exitStyle % GATES.size])
                Fill.DROPOUT -> {
                    instant(out, P.SUB, s - .25f, 0f)
                    instant(out, P.BASS, s - .25f, 0f)
                }
                Fill.THROW -> out.lane(P.SEND).ramp(s - .25f, s, .55f).ramp(s, s + e, 0f)
                Fill.NONE -> Unit
            }
        }
        val bars: Int
        val swap: Int
        var entry = 0
        var hard = false
        if (c.loopOut) out.loops += DjTransitionPlan.Loop(0f, 4f, l)
        when (c.family) {
            Family.MASHUP -> {
                // The incoming's acapella over the outgoing's beat; its own beat drops on bar L.
                swap = c.length
                bars = c.length + 1
                hard = true
                for (p in listOf(P.DRUMS, P.BASSLINE, P.OTHER)) inc.hold(p, 0f).ramp(l - e, l, 1f)
                for (p in listOf(P.MID, P.HIGH)) inc.lane(p).ramp(0f, e, 1f)
                for (p in listOf(P.SUB, P.BASS)) inc.lane(p).ramp(l - e, l, 1f)
                // One vocal: the outgoing's steps aside (stems) or was chosen where it does not sing.
                if (outStems) out.lane(P.VOX).ramp(0f, .25f, 0f)
                // The outgoing hands its low end over on the drop and leaves within a bar.
                for (p in listOf(P.SUB, P.BASS)) instant(out, p, l, 0f)
                out.lane(P.HPF).ramp(l, l + 1f, .35f, accelerate = true)
                out.lane(P.DRY).ramp(l + 1f - .25f, l + 1f, 0f)
                out.lane(P.WET).ramp(l + 1f - e, l + 1f, 0f)
            }
            Family.DOUBLE_DROP -> {
                // A filtered incoming build under the outgoing, then both drops on the same bar.
                swap = c.length
                bars = c.length + 6
                hard = true
                val d = l
                inc.lane(P.HPF).ramp(0f, e, .42f).ramp(d - 1f, d - .25f, .2f).ramp(d, d + e, 0f)
                inc.lane(P.MID).ramp(0f, e, .6f).ramp(d, d + e, 1f)
                inc.lane(P.HIGH).ramp(0f, e, .8f).ramp(d, d + e, 1f)
                for (p in listOf(P.SUB, P.BASS)) instant(inc, p, d, 1f)
                // On the drop the outgoing gives up its low end (and its vocal, with stems) and rides
                // the incoming's drop for four bars, then high-passes away over two.
                for (p in listOf(P.SUB, P.BASS)) instant(out, p, d, 0f)
                if (outStems) out.lane(P.VOX).ramp(d - .25f, d, 0f)
                out.lane(P.MID).ramp(d, d + .5f, .7f)
                out.lane(P.HPF).ramp(d + 4f, d + 6f, .55f, accelerate = true)
                out.lane(P.HIGH).ramp(d + 4f, d + 6f, 0f)
                out.lane(P.DRY).ramp(d + 6f - .5f, d + 6f, 0f)
                out.lane(P.WET).ramp(d + 6f - e, d + 6f, 0f)
            }
            Family.DRUM_TEASE -> {
                // Only the incoming's drums: tops first, no kick.
                swap = c.length
                bars = c.length
                hard = true
                val h = (c.length / 2).toFloat()
                for (p in listOf(P.BASSLINE, P.VOX, P.OTHER)) inc.hold(p, 0f)
                inc.lane(P.HIGH).ramp(0f, e, 1f)
                inc.lane(P.MID).ramp(0f, e, .55f)
                // Halfway: its kick takes over from the outgoing's.
                for (p in listOf(P.SUB, P.BASS, P.MID)) instant(inc, p, h, 1f)
                if (outStems) instant(out, P.DRUMS, h, 0f) else {
                    out.lane(P.HIGH).ramp(h - .5f, h, .5f)
                    instant(out, P.SUB, h, 0f)
                    instant(out, P.BASS, h, 0f)
                }
                // Three quarters: the bass lines swap.
                val b = floor(l * .75f)
                instant(inc, P.BASSLINE, b, 1f)
                if (outStems) {
                    instant(out, P.BASSLINE, b, 0f)
                    out.lane(P.OTHER).ramp(h, l - 1f, .5f)
                }
                // The rest of the incoming opens over the last bar; its vocal on the key bar.
                inc.lane(P.OTHER).ramp(l - 1f, l - e, 1f)
                instant(inc, P.VOX, l - e, 1f)
                out.lane(P.DRY).ramp(l - .5f, l, 0f)
                out.lane(P.WET).ramp(l - e, l, 0f)
            }
            Family.OVERLAY -> {
                // Both at full volume from bar 0; the incoming is untouched apart from
                // the quieter low end being held back, which nothing will miss.
                swap = c.length
                bars = c.length
                hard = true
                for (p in listOf(P.MID, P.HIGH)) inc.lane(p).ramp(0f, e, 1f)
                if (c.killOutgoingLow) {
                    for (p in listOf(P.SUB, P.BASS)) {
                        inc.lane(p).ramp(0f, e, 1f)
                        out.lane(p).ramp(0f, e, 0f)
                    }
                } else for (p in listOf(P.SUB, P.BASS)) inc.lane(p).ramp(l - e, l, 1f)
                // The outgoing's highs make room for the incoming's entry, then return.
                out.lane(P.HIGH).ramp(0f, e, 0f).ramp(e, l / 2, 1f, linear = true)
                // It plays to the key bar, then stops dead: no echo, no tail.
                out.lane(P.DRY).ramp(l - e, l, 0f)
                out.lane(P.WET).ramp(l - e, l, 0f)
            }
            Family.HANDOFF -> {
                // Bar 0: the outgoing keeps only its mids; the incoming comes in at full
                // volume with its highs open, its low end ramping in a straight line.
                swap = c.length
                bars = c.length
                hard = true
                for (p in listOf(P.SUB, P.BASS, P.HIGH)) out.lane(p).ramp(0f, e, 0f)
                inc.lane(P.HIGH).ramp(0f, e, 1f)
                for (p in listOf(P.SUB, P.BASS)) inc.lane(p).ramp(0f, l - e, 1f, linear = true)
                // Mids ease up from 40 %, or wait for the outgoing's last line to end.
                if ((0 until c.length).any { clashBar(it) }) inc.lane(P.MID).ramp(l - .5f, l - e, 1f)
                else inc.lane(P.MID).ramp(0f, e, .4f).ramp(e, l - e, 1f)
                // The outgoing fades over the half bar before the key bar.
                out.lane(P.DRY).ramp(l - .5f, l, 0f)
                out.lane(P.WET).ramp(l - e, l, 0f)
            }
            Family.BASS_SWAP, Family.FILTER_BLEND -> {
                swap = c.length / 2
                val s = swap.toFloat()
                bars = c.length
                if (c.family == Family.BASS_SWAP) {
                    // Incoming thinned by a high-pass with its bass killed; it opens into the swap.
                    inc.lane(P.HPF).ramp(0f, e, .42f).ramp(s - 1f, s, .12f).ramp(s, s + e, 0f)
                    inc.lane(P.HIGH).ramp(0f, 1f, .85f).ramp(s, s + e, 1f)
                } else {
                    inc.lane(P.LPF).ramp(0f, e, .72f).ramp(1f, s, .35f).ramp(s, s + e, 0f)
                    inc.lane(P.HIGH).ramp(0f, 1f, .8f).ramp(s, s + e, 1f)
                    out.lane(P.HPF).ramp(maxOf(0f, s - 3f), s, .5f, accelerate = true)
                        .ramp(s, l, .72f, accelerate = true)
                }
                // Incoming mids only where they do not sing over the outgoing vocal.
                for (b in 0 until swap) {
                    val target = if (clashBar(b)) 0f else if (c.family == Family.FILTER_BLEND) .8f else .7f
                    inc.lane(P.MID).ramp(b.toFloat(), b + .125f, target)
                }
                instant(inc, P.MID, s, 1f)
                instant(inc, P.SUB, s, 1f)
                instant(inc, P.BASS, s, 1f)
                out.lane(P.HIGH).ramp(s - 1f, s, .45f)
                applyFill(c.fill, s)
                if (c.fill == Fill.GATE) instant(out, P.DRY, s, 1f)
                instant(out, P.SUB, s, 0f)
                instant(out, P.BASS, s, 0f)
                // After the swap the outgoing tops leave one of three ways.
                when (c.exitStyle) {
                    0 -> {
                        out.lane(P.MID).ramp(s, l - 1f, .4f)
                        out.lane(P.SEND).ramp(l - 1f, l - .5f, .6f).ramp(l - .5f, l - .5f + e, 0f)
                        instant(out, P.DRY, l - .5f, 0f)
                        out.lane(P.WET).ramp(l - .2f, l, 0f)
                    }
                    1 -> {
                        out.lane(P.MID).ramp(s, l, 0f)
                        out.lane(P.HIGH).ramp(s, l, 0f)
                    }
                    else -> {
                        if (c.family == Family.BASS_SWAP) out.lane(P.HPF).ramp(s, l, .7f, accelerate = true)
                        out.lane(P.DRY).ramp(l - .5f, l, 0f)
                    }
                }
                if (c.exitStyle != 0) out.lane(P.WET).ramp(l - .2f, l, 0f)
            }
            Family.BEAT_UNDER -> {
                // The new beat takes over on the one; the outgoing vocal rides it,
                // then hands over to the incoming vocal on the next phrase.
                swap = 0
                bars = c.length + 1
                hard = true
                for (p in listOf(P.SUB, P.BASS, P.HIGH)) {
                    instant(inc, p, 0f, 1f)
                    instant(out, p, 0f, 0f)
                }
                out.lane(P.HPF).ramp(0f, e, .45f)
                for (b in 0 until c.length) inc.lane(P.MID).ramp(b.toFloat(), b + .125f, if (vocalIn(b) < .4f) .6f else 0f)
                instant(inc, P.MID, l, 1f)
                out.lane(P.SEND).ramp(l - .25f, l, .6f).ramp(l, l + e, 0f)
                instant(out, P.DRY, l, 0f)
                out.lane(P.WET).ramp(l + .75f, l + 1f, 0f)
                out.echoBeats = .75f
            }
            Family.HAT_TEASE -> {
                swap = c.length
                bars = c.length + 1
                inc.lane(P.HIGH).ramp(0f, 2f, 1f)
                out.lane(P.HIGH).ramp(1f, 2f, .45f)
                val fill = if (c.fill == Fill.NONE) Fill.ROLL else c.fill
                applyFill(fill, l)
                if (fill != Fill.THROW) out.lane(P.SEND).ramp(l - .25f, l, .5f).ramp(l, l + e, 0f)
                for (p in listOf(P.SUB, P.BASS, P.MID)) instant(inc, p, l, 1f)
                instant(out, P.DRY, l, 0f)
                out.lane(P.WET).ramp(l + .75f, l + 1f, 0f)
            }
            Family.INTRO_WASH -> {
                // The beatless incoming intro rises, muffled, under the outgoing's
                // build; the outgoing echoes out and the incoming opens on the bar.
                swap = c.length
                bars = c.length + 2
                val m = l
                inc.lane(P.LPF).ramp(0f, e, .62f).ramp(m - 1f, m, .3f).ramp(m, m + .5f, 0f)
                inc.lane(P.MID).ramp(0f, m * .6f, .7f).ramp(m, m + e, 1f)
                inc.lane(P.HIGH).ramp(0f, m * .6f, .7f).ramp(m, m + e, 1f)
                inc.lane(P.BASS).ramp(m - 1f, m, .5f).ramp(m, m + e, 1f)
                instant(inc, P.SUB, m, 1f)
                build(m, l, lowPass = false)
                out.echoBeats = .75f
                out.lane(P.SEND).ramp(m - .25f, m, .65f).ramp(m, m + e, 0f)
                instant(out, P.DRY, m, 0f)
                out.lane(P.WET).ramp(m + 1.5f, m + 2f, 0f)
            }
            else -> {
                // Cuts: a build of `length` bars, the incoming drops on its bar line,
                // and the outgoing's echo rings two bars over it.
                swap = c.length
                bars = c.length + 2
                entry = c.length
                hard = true
                val m = l
                if (c.family == Family.FILTER_FADE) {
                    // A fade fades in: tops over half a bar, the low end a beat later.
                    inc.lane(P.MID).ramp(m, m + .5f, 1f)
                    inc.lane(P.HIGH).ramp(m, m + .5f, 1f)
                    inc.lane(P.SUB).ramp(m + .25f, m + .75f, 1f)
                    inc.lane(P.BASS).ramp(m + .25f, m + .75f, 1f)
                } else if (c.family == Family.ECHO_THROW) {
                    // Leave room for the echo: the incoming eases in muffled at ~60 %,
                    // its low end a beat later, fully open by the end of its first bar.
                    inc.lane(P.MID).ramp(m, m + e, .6f).ramp(m + .25f, m + 1f, 1f)
                    inc.lane(P.HIGH).ramp(m, m + e, .6f).ramp(m + .25f, m + 1f, 1f)
                    inc.lane(P.SUB).ramp(m + .25f, m + 1f, 1f)
                    inc.lane(P.BASS).ramp(m + .25f, m + 1f, 1f)
                    inc.lane(P.LPF).ramp(0f, e, .5f).ramp(m, m + 1f, 0f)
                } else for (p in listOf(P.SUB, P.BASS, P.MID, P.HIGH)) instant(inc, p, m, 1f)
                // Landing: a hard drop onto a strong section; otherwise the incoming
                // swells open over half a bar to a bar so the cut does not slam.
                val strong = ip?.let { it.energy(c.entry) >= .9f * it.bodyEnergy } ?: false
                if (!strong && c.family != Family.BACKSPIN && c.family != Family.BRAKE &&
                    c.family != Family.FILTER_FADE && c.family != Family.ECHO_THROW) {
                    if (c.exitStyle == 1) inc.lane(P.HPF).ramp(0f, e, .5f).ramp(m, m + 1f, 0f)
                    else inc.lane(P.LPF).ramp(0f, e, .6f).ramp(m, m + .5f, 0f)
                }
                // Breath before the drop: none, an echo-trailed beat, or half a beat of silence.
                val breath = c.exitStyle
                when (c.family) {
                    Family.ECHO_THROW -> {
                        build(m, l, lowPass = false)
                        out.echoBeats = if (c.exitStyle == 0) .5f else .75f
                        // Throw the whole last bar into a long, feeding-back echo.
                        out.lane(P.FEEDBACK).ramp(m - .25f, m, .72f)
                        out.lane(P.SEND).ramp(m - .25f, m, .85f, accelerate = true).ramp(m, m + e, 0f)
                        instant(out, P.DRY, m, 0f)
                    }
                    Family.BACKSPIN -> {
                        build(m, l, lowPass = false)
                        out.spins += Spin(m - .25f, 1f, SpinKind.BACKSPIN)
                        instant(out, P.DRY, m, 0f)
                        // The build's send must close with the dry, or the outgoing plays on through the echo.
                        instant(out, P.SEND, m, 0f)
                        // The incoming motor-starts into its beat instead of leaving silence.
                        inc.spins += Spin(m, .5f, SpinKind.SPINUP)
                    }
                    Family.BRAKE -> {
                        build(m, l, lowPass = true)
                        out.spins += Spin(m - .5f, 2f, SpinKind.BRAKE)
                        instant(out, P.DRY, m, 0f)
                        instant(out, P.SEND, m, 0f)
                        inc.spins += Spin(m, .5f, SpinKind.SPINUP)
                    }
                    Family.STUTTER -> {
                        build(m, l, lowPass = false)
                        // Beat repeat from beat 1 of the last bar, halving on the grid.
                        out.rolls += if (l >= 2) listOf(RollStep(m - 1f, 1), RollStep(m - .5f, 2),
                            RollStep(m - .25f, 4), RollStep(m - .125f, 8), RollStep(m, 0))
                        else listOf(RollStep(m - .5f, 2), RollStep(m - .25f, 4), RollStep(m - .125f, 8), RollStep(m, 0))
                        if (breath == 2) instant(out, P.DRY, m - .125f, 0f) else instant(out, P.DRY, m, 0f)
                        instant(out, P.SEND, m, 0f)
                    }
                    else -> {
                        build(m, l, lowPass = true)
                        if (!clean) out.lane(P.SEND).ramp(m - .5f, m, .45f).ramp(m, m + e, 0f)
                        out.lane(P.DRY).ramp(m - .25f, m, 0f)
                        inc.lane(P.HPF).ramp(0f, e, .45f).ramp(m, m + 1f, 0f)
                    }
                }
                if (c.family == Family.ECHO_THROW || c.family == Family.FILTER_FADE) when (breath) {
                    1 -> instant(out, P.DRY, m - .25f, 0f)
                    2 -> {
                        instant(out, P.DRY, m - .125f, 0f)
                        instant(out, P.WET, m - .125f, 0f)
                    }
                    else -> Unit
                }
                out.lane(P.WET).ramp(m + 1.5f, m + 2f, 0f)
            }
        }
        val outStart = og.barStart(c.exit)
        // The incoming is lined up while it plays muted before its cue, which needs
        // ~1.8 s of song before it. Entering at the very start leaves none, so move
        // in by up to two bars when those bars are only quiet intro.
        var entryBar = c.entry
        // An overlay's landing depends on starting exactly at its entry; its overlap is
        // beatless, so a short timing error there is inaudible anyway.
        if (ig != null && c.family != Family.OVERLAY) {
            val before = ig.barStart(entryBar)
            if (before < MIN_PREROLL_MS) {
                val shift = ceil((MIN_PREROLL_MS - before) / ig.barMs).toInt()
                val quiet = ip == null || (entryBar until entryBar + shift).all { ip.energy(it) < .6f * ip.bodyEnergy }
                if (shift <= 2 && quiet) entryBar += shift
            }
        }
        val cue = (ig?.barStart(entryBar) ?: 0.0).coerceAtLeast(0.0)
        val inNeeds = c.family == Family.DRUM_TEASE || c.family == Family.MASHUP
        val outNeeds = outStems && c.family in setOf(Family.DRUM_TEASE, Family.MASHUP, Family.DOUBLE_DROP)
        val inRange = if (inNeeds && ig != null) (cue - 1000).toLong().coerceAtLeast(0)..(cue + bars * ig.barMs + 1000).toLong() else null
        val outRange = if (outNeeds) (outStart - 1000).toLong()..(outStart + bars * og.barMs + 1000).toLong() else null
        return Mix(c.family, if (c.family == Family.HAT_TEASE && c.fill == Fill.NONE) Fill.ROLL else c.fill,
            bars, swap, entry, outStart.roundToLong(), og.barMs, speed, wallBar, cue.roundToLong(), out, inc,
            sync = blend, hardEntry = hard, bpmOut = og.bpm.toFloat(), bpmIn = ig?.bpm?.toFloat(), notes = notes,
            stretchIncoming = stretchIncoming, holdOutgoing = conform,
            incomingBarMs = if (stretchIncoming) ig!!.barMs else wallBar,
            glideBack = stretchIncoming && options.glideBack, exitBar = c.exit, incomingBar = entryBar,
            rideSpeed = rideTo, rideBars = if (rideTo == null) 0 else if (farRide) RIDE_BARS_FAR else RIDE_BARS,
            outStemsMs = outRange, inStemsMs = inRange)
    }

    /**
     * Bed under, chosen by context: when the incoming opens on at least two bars of
     * music without a lead vocal before its first full bar, the last 2, 4 or 8 of them
     * play at the incoming's own tempo under the outgoing, ending as its first full bar
     * lands on the outgoing's key bar (a section or phrase line near the end, after a
     * vocal line). Nothing is stretched: the incoming is present from its first bar,
     * the outgoing's mids and highs step back by the loudness gap, the low ends swap
     * halfway, and the outgoing plays to the key bar's line.
     *
     * Lanes of both decks are in the outgoing's wall bars: the renderer times a
     * native-speed incoming on that clock.
     */
    private fun bedUnder(
        out: Side, og: Grid, ig: Grid?, op: TrackProfile?, ip: TrackProfile?, firstBar: Int, lastBar: Int,
        options: Options, currentSpeed: Float, why: String, inStems: Boolean = false, payoff: Int? = null,
    ): Mix? {
        if (ig == null || ip == null || op == null) return null
        val full = firstFullBar(ip, 0) ?: return null
        if (full < 2) return null
        // Still music the whole way, and no lead vocal over it.
        if ((0 until full).count { ip.energy(it) >= .12f * ip.bodyEnergy } < full - 1) return null
        val bedVocal = (0 until full).map { ip.vocal(it).coerceAtLeast(0f) }.average()
        if (bedVocal >= BED_MAX_VOCAL) return null
        // Two grooves at unrelated tempos flam: a bed with drums needs them taken out (stems),
        // otherwise only its longest drumless stretch before the first full bar qualifies.
        fun drums(len: Int) = (full - len until full).map { ip.drums(it).coerceAtLeast(0f) }.average()
        val lenIn = intArrayOf(8, 4, 2).filter { it <= full }.firstOrNull { inStems || drums(it) < BED_MAX_DRUMS }
            ?: return null
        val muteDrums = inStems && drums(lenIn) >= BED_MAX_DRUMS
        val cueBar = full - lenIn
        val speed = if (options.conformIncoming) currentSpeed else 1f
        val wallBar = og.barMs / speed
        val bedMs = lenIn * ig.barMs
        val l = maxOf(1, (bedMs / wallBar).roundToInt())
        if (firstBar + l > lastBar + 1) return null
        fun boundary(i: Int) = when {
            i in op.sections -> 2.0
            op.isPhraseStart(i, 8) -> 1.0
            op.isPhraseStart(i, 4) -> .5
            else -> 0.0
        }
        fun sung(i: Int) = op.vocal(i) >= .5f
        val keyRange = maxOf(firstBar + l, exitFrom(firstBar, lastBar, payoff) + l)..lastBar + 1
        val key = keyRange.maxByOrNull { k ->
            var s = 1.5 * boundary(k)
            if (op.outroStartBar < op.bars && k >= op.outroStartBar) s += 1.0
            if (k == lastBar + 1) s += 1.5
            // Hand over as a line ends, never through one.
            if (sung(k - 1) && !sung(k)) s += 1.0
            if (sung(k - 1) && sung(k)) s -= 1.0
            s -= (k - l until k).map { op.vocal(it).coerceAtLeast(0f) }.average()
            s -= if (payoff != null) {
                if (k < payoff) 3.0 else ((k - payoff - 16).coerceAtLeast(0) * .03).coerceAtMost(1.5)
            } else ((out.durationMs - og.barStart(k)) / 1000 - 15).coerceAtLeast(0.0) * .05
            s
        } ?: return null
        val exit = key - l
        // Where the bed starts, from mix bar 0; the deck starts on a whole bar, so a
        // fraction moves its cue instead.
        val startIn = l * wallBar - bedMs
        var entry = floor(startIn / wallBar).toInt().coerceAtLeast(0)
        var cue = ig.barStart(cueBar) - (startIn - entry * wallBar)
        while (cue < 0 && entry < l - 1) { entry++; cue += wallBar }
        if (cue < 0) return null
        // Level: the quieter side is matched by turning the louder one down (gains cap at 1).
        val outEnd = op.outroStartBar.coerceIn(minOf(8, op.bars), op.bars)
        val outE = meanEnergy(op, outEnd - 16, outEnd)
        val inE = meanEnergy(ip, 0, 8)
        val gapDb = if (outE > 0 && inE > 0) (10 * log10(outE / inE)).coerceIn(0.0, 6.0) else 0.0
        val duck = 10.0.pow(-(gapDb + 3) / 20).toFloat().coerceIn(.45f, .8f)
        val e = (INSTANT_MS / wallBar).toFloat()
        val lf = l.toFloat()
        val half = (lf / 2).coerceAtLeast(.5f)
        val o = Deck.of(1f)
        // One low end at a time: the outgoing's leaves just before the incoming's arrives.
        for (p in listOf(P.SUB, P.BASS)) o.lane(p).ramp(half - e, half, 0f)
        o.lane(P.MID).ramp(0f, half, duck)
        o.lane(P.HIGH).ramp(0f, half, minOf(duck, .6f)).ramp(half, lf, .35f)
        // To the bar line: a fade over the last half bar sounds like it stopped on beat 2.
        o.lane(P.DRY).ramp(lf - .0625f, lf, 0f)
        o.lane(P.WET).ramp(lf - e, lf, 0f)
        val n = Deck.of(0f)
        val s = entry.toFloat()
        // With stems the bed's own drums wait for the key bar: they arrive as the incoming lands.
        if (muteDrums) n.hold(P.DRUMS, 0f).ramp(lf - e, lf, 1f)
        val open = maxOf(half, s + 2 * e)
        n.lane(P.MID).ramp(s, s + e, .8f).ramp(s + e, open, 1f)
        n.lane(P.HIGH).ramp(s, s + e, .75f).ramp(s + e, open, 1f)
        for (p in listOf(P.SUB, P.BASS)) n.lane(p).ramp(maxOf(half, s), maxOf(half, s) + e, 1f)
        val notes = "context: bed under bed=${full}bars(vocal ${"%.2f".format(bedVocal)} drums ${"%.2f".format(drums(lenIn))}" +
            "${if (muteDrums) ", muted by stems" else ""}) $lenIn in-bars over $l out-bars " +
            "key@$key(boundary ${boundary(key)}) gap=${"%.1f".format(gapDb)}dB duck=${"%.2f".format(duck)} $why " +
            "outConf=${"%.2f".format(og.confidence)} inConf=${"%.2f".format(ig.confidence)}"
        return Mix(Family.BED_UNDER, Fill.NONE, l, l, entry, og.barStart(exit).roundToLong(), og.barMs, speed, wallBar,
            cue.roundToLong(), o, n, sync = false, hardEntry = true, bpmOut = og.bpm.toFloat(), bpmIn = ig.bpm.toFloat(),
            notes = notes, holdOutgoing = options.conformIncoming, exitBar = exit, incomingBar = cueBar,
            inStemsMs = if (muteDrums) (cue - 1000).roundToLong().coerceAtLeast(0)..(cue + bedMs + 1000).roundToLong() else null)
    }

    /**
     * Context for a pair the search could not blend: prefer what each record offers over
     * a cut. A big drop in loudness previews the incoming's hook over a build; an
     * instrumental opening becomes a bed under the outgoing; a vocal line that ends on a
     * phrase can carry alone over the switch; a quieter record also gets the preview.
     */
    private fun contextMove(
        out: Side, inc: Side, og: Grid, ig: Grid?, op: TrackProfile?, ip: TrackProfile?, firstBar: Int, lastBar: Int,
        options: Options, currentSpeed: Float, keyClash: Boolean, why: String, farRide: () -> Mix? = { null },
        recent: List<Played> = emptyList(),
    ): Mix? {
        if (op == null || ip == null || ig == null) return null
        val drop = dropDb(op, ip)
        val payoff = if (options.djPacing) payoffBar(op) else null
        // Every move the music allows, with how much a DJ would want it here.
        val moves = ArrayList<Pair<Mix, Double>>()
        fun add(m: Mix?, base: Double) { if (m != null) moves += m to base }
        val slower = ig.bpm < og.bpm * currentSpeed * .75 && drop <= -1.5
        if (inc.stems) add(vocalPreview(og, ig, op, ip, firstBar, lastBar, options, currentSpeed, out.stems, drop, why, payoff),
            when { drop <= -4.0 -> 2.6; slower -> 1.8; else -> .9 })
        bedUnder(out, og, ig, op, ip, firstBar, lastBar, options, currentSpeed, why, inc.stems, payoff)?.let {
            // A naturally beatless intro over the outgoing is the classic intro-over-outro; one emptied
            // by stems is a little less so.
            add(it, if (it.inStemsMs == null) 2.3 else 1.4)
        }
        // Beat-matched results rank highest: a far ride, then a loop ride for distant tempos.
        add(farRide(), 2.4)
        if (options.tempoRide) add(loopRide(og, ig, op, ip, firstBar, lastBar, options, currentSpeed, out.stems, inc.stems,
            payoff, why), 2.1)
        if (out.stems && !keyClash) add(acapellaOut(og, ig, op, ip, firstBar, lastBar, options, currentSpeed, why, inc.stems,
            payoff), 1.8)
        val pick = moves.maxByOrNull { (m, base) ->
            var v = base
            // The set's memory: the same move again sounds like a preset.
            recent.take(3).forEachIndexed { k, r -> if (r.family == m.family) v -= if (k == 0) 1.3 else .6 }
            // Energy flow at the handover, as for blends.
            v + .5 * energyFlow(op, m.exitBar + m.swapBar - 1, ip, m.incomingBar + (m.bars - m.entryBar))
        }?.first
        // Nothing fits: no tail over (it let the set sag); the engine falls back to v1's blend.
        return pick
    }

    /** First outgoing bar a context move may start from: after the payoff with DJ pacing, else the last 48 bars. */
    private fun exitFrom(firstBar: Int, lastBar: Int, payoff: Int?) = maxOf(firstBar, minOf(lastBar - 47, (payoff ?: lastBar) - 4))

    /**
     * Loop ride: loop one or two clean bars of the outgoing (no vocal, or its vocal taken out
     * by stems; with a groove to ride), let them play, ride its tempo to the incoming's over
     * 8 or 16 bars (key held, up to [LOOP_RIDE_MAX] from native), hold a bar, then bring the
     * incoming in beat-matched at its own tempo and swap the bass halfway through a 4- or 8-bar
     * blend. With the incoming's stems its drums come first and its vocal on the last bar.
     */
    private fun loopRide(
        og: Grid, ig: Grid, op: TrackProfile, ip: TrackProfile, firstBar: Int, lastBar: Int, options: Options,
        currentSpeed: Float, outStems: Boolean, inStems: Boolean, payoff: Int?, why: String,
    ): Mix? {
        if (og.confidence < MIN_CONFIDENCE || ig.confidence < MIN_CONFIDENCE) return null
        // The outgoing ends at the incoming's tempo, or at half or double it (a 128 over a 64
        // groove: every outgoing beat is two incoming beats), so the incoming plays native.
        val (k, target) = listOf(1.0, .5, 2.0).map { it to (ig.bpm * it / og.bpm).toFloat() }
            .filter { abs(it.second - 1) <= LOOP_RIDE_MAX }
            .minByOrNull { abs(it.second - currentSpeed) } ?: return null
        // Incoming bars per mix (outgoing) bar.
        val per = 1.0 / k
        val change = abs(target / currentSpeed - 1)
        // A flick of the fader for a small gap, longer rides for bigger ones.
        val ride = when { change <= .10f -> 4; change <= .18f -> 8; else -> 12 }
        // The ride is the long part; the blend after it is short.
        val blend = 4
        val durationMs = (og.barStart(lastBar + 1) + 400).toLong()
        // A clean stretch to loop: no vocal (or stems take it out); two bars when there are, else one.
        // A groove is best; a beatless bar rides inaudibly, which works too.
        var found: Pair<Int, Int>? = null
        for (lb in if (2 * og.barMs <= 9500) intArrayOf(2, 1) else intArrayOf(1)) {
            val range = exitFrom(firstBar, lastBar, payoff)..(lastBar + 1 - (lb + ride + 1 + blend))
            if (range.isEmpty()) continue
            // Any bar can loop; a clean one (no vocal, or stems take it out) and a groove are best.
            fun clean(b: Int) = (b until b + lb).all { x -> outStems || op.vocal(x) in 0f..(.3f) }
            fun groove(b: Int) = (b until b + lb).all { op.drums(it) >= .25f }
            val b = keyBar(op, og, durationMs, range, payoff) { b -> (if (clean(b)) 1.0 else 0.0) + if (groove(b)) .5 else 0.0 }
                ?: continue
            found = b to lb
            if (clean(b) || lb == 1) break
        }
        val (i, loopBars) = found ?: return null
        // A sung loop steps its mids back once the incoming is in, so two voices never sit together.
        val sungLoop = !outStems && (i until i + loopBars).any { op.vocal(it) > .3f }
        val hold = loopBars.toFloat()
        val entry = loopBars + ride + 1
        val bars = entry + blend
        // The incoming enters on a phrase line so its first full bar lands at the blend's end
        // (the blend is blend × per of its own bars).
        val inBlend = (blend * per).roundToInt()
        val full = firstFullBar(ip, 0)
        val j = full?.let { (it - inBlend).coerceAtLeast(0) }?.takeIf { ig.barStart(it) <= 16_000 } ?: 0
        val wallBar = og.barMs / target
        val e = (INSTANT_MS / wallBar).toFloat()
        val en = entry.toFloat()
        val sw = en + blend / 2
        val end = bars.toFloat()
        val o = Deck.of(1f)
        o.loops += DjTransitionPlan.Loop(0f, 4f * loopBars, end)
        if (outStems) o.lane(P.VOX).ramp(0f, e, 0f)
        // Creative colours the ride: a rising high-pass while it speeds up, a closing low-pass
        // while it slows down, both released as the incoming arrives.
        if (options.style == MixStyle.CREATIVE) {
            val up = target > currentSpeed
            o.lane(if (up) P.HPF else P.LPF).ramp(hold, en - 1f, if (up) .28f else .3f, accelerate = true).ramp(en - .5f, en, 0f)
        }
        o.lane(P.HIGH).ramp(en, en + .5f, .7f)
        if (sungLoop) o.lane(P.MID).ramp(en, en + .5f, .45f)
        for (p in listOf(P.SUB, P.BASS)) instant(o, p, sw, 0f, wallBar)
        o.lane(P.MID).ramp(sw, end, 0f)
        o.lane(P.HIGH).ramp(sw, end, 0f)
        o.lane(P.HPF).ramp(sw, end, .4f, accelerate = true)
        o.lane(P.DRY).ramp(end - e, end, 0f)
        o.lane(P.WET).ramp(end - e, end, 0f)
        val n = Deck.of(0f)
        n.lane(P.HPF).ramp(en, en + e, .3f).ramp(sw - 1f, sw, 0f)
        n.lane(P.HIGH).ramp(en, en + e, .85f).ramp(sw, sw + e, 1f)
        n.lane(P.MID).ramp(en, en + e, .7f).ramp(sw, sw + e, 1f)
        for (p in listOf(P.SUB, P.BASS)) instant(n, p, sw, 1f, wallBar)
        if (inStems) {
            n.hold(P.VOX, 0f).ramp(end - 1f, end - e, 1f)
            for (p in listOf(P.OTHER, P.BASSLINE)) n.hold(p, 0f).ramp(sw, sw + e, 1f)
        }
        val outStart = og.barStart(i)
        val cue = ig.barStart(j).coerceAtLeast(0.0)
        val time = when (k) { .5 -> " (half-time)"; 2.0 -> " (double-time)"; else -> "" }
        val notes = "context: loop ride ${loopBars}-bar loop @$i${if (sungLoop) " sung" else ""}, " +
            "${"%.3f".format(currentSpeed)} → ${"%.3f".format(target)}$time " +
            "over $ride bars, in@$j blend $blend $why outConf=${"%.2f".format(og.confidence)} inConf=${"%.2f".format(ig.confidence)}"
        return Mix(Family.LOOP_RIDE, Fill.NONE, bars, sw.toInt(), entry, outStart.roundToLong(), og.barMs, currentSpeed,
            wallBar, cue.roundToLong(), o, n, sync = true, hardEntry = true, bpmOut = og.bpm.toFloat(), bpmIn = ig.bpm.toFloat(),
            notes = notes, holdOutgoing = true, incomingBarMs = ig.barMs, exitBar = i, incomingBar = j,
            rampSpeed = target, rampFromBar = hold, rampToBar = (entry - 1).toFloat(),
            outStemsMs = if (outStems) (outStart - 1000).roundToLong()..(outStart + bars * og.barMs + 1000).roundToLong() else null,
            inStemsMs = if (inStems) (cue - 1000).roundToLong().coerceAtLeast(0)..(cue + (inBlend + 1) * ig.barMs + 1000).roundToLong() else null)
    }

    private fun instant(d: Deck, p: P, at: Float, to: Float, wallBar: Double = 2000.0) {
        val e = (INSTANT_MS / wallBar).toFloat()
        d.lane(p).ramp(at, at + e, to)
    }

    /** Key bar near the outgoing's end: a phrase or section line, the outro, its last bar, as a line ends. */
    private fun keyBar(op: TrackProfile, og: Grid, durationMs: Long, range: IntRange, payoff: Int? = null,
                       extra: (Int) -> Double): Int? {
        fun boundary(i: Int) = when {
            i in op.sections -> 2.0
            op.isPhraseStart(i, 8) -> 1.0
            op.isPhraseStart(i, 4) -> .5
            else -> 0.0
        }
        return range.maxByOrNull { k ->
            var s = 1.5 * boundary(k)
            if (op.outroStartBar < op.bars && k >= op.outroStartBar) s += 1.0
            s -= if (payoff != null) {
                if (k < payoff) 3.0 else ((k - payoff - 16).coerceAtLeast(0) * .03).coerceAtMost(1.5)
            } else ((durationMs - og.barStart(k)) / 1000 - 15).coerceAtLeast(0.0) * .05
            s + extra(k)
        }
    }

    /**
     * Acapella out (outgoing stems): on a phrase line the outgoing's drums, bass line and
     * the rest drop away and its vocal carries its last line over the incoming's opening
     * with no beat under it (a drumless intro, or with the incoming's stems its melody with
     * drums and bass held back). The incoming's beat lands as the line ends. Creative throws
     * the last word into the echo.
     */
    private fun acapellaOut(
        og: Grid, ig: Grid, op: TrackProfile, ip: TrackProfile, firstBar: Int, lastBar: Int,
        options: Options, currentSpeed: Float, why: String, inStems: Boolean = false, payoff: Int? = null,
    ): Mix? {
        fun sung(i: Int) = op.vocal(i) >= .5f
        val clean = options.style == MixStyle.CLEAN
        val durationMs = (og.barStart(lastBar + 1) + 400).toLong()
        // The line must fill the a cappella and end on the key bar.
        val lens = intArrayOf(2, 1)
        var best: Pair<Int, Int>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (a in lens) {
            val range = maxOf(firstBar + a, exitFrom(firstBar, lastBar, payoff))..lastBar
            val k = keyBar(op, og, durationMs, range, payoff) { k ->
                if (!(sung(k - 1) && !sung(k)) || (k - a until k).any { !sung(it) }) -1e9 else if (a == 2) .5 else 0.0
            } ?: continue
            if (!(sung(k - 1) && !sung(k)) || (k - a until k).any { !sung(it) }) continue
            val score = if (a == 2) .5 else 0.0
            if (best == null || score > bestScore) { best = k to a; bestScore = score }
        }
        val (key, a) = best ?: return null
        val speed = if (options.conformIncoming) currentSpeed else 1f
        val wallBar = og.barMs / speed
        val e = (INSTANT_MS / wallBar).toFloat()
        val m = a.toFloat()
        // What of the incoming plays under the a cappella (native tempo, timed on the outgoing's
        // wall bars): the stretch just before its landing bar, which must carry no beat.
        val underMs = a * wallBar
        // The incoming's beat lands as the line ends: its first full bar, else its start. With
        // nothing before that bar, its stems let its opening bars play under the line instead.
        var j = firstFullBar(ip, 0)?.takeIf { ig.barStart(it) <= 16_000 } ?: 0
        if (ig.barStart(j) < underMs) {
            if (!inStems) return null
            j = ceil((underMs - ig.barStart(0)) / ig.barMs).toInt().coerceAtLeast(1)
        }
        var cue = ig.barStart(j) - underMs
        var entry = 0
        while (cue < 0 && entry < a) { entry++; cue += wallBar }
        if (cue < 0) cue = 0.0
        val underFrom = ip.barAt(cue).coerceAtLeast(0)
        val underDrums = (underFrom until j).map { ip.drums(it).coerceAtLeast(0f) }.average().takeIf { !it.isNaN() } ?: 0.0
        val underVocal = (underFrom until j).map { ip.vocal(it).coerceAtLeast(0f) }.average().takeIf { !it.isNaN() } ?: 0.0
        // Two voices or two beats would clash: drums need stems to hold back; a sung intro is never under it.
        if (underVocal >= BED_MAX_VOCAL || (!inStems && underDrums >= BED_MAX_DRUMS)) return null
        val holdDrums = inStems && underDrums >= BED_MAX_DRUMS
        val o = Deck.of(1f)
        for (p in listOf(P.DRUMS, P.BASSLINE, P.OTHER)) o.lane(p).ramp(0f, .0625f, 0f)
        // Clean: the incoming owns one bar before the handover; Creative lets the echo ring over two.
        val tail = if (clean) 1 else 2
        if (!clean) {
            // The last word into a long echo, closed with the dry.
            o.echoBeats = .75f
            o.lane(P.FEEDBACK).ramp(m - .25f, m, .6f)
            o.lane(P.SEND).ramp(m - .25f, m, .7f).ramp(m, m + e, 0f)
            o.lane(P.WET).ramp(m + 1.5f, m + 2f, 0f)
        } else o.lane(P.WET).ramp(m - e, m, 0f)
        o.lane(P.DRY).ramp(m - .0625f, m, 0f)
        val n = Deck.of(0f)
        val s0 = entry.toFloat()
        // Under the line: the incoming's opening, softer and without its low end; all of it on the landing.
        if (entry < a) {
            n.lane(P.MID).ramp(s0, s0 + e, .7f)
            n.lane(P.HIGH).ramp(s0, s0 + e, .7f)
        }
        if (holdDrums) for (p in listOf(P.DRUMS, P.BASSLINE)) n.hold(p, 0f).ramp(m - e, m, 1f)
        for (p in listOf(P.MID, P.HIGH)) n.lane(p).ramp(m - e, m, 1f)
        for (p in listOf(P.SUB, P.BASS)) n.lane(p).ramp(m - e, m, 1f)
        val exit = key - a
        val outStart = og.barStart(exit)
        val notes = "context: acapella out line ends @$key, $a bar${if (a > 1) "s" else ""} a cappella over the incoming's " +
            "opening${if (holdDrums) " (drums held by stems)" else ""}, in@$j $why " +
            "outConf=${"%.2f".format(og.confidence)} inConf=${"%.2f".format(ig.confidence)}"
        return Mix(Family.ACAPELLA_OUT, Fill.NONE, a + tail, a, entry, outStart.roundToLong(), og.barMs, speed, wallBar,
            cue.roundToLong(), o, n, sync = false, hardEntry = true, bpmOut = og.bpm.toFloat(), bpmIn = ig.bpm.toFloat(),
            notes = notes, holdOutgoing = options.conformIncoming, exitBar = exit, incomingBar = ip.barAt(cue),
            outStemsMs = (outStart - 1000).roundToLong()..(outStart + (a + 1) * og.barMs + 1000).roundToLong(),
            inStemsMs = if (holdDrums) (cue - 1000).roundToLong().coerceAtLeast(0)..(cue + underMs + 1000).roundToLong() else null)
    }

    /**
     * Vocal preview (incoming stems): over a 4-bar build on the outgoing, the incoming's
     * hook vocal (two of its bars, its stem only) is voiced on the outgoing deck, ending on
     * the cut; then the incoming starts from its beginning. With outgoing stems its own
     * vocal steps aside under the preview; without, the build is placed where it is not singing.
     */
    private fun vocalPreview(
        og: Grid, ig: Grid, op: TrackProfile, ip: TrackProfile, firstBar: Int, lastBar: Int, options: Options,
        currentSpeed: Float, outStems: Boolean, drop: Double, why: String, payoff: Int? = null,
    ): Mix? {
        val hook = hook(ip) ?: return null
        val clean = options.style == MixStyle.CLEAN
        val speed = if (options.conformIncoming) currentSpeed else 1f
        val wallBar = og.barMs / speed
        val clipBarsIn = 2
        val clipWall = clipBarsIn * ig.barMs / wallBar
        val length = 4
        if (clipWall > length - .5) return null
        val tail = if (clean) 1 else 2
        val durationMs = (og.barStart(lastBar + 1) + 400).toLong()
        val range = maxOf(firstBar + length, exitFrom(firstBar, lastBar, payoff) + length)..(lastBar + 1 - tail)
        if (range.isEmpty()) return null
        val from = length - clipWall
        val cut = keyBar(op, og, durationMs, range, payoff) { k ->
            // Under the preview the outgoing should not be singing (unless stems take it out).
            val under = (floor(k - clipWall).toInt() until k).map { op.vocal(it).coerceAtLeast(0f) }.average()
            if (outStems) 0.0 else -2.0 * under
        } ?: return null
        val exit = cut - length
        // The incoming must land on something from its start.
        val j = if (ip.energy(0) >= .3f * ip.bodyEnergy) 0 else firstFullBar(ip, 0)?.takeIf { ig.barStart(it) <= 16_000 } ?: return null
        val e = (INSTANT_MS / wallBar).toFloat()
        val m = length.toFloat()
        val o = Deck.of(1f)
        // The build: a rising high-pass, the low end thinning, the dry gone on the cut.
        o.lane(P.HPF).ramp(0f, m - .25f, .42f, accelerate = true)
        o.lane(P.SUB).ramp(m / 2, m - .25f, .3f)
        o.lane(P.BASS).ramp(m / 2, m - .25f, .4f)
        val at = from.toFloat()
        o.sampleAt = at
        o.lane(P.SAMPLE).ramp(at, at + e, .9f).ramp(m - e, m, 0f)
        if (outStems) o.lane(P.VOX).ramp(at - .25f, at, 0f)
        if (!clean) {
            o.echoBeats = .5f
            o.lane(P.SEND).ramp(m - .5f, m, .4f).ramp(m, m + e, 0f)
        }
        o.lane(P.DRY).ramp(m - .0625f, m, 0f)
        o.lane(P.WET).ramp(m + tail - .5f, m + tail, 0f)
        val n = Deck.of(0f)
        for (p in listOf(P.SUB, P.BASS, P.MID, P.HIGH)) n.lane(p).ramp(m, m + e, 1f)
        val outStart = og.barStart(exit)
        val clip = ig.barStart(hook).roundToLong()..ig.barStart(hook + clipBarsIn).roundToLong()
        val notes = "context: vocal preview hook@$hook over a $length-bar build, cut@$cut drop=${"%.1f".format(drop)}dB $why " +
            "outConf=${"%.2f".format(og.confidence)} inConf=${"%.2f".format(ig.confidence)}"
        return Mix(Family.VOCAL_PREVIEW, Fill.NONE, length + tail, length, length, outStart.roundToLong(), og.barMs,
            speed, wallBar, ig.barStart(j).coerceAtLeast(0.0).roundToLong(), o, n, sync = false, hardEntry = true,
            bpmOut = og.bpm.toFloat(), bpmIn = ig.bpm.toFloat(), notes = notes, holdOutgoing = options.conformIncoming,
            exitBar = exit, incomingBar = j, clipMs = clip,
            outStemsMs = if (outStems) (outStart - 1000).roundToLong()..(outStart + (length + 1) * og.barMs + 1000).roundToLong() else null)
    }

    /**
     * How a planned mix compares with a clean DJ transition. Both reference
     * transitions studied for v2 score 0 on every field.
     *
     * - [landingErrorBeats]: distance from where the incoming is fully in (its first
     *   full bar with every band open) to the nearest outgoing 8-bar phrase line or
     *   the outgoing's end; null when the incoming never measures as full.
     * - [bothLowBars]: bars where both decks' low ends are audible together.
     * - [outgoingAfterFullBars]: bars the outgoing is still heard after that point.
     * - [outgoingSpeedChangePct]: tempo change applied to the song already playing.
     */
    data class Metrics(
        val landingErrorBeats: Int?,
        val bothLowBars: Int,
        val outgoingAfterFullBars: Int,
        val outgoingSpeedChangePct: Double,
    ) {
        override fun toString() = "landing=${landingErrorBeats ?: "?"}beats bothLow=${bothLowBars}bars " +
            "outAfter=${outgoingAfterFullBars}bars outSpeed=${"%+.1f".format(outgoingSpeedChangePct)}%"
    }

    fun metrics(mix: Mix, out: TrackProfile?, inc: TrackProfile?, currentSpeed: Float = 1f): Metrics {
        val o = mix.outgoing
        val n = mix.incoming
        fun inBar(b: Int) = mix.incomingBar + (b - mix.entryBar)
        fun inFull(b: Int) = inc == null || (inc.drums(inBar(b)) >= .25f && inc.energy(inBar(b)) >= .9f * inc.bodyEnergy)
        val bands = listOf(P.SUB, P.BASS, P.MID, P.HIGH)
        var full: Int? = null
        for (b in mix.entryBar..mix.bars) {
            val x = b + .05f
            val open = b == mix.bars || bands.all { n.at(it, x) >= .95f }
            if (open && inFull(b)) { full = b; break }
        }
        val landing = full?.let { f ->
            val at = mix.exitBar + f
            val off = out?.phraseOffset ?: 0
            val r = (at - off).mod(8)
            val toLine = minOf(r, 8 - r)
            val toEnd = out?.let { abs(it.bars - at) } ?: Int.MAX_VALUE
            minOf(toLine, toEnd) * 4
        }
        fun low(p: TrackProfile?, i: Int) = if (p == null) 1f else lowAt(p, i).coerceAtLeast(0f)
        var both = 0
        var after = 0
        for (b in 0 until mix.bars) {
            val x = b + .5f
            val outLow = o.at(P.BASS, x) * o.at(P.DRY, x) * low(out, mix.exitBar + b)
            val inLow = if (b >= mix.entryBar) n.at(P.BASS, x) * n.at(P.DRY, x) * low(inc, inBar(b)) else 0f
            if (outLow > .25f && inLow > .25f) both++
            val outAudible = o.at(P.DRY, x) * bands.maxOf { o.at(it, x) }
            if (full != null && b >= full && outAudible > .1f) after++
        }
        val change = if (mix.holdOutgoing) 0.0 else (mix.speed / currentSpeed - 1.0) * 100
        return Metrics(landing, both, after, change)
    }
}
