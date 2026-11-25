/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data

import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.rab1d.petal.music.model.Track
import java.io.File

/**
 * Dumb little json cache for playlist/album track lists. Reopening a playlist
 * or hitting shuffle offline should not wait on the network, and remote art
 * needs the tracks around to even render.
 */
object TrackListCache {
    private val json = Json { ignoreUnknownKeys = true }
    private const val TAG = "PetalListCache"

    private fun dir(filesDir: File): File =
        File(filesDir, "tracklists").apply { mkdirs() }

    private fun fileFor(filesDir: File, key: String): File {
        val safe = key.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '.') c else '_' }
            .joinToString("")
        return File(dir(filesDir), "$safe.json")
    }

    @Synchronized
    fun read(filesDir: File, key: String): List<Track>? = runCatching {
        val f = fileFor(filesDir, key)
        if (!f.isFile) return null
        json.decodeFromString(ListSerializer(Track.serializer()), f.readText())
    }.onFailure { Log.w(TAG, "cache read borked for $key: ${it.message}") }.getOrNull()

    @Synchronized
    fun write(filesDir: File, key: String, tracks: List<Track>) {
        runCatching {
            val tmp = File(fileFor(filesDir, key).absolutePath + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Track.serializer()), tracks))
            if (!tmp.renameTo(fileFor(filesDir, key))) {
                tmp.delete()
                Log.w(TAG, "cache swap failed for $key")
            }
        }.onFailure { Log.w(TAG, "cache write borked for $key: ${it.message}") }
    }

    @Synchronized
    fun invalidate(filesDir: File, key: String) {
        fileFor(filesDir, key).delete()
    }
}
