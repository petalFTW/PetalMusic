/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.floor
import kotlin.math.roundToInt

/** Final overlap budget, evaluated after preparing and cueing both decks. */
internal object MixTiming {
    /** Protect even PCM buffered at unity before the EQ targets were applied.
     * Media time freezes this ramp during pause/buffering. Always finish before
     * the closing exchange, including emergency short-tail transitions. */
    fun entryGain(elapsedMs: Float, durationMs: Int, beatMs: Float): Float {
        val attack = if (beatMs.isFinite() && beatMs > 0f)
            (beatMs * 2f).coerceIn(800f, 1600f) else 1000f
        val span = minOf(attack, durationMs.coerceAtLeast(1) * .5f)
        val t = (elapsedMs / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Fade one unit before the final unit; full bars when at least four fit. */
    fun closingWindow(durationMs: Float, beatMs: Float): EqTransition.Window {
        val unit = if (beatMs.isFinite() && beatMs > 0f) {
            if (durationMs + 1f >= beatMs * 16f) beatMs * 4f else beatMs
        } else durationMs * .1f
        val fraction = (unit / durationMs.coerceAtLeast(1f)).coerceIn(.001f, .25f)
        return EqTransition.Window(1f - 2f * fraction, 1f - fraction)
    }

    fun durationMs(
        requestedMs: Int,
        availableMs: Double?,
        incomingStartMs: Long,
        incomingCueMs: Long,
        beatMs: Float,
        outro: Boolean = false,
    ): Int {
        // Leave room for decoder start and control-loop latency before the tail
        // ends. Do not extend a short tail to manufacture a complete bar.
        val maximum = if (!outro && beatMs.isFinite() && beatMs > 0f) beatMs.toDouble() * 64 else Double.MAX_VALUE
        val budget = minOf(requestedMs.toDouble(), maximum,
            availableMs?.minus(250.0)?.coerceAtLeast(1.0) ?: requestedMs.toDouble())
            .coerceAtLeast(1.0)
        if (beatMs.isFinite() && beatMs > 0f) {
            val offset = (incomingStartMs - incomingCueMs).toDouble()
            // Prefer complete eight-beat phrases; use four when eight won't fit.
            // Anchor to the cue, not an arbitrarily sampled playback position.
            // An ending cue already supplies structure. Preserve its full span
            // rather than dropping several seconds to manufacture a bar group.
            for (beats in if (outro) intArrayOf(1) else intArrayOf(8, 4)) {
                val group = beatMs.toDouble() * beats
                // Requested durations are rounded to ms; allow half a ms so an
                // exact fractional-tempo phrase doesn't lose an entire group.
                val count = floor((offset + budget + .5) / group)
                val duration = (count * group - offset).roundToInt()
                if (count >= 1 && duration >= 500 && duration <= budget + .5) return duration
            }
        }
        // Insufficient tail or no tempo: retain the bounded emergency fade.
        return floor(budget).toInt().coerceAtLeast(1)
    }
}
