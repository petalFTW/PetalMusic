/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

/**
 * BridgeFX values shared by every build. The internal AutoMix engine mirrors
 * the HAL's styles, so these exist even where the effect itself (rom flavor
 * only, see BridgeFXLink) does not.
 */
object BridgeFX {
    const val PROFILES_FILE = "bridge_profiles_v3"

    /** DSP styles — values must match bridge::Style in BridgeEngine.h. */
    const val STYLE_CLEAN = 0
    const val STYLE_REVERB = 1
    const val STYLE_STUTTER = 2
    const val STYLE_ADAPTIVE = 3
}
