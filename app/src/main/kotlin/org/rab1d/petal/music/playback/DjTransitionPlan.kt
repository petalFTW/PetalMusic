/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

/**
 * Automation primitives for AutoMix v2. Each deck gets lanes (band kills,
 * resonant filter sweeps, a tempo-synced echo, gates) plus roll and spin events,
 * evaluated by its own audio processor against its media clock, so a bass swap
 * lands on the downbeat sample rather than the next control tick. Positions are
 * in bars from the mix start (bar 0). [DjComposer] decides what to write here.
 */
object DjTransitionPlan {

    /**
     * Band gains first (the effects index them 0..3), then filters and echo. DRUMS,
     * BASSLINE, VOX and OTHER are stem gains, applied only on a deck that carries a
     * [StemWindow] for the mix range (unity = the original audio). SAMPLE is the gain
     * of a [StemClip] voiced on this deck from [Deck.sampleAt].
     */
    enum class P { SUB, BASS, MID, HIGH, HPF, LPF, SEND, FEEDBACK, DRY, WET, DRUMS, BASSLINE, VOX, OTHER, SAMPLE }

    /** Piecewise ramps in bars. Each ramp starts from the value the lane already holds. */
    class Lane(initial: Float) {
        private val starts = ArrayList<Float>()
        private val ends = ArrayList<Float>()
        private val froms = ArrayList<Float>()
        private val tos = ArrayList<Float>()
        /** 0 = smoothstep, 1 = accelerate (t²), 2 = linear. */
        private val curves = ArrayList<Int>()
        private var value = initial
        private val first = initial

        /** The value this lane holds after its last ramp. */
        val last: Float get() = value

        fun ramp(start: Float, end: Float, to: Float, accelerate: Boolean = false, linear: Boolean = false): Lane {
            val s = maxOf(start, ends.lastOrNull() ?: 0f)
            starts += s
            ends += maxOf(end, s)
            froms += value
            tos += to
            curves += if (linear) 2 else if (accelerate) 1 else 0
            value = to
            return this
        }

        fun at(bar: Float): Float {
            var v = first
            for (i in starts.indices) {
                if (bar < starts[i]) return v
                if (bar >= ends[i]) { v = tos[i]; continue }
                val t = (bar - starts[i]) / (ends[i] - starts[i])
                val u = when (curves[i]) { 2 -> t; 1 -> t * t; else -> t * t * (3f - 2f * t) }
                return froms[i] + (tos[i] - froms[i]) * u
            }
            return v
        }
    }

    /** Roll slices per beat from [at] bars; 0 ends the roll. Steps halve the slice. */
    data class RollStep(val at: Float, val perBeat: Int)

    /**
     * Loop: the deck's audio from [at] for [beats] beats repeats until bar [until]. The
     * media keeps playing underneath, so a loop replaces those bars (a DJ looping the
     * last clean bar so a vocal does not run on over the next record).
     */
    data class Loop(val at: Float, val beats: Float, val until: Float)

    enum class SpinKind { BACKSPIN, BRAKE, SPINUP }

    /** Turntable move over [beats] from [at]: spin-back, tape-stop, or a motor start that lands on the beat. */
    data class Spin(val at: Float, val beats: Float, val kind: SpinKind)

    class Deck(
        val lanes: Array<Lane>,
        val rolls: MutableList<RollStep> = ArrayList(),
        var echoBeats: Float = .5f,
        val spins: MutableList<Spin> = ArrayList(),
        val loops: MutableList<Loop> = ArrayList(),
        /** Bar at which this deck's sample clip starts (negative: none). */
        var sampleAt: Float = -1f,
    ) {
        fun at(p: P, bar: Float) = lanes[p.ordinal].at(bar)
        fun lane(p: P) = lanes[p.ordinal]

        /** Start lane [p] at [value] from bar 0 (before any ramp on it). */
        fun hold(p: P, value: Float): Lane = Lane(value).also { lanes[p.ordinal] = it }

        /** The active roll group: (anchor bar, slices per beat), or null. */
        fun roll(bar: Float): Pair<Float, Int>? {
            var anchor = -1f
            var active = 0
            for (step in rolls) {
                if (bar < step.at) break
                if (step.perBeat == 0) { anchor = -1f; active = 0; continue }
                if (active == 0) anchor = step.at
                active = step.perBeat
            }
            return if (active > 0) anchor to active else null
        }

        fun spin(bar: Float): Spin? = spins.firstOrNull { bar >= it.at && bar < it.at + it.beats / 4f }

        fun loop(bar: Float): Loop? = loops.firstOrNull { bar >= it.at && bar < it.until }

        /** True when any stem lane moves away from unity. */
        val usesStems: Boolean get() = listOf(P.DRUMS, P.BASSLINE, P.VOX, P.OTHER).any { p ->
            (0..400).any { lane(p).at(it / 10f) < .999f }
        }

        companion object {
            /** Bands at [gain], filters open, echo idle, dry and wet at unity. */
            fun of(gain: Float) = Deck(Array(P.entries.size) { i ->
                Lane(when (P.entries[i]) {
                    P.SUB, P.BASS, P.MID, P.HIGH -> gain
                    P.HPF, P.LPF, P.SEND -> 0f
                    P.FEEDBACK -> .5f
                    P.DRY, P.WET -> 1f
                    P.DRUMS, P.BASSLINE, P.VOX, P.OTHER -> 1f
                    P.SAMPLE -> 0f
                })
            })
        }
    }
}

/** One deck's arrangement on its own media clock: bar 0 at [originMs]. */
internal class DeckAutomation(
    val originMs: Double,
    /** Bar length in this deck's media time (wall bar × its playback speed). */
    val barMs: Double,
    val deck: DjTransitionPlan.Deck,
    /** Separated stems over this deck's mix range, or null (stem lanes are then ignored). */
    val stems: StemWindow? = null,
    /** A clip voiced on this deck from [DjTransitionPlan.Deck.sampleAt]. */
    val clip: StemClip? = null,
) {
    val beatMs: Double get() = barMs / 4
    fun bar(mediaMs: Double) = ((mediaMs - originMs) / barMs).toFloat()
}
