/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rab1d.petal.music.desktop.audio.BridgeFXWin
import org.rab1d.petal.music.desktop.library.DesktopTrack
import org.rab1d.petal.music.desktop.library.Library
import org.rab1d.petal.music.desktop.library.Prefs
import java.io.File
import java.nio.file.Path

data class DesktopAlbum(val id: String, val title: String, val artist: String, val trackCount: Int)
data class DesktopArtist(val id: String, val name: String, val trackCount: Int, val albumCount: Int)
data class DesktopSearch(
    val tracks: List<DesktopTrack> = emptyList(),
    val albums: List<DesktopAlbum> = emptyList(),
    val artists: List<DesktopArtist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/**
 * Desktop analogue of the Android PetalViewModel/PlaybackBus: owns the library,
 * queue, transport, shuffle/repeat and mix settings, and mirrors them into
 * Compose state for the phone-shaped UI.
 */
class DesktopController(
    private val engine: BridgeFXWin,
    private val scope: CoroutineScope,
) {
    var library by mutableStateOf<List<DesktopTrack>>(emptyList()); private set
    var current by mutableStateOf<DesktopTrack?>(null); private set
    var playing by mutableStateOf(false); private set
    var isMixing by mutableStateOf(false); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var status by mutableStateOf("${BridgeFXWin.NAME} ready"); private set
    var libraryPath by mutableStateOf(Prefs.libraryPath); private set
    var scanning by mutableStateOf(false); private set

    var queue by mutableStateOf<List<DesktopTrack>>(emptyList()); private set
    var queueIndex by mutableStateOf(-1); private set
    var shuffle by mutableStateOf(false); private set
    /** 0 off, 1 all, 2 one. */
    var repeatMode by mutableStateOf(0); private set

    var fadeMs by mutableStateOf(Prefs.fadeMs.coerceIn(BridgeFXWin.MIN_FADE_MS, BridgeFXWin.MAX_FADE_MS)); private set
    var style by mutableStateOf(Prefs.style.coerceIn(0, 3)); private set
    var automix by mutableStateOf(Prefs.automix); private set
    var volume by mutableStateOf(Prefs.volume); private set

    var recents by mutableStateOf<List<DesktopTrack>>(emptyList()); private set

    init {
        engine.onEvent = { handleEvent(it) }
        scope.launch {
            while (true) {
                playing = engine.isPlaying
                isMixing = engine.isMixing
                positionMs = engine.positionMs
                durationMs = engine.durationMs
                delay(120)
            }
        }
        scanLibrary(libraryPath)
    }

    fun reportStatus(message: String) {
        status = message
    }

    // ---- library ---------------------------------------------------------------

    fun scanLibrary(path: String) {
        val requested = path.trim().ifBlank {
            File(System.getProperty("user.home"), "Music/PetalMusic").absolutePath
        }
        val folder = File(requested)
        if (!folder.exists() && !folder.mkdirs()) {
            status = "Could not create music folder: $requested"
            return
        }
        if (!folder.isDirectory) {
            status = "Not a folder: $requested"
            return
        }
        val resolved = folder.absolutePath
        libraryPath = resolved
        Prefs.libraryPath = resolved
        scanning = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { Library.scan(Path.of(resolved)) }.getOrDefault(emptyList())
            }
            library = list
            scanning = false
            status = if (list.isEmpty()) {
                "Library folder ready: $resolved"
            } else {
                "Library: ${list.size} tracks"
            }
        }
    }

    fun albums(): List<DesktopAlbum> =
        library.groupBy { it.album.ifBlank { "Unknown album" } }
            .map { (album, ts) ->
                DesktopAlbum(album, album, ts.first().artist, ts.size)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    fun artists(): List<DesktopArtist> =
        library.groupBy { it.artist.ifBlank { "Unknown artist" } }
            .map { (artist, ts) ->
                DesktopArtist(artist, artist, ts.size, ts.map { it.album }.distinct().size)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun albumTracks(albumId: String): List<DesktopTrack> =
        library.filter { it.album.ifBlank { "Unknown album" } == albumId }

    fun artistTracks(artistId: String): List<DesktopTrack> =
        library.filter { it.artist.ifBlank { "Unknown artist" } == artistId }

    fun search(query: String): DesktopSearch {
        val q = query.trim()
        if (q.isBlank()) return DesktopSearch()
        val tracks = library.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
        val albums = albums().filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        val artists = artists().filter { it.name.contains(q, true) }
        return DesktopSearch(tracks.take(40), albums, artists)
    }

    // ---- transport -------------------------------------------------------------

    fun play(track: DesktopTrack, list: List<DesktopTrack> = library) {
        if (list.isEmpty()) return
        val program = if (shuffle) list.shuffled() else list
        val index = program.indexOfFirst { it.path == track.path }.coerceAtLeast(0)
        queue = program
        queueIndex = index
        engine.setProgram(program, index)
    }

    fun playAll(list: List<DesktopTrack> = library, shuffle: Boolean = this.shuffle) {
        if (list.isEmpty()) return
        this.shuffle = shuffle
        val program = if (shuffle) list.shuffled() else list
        queue = program
        queueIndex = 0
        engine.setProgram(program, 0)
    }

    fun playFromQueue(index: Int) {
        if (index !in queue.indices) return
        queueIndex = index
        engine.playNow(index)
    }

    fun playPause() {
        if (engine.currentTrack == null) {
            playAll()
            return
        }
        engine.togglePause()
    }

    fun next() {
        if (queue.isEmpty()) return
        val n = queueIndex + 1
        when {
            n < queue.size -> engine.playNow(n)
            repeatMode == 1 -> engine.playNow(0)
            repeatMode == 2 -> engine.playNow(queueIndex.coerceAtLeast(0))
        }
    }

    fun previous() {
        // Restart the song first; pressing again (inside the first 5 s) steps
        // back through the queue.
        if (positionMs > 5000) {
            engine.seekTo(0)
            return
        }
        if (queue.isEmpty()) return
        val p = queueIndex - 1
        when {
            p >= 0 -> engine.playNow(p)
            repeatMode == 1 -> engine.playNow(queue.lastIndex)
            else -> engine.seekTo(0)
        }
    }

    fun seekTo(ms: Long) = engine.seekTo(ms)

    fun toggleShuffle() {
        shuffle = !shuffle
        val cur = queue.getOrNull(queueIndex) ?: return
        val pos = positionMs
        val rest = queue.filterIndexed { i, _ -> i != queueIndex }
        val reordered = listOf(cur) + if (shuffle) rest.shuffled() else rest
        queue = reordered
        queueIndex = 0
        // Re-seat the engine on the reordered program without restarting audio.
        engine.setProgram(reordered, 0)
        if (pos > 0) engine.seekTo(pos)
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
    }

    /** 0 = off, 1 = loop list, 2 = loop one. Tapping the active mode clears it. */
    fun setRepeat(mode: Int) {
        repeatMode = mode.coerceIn(0, 2)
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val playing = queue.getOrNull(queueIndex)
        val pos = positionMs
        val newQ = queue.toMutableList().also { it.removeAt(index) }
        if (newQ.isEmpty()) {
            engine.stopPlayback()
            queue = emptyList()
            queueIndex = -1
            return
        }
        val newIndex = playing?.let { p -> newQ.indexOfFirst { it.path == p.path } }
            ?.takeIf { it >= 0 } ?: 0
        queue = newQ
        queueIndex = newIndex
        engine.setProgram(newQ, newIndex)
        if (pos > 0) engine.seekTo(pos)
    }

    // ---- mix settings ----------------------------------------------------------

    fun updateFadeMs(v: Int) {
        fadeMs = v.coerceIn(BridgeFXWin.MIN_FADE_MS, BridgeFXWin.MAX_FADE_MS)
        engine.fadeMs = fadeMs
        Prefs.fadeMs = fadeMs
    }

    fun updateStyle(v: Int) {
        style = v.coerceIn(0, 3)
        engine.style = style
        Prefs.style = style
    }

    fun updateAutomix(v: Boolean) {
        automix = v
        engine.automix = v
        Prefs.automix = v
    }

    fun updateVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        engine.volume = volume
        Prefs.volume = volume
    }

    // ---- engine events ---------------------------------------------------------

    private fun handleEvent(event: BridgeFXWin.Event) {
        when (event) {
            is BridgeFXWin.Event.TrackStarted -> {
                current = event.track
                queueIndex = event.index
                recents = (listOf(event.track) + recents.filterNot { it.path == event.track.path }).take(20)
                status = "${event.track.title} — ${event.track.artist}"
            }
            is BridgeFXWin.Event.TransitionStarted -> {
                status = if (event.fadeMs > 0) {
                    "${BridgeFXWin.NAME}: blending '${event.to.title}' (${event.fadeMs} ms)"
                } else {
                    "Switched to '${event.to.title}'"
                }
            }
            is BridgeFXWin.Event.PlaybackEnded -> onPlaybackEnded()
            BridgeFXWin.Event.PlaybackStopped -> {
                current = null
                queueIndex = -1
                playing = false
                status = "Stopped"
            }
            is BridgeFXWin.Event.Error -> status = "Error: ${event.message}"
        }
    }

    private fun onPlaybackEnded() {
        when {
            repeatMode == 1 && queue.isNotEmpty() -> engine.setProgram(queue, 0)
            repeatMode == 2 && queueIndex in queue.indices -> engine.setProgram(queue, queueIndex)
            else -> {
                playing = false
                status = "Queue drained"
            }
        }
    }
}
