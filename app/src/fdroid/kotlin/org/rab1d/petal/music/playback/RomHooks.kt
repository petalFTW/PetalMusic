/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context

/** F-Droid build: there is no petalOS SystemUI island or BridgeController. */
@Suppress("UNUSED_PARAMETER")
object RomHooks {
    fun announceInAppTransition(context: Context, durationMs: Int) = Unit
    fun announceArtFade(context: Context, cover: String?, durationMs: Int) = Unit
    fun announceArtFadeEnd(context: Context) = Unit
    fun declareAutomixOwnership(context: Context) = Unit
    fun clearAutomixOwnership(context: Context) = Unit
}
