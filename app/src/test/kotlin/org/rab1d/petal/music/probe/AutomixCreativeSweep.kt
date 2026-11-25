/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.junit.Test
import org.rab1d.petal.music.playback.DjComposerProbe.Family
import org.rab1d.petal.music.playback.DjComposerProbe.MixStyle
import org.rab1d.petal.music.playback.DjTransitionPlan.P
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Creative-style investigation (skipped unless PETAL_BENCH_DIR and PETAL_SWEEP are set).
 *
 *   PETAL_SWEEP=sweep   every ordered pair: Clean, Creative, each family forced, extra seeds,
 *                       no-jitter; plus shuffled playlists with history.  → out/sweep/ (tsv files)
 *   PETAL_RENDER=<file> lines "name|from|to|CREATIVE|CLEAN|<FAMILY>[|jitter0]" → out/renders/name.mp3
 *
 * Uses the generated DjComposerProbe/TrackAnalyzerProbe (identical logic plus knobs).
 */
class AutomixCreativeSweep {
    private val rate = 44100

    class Song(val name: String, val genre: String, val p: TrackProfile?, val durMs: Long, val diag: String, val file: File)

    @Test fun sweep() {
        val dir = System.getenv("PETAL_BENCH_DIR")?.let { File(it).absoluteFile } ?: return
        val mode = System.getenv("PETAL_SWEEP") ?: ""
        val render = System.getenv("PETAL_RENDER")
        if (mode.isEmpty() && render == null) return
        val out = File(dir, "out/sweep").apply { mkdirs() }
        val genres = HashMap<String, String>()
        File(dir, "incoming/names.tsv").takeIf { it.exists() }?.readLines()?.forEach { l ->
            val f = l.split('\t'); if (f.size >= 3) genres[f[2].replace(Regex(" +"), " ").trim()] = f[1]
        }
        val files = File(dir, "songs").listFiles { f -> f.extension.lowercase() in setOf("m4a", "flac", "mp3", "wav") }!!
            .sortedBy { it.name }
        val pool = Executors.newFixedThreadPool(8)
        val songs = files.map { f -> pool.submit<Song> { analyse(f, File(out, "profiles"), genres[f.nameWithoutExtension] ?: "laroi") } }
            .map { it.get() }
        pool.shutdown()
        File(out, "songs.tsv").printWriter().use { w ->
            w.println("song\tgenre\tdurS\tbpm\tconf\tdownbeat\tdownConf\tbars\tintro\toutro\tsections\tphrase\tkey\tkeyConf\tvocalBars\tdrumless0_8\tdiag")
            for (s in songs) {
                val p = s.p
                if (p == null) { w.println("${s.name}\t${s.genre}\t${s.durMs / 1000}\t\t\t\t\t\t\t\t\t\t\t\t\t\t${s.diag}"); continue }
                val drumless = (0 until minOf(8, p.bars)).count { p.drums(it) < .25f }
                w.println(listOf(s.name, s.genre, s.durMs / 1000, "%.2f".format(p.bpm), "%.3f".format(p.gridConfidence),
                    p.hasDownbeat, "%.2f".format(p.downbeatConfidence), p.bars, p.introEndBar, p.outroStartBar,
                    p.sections.joinToString(","), p.phraseOffset, "${p.camelot}${if (p.minor == true) "A" else "B"}",
                    "%.2f".format(p.keyConfidence), p.barVocal.count { it > .5f }, drumless, s.diag).joinToString("\t"))
            }
        }
        if (mode.contains("sweep")) sweepPairs(songs, out)
        if (mode.contains("seq")) sequences(songs, out)
        render?.let { renderList(File(it), songs, File(dir, "out/renders").apply { mkdirs() }) }
    }

    // ---- analysis ------------------------------------------------------------------------------

