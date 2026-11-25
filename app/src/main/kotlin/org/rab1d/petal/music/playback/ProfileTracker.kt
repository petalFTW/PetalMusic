/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.rab1d.petal.music.model.TrackAnalysis
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Keeps whole-track profiles for the playing and next records: files are
 * decoded ahead; streams are analysed from what their deck has played, and the
 * next stream from a silent prelisten. All analysis runs on one low-priority
 * thread. Results also refresh [AnalysisCache], which v1 and the UI read.
 */
internal class ProfileTracker(private val context: Context, private val onUpdate: () -> Unit) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PetalAnalysis").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val running = HashSet<String>()
    private val decoded = HashSet<String>()
    private var lastLiveAt = 0L

    init {
        ProfileStore.attach(context.filesDir)
        AnalysisCache.attach(context.filesDir)
    }

    fun get(key: String): TrackProfile? = ProfileStore.get(key)

    fun hasComplete(key: String) = ProfileStore.get(key)?.complete == true

    /** Decode a local/downloaded file ahead; false when it cannot be or need not be. */
    fun lookahead(key: String, uri: Uri?): Boolean {
        if (!TrackLookahead.supports(uri) || key in decoded || key in running || hasComplete(key)) return false
        decoded += key
        running += key
        executor.execute {
            val p = runCatching { TrackLookahead.analyse(context, uri!!) }.getOrNull()
            handler.post {
                running -= key
                if (p != null) publish(key, p, "lookahead")
                onUpdate()
            }
        }
        return true
    }

    fun isDecoding(key: String) = key in running

    /** Re-analyse what the live deck has heard of [key], at most every 15 s. */
    fun live(key: String, snapshot: () -> Recording?) {
        if (hasComplete(key) || key in running) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLiveAt < 15_000) return
        val s = snapshot()?.takeIf { it.bands.size * it.bands.hopMs >= 20_000 } ?: return
        lastLiveAt = now
        analyse(key, s, "live")
    }

    /** Analyse a prelistened opening of the next stream. */
    fun prelistened(key: String, s: Recording?) {
        if (s == null || hasComplete(key)) return
        analyse(key, s, "prelisten")
    }

    private fun analyse(key: String, s: Recording, source: String) {
        running += key
        executor.execute {
            val p = runCatching { TrackAnalyzer.analyse(s.bands, s.chroma, complete = false) }.getOrNull()
            handler.post {
                running -= key
                // A shorter partial window must not replace a longer one.
                val old = ProfileStore.get(key)
                if (p != null && (old == null || old.analysedToMs - old.analysedFromMs <=
                        p.analysedToMs - p.analysedFromMs + 1000)) publish(key, p, source)
                onUpdate()
            }
        }
    }

    private fun publish(key: String, p: TrackProfile, source: String) {
        if (!ProfileStore.put(key, p)) return
        AutomixLog.i(TAG, "$source $key: ${TrackLookahead.describe(p)}")
        val old = AnalysisCache.get(key)
        var gridded = p.gridConfidence >= .3f
        var bpm = p.bpm
        var downbeat = p.downbeatMs?.takeIf { p.downbeatConfidence >= .06f }
        if (gridded && old != null && old.authoritative && old.bpm > 0f) {
            // Metadata names the tempo octave; a disagreeing grid is not trusted.
            val ratio = bpm / old.bpm
            when {
                abs(ratio - 1f) < .03f -> Unit
                abs(ratio * 2f - 1f) < .03f -> bpm = old.bpm
                abs(ratio / 2f - 1f) < .03f -> { bpm = old.bpm; downbeat = null }
                else -> gridded = false
            }
        }
        val base = old ?: TrackAnalysis(0f)
        AnalysisCache.put(key, base.copy(
            bpm = if (gridded) bpm else base.bpm,
            phaseMs = if (gridded) p.phaseMs.toFloat() else base.phaseMs,
            strength = if (gridded) p.gridConfidence else base.strength,
            downbeatMs = if (gridded) downbeat?.toFloat() else base.downbeatMs,
            camelot = base.camelot.takeIf { base.authoritative } ?: p.camelot?.takeIf { p.keyConfidence >= .08f }
                ?: base.camelot,
            minor = if (base.authoritative && base.camelot != null) base.minor
                else p.minor?.takeIf { p.keyConfidence >= .08f } ?: base.minor,
            introDropMs = p.introEndBar.takeIf { it > 0 }?.let { p.barStartMs(it).toLong() },
            drumIntro = p.introEndBar.takeIf { it > 0 }?.let { end ->
                (0 until end).map { p.drums(it) }.average() >= .6 * (p.barDrums.average())
            } ?: (p.drums(0) >= .3f),
        ))
    }

    companion object { private const val TAG = "PetalMusic/Profile" }
}
