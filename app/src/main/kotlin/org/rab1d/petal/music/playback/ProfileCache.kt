/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Musical-profile cache shared (by file name) with the BridgeController, so
 * intro/outro profiles learned by either app are pushed to the HAL via op 15.
 * Record format: "<timestampMs>,<int,int,...>" — same wire shape.
 */
object ProfileCache {
    private const val MAX = 500

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(BridgeFX.PROFILES_FILE, Context.MODE_PRIVATE)

    fun intro(context: Context, key: String?): IntArray? {
        if (key == null) return null
        val record = prefs(context).getString("$key:intro", null) ?: return null
        val parts = record.split(',')
        val stamp = parts.firstOrNull()?.toLongOrNull() ?: return null
        // 30 day validity, like the controller.
        if (System.currentTimeMillis() - stamp !in 0..(30L * 24 * 60 * 60 * 1000)) return null
        val p = parts.drop(1).mapNotNull { it.toIntOrNull() }
        return if (p.size in 7..8) p.toIntArray() else null
    }

    fun put(context: Context, profileKey: String, profile: IntArray) {
        val p = prefs(context)
        if (p.all.size >= MAX && !p.contains(profileKey)) {
            p.all.keys.firstOrNull()?.let { p.edit().remove(it).apply() }
        }
        p.edit()
            .putString(profileKey, "${System.currentTimeMillis()},${profile.joinToString(",")}")
            .apply()
    }

    fun wipe(context: Context) {
        File(context.filesDir.parentFile, "shared_prefs/${BridgeFX.PROFILES_FILE}.xml").delete()
    }
}
