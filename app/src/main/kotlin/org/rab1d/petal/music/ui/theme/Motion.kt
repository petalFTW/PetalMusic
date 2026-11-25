/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Motion tokens. Nothing here overshoots: springs are critically damped (ratio 1) and
 * the curves never pass 1. See docs/ui-redesign.md → Motion.
 */
object Motion {
    // Device feedback: 120/220/420 felt laggy, 90/160/260 felt choppy ("flashbanged").
    const val FAST = 110
    const val BASE = 200
    const val SLOW = 320
    const val THEME = 800

    /** Smooth deceleration, for things arriving. */
    val Enter = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    /** Snappy, for presses and toggles. */
    val Press = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Things leaving accelerate out. */
    val Exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> enter(ms: Int = BASE): FiniteAnimationSpec<T> = tween(ms, easing = Enter)
    fun <T> press(ms: Int = FAST): FiniteAnimationSpec<T> = tween(ms, easing = Press)
    fun <T> exit(ms: Int = FAST): FiniteAnimationSpec<T> = tween(ms, easing = Exit)

    /** Released drags (queue rows, the mini-player) settle without overshoot. */
    fun <T> drag(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 700f)
    /** Sheets and the player. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 500f)

    /** [spec] unless motion is reduced, then a snap. */
    fun <T> orSnap(reduced: Boolean, spec: FiniteAnimationSpec<T>): AnimationSpec<T> =
        if (reduced) snap() else spec
}

/** True when the system's "Remove animations" is on: movement becomes fades. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** True on battery saver or when the user asked for solid surfaces: glass loses its blur. */
val LocalReducedTransparency = staticCompositionLocalOf { false }

/** True while the system's battery saver is on: no audio-reactive drawing, no optional work. */
val LocalPowerSave = staticCompositionLocalOf { false }

object PetalMotion {
    val reduced: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
