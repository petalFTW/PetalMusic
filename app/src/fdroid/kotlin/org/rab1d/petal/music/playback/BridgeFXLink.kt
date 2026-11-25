/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context

/**
 * F-Droid build: no BridgeFX HAL. Never attaches, so AutomixEngine always
 * takes the internal DSP path. Mirrors the rom flavor's public surface.
 */
@Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
class BridgeFXLink {
    val attached: Boolean get() = false

    fun attach(audioSessionId: Int): Boolean = false
    fun detach() = Unit
    fun setPlaybackActive(active: Boolean) = Unit
    fun setFade(ms: Int): Boolean = false
    fun setStyle(style: Int): Boolean = false
    fun arm(countdownMs: Int): Boolean = false
    fun abort(): Boolean = false
    fun boundary(introAgeMs: Int, transitionId: Int): Boolean = false
    fun pushProfile(profile: IntArray): Boolean = false
    fun analysis(): IntArray? = null
    fun diagnostics(): IntArray? = null
    fun renderStats(): IntArray? = null

    companion object {
        fun announce(context: Context, fadeMs: Int) = Unit
    }
}
