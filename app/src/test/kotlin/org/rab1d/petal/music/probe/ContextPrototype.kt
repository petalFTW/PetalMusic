/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import org.junit.Test
import org.rab1d.petal.music.playback.DjComposerProbe.Family
import org.rab1d.petal.music.playback.DjComposerProbe.MixStyle
import org.rab1d.petal.music.playback.DjTransitionPlan.P
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * "Context" prototype (test-only; skipped unless PETAL_BENCH_DIR and PETAL_CONTEXT are set).
 * Reads the profiles AutomixCreativeSweep cached, describes each side of every pair, picks the
 * move by rules instead of seed jitter, and adds two moves the engine does not have:
 *
 *  - BED_UNDER: the incoming's instrumental intro plays under the outgoing's last phrase at its
 *    own tempo; one low end at a time; the outgoing is gone when the incoming's first full bar lands.
 *  - VOCAL_PREVIEW: on a big drop in energy, the incoming's hook plays thinned (no low end, filtered)
 *    under the outgoing's build, then the incoming starts from its very beginning at the cut.
 *    (Needs a second voice for the incoming in the engine.)
 *
 *   PETAL_CONTEXT=all            → out/context/context.tsv for all ordered pairs
 *   PETAL_CONTEXT_RENDER=<file>  lines "name|from|to[|MOVE]" → out/renders/name.mp3
 */
class ContextPrototype {
    private val rate = 44100

    class Song(val name: String, val p: TrackProfile, val durMs: Long, val file: File)

    enum class Move { BLEND, BED_UNDER, VOCAL_PREVIEW, CUT }

    class Ctx(
        val bed: Int, val bedVocal: Float, val bedDrums: Float, val firstFull: Int?, val vocalFirst: Boolean,
        val inOpenDb: Double, val outEndDb: Double, val bpmRatio: Double, val relation: String,
        val direction: String, val matched: Boolean, val hook: Int?,
    ) {
        val dropDb get() = inOpenDb - outEndDb
        override fun toString() = "bed=$bed(v%.2f d%.2f) full=$firstFull vocalFirst=$vocalFirst open=%.1fdB end=%.1fdB drop=%+.1fdB ratio=%.3f %s %s matched=$matched hook=$hook"
            .format(bedVocal, bedDrums, inOpenDb, outEndDb, dropDb, bpmRatio, relation, direction)
    }

    /** One deck of a planned transition, in wall time from the mix start. */
    class DeckSpec(val song: Song, val deck: DjTransitionPlan.Deck, val startWallMs: Double, val mediaCueMs: Double,
                   val barMs: Double, val tempo: Double = 1.0)

    class Plan(val move: Move, val label: String, val decks: List<DeckSpec>, val keyWallMs: Double, val endWallMs: Double,
               val outStartMs: Double, val why: String, val mix: DjComposerProbe.Mix? = null)

    // ---- loading ----------------------------------------------------------------------------

    private fun songs(dir: File): List<Song> {
        val cache = File(dir, "out/sweep/profiles")
        return File(dir, "songs").listFiles { f -> f.extension.lowercase() in setOf("m4a", "flac", "mp3", "wav") }!!
            .sortedBy { it.name }.mapNotNull { f ->
                val bin = File(cache, f.nameWithoutExtension + ".bin")
                val meta = File(cache, f.nameWithoutExtension + ".meta")
                if (!bin.exists()) return@mapNotNull null
                val p = DataInputStream(bin.inputStream().buffered()).use { if (it.readBoolean()) TrackProfile.read(it) else null }
                    ?: return@mapNotNull null
                Song(f.nameWithoutExtension, p, meta.readLines()[0].toLong(), f)
            }
    }

    // ---- context ----------------------------------------------------------------------------

    private fun db(x: Double) = 10 * log10(x.coerceAtLeast(1e-12))
    private fun mean(p: TrackProfile, from: Int, to: Int, f: (Int) -> Float): Float {
        val v = (from.coerceAtLeast(0) until to.coerceAtMost(p.bars)).map(f).filter { it >= 0f }
        return if (v.isEmpty()) 0f else v.average().toFloat()
    }

