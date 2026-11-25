/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.rab1d.petal.music.model.LyricLine
import org.rab1d.petal.music.model.Lyrics
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.sources.SourceProvider
import java.util.concurrent.TimeUnit

/** LRC (`[mm:ss.xx] line`) or plain text → [Lyrics]. */
object LyricsParser {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String, provider: String): Lyrics? {
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) {
                // Metadata tags like [ar:...] carry no time and aren't lyrics.
                if (!raw.trimStart().startsWith("[")) plain += raw.trim()
                continue
            }
            val line = raw.substring(stamps.last().range.last + 1).trim()
            // One line can carry several stamps (a repeated chorus).
            for (m in stamps) {
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                timed += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs, line)
            }
        }
        if (timed.isNotEmpty()) return Lyrics(timed.sortedBy { it.timeMs }, synced = true, provider = provider)
        val lines = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return null
        return Lyrics(lines.map { LyricLine(-1, it) }, synced = false, provider = provider)
    }

    /** Index of the line playing at [positionMs], or -1 before the first. */
    fun current(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/**
 * Lyrics for a song: the source's own first, then LRCLIB (lrclib.net, a free public
 * database) when [lrclib] is on. LRCLIB is asked by title, artist, album and duration,
 * nothing else. Results, including misses, are cached for the session.
 */
class LyricsRepository(private val providers: () -> Map<org.rab1d.petal.music.model.Source, SourceProvider>) {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val cache = LruCache<String, Result>(64)

    private class Result(val lyrics: Lyrics?)

    fun cached(track: Track): Lyrics? = cache.get(key(track))?.lyrics

    suspend fun lyrics(track: Track, lrclib: Boolean): Lyrics? {
        val k = key(track)
        cache.get(k)?.let { return it.lyrics }
        val found = runCatching { providers()[track.source]?.lyrics(track) }.getOrNull()
            ?: if (lrclib) fromLrclib(track) else null
        cache.put(k, Result(found))
        return found
    }

    private fun key(track: Track) = "${track.source.id}/${track.id}"

    private suspend fun fromLrclib(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        if (track.title.isBlank() || track.artist.isBlank()) return@withContext null
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist.substringBefore(",").trim())
            .apply {
                if (track.album.isNotBlank()) addQueryParameter("album_name", track.album)
                if (track.durationMs > 0) addQueryParameter("duration", (track.durationMs / 1000).toString())
            }.build()
        val req = Request.Builder().url(url).header("User-Agent", "PetalMusic/2.0").build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = JSONObject(resp.body?.string().orEmpty())
                if (body.optBoolean("instrumental")) return@use null
                val text = body.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                    ?: body.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                    ?: return@use null
                LyricsParser.parse(text, "LRCLIB")
            }
        }.getOrNull()
    }
}
