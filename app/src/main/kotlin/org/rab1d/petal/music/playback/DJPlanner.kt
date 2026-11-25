/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.rab1d.petal.music.data.PlaybackSettings
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.TrackAnalysis
import kotlin.math.abs
import kotlin.math.roundToInt


/** Plans tempo ratios, beat cues and overlap budgets for the available renderer.
 * Only tempo is matched; musical key is never altered. Beat phase is not a
 * downbeat/phrase annotation; the structure analyser supplies independent outro
 * cues. The dual-player renderer owns live spectral decisions.
 */
object DJPlanner {

    enum class Kind { BEATMATCH, PLAIN, SLAM }

    data class Plan(
        val kind: Kind,
        val fadeMs: Int,
        /**
         * Playback speed for the **outgoing** track so its tempo matches the
         * incoming track for the whole blend (1.0 = untouched). The incoming
         * keeps its native tempo and pitch, so the new track carries on exactly
         * as recorded.
         */
        val outgoingSpeed: Float = 1f,
        /** delay before firing the boundary so the switch lands on a measured beat. */
        val alignMs: Long,
        val bpmOut: Float?,
        val bpmIn: Float?,
        /** length of the blend in bars (0 when unknown). */
        val bars: Int = 0,
        val eq: EqTransition.Context = EqTransition.Context(),
    )