    private fun firstFull(p: TrackProfile, from: Int = 0): Int? =
        (from until minOf(p.bars, from + 24)).firstOrNull { p.drums(it) >= .25f && p.energy(it) >= .9f * p.bodyEnergy }

    fun context(a: Song, b: Song, matched: Boolean): Ctx {
        val po = a.p; val pi = b.p
        val full = firstFull(pi)
        // An instrumental bed: the bars before the incoming is fully in, still music, with no lead vocal.
        val bedEnd = full ?: 0
        val musical = (0 until bedEnd).count { pi.energy(it) >= .12f * pi.bodyEnergy }
        val bedVocal = mean(pi, 0, bedEnd) { pi.vocal(it) }
        val bedDrums = mean(pi, 0, bedEnd) { pi.drums(it) }
        val bed = if (bedEnd >= 2 && musical >= bedEnd - 1 && bedVocal < .35f) bedEnd else 0
        val vocalFirst = mean(pi, 0, 4) { pi.vocal(it) } >= .5f
        // Loudness the listener compares: the incoming's opening against the outgoing's last body phrase.
        val outEnd = po.outroStartBar.coerceIn(8, po.bars)
        val outEndDb = db(mean(po, outEnd - 16, outEnd) { po.energy(it) }.toDouble())
        val inOpenDb = db(mean(pi, 0, 8) { pi.energy(it) }.toDouble())
        val ratio = pi.bpm.toDouble() / po.bpm
        val relation = when {
            abs(ratio - 1) <= .04 -> "1:1"
            abs(ratio / 2 - 1) <= .04 -> "2:1"
            abs(ratio / .5 - 1) <= .04 -> "1:2"
            else -> "far"
        }
        val drop = inOpenDb - outEndDb
        // Down: clearly quieter, or much slower and not louder.
        val direction = when {
            drop <= -4.0 || (ratio <= .75 && drop <= -1.5) -> "down"
            drop >= 4.0 || (ratio >= 1.33 && drop >= 1.5) -> "up"
            else -> "level"
        }
        // The hook to preview: the first sustained, full, sung stretch, preferably on a section line.
        val hookCandidates = (maxOf(pi.introEndBar, 2) until minOf(pi.bars - 4, 64)).filter { h ->
            (h until h + 2).all { pi.vocal(it) >= .6f } && pi.energy(h) >= .8f * pi.bodyEnergy
        }
        val hook = hookCandidates.firstOrNull { it in pi.sections } ?: hookCandidates.firstOrNull()
        return Ctx(bed, bedVocal, bedDrums, full, vocalFirst, inOpenDb, outEndDb, ratio, relation, direction, matched, hook)
    }

    // ---- choosing ---------------------------------------------------------------------------

    private fun compose(a: Song, b: Song, style: MixStyle, forced: Family? = null, jitter: Double = 1.0): DjComposerProbe.Mix? {
        DjComposerProbe.force.set(forced); DjComposerProbe.jitterScale.set(jitter)
        try {
            return DjComposerProbe.compose(DjComposerProbe.Side(a.p, null, a.durMs, a.name),
                DjComposerProbe.Side(b.p, null, b.durMs, b.name), a.durMs - 90_000, emptyList(),
                "${a.name}→${b.name}".hashCode(), options = DjComposerProbe.Options(style = style))
        } finally { DjComposerProbe.force.set(null); DjComposerProbe.jitterScale.set(1.0) }
    }

    private fun score(m: DjComposerProbe.Mix) = Regex("score=([-0-9.]+)").find(m.notes)?.groupValues?.get(1)?.toDouble() ?: -99.0

