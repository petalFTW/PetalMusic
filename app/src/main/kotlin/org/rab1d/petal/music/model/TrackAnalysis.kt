/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.model

/**
 * Per-track musical facts used by the DJ planner. Shared between sources (which
 * can supply authoritative metadata) and playback (which derives it from the
 * audio stream when the source has none).
 */
data class TrackAnalysis(
    val bpm: Float,
    /** ms into the track where a beat lands (0 when unknown). */
    val phaseMs: Float = 0f,
    val strength: Float = 0f,
    /** Camelot wheel code (1..12), when available. */
    val camelot: Int? = null,
    /**
     * True when [bpm]/[camelot] come from the source's own metadata (TIDAL)
     * rather than being estimated from the waveform. Authoritative facts are
     * never overwritten by the HAL's guesses.
     */
    val authoritative: Boolean = false,
    /** Camelot A (minor) or B (major); null when the source didn't identify mode. */
    val minor: Boolean? = null,
    /** Measured media position of a bar's first beat; null when the bar phase is ambiguous. */
    val downbeatMs: Float? = null,
    /** First bar where the low end fully arrives after a thinner intro (null: none found). */
    val introDropMs: Long? = null,
    /** The opening bars are percussive rather than tonal pads/vocals (null: not analysed). */
    val drumIntro: Boolean? = null,
) {
    /** True when the beat phase was measured from audio rather than metadata. */
    val gridded: Boolean get() = strength >= .3f && phaseMs.isFinite()

    /** Keep an earlier measurement when a source refreshes metadata-only facts. */
    fun withMeasurementsFrom(old: TrackAnalysis?): TrackAnalysis {
        if (old == null) return this
        val agrees = old.bpm > 0f && bpm > 0f &&
            kotlin.math.abs(old.bpm - bpm) / bpm < .03f
        val grid = !gridded && old.gridded && (agrees || bpm <= 0f)
        return copy(
            bpm = if (bpm <= 0f) old.bpm else bpm,
            phaseMs = if (grid) old.phaseMs else phaseMs,
            strength = if (grid) old.strength else strength,
            camelot = camelot ?: old.camelot,
            minor = minor ?: old.minor,
            downbeatMs = downbeatMs ?: old.downbeatMs.takeIf { grid || gridded && agrees },
            introDropMs = introDropMs ?: old.introDropMs,
            drumIntro = drumIntro ?: old.drumIntro,
        )
    }
}
