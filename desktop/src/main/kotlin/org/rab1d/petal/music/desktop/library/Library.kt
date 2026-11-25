/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.library

import org.rab1d.petal.music.desktop.audio.PcmIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.streams.toList

data class DesktopTrack(
    val path: Path,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    val durationLabel: String
        get() {
            if (durationMs <= 0) return "–:––"
            val total = durationMs / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }
}

object Library {
    val SUPPORTED = setOf("mp3", "wav", "ogg", "oga", "aif", "aiff", "au")

    /** Walk [root] for supported audio files; "Artist - Title.ext" names are parsed. */
    fun scan(root: Path): List<DesktopTrack> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.extension.lowercase() in SUPPORTED }
                .toList()
                .mapNotNull { f ->
                    val duration = PcmIO.estimateDurationMs(f)
                    val stem = f.nameWithoutExtension
                    val dash = stem.indexOf(" - ")
                    val (artist, title) = if (dash > 0) {
                        stem.substring(0, dash).trim() to stem.substring(dash + 3).trim()
                    } else {
                        "" to stem
                    }
                    DesktopTrack(
                        path = f,
                        title = title.ifBlank { stem },
                        artist = artist.ifBlank { root.fileName.toString() },
                        album = f.parent?.fileName?.toString() ?: "",
                        durationMs = duration,
                    )
                }
                .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
        }
    }
}

/** Small preference store for the desktop edition (library path, mix settings). */
object Prefs {
    private val node = Preferences.userNodeForPackage(Library::class.java)

    var libraryPath: String
        get() = node.get("libraryPath", "")
        set(value) = node.put("libraryPath", value)

    var fadeMs: Int
        get() = node.getInt("fadeMs", 6000)
        set(value) = node.putInt("fadeMs", value)

    var style: Int
        get() = node.getInt("style", 0)
        set(value) = node.putInt("style", value)

    var automix: Boolean
        get() = node.getBoolean("automix", true)
        set(value) = node.putBoolean("automix", value)

    var volume: Float
        get() = node.getFloat("volume", 1f)
        set(value) = node.putFloat("volume", value)

    /** Device Link imports playlist metadata only unless this is enabled. */
    var downloadAudio: Boolean
        get() = node.getBoolean("downloadAudio", false)
        set(value) = node.putBoolean("downloadAudio", value)

}