    fun plan(a: Song, b: Song, forcedMove: Move? = null): Pair<Ctx, Plan?> {
        val creative = compose(a, b, MixStyle.CREATIVE)
        val ctx = context(a, b, creative?.notes?.contains("matched=true") == true)
        val move = forcedMove ?: when {
            ctx.matched -> Move.BLEND
            // A big drop in energy: tease the new song's voice over a build, then start it clean.
            ctx.dropDb <= -4.0 && ctx.hook != null -> Move.VOCAL_PREVIEW
            ctx.bed >= 2 && ctx.bedVocal < .25f -> Move.BED_UNDER
            ctx.direction == "down" && ctx.hook != null -> Move.VOCAL_PREVIEW
            else -> Move.CUT
        }
        val plan = when (move) {
            Move.BLEND -> compose(a, b, MixStyle.CLEAN)?.let { fromMix(Move.BLEND, a, b, it, "matched: Clean's blend") }
            Move.BED_UNDER -> bedUnder(a, b, ctx) ?: cut(a, b, ctx)
            Move.VOCAL_PREVIEW -> vocalPreview(a, b, ctx) ?: cut(a, b, ctx)
            Move.CUT -> cut(a, b, ctx)
        }
        return ctx to plan
    }

    /** Evidence-picked cut, no jitter: no echo tail across a tempo gap, no stutter across energy drops. */
    private fun cut(a: Song, b: Song, ctx: Ctx): Plan? {
        val gap = abs(ctx.bpmRatio - 1)
        val allowed = buildList {
            add(Family.FILTER_FADE)
            add(Family.BACKSPIN); add(Family.BRAKE)
            if (gap <= .05) add(Family.ECHO_THROW)
            if (ctx.direction != "down") add(Family.STUTTER)
        }
        val best = allowed.mapNotNull { f ->
            val style = if (f == Family.FILTER_FADE && gap > .05) MixStyle.CLEAN else MixStyle.CREATIVE
            compose(a, b, style, forced = f, jitter = 0.0)?.takeIf { it.family == f }
        }.maxByOrNull { score(it) } ?: return null
        return fromMix(Move.CUT, a, b, best, "cut by evidence (gap ${"%.0f".format(gap * 100)}%, ${ctx.direction})")
    }

    private fun fromMix(move: Move, a: Song, b: Song, m: DjComposerProbe.Mix, why: String): Plan {
        val inTempo = if (m.stretchIncoming) m.incomingBarMs / m.wallBarMs else 1.0
        // Incoming media at mix bar 0: its cue less the bars before entry (in its own media bars).
        val inBar0Media = m.incomingCueMs - m.entryBar * m.incomingBarMs
        val outSpec = DeckSpec(a, m.outgoing, 0.0, m.outStartMs.toDouble(), m.outBarMs)
        val inSpec = DeckSpec(b, m.incoming, 0.0, inBar0Media, m.incomingBarMs, inTempo)
        return Plan(move, m.label, listOf(outSpec, inSpec), m.swapBar * m.wallBarMs, m.bars * m.wallBarMs,
            m.outStartMs.toDouble(), "$why | ${m.notes}", m)
    }

    private fun lastBar(s: Song) = floor((s.durMs - 400 - s.p.firstBarMs) / s.p.barMs).toInt() - 1

    private fun boundary(p: TrackProfile, i: Int) = when {
        i in p.sections -> 2.0
        p.isPhraseStart(i, 8) -> 1.0
        p.isPhraseStart(i, 4) -> .5
        else -> 0.0
    }

    /** The outgoing bar the move hands over on: a phrase line near the end, after a vocal line. */
    private fun pickKey(a: Song, overlapBars: Int, vocalWeight: Double): Int? {
        val po = a.p
        val last = lastBar(a)
        val first = maxOf(overlapBars, ((a.durMs - 90_000 - po.firstBarMs) / po.barMs).toInt() + overlapBars)
        return (maxOf(first, last + 1 - 48)..last + 1).maxByOrNull { k ->
            var s = 1.5 * boundary(po, k)
            if (po.outroStartBar < po.bars && k >= po.outroStartBar) s += 1.0
            if (k == last + 1) s += 1.5
            if (po.vocal(k - 1) >= .5f && po.vocal(k) < .5f) s += 1.0
            if (po.vocal(k - 1) >= .5f && po.vocal(k) >= .5f) s -= 1.0
            s -= vocalWeight * mean(po, k - overlapBars, k) { po.vocal(it) }
            s -= ((a.durMs - po.barStartMs(k)) / 1000 - 15).coerceAtLeast(0.0) * .05
            s
        }
    }

