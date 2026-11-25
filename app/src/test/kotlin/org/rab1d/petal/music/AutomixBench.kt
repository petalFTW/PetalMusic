/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Test
import org.rab1d.petal.music.playback.*
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Analyser benchmark on real records (skipped unless PETAL_BENCH_DIR is set):
 * decodes `songs/` with ffmpeg, runs the on-device analysis pipeline, prints a
 * report and writes `out/<song> (clicks).mp3` with the detected grid audible —
 * a click on every beat, an accented click on beat 1, a low thump at section
 * starts and a two-note chime where the vocal-presence estimate turns on.
 *
 *   PETAL_BENCH_DIR=tools/automix-bench ./gradlew :app:testDebugUnitTest --tests '*AutomixBench*'
 */
class AutomixBench {
    private val rate = 44100

    @Test fun bench() {
        val dir = System.getenv("PETAL_BENCH_DIR")?.let { File(it).absoluteFile } ?: return
        val songs = File(dir, "songs").listFiles { f -> f.extension.lowercase() in setOf("m4a", "flac", "mp3", "wav") }
            ?.sortedBy { it.name } ?: return
        val out = File(dir, "out").apply { mkdirs() }
        val report = StringBuilder()
        val analysed = ArrayList<Triple<String, TrackProfile?, Long>>()
        for (song in songs) {
            val pcm = decode(song)
            val started = System.nanoTime()
            val env = BandEnvelope(BandEnvelope.hopsFor(pcm.size * 1000.0 / rate + 5000))
            env.reset(0.0, rate)
            val crossover = DjCrossover(rate)
            val vocal = VocalBand(rate)
            val chroma = ChromaEnvelope(ChromaEnvelope.framesFor(pcm.size * 1000.0 / rate + 5000))
            chroma.reset(0.0, rate)
            val bands = FloatArray(4)
            for (x in pcm) {
                crossover.split(x, bands)
                env.frame(x, bands[0] + bands[1], bands[2], bands[3], vocal.process(x))
                chroma.sample(x)
            }
            val snapshot = env.snapshot()!!
            val p = TrackAnalyzer.analyse(snapshot, chroma.snapshot(), complete = true)
            val ms = (System.nanoTime() - started) / 1_000_000
            val line = "${song.nameWithoutExtension.padEnd(26)} ${TrackLookahead.describe(p)} " +
                (p?.let { alignment(snapshot, it) } ?: "") + " ${ms}ms"
            println("BENCH $line")
            report.appendLine(line)
            if (p != null) writeClicks(pcm, p, File(out, "${song.nameWithoutExtension} (clicks).mp3"))
            analysed += Triple(song.nameWithoutExtension, p, pcm.size * 1000L / rate)
        }
        // What v2 would play between consecutive songs, in both styles, with the four
        // clean-transition metrics (the reference transitions score 0 on each).
        for (style in DjComposer.MixStyle.entries) {
            val recent = ArrayList<DjComposer.Played>()
            val totals = IntArray(3)
            var landed = 0
            report.appendLine("-- ${style.name.lowercase()} --")
            for (k in 0 until analysed.size - 1) {
                val (a, pa, da) = analysed[k]
                val (b, pb, db) = analysed[k + 1]
                val mix = DjComposer.compose(DjComposer.Side(pa, null, da, a), DjComposer.Side(pb, null, db, b),
                    da - 90_000, recent, "$a→$b".hashCode(), options = DjComposer.Options(style = style))
                val metrics = mix?.let { DjComposer.metrics(it, pa, pb) }
                val exit = mix?.let { "%.1fs".format(it.outStartMs / 1000.0) }
                val line = "$a → $b: ${mix?.label ?: "none"} from $exit cue=${mix?.incomingCueMs}ms | $metrics | ${mix?.notes}"
                println("MIX ${style.name} $line")
                report.appendLine(line)
                metrics?.let {
                    if (it.landingErrorBeats == 0) landed++
                    totals[0] += it.bothLowBars
                    totals[1] += it.outgoingAfterFullBars
                }
                mix?.let { recent.add(0, DjComposer.Played(it.family, it.bars)) }
            }
            val summary = "${style.name}: landed on a phrase line $landed/${analysed.size - 1}, " +
                "bars with two low ends ${totals[0]}, outgoing bars after the incoming is full ${totals[1]}"
            println("SUMMARY $summary")
            report.appendLine(summary)
        }
        File(out, "report.txt").writeText(report.toString())
    }

    /** How tightly low-band onsets sit on the grid: median |offset| and share of beats with a hit. */
    private fun alignment(s: BandEnvelope.Snapshot, p: TrackProfile): String {
        val low = s[BandEnvelope.LOW]
        val offsets = ArrayList<Double>()
        var beats = 0
        var t = p.phaseMs.mod(p.beatMs)
        while (t < s.endMs - 100) {
            beats++
            val c = ((t - s.originMs) / s.hopMs).toInt()
            var best = 0f
            var at = -1
            for (i in c - 10..c + 10) if (i in 1 until low.size) {
                val rise = low[i] - low[i - 1]
                if (rise > best) { best = rise; at = i }
            }
            val ref = low.average().toFloat()
            if (at >= 0 && best > ref * .3f) offsets += (at - c) * s.hopMs
            t += p.beatMs
        }
        if (offsets.isEmpty()) return "align=none"
        val sorted = offsets.map { abs(it) }.sorted()
        return "align=median ${"%.1f".format(sorted[sorted.size / 2])}ms hits=${offsets.size * 100 / beats}%"
    }

    private fun decode(f: File): FloatArray {
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", f.absolutePath, "-ac", "1", "-ar", "$rate",
            "-f", "f32le", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun writeClicks(pcm: FloatArray, p: TrackProfile, target: File) {
        val mix = FloatArray(pcm.size) { pcm[it] * .6f }
        fun tone(atMs: Double, hz: Double, ms: Double, gain: Double) {
            val start = (atMs * rate / 1000).toInt()
            for (i in 0 until (ms * rate / 1000).toInt()) {
                val j = start + i
                if (j !in mix.indices) continue
                val t = i.toDouble() / rate
                mix[j] += (gain * sin(2 * PI * hz * t) * exp(-t / (ms / 4000))).toFloat()
            }
        }
        var k = 0
        var t = p.phaseMs.mod(p.beatMs)
        while (t < pcm.size * 1000.0 / rate) {
            val bar = p.barAt(t + 1)
            val onBar = abs(t - p.barStartMs(bar)) < 2
            if (onBar && p.hasDownbeat) tone(t, 2400.0, 40.0, .45) else tone(t, 1400.0, 25.0, .25)
            if (onBar && bar in p.sections) tone(t, 70.0, 250.0, .6)
            if (onBar && bar > 0 && p.vocal(bar) >= .5f && p.vocal(bar - 1) < .5f) {
                tone(t, 880.0, 120.0, .2); tone(t + 120, 1320.0, 160.0, .2)
            }
            t += p.beatMs
            k++
        }
        val raw = java.nio.ByteBuffer.allocate(mix.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in mix) raw.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", "$rate", "-ac", "1",
            "-i", "-", "-b:a", "192k", target.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.use { it.write(raw.array()) }
        process.waitFor()
    }
}
