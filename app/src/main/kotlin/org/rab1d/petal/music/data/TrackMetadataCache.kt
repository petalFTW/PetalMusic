/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data

import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.key
import java.io.File

/**
 * Persistent key -> [Track] metadata store. Playlists (and anything else that
 * references a track by key) can resolve a remote song's title/artist/artwork
 * without a network round trip, which is what makes saved playlists survive
 * offline and legacy key-only playlists still render.
 */
object TrackMetadataCache {
    private const val TAG = "PetalMeta"
    private val json = Json { ignoreUnknownKeys = true }

    private var loaded = false
    private var file: File? = null
    private val map = LinkedHashMap<String, Track>()

    @Synchronized
    private fun ensure(filesDir: File) {
        if (loaded && file?.parentFile == filesDir) return
        map.clear()
        val f = File(filesDir, "track_metadata.json")
        file = f
        if (f.isFile) {
            runCatching {
                json.decodeFromString(ListSerializer(Track.serializer()), f.readText())
            }.onSuccess { list -> list.forEach { map[it.key()] = it } }
                .onFailure { Log.w(TAG, "metadata cache read failed: ${it.message}") }
        }
        loaded = true
    }

    @Synchronized
    fun remember(filesDir: File, tracks: List<Track>) {
        ensure(filesDir)
        var changed = false
        for (t in tracks) {
            if (t.id.isBlank() || t.title.isBlank()) continue
            val key = t.key()
            val existing = map[key]
            if (existing == null || existing != t) {
                map[key] = t
                changed = true
            }
        }
        if (changed) persist()
    }

    @Synchronized
    fun get(filesDir: File, key: String): Track? {
        ensure(filesDir)
        return map[key]
    }

    @Synchronized
    fun all(filesDir: File): List<Track> {
        ensure(filesDir)
        return map.values.toList()
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.absolutePath + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Track.serializer()), map.values.toList()))
            if (!tmp.renameTo(f)) {
                tmp.delete()
                Log.w(TAG, "metadata cache swap failed")
            }
        }.onFailure { Log.w(TAG, "metadata cache write failed: ${it.message}") }
    }
}
