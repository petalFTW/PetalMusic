/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.abs
import kotlin.math.round

/** An arrangement of held spectral parts, selected from both dry decks. */
internal class PartExchangePlan private constructor(
    val lead: Int,
    val carrier: Int,
    val exchanges: List<Exchange>,
    val description: String,
) {
    /** Incoming ownership changes on a cue; between cues the parts hold steady. */
    data class Exchange(val group: Int, val start: Float, val end: Float, val incoming: Float)

    fun incoming(band: Int, progress: Float): Float {
        val group = if (band <= 1) 0 else band - 1
        var value = 0f
        for (event in exchanges) {
            if (event.group != group || progress < event.start) continue
            if (progress >= event.end) value = event.incoming else {
                val t = ((progress - event.start) / (event.end - event.start)).coerceIn(0f, 1f)
                return value + (event.incoming - value) * t * t * (3f - 2f * t)
            }
        }
        return value
    }

    companion object {
        private val names = arrayOf("bass", "mids", "percussion")
        fun analyse(a: MixAudioMonitor.Frame, b: MixAudioMonitor.Frame,
                    context: EqTransition.Context, durationMs: Float): PartExchangePlan {
            fun energy(f: MixAudioMonitor.Frame, g: Int) = when (g) {
                0 -> f.share(0) + f.share(1)
                else -> f.share(g + 1)
            }
            fun onset(f: MixAudioMonitor.Frame, g: Int) = when (g) {
                0 -> maxOf(f.onset.sub, f.onset.bass)
                else -> f.onset[g + 1]
            }
            fun change(f: MixAudioMonitor.Frame, g: Int): Float {
                val bands = if (g == 0) 0..1 else (g + 1)..(g + 1)
                val now = bands.sumOf { f.bands[it].toDouble() }
                val before = bands.sumOf { f.baseline[it].toDouble() }
                return ((now - before) / maxOf(before, .002)).toFloat().coerceIn(-1f, 1f)
            }
            val ea = FloatArray(3) { energy(a, it) }
            val eb = FloatArray(3) { energy(b, it) }
            // Choose a complementary pair jointly, not a fixed high/bass/mid order.
            // Sustained incompatible mids make a poor outgoing carrier.
            var lead = 2
            var carrier = 0
            var best = -Float.MAX_VALUE
            for (l in 0..2) for (c in 0..2) {
                if (l == c) continue
                // Prefer to keep the outgoing's bass or body and let its
                // percussion trade away. Retained drums are the main source of a
                // cluttered, obnoxious overlap, so they score far lower here.
                val carrierBias = when (c) { 0 -> 1.15f; 1 -> 1f; else -> 0.4f }
                // A distant known key favours a single melodic owner. Unknown
                // keys make no compatibility claim; spectral evidence still leads.
                val tonalFit = if (context.tonalClash && c == 1) .15f else 1f
                val melodicLead = if (context.tonalClash && l == 1) 1.25f else 1f
                val score = melodicLead * eb[l] * (1f - .6f * ea[l]) * (1f + onset(b, l)) +
                    ea[c] * (1f - .6f * eb[c]) * carrierBias * tonalFit * (1f + onset(a, c))
                if (score > best) { best = score; lead = l; carrier = c }
            }
            val body = (0..2).first { it != lead && it != carrier }
            val distance = (0..2).sumOf { abs(ea[it] - eb[it]).toDouble() }.toFloat() * .5f
            val beat = if (context.beatMs > 0f && durationMs >= context.beatMs * 8)
                context.beatMs / durationMs else 0f
            fun cue(value: Float, lo: Float, hi: Float): Float {
                val bounded = value.coerceIn(lo, hi)
                if (beat == 0f) return bounded
                val first = kotlin.math.ceil(lo / beat)
                val last = kotlin.math.floor(hi / beat)
                return if (first <= last) round(bounded / beat).coerceIn(first, last) * beat else bounded
            }
            // Short exchanges surrounding long holds: signal features set the
            // arrival points, while a known beat grid constrains their placement.
            val entry = cue(.12f + .12f * ea[lead] - .06f * change(b, lead), .08f, .26f)
            val bodyAt = cue(.40f + .18f * ea[body] - .12f * eb[body] -
                .07f * change(b, body) + .06f * change(a, body), .28f, .65f)
            val exchangeMs = when {
                context.seam && context.beatMs > 0f -> (context.beatMs * 2f).coerceIn(800f, 2000f)
                context.seam -> 1200f + 600f * (1f - distance)
                context.beatMs > 0f -> (context.beatMs * .75f).coerceIn(250f, 600f)
                else -> 350f + 250f * (1f - distance)
            }
            val width = (exchangeMs / durationMs).coerceIn(.006f, .12f)
            val events = arrayListOf(
                Exchange(lead, entry, entry + width, 1f),
                Exchange(body, bodyAt, bodyAt + width, 1f),
            )
            // A rhythmic outgoing part may answer the incoming lead once. Never
            // cut up sustained mids to manufacture variation. Require evidence
            // of energy and transients in that exact outgoing part.
            val answer = context.style == org.rab1d.petal.music.data.AutomixStyle.STUTTER &&
                beat > 0f && !context.tonalClash && lead != 1 &&
                ea[lead] > .08f && onset(a, lead) > .12f
            if (answer) {
                val at = cue(maxOf(bodyAt + width * 2, .52f + .12f * eb[lead]),
                    .48f, minOf(.73f, .90f - width * 2))
                val back = cue(at + maxOf(beat * 2, .10f + .08f * ea[lead]), at + width * 2, .90f)
                events += Exchange(lead, at, at + width, 0f)
                events += Exchange(lead, back, minOf(back + width, .94f), 1f)
            }
            // Fit the arrangement before the common closing fade. Nothing may
            // return once all outgoing parts have started their final release.
            val closing = MixTiming.closingWindow(durationMs, context.beatMs)
            val arranged = events.map {
                val width = (it.end - it.start) * closing.start
                // Snap AFTER fitting into the closing budget. Scaling a snapped
                // cue afterwards moved every EQ exchange off the measured beat.
                val start = cue(it.start * closing.start, .08f, closing.start - width)
                it.copy(start = start, end = start + width)
            }.toMutableList()
            arranged += Exchange(carrier, closing.start, closing.end, 1f)
            return PartExchangePlan(lead, carrier, arranged.sortedBy { it.start },
                "${names[lead]} in · hold outgoing ${names[carrier]}" + if (answer) " · rhythmic return" else "")
        }
    }
}
