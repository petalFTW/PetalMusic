/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** petalOS-only broadcasts to the SystemUI island and BridgeController. */
object RomHooks {
    /** incoming-cover cue for the SystemUI island's art fade */
    const val ACTION_ART_FADE = "org.rab1d.bridge.action.TRANSITION_ART"

    private var ownershipDeclared = false

    // Tell the island we're mixing so it picks the right shiny crap.
    fun announceInAppTransition(context: Context, durationMs: Int) {
        runCatching {
            context.sendBroadcast(
                Intent("org.rab1d.bridge.action.TRANSITION")
                    .setPackage("com.android.systemui")
                    .putExtra("duration", durationMs)
                    .putExtra("owner", context.packageName)
                    .putExtra("style", 0),
            )
        }
    }

    /** Hand the island the incoming sleeve so it can fade it in like the app. */
    fun announceArtFade(context: Context, cover: String?, durationMs: Int) {
        if (cover.isNullOrBlank()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_ART_FADE)
                    .setPackage("com.android.systemui")
                    .putExtra("owner", context.packageName)
                    .putExtra("cover", cover)
                    .putExtra("duration", durationMs)
                    .putExtra("active", true),
            )
        }
    }

    fun announceArtFadeEnd(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_ART_FADE)
                    .setPackage("com.android.systemui")
                    .putExtra("owner", context.packageName)
                    .putExtra("active", false),
            )
        }
    }

    // Tell BridgeController to leave our mix alone until we're done.
    fun declareAutomixOwnership(context: Context) {
        // Broadcast ownership; that old privileged settings write just threw.
        context.sendBroadcast(
            Intent("org.rab1d.bridge.action.OWNER")
                .setPackage("org.rab1d.bridge")
                .putExtra("package", context.packageName)
                .putExtra("owner", true),
        )
        if (!ownershipDeclared) {
            ownershipDeclared = true
            Log.i("PetalMusic/BridgeFX", "declared automix ownership to BridgeController")
        }
    }

    fun clearAutomixOwnership(context: Context) {
        context.sendBroadcast(
            Intent("org.rab1d.bridge.action.OWNER")
                .setPackage("org.rab1d.bridge")
                .putExtra("owner", false),
        )
        runCatching {
            if (!Settings.System.canWrite(context)) return@runCatching
            val current = Settings.System.getString(context.contentResolver, "petal_bridge_automix_pkg")
            if (current == context.packageName) {
                Settings.System.putString(context.contentResolver, "petal_bridge_automix_pkg", null)
            }
        }
    }
}