    private fun analyse(f: File, cache: File, genre: String): Song {
        cache.mkdirs()
        val bin = File(cache, f.nameWithoutExtension + ".bin")
        val meta = File(cache, f.nameWithoutExtension + ".meta")
        if (bin.exists() && meta.exists() && bin.lastModified() >= f.lastModified()) {
            val m = meta.readLines()
            val p = DataInputStream(bin.inputStream().buffered()).use { if (it.readBoolean()) TrackProfile.read(it) else null }
            return Song(f.nameWithoutExtension, genre, p, m[0].toLong(), m.getOrElse(1) { "" }, f)
        }
        val durMs = probeMs(f)
        val env = BandEnvelope(BandEnvelope.hopsFor(durMs + 5000.0))
        env.reset(0.0, rate)
        val crossover = DjCrossover(rate)
        val vocal = VocalBand(rate)
        val chroma = ChromaEnvelope(ChromaEnvelope.framesFor(durMs + 5000.0))
        chroma.reset(0.0, rate)
        val bands = FloatArray(4)
        var n = 0L
        stream(f, 1, null) { x ->
            crossover.split(x, bands)
            env.frame(x, bands[0] + bands[1], bands[2], bands[3], vocal.process(x))
            chroma.sample(x)
            n++
        }
        TrackAnalyzerProbe.diag.set("")
        val snap = env.snapshot()!!
        val p = TrackAnalyzerProbe.analyse(snap, chroma.snapshot(), complete = true)
        val diag = (TrackAnalyzerProbe.diag.get() ?: "") + (p?.let { q ->
            " align=" + alignment(snap, q, 0.0, 1e12) + " alignHead=" + alignment(snap, q, 0.0, q.barStartMs(32)) +
                " alignTail=" + alignment(snap, q, q.barStartMs(q.bars - 32), 1e12) } ?: "")
        val realMs = n * 1000 / rate
        DataOutputStream(bin.outputStream().buffered()).use { o -> o.writeBoolean(p != null); p?.write(o) }
        meta.writeText("$realMs\n$diag\n")
        println("ANALYSED ${f.nameWithoutExtension} ${TrackLookahead.describe(p)} $diag")
        return Song(f.nameWithoutExtension, genre, p, realMs, diag, f)
    }

    /** Median |offset| (ms) of low-band onsets from grid beats in [from, to), and the share of beats with a hit. */
    private fun alignment(s: BandEnvelope.Snapshot, p: TrackProfile, from: Double, to: Double): String {
        val low = s[BandEnvelope.LOW]
        val offsets = ArrayList<Double>()
        var beats = 0
        var t = p.phaseMs.mod(p.beatMs)
        val ref = low.average().toFloat()
        while (t < s.endMs - 100) {
            if (t >= from && t < to) {
                beats++
                val c = ((t - s.originMs) / s.hopMs).toInt()
                var best = 0f; var at = -1
                for (i in c - 10..c + 10) if (i in 1 until low.size) {
                    val rise = low[i] - low[i - 1]
                    if (rise > best) { best = rise; at = i }
                }
                if (at >= 0 && best > ref * .3f) offsets += (at - c) * s.hopMs
            }
            t += p.beatMs
        }
        if (offsets.isEmpty() || beats == 0) return "none"
        val sorted = offsets.map { abs(it) }.sorted()
        return "%.1f/%d%%".format(sorted[sorted.size / 2], offsets.size * 100 / beats)
    }

