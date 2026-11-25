/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.rab1d.petal.music.playback.PlaybackBus
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import kotlin.math.exp
import kotlin.math.max

/**
 * The audible deck's level, normalised against its own recent peak so a quiet ballad
 * and a loud club track both use the full 0..1 range. Values are snapshot state read
 * at draw time, so only the drawing that uses them redraws.
 */
@Stable
class LiveLevel {
    /** 0..1, smoothed: the glow behind the cover. */
    var level by mutableFloatStateOf(0f)
        internal set
    /** 0..1 per band (sub, bass, mid, high), for the equaliser glyph. */
    var sub by mutableFloatStateOf(0f)
        internal set
    var bass by mutableFloatStateOf(0f)
        internal set
    var mid by mutableFloatStateOf(0f)
        internal set
    var high by mutableFloatStateOf(0f)
        internal set
    /** 1 while real measurements arrive, easing to 0 without them: below .5, draw the static form. */
    var live by mutableFloatStateOf(0f)
        internal set

    internal var peak = .05f
    internal val bandPeak = floatArrayOf(.05f, .05f, .05f, .05f)

    /**
     * How many on-screen things draw from this level (row equalisers). A shared level with
     * no watchers doesn't sample at all, so a list without the playing song costs nothing.
     */
    internal var watchers by mutableIntStateOf(0)
}

/** Register a drawing that reads [level] for as long as it is composed. */
@Composable
fun WatchLevel(level: LiveLevel?) {
    if (level == null) return
    androidx.compose.runtime.DisposableEffect(level) {
        level.watchers++
        onDispose { level.watchers-- }
    }
}

/**
 * Samples [PlaybackBus.levelProvider] [hz] times a second while [active], the screen is
 * started and (with [onlyWatched]) something on screen reads it. Off when motion is
 * reduced or battery saver is on: audio reactivity is movement, and movement costs frames.
 * Each sample redraws only the drawings that read the level, never recomposes.
 */
@Composable
fun rememberLiveLevel(active: Boolean, hz: Int, onlyWatched: Boolean = false): LiveLevel {
    val live = remember { LiveLevel() }
    val reduced = LocalReducedMotion.current || org.rab1d.petal.music.ui.theme.LocalPowerSave.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val watched = !onlyWatched || live.watchers > 0
    LaunchedEffect(active, reduced, lifecycle, watched) {
        if (!active || reduced || !watched) {
            live.level = 0f; live.sub = 0f; live.bass = 0f; live.mid = 0f; live.high = 0f; live.live = 0f
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            val periodMs = (1000L / hz.coerceIn(1, 60))
            while (true) {
                // A timer, not the frame clock: on a 120 Hz screen the frame clock would ask
                // for 120 redraws a second for a glow that reads the same at 30.
                kotlinx.coroutines.delay(periodMs)
                run {
                    val now = System.nanoTime()
                    val dt = if (last == 0L) periodMs / 1000f else ((now - last) / 1e9f).coerceIn(.001f, .25f)
                    last = now
                    val l = runCatching { PlaybackBus.levelProvider?.invoke() }.getOrNull()
                    // Attack fast, release slower: it should feel like a meter, not a strobe.
                    fun follow(cur: Float, target: Float): Float {
                        val tau = if (target > cur) .045f else .22f
                        return cur + (target - cur) * (1f - exp(-dt / tau))
                    }
                    if (l == null) {
                        live.level = follow(live.level, 0f)
                        live.live = follow(live.live, 0f)
                        return@run
                    }
                    // Peaks decay over ~6 s, so the range re-fits a song's own dynamics.
                    val decay = exp(-dt / 6f)
                    live.peak = max(l.rms, max(.02f, live.peak * decay))
                    val bands = floatArrayOf(l.sub, l.bass, l.mid, l.high)
                    for (i in 0..3) live.bandPeak[i] = max(bands[i], max(.01f, live.bandPeak[i] * decay))
                    live.level = follow(live.level, (l.rms / live.peak).coerceIn(0f, 1f))
                    live.sub = follow(live.sub, (bands[0] / live.bandPeak[0]).coerceIn(0f, 1f))
                    live.bass = follow(live.bass, (bands[1] / live.bandPeak[1]).coerceIn(0f, 1f))
                    live.mid = follow(live.mid, (bands[2] / live.bandPeak[2]).coerceIn(0f, 1f))
                    live.high = follow(live.high, (bands[3] / live.bandPeak[3]).coerceIn(0f, 1f))
                    live.live = follow(live.live, 1f)
                }
            }
        }
    }
    return live
}

/**
 * The song's waveform, written as it is heard: each of [BINS] slots keeps the loudest
 * level measured while the playhead was in it. Slots not yet heard stay empty, so the
 * scrubber never shows a waveform it doesn't have.
 */
@Stable
class HeardWaveform internal constructor(val bins: FloatArray) {
    /** Bumped on every write so the drawing that reads it refreshes. */
    var version by mutableIntStateOf(0)
        internal set

    companion object {
        const val BINS = 96
        private val memory = LruCache<String, HeardWaveform>(48)
        internal fun forKey(key: String): HeardWaveform =
            memory.get(key) ?: HeardWaveform(FloatArray(BINS)).also { memory.put(key, it) }
    }
}

@Composable
fun rememberHeardWaveform(
    trackKey: String?,
    level: LiveLevel,
    fraction: () -> Float,
    playing: Boolean,
): HeardWaveform? {
    if (trackKey == null) return null
    val wave = remember(trackKey) { HeardWaveform.forKey(trackKey) }
    LaunchedEffect(wave, playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            // 96 slots over a 3-minute song is one slot every ~2 s: ten looks a second is plenty.
            kotlinx.coroutines.delay(100)
            if (level.live < .5f) continue
            val f = fraction()
            if (f !in 0f..1f) continue
            val bin = (f * HeardWaveform.BINS).toInt().coerceIn(0, HeardWaveform.BINS - 1)
            val v = level.level
            if (v > wave.bins[bin]) {
                wave.bins[bin] = v
                wave.version++
            }
        }
    }
    return wave
}
