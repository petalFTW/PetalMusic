/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.rab1d.petal.music.data.AutomixAlgorithm
import org.rab1d.petal.music.data.AutomixStyle

/** Immutable history of the actual gain commands; the UI never rewrites a completed curve. */
data class EqTransition(val label: String, val points: List<Point> = emptyList()) {
    data class Point(
        val progress: Float,
        val outgoing: BandLevels,
        val incoming: BandLevels,
        val outgoingRms: Float = 0f,
        val incomingRms: Float = 0f,
        /** v2 effect levels (0..1); zero for v1 blends. */
        val fx: Fx = Fx.NONE,
    )

    data class Fx(val outFilter: Float, val outEcho: Float, val outRoll: Float, val inFilter: Float) {
        companion object { val NONE = Fx(0f, 0f, 0f, 0f) }
    }


    /** Musical hints, not a choice of pre-authored band curves. */
    data class Context(
        val style: AutomixStyle = AutomixStyle.CLEAN,
        val algorithm: AutomixAlgorithm = AutomixAlgorithm.V1_BLEND,
        val beatMs: Float = 0f,
        val seam: Boolean = false,
        /** Distant known Camelot families: avoid retaining competing tonal body. */
        val tonalClash: Boolean = false,
        /** Measured beat origin in outgoing media time; null means no reliable grid. */
        val outgoingPhaseMs: Float? = null,
        val outgoingBeatMs: Float = 0f,
        /** Start on the incoming measured beat, preserving the rest of its intro. */
        val incomingCueMs: Long = 0L,
        /** Measured outgoing bar origin; null when only the beat phase is known. */
        val outgoingDownbeatMs: Float? = null,
        /** v2 composed mix; null renders the v1 live part exchange. */
        val dj: DjComposer.Mix? = null,
    ) {
        fun forOutro() = copy(seam = true)
    }

    data class Window(val start: Float, val end: Float) {
        init { require(start >= 0f && end <= 1f && start <= end) }
        fun gain(progress: Float): Float {
            if (progress < start) return 0f
            if (progress >= end) return 1f
            val t = (progress - start) / (end - start)
            return t * t * (3f - 2f * t)
        }
    }

    data class Band(val outgoing: Window, val incoming: Window) {
        private fun scale(t: Float) = maxOf(1f, 1f - outgoing.gain(t) + incoming.gain(t))
        fun outgoingGain(t: Float) = (1f - outgoing.gain(t)) / scale(t)
        fun incomingGain(t: Float) = incoming.gain(t) / scale(t)
    }

    companion object {
        val WAITING = EqTransition("Listening to both tracks")
    }
}
