/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bar-phase ground truth (test-only; skipped unless PETAL_BENCH_DIR is set and
 * downbeats/labels.tsv has lines). Compares each cached profile from AutomixCreativeSweep
 * (out/sweep/profiles/<name>.bin) with the beat 1 you tapped in downbeats/tapper.html:
 *
 *  - tempo: the profile's bar against yours (half/double counted separately);
 *  - bar phase: how many beats the profile's "1" sits from yours (0 is right; 1, 2 or 3
 *    means every phrase line, swap and drop lands that many beats off);
 *  - grid offset in ms within the beat.
 *
 * Writes out/downbeats/report.tsv and prints a summary.
 */
class DownbeatEval {
    @Test fun evaluate() {
        val dir = System.getenv("PETAL_BENCH_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val labels = File(dir, "downbeats/labels.tsv").takeIf { it.isFile }?.readLines()
            ?.filter { it.isNotBlank() && !it.startsWith("#") }?.map { it.split('\t') } ?: emptyList()
        assumeTrue("no labels yet", labels.isNotEmpty())
        val out = File(dir, "out/downbeats").apply { mkdirs() }
        val rows = ArrayList<String>()
        rows += "name\tlabelBpm\tprofileBpm\ttempo\tphaseBeats\tbeatOffsetMs\tgridConf\tdownbeatConf\thasDownbeat"
        var right = 0
        var scored = 0
        var tempoRight = 0
        for (l in labels) {
            val name = l[0]
            val firstS = l[1].toDouble()
            val barS = l[2].toDouble()
            val bin = File(dir, "out/sweep/profiles/$name.bin")
            if (!bin.isFile) { println("DOWNBEAT $name: no cached profile (run AutomixCreativeSweep first)"); continue }
            val p = DataInputStream(bin.inputStream().buffered()).use { if (it.readBoolean()) TrackProfile.read(it) else null }
                ?: continue
            val labelBar = barS * 1000
            val ratio = labelBar / p.barMs
            val tempo = when {
                abs(ratio - 1) < .02 -> "same"
                abs(ratio - 2) < .04 -> "profile double"
                abs(ratio - .5) < .02 -> "profile half"
                else -> "wrong"
            }
            if (tempo == "same") tempoRight++
            // Phase of the label's "1" against the profile's bar grid, measured in profile beats.
            val labelMs = firstS * 1000
            val rel = ((labelMs - p.firstBarMs) % p.barMs + p.barMs) % p.barMs
            val beats = rel / p.beatMs
            val whole = beats.roundToInt() % 4
            val offsetMs = (beats - beats.roundToInt()) * p.beatMs
            if (tempo == "same") {
                scored++
                if (whole == 0) right++
            }
            rows += "$name\t${"%.2f".format(240 / barS)}\t${"%.2f".format(p.bpm)}\t$tempo\t$whole\t${"%.1f".format(offsetMs)}\t" +
                "${"%.2f".format(p.gridConfidence)}\t${"%.2f".format(p.downbeatConfidence)}\t${p.hasDownbeat}"
        }
        File(out, "report.tsv").writeText(rows.joinToString("\n") + "\n")
        rows.forEach { println("DOWNBEAT $it") }
        println("DOWNBEAT summary: tempo right ${tempoRight}/${labels.size}; bar phase right $right/$scored of the songs with the right tempo")
    }
}
