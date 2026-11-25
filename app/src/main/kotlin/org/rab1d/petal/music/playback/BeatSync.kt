/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Keeps two independently started decks on the same beat, as a DJ rides the
 * jog wheel: measure where each deck's audible playhead sits on its own bar
 * grid, and when they disagree nudge the outgoing tempo by at most 1.2 % for
 * just long enough to close the gap, then return to the matched speed.
 */
internal object BeatSync {
    /** Below this the offset is inaudible as a flam; leave the decks alone. */
    const val DEADBAND_MS = 5.0
    private const val MAX_NUDGE = .012
    private const val CORRECTION_MS = 1600.0

    /**
     * Wall-clock ms by which the outgoing's grid leads the incoming's
     * (+ = outgoing early), wrapped to the nearest beat.
     */
    fun errorMs(
        outPositionMs: Long, outOriginMs: Double, outBarMs: Double,
        inPositionMs: Long, inOriginMs: Double, inBarMs: Double,
        /** Bar length in wall time; equals [inBarMs] when the incoming plays at native speed. */
        wallBarMs: Double = inBarMs,
    ): Double {
        val bars = (outPositionMs - outOriginMs) / outBarMs - (inPositionMs - inOriginMs) / inBarMs
        val beats = bars * 4
        return (beats - Math.round(beats)) * wallBarMs / 4
    }

    data class Nudge(val speed: Float, val durationMs: Long)

    /** A temporary outgoing speed that cancels [errorMs], or null inside the deadband. */
    fun nudge(errorMs: Double, matchedSpeed: Float): Nudge? {
        if (abs(errorMs) < DEADBAND_MS) return null
        val rel = (errorMs / CORRECTION_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        return Nudge((matchedSpeed * (1 - rel)).toFloat(), (abs(errorMs) / abs(rel)).roundToLong())
    }
}
