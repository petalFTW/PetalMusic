/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry mapping petal://<key> placeholders to real streams.
 * Remote tracks are enqueued as petal:// uris; [PetalDataSource] resolves
 * them (url + headers + optional decryptor) at open time. This keeps per-item
 * http headers and stream decryption out of MediaItem's data model.
 */
object PetalStreamResolver {
    private val handles = ConcurrentHashMap<String, StreamHandle>()

    fun register(key: String, handle: StreamHandle) {
        handles[key] = handle
    }

    fun resolve(key: String): StreamHandle? = handles[key]

    fun unregister(key: String) {
        handles.remove(key)
    }

    fun uri(key: String): String = "petal://$key"
}

/** A resolved, playable stream. */
sealed interface StreamHandle {
    /** Direct url, optionally with http headers. */
    data class Url(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val mimeType: String? = null,
    ) : StreamHandle

    /** An in-memory DASH MPD document (Tidal /playbackinfo manifest). */
    data class Mpd(val xml: String) : StreamHandle

    /**
     * Stream whose first [encryptedChunks] 2048-byte chunks are
     * Blowfish/CBC/NoPadding encrypted (Deezer).
     */
    data class Blowfish(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val key: ByteArray,
        val iv: ByteArray,
        val encryptedChunks: Int = 3,
        val chunkSize: Int = 2048,
    ) : StreamHandle {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