    private fun bedUnder(a: Song, b: Song, ctx: Ctx): Plan? {
        val po = a.p; val pi = b.p
        val full = ctx.firstFull ?: return null
        val lenIn = listOf(8, 4, 2).first { it <= ctx.bed }
        val cueBar = full - lenIn
        val bedMs = lenIn * pi.barMs
        val overlapOut = maxOf(1, (bedMs / po.barMs).roundToInt())
        val key = pickKey(a, overlapOut, 1.0) ?: return null
        val keyMs = overlapOut * po.barMs                   // mix-relative wall time of the key bar
        val startIn = keyMs - bedMs                         // the bed starts so its last bar ends on the key bar
        val o = DjTransitionPlan.Deck.of(1f)
        val n = DjTransitionPlan.Deck.of(0f)
        val l = overlapOut.toFloat()
        val e = .004f
        // Outgoing (its bars): low end hands over halfway, hats thin, gone over the last half bar.
        val half = (l / 2).coerceAtLeast(.5f)
        for (p in listOf(P.SUB, P.BASS)) o.lane(p).ramp(half, half + e, 0f)
        // Make room: the outgoing's mids and highs step back by the loudness gap (+3 dB) by halfway,
        // so the bed is heard from the start rather than noticed at the end. Gains cap at 1, so the
        // quieter side is matched by turning the louder one down.
        val gapDb = (ctx.outEndDb - ctx.inOpenDb).coerceIn(0.0, 6.0)
        val duck = Math.pow(10.0, -(gapDb + 3) / 20).toFloat().coerceIn(.45f, .8f)
        o.lane(P.MID).ramp(0f, half, duck)
        o.lane(P.HIGH).ramp(0f, half, minOf(duck, .6f)).ramp(half, l, .35f)
        // Plays to the bar line: a fade over the last half bar sounds like it stopped on beat 2.
        o.lane(P.DRY).ramp(l - .0625f, l, 0f)
        o.lane(P.WET).ramp(l - e, l, 0f)
        // Incoming (its bars, from its cue): in the background, low end off until halfway, full on its first full bar.
        val li = lenIn.toFloat()
        val inHalf = (half * po.barMs / pi.barMs).toFloat()
        // Clearly present from its first bar, full by halfway (its low end still waits for the swap).
        n.lane(P.MID).ramp(0f, e, .8f).ramp(e, inHalf, 1f)
        n.lane(P.HIGH).ramp(0f, e, .75f).ramp(e, inHalf, 1f)
        for (p in listOf(P.SUB, P.BASS)) n.lane(p).ramp(inHalf, inHalf + e, 1f)
        val decks = listOf(
            DeckSpec(a, o, 0.0, po.barStartMs(key - overlapOut), po.barMs),
            DeckSpec(b, n, startIn, pi.barStartMs(cueBar).coerceAtLeast(0.0), pi.barMs),
        )
        return Plan(Move.BED_UNDER, "Bed under · $lenIn incoming bars over $overlapOut outgoing bars", decks, keyMs,
            keyMs, po.barStartMs(key - overlapOut),
            "bed=${ctx.bed} cueBar=$cueBar key=$key (boundary ${boundary(po, key)}) out vocal at key ${"%.2f".format(po.vocal(key))}")
    }