    fun plan(
        current: Track?,
        incoming: Track?,
        out: TrackAnalysis?,
        inA: TrackAnalysis?,
        settings: PlaybackSettings,
        remainingMs: Long,
        /** A live structure cue identified the ending; use its remaining span. */
        outro: Boolean = false,
    ): Plan {
        fun tempo(a: TrackAnalysis?) = a?.bpm?.takeIf {
            it.isFinite() && it in 40f..250f && (a.authoritative || a.strength >= .3f)
        }
        val bpmOut = tempo(out)
        val bpmIn = tempo(inA)
        // A reliable beat *phase* only exists when the grid was measured (HAL or
        // waveform analysis). Source metadata supplies tempo but no phase, so its
        // zero phase must not be mistaken for a real downbeat.
        val outGrid = out?.takeIf { settings.beatAlign && it.strength >= .3f && it.phaseMs.isFinite() }
        val inGrid = inA?.takeIf { settings.beatAlign && it.strength >= .3f && it.phaseMs.isFinite() }
        val bothGrids = outGrid != null && inGrid != null && bpmIn != null
        // A measured bar phase lets both decks start on "1", not on any beat.
        val outDownbeat = outGrid?.downbeatMs?.takeIf { it.isFinite() }
        val inDownbeat = inGrid?.downbeatMs?.takeIf { it.isFinite() }
        val eqContext = EqTransition.Context(
            style = settings.style,
            tonalClash = out?.camelot?.takeIf { it in 1..12 }?.let { a ->
                inA?.camelot?.takeIf { it in 1..12 }?.let { b ->
                    val distance = abs(a - b)
                    minOf(distance, 12 - distance) > 1 ||
                        (a != b && out.minor != null && inA.minor != null && out.minor != inA.minor)
                }
            } ?: false,
            beatMs = bpmIn?.let { 60000f / it } ?: 0f,
            outgoingPhaseMs = outGrid?.phaseMs,
            outgoingBeatMs = bpmOut?.let { 60000f / it } ?: 0f,
            incomingCueMs = if (bothGrids) (inDownbeat?.mod(240000f / bpmIn!!)
                ?: inGrid!!.phaseMs.mod(60000f / bpmIn!!)).toLong() else 0L,
            outgoingDownbeatMs = outDownbeat,
        )


        // Octave neighbours are fine; big speed swings sound bloody awful.
        val outgoing = if (settings.automixEnabled && bpmOut != null && bpmIn != null) {
            floatArrayOf(bpmOut, bpmOut / 2f, bpmOut * 2f)
                .map { bpmIn / it }
                .filter { it in 0.92f..1.08f }
                .minByOrNull { abs(it - 1f) }
        } else null

        if (!settings.automixEnabled || (!settings.autoFade && settings.fadeMs < 250) || remainingMs <= 250) {
            return Plan(Kind.SLAM, 0, 1f, 0, bpmOut, bpmIn)
        }
        val followOutro = settings.autoFade && outro
        val requested = if (settings.autoFade) 45_000 else settings.fadeMs.coerceIn(250, 32_000)
        // An overlap must not swallow a short incoming track. Its native intro
        // remains intact, and most of the record plays after the handoff.
        val incomingBudget = incoming?.durationMs?.takeIf { it > 0 }
            ?.let { (it / 3).coerceAtLeast(250) } ?: 45_000L

        // Match only when the outgoing can stay close to its own tempo.
        if (outgoing != null && bpmOut != null && bpmIn != null && settings.automixEnabled) {
            val beatMs = 60000.0 / bpmIn
            val barMs = beatMs * 4
            // Missing analysis is not evidence that a short bridge sounds best.
            // Leave room for the incoming intro; live EQ manages competing parts.
            // A detected outro takes precedence over these fallback bar budgets.
            val preferredBars = when {
                !settings.autoFade -> 8
                followOutro -> 32
                eqContext.tonalClash || abs(outgoing - 1f) > .04f -> 8
                outGrid == null || inGrid == null -> 8
                out!!.strength < .65f || inA!!.strength < .65f -> 8
                out.camelot == null || inA.camelot == null -> 8
                else -> 16
            }
            val position = ((current?.durationMs ?: 0L) - remainingMs).coerceAtLeast(0)
            val beatDelay = when {
                outDownbeat != null -> BeatAnalyzer.align(position, bpmOut / 4f, outDownbeat) - position
                outGrid != null -> BeatAnalyzer.align(position, bpmOut, outGrid.phaseMs) - position
                else -> 0L
            }
            val align = beatDelay.takeIf { it < remainingMs - 250 } ?: 0L
            // Duration is wall time at the incoming tempo. Keep complete bars
            // when there is room, accounting for the outgoing speed change.
            val available = minOf(
                ((remainingMs - align - 250).coerceAtLeast(0) / outgoing).toDouble(),
                requested.toDouble(), incomingBudget.toDouble(),
                preferredBars * barMs,
                if (eqContext.tonalClash && !settings.autoFade) barMs * 2 else requested.toDouble(),
            )
            // Prefer complete bar groups when possible; a measured beat is
            // not evidence of a 16-bar phrase starting at the beginning of a file.
            val fitting = (available / barMs).toInt()
            val actualBars = if (followOutro) 0 else listOf(16, 8, 4, 2, 1).firstOrNull { it <= fitting } ?: 0
            val fade = if (actualBars > 0) (actualBars * barMs).roundToInt()
                else available.toInt().coerceAtLeast(1)
            // A beat phase does not identify a downbeat or a four-bar phrase.
            return Plan(Kind.BEATMATCH, fade, outgoing, align, bpmOut, bpmIn, actualBars,
                eqContext.copy(beatMs = if (bothGrids) eqContext.beatMs else 0f))

        }

        // No tempo on one side: hold complementary spectral parts and don't
        // pretend there's a grid to lock to.
        return Plan(
            Kind.PLAIN,
            minOf(requested.toLong(), incomingBudget, (remainingMs - 250).coerceAtLeast(1),
                when {
                    followOutro -> requested.toLong()
                    settings.autoFade -> if (bpmOut != null && bpmIn != null) 8000L else 12000L
                    bpmOut != null && bpmIn != null -> 2500L
                    else -> 4000L
                }).toInt(),
            1f,
            0,
            bpmOut,
            bpmIn,
            eq = eqContext.copy(beatMs = 0f, outgoingPhaseMs = null, incomingCueMs = 0L),
        )
    }

    /** Human description for the now-playing chip. */
    fun renderedPlan(plan: Plan?, active: Boolean, durationMs: Int): Plan? =
        if (active && durationMs > 0 && plan != null && durationMs != plan.fadeMs)
            plan.copy(fadeMs = durationMs, bars = 0) else plan

    fun describe(p: Plan): String = p.eq.dj?.let { "DJ · ${it.label} · ${p.fadeMs / 1000.0}s" } ?: when (p.kind) {
        Kind.BEATMATCH -> "Beatmatch ${"%.0f".format(p.bpmOut)}→${"%.0f".format(p.bpmIn)} BPM · ${p.fadeMs / 1000.0}s"
        Kind.PLAIN -> "AutoMix · ${p.fadeMs / 1000.0}s"
        Kind.SLAM -> "Cut"
    }

    /** One-line summary of the bands the blend moves. */
    fun eqSummary(p: Plan, seam: Boolean): String {
        val head = if (seam) "outro" else "phrase"
        val len = if (p.bars > 0) "${p.bars}b" else "${"%.0f".format(p.fadeMs / 1000.0)}s"
        p.eq.dj?.let { return "$head · ${it.label}" }
        return "$head · $len · live adaptive EQ"
    }
}