    private fun probeMs(f: File): Long {
        val pr = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f.absolutePath)
            .redirectErrorStream(true).start()
        val s = pr.inputStream.bufferedReader().readText().trim()
        pr.waitFor()
        return ((s.toDoubleOrNull() ?: 600.0) * 1000).toLong()
    }

    /** Streams f32le samples (interleaved when [ch] = 2) without holding the file in memory. */
    private fun stream(f: File, ch: Int, filter: String?, sink: (Float) -> Unit) {
        val cmd = arrayListOf("ffmpeg", "-v", "error", "-i", f.absolutePath)
        if (filter != null) { cmd += "-af"; cmd += filter }
        cmd += listOf("-ac", "$ch", "-ar", "$rate", "-f", "f32le", "-")
        val pr = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val input = pr.inputStream.buffered(1 shl 16)
        val buf = ByteArray(1 shl 16)
        var carry = 0
        val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val r = input.read(buf, carry, buf.size - carry)
            if (r < 0) break
            val total = carry + r
            val whole = total / 4 * 4
            var i = 0
            while (i < whole) { sink(bb.getFloat(i)); i += 4 }
            carry = total - whole
            for (k in 0 until carry) buf[k] = buf[whole + k]
        }
        pr.waitFor()
    }

    // ---- composing -----------------------------------------------------------------------------

    private class Row(val mix: DjComposerProbe.Mix?, val line: String)

    private fun compose(
        a: Song, b: Song, style: MixStyle, forced: Family? = null, seed: Int = "${a.name}→${b.name}".hashCode(),
        recent: List<DjComposerProbe.Played> = emptyList(), jitter: Double = 1.0, variety: Boolean = true,
    ): DjComposerProbe.Mix? {
        DjComposerProbe.force.set(forced)
        DjComposerProbe.jitterScale.set(jitter)
        DjComposerProbe.variety.set(variety)
        try {
            return DjComposerProbe.compose(DjComposerProbe.Side(a.p, null, a.durMs, a.name),
                DjComposerProbe.Side(b.p, null, b.durMs, b.name), a.durMs - 90_000, recent, seed,
                options = DjComposerProbe.Options(style = style))
        } finally {
            DjComposerProbe.force.set(null); DjComposerProbe.jitterScale.set(1.0); DjComposerProbe.variety.set(true)
        }
    }

    private val header = "kind\tfrom\tto\tgFrom\tgTo\tbpmFrom\tbpmTo\tconfFrom\tconfTo\tratio\tfamily\tfill\tbars\tswap\t" +
        "entryBar\tcueS\toutStartS\toutLeftS\tlanding\tbothLow\toutAfter\techoBars\techoDriftMs\tsync\tstretch\t" +
        "ghostBars\tdropEnergy\tdropDrums\tdropVocal\tkeyBoundary\tmidWord\tvocalOverlap\tnotes"

    private fun describe(kind: String, a: Song, b: Song, m: DjComposerProbe.Mix?): String {
        val pa = a.p; val pb = b.p
        val ratio = if (pa != null && pb != null) pb.bpm / pa.bpm else Float.NaN
        val head = listOf(kind, a.name, b.name, a.genre, b.genre, pa?.bpm?.let { "%.2f".format(it) } ?: "",
            pb?.bpm?.let { "%.2f".format(it) } ?: "", pa?.gridConfidence?.let { "%.3f".format(it) } ?: "",
            pb?.gridConfidence?.let { "%.3f".format(it) } ?: "", "%.4f".format(ratio))
        if (m == null) return (head + listOf("NONE") + List(21) { "" } + listOf("")).joinToString("\t")
        val met = DjComposerProbe.metrics(m, pa, pb)
        val (echoBars, drift) = echoTail(m, pb, ratio)
        val outLeft = (a.durMs - (m.outStartMs + m.bars * m.wallBarMs)) / 1000.0
        return (head + listOf(m.family.name, m.fill.name, m.bars, m.swapBar, m.entryBar, "%.1f".format(m.incomingCueMs / 1000.0),
            "%.1f".format(m.outStartMs / 1000.0), "%.1f".format(outLeft), met.landingErrorBeats ?: "", met.bothLowBars,
            met.outgoingAfterFullBars, echoBars, "%.0f".format(drift), m.sync, m.stretchIncoming) + extra(m, pa, pb) +
            listOf(m.notes)).joinToString("\t")
    }

    /**
     * ghostBars: bars after the key bar where the outgoing is muted dry but still feeds an
     * open echo send (it keeps sounding through the wet path). drop*: the incoming at the key
     * bar, relative to its body. keyBoundary: 2 section, 1 8-bar phrase, .5 4-bar, 0 none (outgoing).
     * midWord: the outgoing vocal runs through the key bar. vocalOverlap: bars both decks carry
     * vocals at > .4 gain while overlapping.
     */
    private fun extra(m: DjComposerProbe.Mix, pa: TrackProfile?, pb: TrackProfile?): List<Any> {
        val o = m.outgoing; val n = m.incoming
        var ghost = 0
        for (b in m.swapBar until m.bars) {
            val x = b + .5f
            if (o.at(P.DRY, x) < .1f && o.at(P.WET, x) > .1f && o.at(P.SEND, x) > .05f) ghost++
        }
        val inKey = m.incomingBar + (m.swapBar - m.entryBar)
        val body = pb?.bodyEnergy?.coerceAtLeast(1e-6f)
        val dropE = pb?.energy(inKey)?.let { it / body!! }
        val outKey = m.exitBar + m.swapBar
        val boundary = when {
            pa == null -> ""
            outKey in pa.sections -> "2"
            pa.isPhraseStart(outKey, 8) -> "1"
            pa.isPhraseStart(outKey, 4) -> ".5"
            else -> "0"
        }
        val mid = pa != null && pa.vocal(outKey - 1) >= .5f && pa.vocal(outKey) >= .5f
        var overlap = 0
        for (b in 0 until m.bars) {
            val x = b + .5f
            val oV = o.at(P.DRY, x) * o.at(P.MID, x) * (pa?.vocal(m.exitBar + b)?.coerceAtLeast(0f) ?: .5f)
            val iV = if (b >= m.entryBar) n.at(P.MID, x) * (pb?.vocal(m.incomingBar + b - m.entryBar)?.coerceAtLeast(0f) ?: .5f) else 0f
            if (oV > .4f && iV > .4f) overlap++
        }
        return listOf(ghost, dropE?.let { "%.2f".format(it) } ?: "", pb?.drums(inKey)?.let { "%.2f".format(it) } ?: "",
            pb?.vocal(inKey)?.let { "%.2f".format(it) } ?: "", boundary, mid, overlap)
    }

    /**
     * Bars where the outgoing's echo rings (dry gone, wet open, something was sent in the
     * bar before) while the incoming plays drums; and how far, per bar, the echo's
     * repeats drift from the incoming beat (unsynced decks at different tempi).
     */
    private fun echoTail(m: DjComposerProbe.Mix, pb: TrackProfile?, ratio: Float): Pair<Int, Double> {
        val o = m.outgoing; val n = m.incoming
        var bars = 0
        for (b in m.swapBar until m.bars) {
            val x = b + .5f
            val sent = (0..8).maxOf { k -> o.at(P.SEND, m.swapBar - 1 + k / 8f) }
            val ringing = o.at(P.WET, x) > .1f && o.at(P.DRY, x) < .1f && sent > .2f
            val inBar = m.incomingBar + (b - m.entryBar)
            val incDrums = b >= m.entryBar && (pb == null || pb.drums(inBar) >= .25f) &&
                listOf(P.SUB, P.BASS, P.MID, P.HIGH).maxOf { n.at(it, x) } > .5f
            if (ringing && incDrums) bars++
        }
        val drift = if (bars == 0 || m.sync || ratio.isNaN()) 0.0 else abs(1 - 1 / ratio.toDouble()) * m.wallBarMs
        return bars to drift
    }

    private fun sweepPairs(songs: List<Song>, out: File) {
        val pairs = songs.flatMap { a -> songs.filter { it !== a }.map { a to it } }
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(24))
        val futures = pairs.map { (a, b) ->
            pool.submit<List<String>> {
                val rows = ArrayList<String>()
                rows += describe("CLEAN", a, b, compose(a, b, MixStyle.CLEAN))
                val creative = compose(a, b, MixStyle.CREATIVE)
                rows += describe("CREATIVE", a, b, creative)
                // Runner-up families: best score per family among the candidates.
                val cands = DjComposerProbe.lastCandidates.get().orEmpty()
                val byFam = cands.groupBy { it.first }.mapValues { e -> e.value.maxOf { it.second } }
                rows += "#CANDS\t${a.name}\t${b.name}\t" + Family.entries.joinToString("\t") { f ->
                    byFam[f]?.let { "%.2f".format(it) } ?: "" }
                rows += describe("NOJITTER", a, b, compose(a, b, MixStyle.CREATIVE, jitter = 0.0))
                for (k in 1..6) rows += describe("SEED$k", a, b,
                    compose(a, b, MixStyle.CREATIVE, seed = "${a.name}→${b.name}".hashCode() + k * 7919))
                for (f in Family.entries) rows += describe("F_${f.name}", a, b, compose(a, b, MixStyle.CREATIVE, forced = f))
                // The probe must match the engine exactly with its knobs at rest.
                val orig = DjComposer.compose(DjComposer.Side(a.p, null, a.durMs, a.name), DjComposer.Side(b.p, null, b.durMs, b.name),
                    a.durMs - 90_000, emptyList(), "${a.name}→${b.name}".hashCode(),
                    options = DjComposer.Options(style = DjComposer.MixStyle.CREATIVE))
                if (orig?.label != creative?.label || orig?.outStartMs != creative?.outStartMs)
                    println("MISMATCH ${a.name} → ${b.name}: ${orig?.label} vs ${creative?.label}")
                rows
            }
        }
        File(out, "pairs.tsv").printWriter().use { w ->
            w.println(header)
            for (f in futures) f.get().forEach { w.println(it) }
        }
        pool.shutdown()
        println("SWEEP ${pairs.size} pairs in ${(System.nanoTime() - started) / 1_000_000_000}s")
        // Grid-confidence threshold experiment (single thread: MIN_CONFIDENCE is global).
        File(out, "threshold.tsv").printWriter().use { w ->
            w.println(header)
            for (t in listOf(.3f, .2f, .1f)) {
                DjComposerProbe.MIN_CONFIDENCE = t
                for ((a, b) in pairs) {
                    val pa = a.p ?: continue; val pb = b.p ?: continue
                    if (abs(pb.bpm / pa.bpm - 1) > .04 || minOf(pa.gridConfidence, pb.gridConfidence) >= .45f) continue
                    w.println(describe("MINCONF_$t", a, b, compose(a, b, MixStyle.CREATIVE)))
                    w.println(describe("MINCONF_${t}_CLEAN", a, b, compose(a, b, MixStyle.CLEAN)))
                }
            }
            DjComposerProbe.MIN_CONFIDENCE = .45f
            for (st in listOf(.06, .08)) {
                DjComposerProbe.MAX_STRETCH = st
                for ((a, b) in pairs) {
                    val pa = a.p ?: continue; val pb = b.p ?: continue
                    val d = abs(pb.bpm / pa.bpm - 1)
                    if (d <= .04 || d > st) continue
                    w.println(describe("STRETCH_$st", a, b, compose(a, b, MixStyle.CREATIVE)))
                }
            }
            DjComposerProbe.MAX_STRETCH = .04
        }
    }

    private fun sequences(songs: List<Song>, out: File) {
        File(out, "seq.tsv").printWriter().use { w ->
            w.println("run\tstep\t" + header + "\tfamilyNoHistory\tlandingNoHistory\tfamilyClean\tlandingClean")
            for (run in 0 until 30) {
                val order = songs.shuffled(java.util.Random(run.toLong()))
                val recent = ArrayList<DjComposerProbe.Played>()
                for (k in 0 until order.size - 1) {
                    val a = order[k]; val b = order[k + 1]
                    val m = compose(a, b, MixStyle.CREATIVE, recent = recent)
                    val alone = compose(a, b, MixStyle.CREATIVE, variety = false)
                    val clean = compose(a, b, MixStyle.CLEAN)
                    fun land(x: DjComposerProbe.Mix?) = x?.let { DjComposerProbe.metrics(it, a.p, b.p).landingErrorBeats?.toString() } ?: ""
                    w.println("$run\t$k\t" + describe("SEQ", a, b, m) +
                        "\t${alone?.family ?: "NONE"}\t${land(alone)}\t${clean?.family ?: "NONE"}\t${land(clean)}")
                    m?.let { recent.add(0, DjComposerProbe.Played(it.family, it.bars)) }
                }
            }
        }
    }

    // ---- offline render ------------------------------------------------------------------------

    private fun renderList(list: File, songs: List<Song>, dir: File) {
        val byName = songs.associateBy { it.name }
        val index = StringBuilder()
        for (line in list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val f = line.split('|')
            val a = byName[f[1]] ?: error("no song ${f[1]}")
            val b = byName[f[2]] ?: error("no song ${f[2]}")
            val opts = f.drop(4)
            val jitter = if ("jitter0" in opts) 0.0 else 1.0
            opts.firstOrNull { it.startsWith("minconf=") }?.let { DjComposerProbe.MIN_CONFIDENCE = it.substringAfter('=').toFloat() }
            val m = when (f[3]) {
                "CLEAN" -> compose(a, b, MixStyle.CLEAN)
                "CREATIVE" -> compose(a, b, MixStyle.CREATIVE, jitter = jitter)
                else -> compose(a, b, MixStyle.CREATIVE, forced = Family.valueOf(f[3]), jitter = jitter)
            } ?: run { index.appendLine("${f[0]}\tNO MIX"); null }
            DjComposerProbe.MIN_CONFIDENCE = .45f
            if (m == null) continue
            val lead = 6 * m.wallBarMs
            renderMix(a, b, m, lead, File(dir, "${f[0]}.mp3"), outOnly = "outonly" in opts)
            val met = DjComposerProbe.metrics(m, a.p, b.p)
            val times = "mix starts at %.1fs, key bar at %.1fs, ends at %.1fs"
                .format(lead / 1000, (lead + m.swapBar * m.wallBarMs) / 1000, (lead + m.bars * m.wallBarMs) / 1000)
            val l = "${f[0]}\t${a.name} → ${b.name}\t${f.drop(3).joinToString(" ")}\t${m.label}\t$times\t$met\t${m.notes}"
            println("RENDER $l")
            index.appendLine(l)
        }
        File(dir, "index.tsv").appendText(index.toString())
    }

    /** Decodes a deck (stretched by [tempo] when ≠ 1) into interleaved stereo. */
    private fun load(f: File, tempo: Double): FloatArray {
        val list = FloatArrayList()
        stream(f, 2, if (abs(tempo - 1) > 1e-4) "atempo=%.6f".format(tempo) else null) { list.add(it) }
        return list.toArray()
    }

    private class FloatArrayList {
        var a = FloatArray(1 shl 22); var n = 0
        fun add(x: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = x }
        fun toArray() = a.copyOf(n)
    }

    /**
     * Same chain as EqBendProcessor with an automation: 4-band split, band gains, then
     * DjFx (filters, echo, rolls, spins). The incoming is pre-stretched with atempo so its
     * bars last a wall bar; beat-sync nudges and the post-mix glide are not modelled.
     */
    private fun renderMix(a: Song, b: Song, m: DjComposerProbe.Mix, leadMs: Double, target: File, outOnly: Boolean = false) {
        val inTempo = if (m.stretchIncoming) m.incomingBarMs / m.wallBarMs else 1.0
        val outPcm = load(a.file, 1.0)
        val inPcm = load(b.file, inTempo)
        val totalMs = leadMs + (m.bars + 8) * m.wallBarMs
        val frames = (totalMs * rate / 1000).toInt()
        val mix = FloatArray(frames * 2)
        // Each deck in its own (stretched) sample time; bar 0 at wall leadMs.
        fun deck(pcm: FloatArray, d: DjTransitionPlan.Deck, bar0Ms: Double, barMs: Double) {
            val auto = DeckAutomation(bar0Ms, barMs, d)
            val fx = DjFx(rate, 2)
            val xo = Array(2) { DjCrossover(rate) }
            val split = FloatArray(4)
            val firstFrame = ((bar0Ms - leadMs) * rate / 1000).toLong()
            var left = 0
            for (k in 0 until frames) {
                val f = firstFrame + k
                if (left == 0) { fx.beginBlock(auto, f, 0.0); left = DjFx.BLOCK }
                for (c in 0..1) {
                    val i = (f * 2 + c).toInt()
                    val x = if (f >= 0 && i < pcm.size) pcm[i] else 0f
                    xo[c].split(x, split)
                    val g = fx.gain
                    val y = split[0] * g[0] + split[1] * g[1] + split[2] * g[2] + split[3] * g[3]
                    mix[k * 2 + c] += fx.process(c, y, f)
                }
                fx.endFrame(f)
                left--
            }
        }
        deck(outPcm, m.outgoing, m.outStartMs.toDouble(), m.outBarMs)
        // Incoming stretched time: media / tempo. Mix bar 0 sits entryBar bars before its cue.
        val inBar0 = (m.incomingCueMs - m.entryBar * m.incomingBarMs) / inTempo
        if (!outOnly) deck(inPcm, m.incoming, inBar0, m.incomingBarMs / inTempo)
        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val g = if (peak > .98f) .98f / peak else 1f
        val raw = java.nio.ByteBuffer.allocate(mix.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in mix) raw.putShort(((x * g).coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val pr = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", "$rate", "-ac", "2", "-i", "-",
            "-b:a", "192k", target.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        pr.outputStream.use { it.write(raw.array()) }
        pr.waitFor()
    }
}