    private fun vocalPreview(a: Song, b: Song, ctx: Ctx): Plan? {
        val po = a.p; val pi = b.p
        val hook = ctx.hook ?: return null
        val l = if (po.bpm >= 110) 8 else 4
        val key = pickKey(a, l, 1.5) ?: return null
        val keyMs = l * po.barMs
        val o = DjTransitionPlan.Deck.of(1f)
        val lf = l.toFloat()
        val e = .004f
        // Outgoing: a filtered build, low end thinning, half a beat of breath before the start.
        o.lane(P.HPF).ramp(lf / 2, lf - .25f, .55f, accelerate = true)
        o.lane(P.SUB).ramp(lf / 2, lf - .25f, .2f)
        o.lane(P.BASS).ramp(lf / 2, lf - .25f, .35f)
        o.lane(P.DRY).ramp(lf - .125f - e, lf - .125f, 0f)
        o.lane(P.WET).ramp(lf - .125f - e, lf - .125f, 0f)
        // Preview (incoming bars from the hook): mids only, filtered, rising; stops with the outgoing.
        val pv = DjTransitionPlan.Deck.of(0f)
        val pl = (keyMs / pi.barMs).toFloat()
        pv.lane(P.HPF).ramp(0f, e, .42f)
        pv.lane(P.LPF).ramp(0f, e, .3f).ramp(e, pl, .15f)
        pv.lane(P.MID).ramp(0f, pl * .75f, .7f).ramp(pl - .125f - e, pl - .125f, 0f)
        pv.lane(P.HIGH).ramp(0f, pl * .75f, .25f).ramp(pl - .125f - e, pl - .125f, 0f)
        // The incoming from its very beginning; its first bar line lands on the key bar.
        val n = DjTransitionPlan.Deck.of(1f)
        val lead = pi.firstBarMs.coerceIn(0.0, pi.barMs)
        val decks = listOf(
            DeckSpec(a, o, 0.0, po.barStartMs(key - l), po.barMs),
            DeckSpec(b, pv, 0.0, pi.barStartMs(hook), pi.barMs),
            DeckSpec(b, n, keyMs - lead, 0.0, pi.barMs),
        )
        return Plan(Move.VOCAL_PREVIEW, "Vocal preview → restart · $l bars", decks, keyMs, keyMs + 4 * pi.barMs,
            po.barStartMs(key - l), "hook=$hook key=$key (boundary ${boundary(po, key)}) out vocal over build " +
                "%.2f drop %+.1fdB".format(mean(po, key - l, key) { po.vocal(it) }, ctx.dropDb))
    }

    // ---- run --------------------------------------------------------------------------------

    @Test fun context() {
        val dir = System.getenv("PETAL_BENCH_DIR")?.let { File(it).absoluteFile } ?: return
        val all = System.getenv("PETAL_CONTEXT")
        val renders = System.getenv("PETAL_CONTEXT_RENDER")
        if (all == null && renders == null) return
        val songs = songs(dir)
        val byName = songs.associateBy { it.name }
        if (all != null) {
            val out = File(dir, "out/context").apply { mkdirs() }
            File(out, "context.tsv").printWriter().use { w ->
                w.println("from\tto\tcreative\tmove\tlabel\tbed\tbedVocal\tbedDrums\tfirstFull\tvocalFirst\tdropDb\tratio\trelation\tdirection\tmatched\thook\twhy")
                for (a in songs) for (b in songs) if (a !== b) {
                    val creative = compose(a, b, MixStyle.CREATIVE)
                    val (c, p) = plan(a, b)
                    w.println(listOf(a.name, b.name, creative?.family ?: "NONE", p?.move ?: "NONE", p?.label ?: "",
                        c.bed, "%.2f".format(c.bedVocal), "%.2f".format(c.bedDrums), c.firstFull ?: "", c.vocalFirst,
                        "%.1f".format(c.dropDb), "%.3f".format(c.bpmRatio), c.relation, c.direction, c.matched,
                        c.hook ?: "", p?.why ?: "").joinToString("\t"))
                }
            }
            for ((x, y) in listOf("20 MIN - LIL UZI VERT" to "INTENTIONS - JUSTIN BIEBER", "THATPOWER - WILL.I.AM" to "CINDERELLA - FUTURE")) {
                val (c, p) = plan(byName[x]!!, byName[y]!!)
                println("CONTEXT $x → $y: $c → ${p?.move} ${p?.label} | ${p?.why}")
            }
        }
        renders?.let { f ->
            val target = File(dir, "out/renders").apply { mkdirs() }
            val index = StringBuilder()
            for (line in File(f).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val parts = line.split('|')
                val a = byName[parts[1]]!!; val b = byName[parts[2]]!!
                val (c, p) = plan(a, b, parts.getOrNull(3)?.let { Move.valueOf(it) })
                if (p == null) { index.appendLine("${parts[0]}\tNO PLAN"); continue }
                val lead = 6 * a.p.barMs
                render(p, lead, File(target, "${parts[0]}.mp3"))
                pcmCache.clear()
                val l = "${parts[0]}\t${a.name} → ${b.name}\t${p.move}\t${p.label}\t" +
                    "mix starts at %.1fs, key at %.1fs, ends %.1fs".format(lead / 1000, (lead + p.keyWallMs) / 1000,
                        (lead + p.endWallMs) / 1000) + "\t$c\t${p.why}"
                println("RENDER $l")
                index.appendLine(l)
            }
            File(target, "context-index.tsv").appendText(index.toString())
        }
    }

    // ---- render -----------------------------------------------------------------------------

    private fun stream(f: File, filter: String?, sink: (Float) -> Unit) {
        val cmd = arrayListOf("ffmpeg", "-v", "error", "-i", f.absolutePath)
        if (filter != null) { cmd += "-af"; cmd += filter }
        cmd += listOf("-ac", "2", "-ar", "$rate", "-f", "f32le", "-")
        val pr = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val input = pr.inputStream.buffered(1 shl 16)
        val buf = ByteArray(1 shl 16)
        val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var carry = 0
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

    private val pcmCache = HashMap<Pair<String, Double>, FloatArray>()
    private fun load(s: Song, tempo: Double): FloatArray = pcmCache.getOrPut(s.name to tempo) {
        var a = FloatArray(1 shl 22); var n = 0
        stream(s.file, if (abs(tempo - 1) > 1e-4) "atempo=%.6f".format(tempo) else null) {
            if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = it
        }
        a.copyOf(n)
    }

    /** Every deck through the engine's band split + DjFx, placed on the wall clock; bar 0 of each deck at its start. */
    private fun render(p: Plan, leadMs: Double, target: File) {
        val totalMs = leadMs + p.endWallMs + 8 * p.decks[0].barMs
        val frames = (totalMs * rate / 1000).toInt()
        val mix = FloatArray(frames * 2)
        for (d in p.decks) {
            val pcm = load(d.song, d.tempo)
            // Deck time = stretched media time; the lanes' bar 0 is the deck's wall start.
            val barMs = d.barMs / d.tempo
            val auto = DeckAutomation(d.mediaCueMs / d.tempo, barMs, d.deck)
            val fx = DjFx(rate, 2)
            val xo = Array(2) { DjCrossover(rate) }
            val split = FloatArray(4)
            // Wall frame k ↔ deck frame f: deck media at wall (leadMs + startWall) is its cue.
            val offset = ((d.mediaCueMs / d.tempo - (leadMs + d.startWallMs)) * rate / 1000).toLong()
            var left = 0
            for (k in 0 until frames) {
                val f = k + offset
                if (left == 0) { fx.beginBlock(auto, f, 0.0); left = DjFx.BLOCK }
                for (c in 0..1) {
                    val i = (f * 2 + c).toInt()
                    val x = if (f >= 0 && i < pcm.size) pcm[i] else 0f
                    xo[c].split(x, split)
                    val g = fx.gain
                    mix[k * 2 + c] += fx.process(c, split[0] * g[0] + split[1] * g[1] + split[2] * g[2] + split[3] * g[3], f)
                }
                fx.endFrame(f)
                left--
            }
        }
        // Before a deck's own bar 0 the lanes hold their first value; the incoming decks start at 0 gain,
        // the outgoing plays its lead-in; after its end it is muted by DRY.
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
