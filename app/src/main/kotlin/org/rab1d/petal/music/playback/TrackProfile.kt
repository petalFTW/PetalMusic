/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What a DJ knows about a record after listening to it: a constant-tempo beat
 * grid fitted to the kicks, the bar phase, per-bar energy/vocal/drum activity,
 * section boundaries and the phrase grid, intro/outro bars and the key.
 *
 * Bar `i` starts at media position [barStartMs]`(i)`. Bars after
 * [analysedToMs] were not heard yet; their features read as unknown (-1).
 */
class TrackProfile(
    val beatMs: Double,
    val phaseMs: Double,
    /** 0..1: how well one constant grid explains the tracked beats. */
    val gridConfidence: Float,
    val downbeatMs: Double?,
    val downbeatConfidence: Float,
    val firstBarMs: Double,
    val barEnergy: FloatArray,
    val barLow: FloatArray,
    /** 0..1 sustained 1–4 kHz content: vocals and leads, the parts that clash. */
    val barVocal: FloatArray,
    val barDrums: FloatArray,
    /** Section starts as bar indices, ascending. */
    val sections: IntArray,
    /** Phrases start on bars ≡ this (mod 4). */
    val phraseOffset: Int,
    val introEndBar: Int,
    val outroStartBar: Int,
    val camelot: Int?,
    val minor: Boolean?,
    val keyConfidence: Float,
    val analysedFromMs: Double,
    val analysedToMs: Double,
    /** The whole file was analysed, not just what has played so far. */
    val complete: Boolean,
) {
    val bpm: Float get() = (60000.0 / beatMs).toFloat()
    val barMs: Double get() = beatMs * 4
    val bars: Int get() = barEnergy.size
    val hasDownbeat: Boolean get() = downbeatMs != null

    fun barStartMs(i: Int) = firstBarMs + i * barMs

    /** Bar containing media position [ms] (may be negative before the first bar). */
    fun barAt(ms: Double) = kotlin.math.floor((ms - firstBarMs) / barMs).toInt()

    private fun feature(a: FloatArray, i: Int) = if (i in a.indices) a[i] else -1f
    fun vocal(i: Int) = feature(barVocal, i)
    fun energy(i: Int) = feature(barEnergy, i)
    fun drums(i: Int) = feature(barDrums, i)

    /** Median bar energy of the body, between intro and outro. */
    val bodyEnergy: Float by lazy {
        val body = barEnergy.copyOfRange(introEndBar.coerceIn(0, bars),
            outroStartBar.coerceIn(introEndBar.coerceIn(0, bars), bars)).filter { it >= 0f }.sorted()
        if (body.isEmpty()) barEnergy.filter { it >= 0f }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        else body[body.size / 2]
    }

    fun isPhraseStart(i: Int, length: Int = 4) = (i - phraseOffset).mod(length) == 0

    fun write(out: DataOutputStream) {
        out.writeInt(VERSION)
        out.writeDouble(beatMs); out.writeDouble(phaseMs); out.writeFloat(gridConfidence)
        out.writeBoolean(downbeatMs != null); out.writeDouble(downbeatMs ?: 0.0); out.writeFloat(downbeatConfidence)
        out.writeDouble(firstBarMs)
        for (a in listOf(barEnergy, barLow, barVocal, barDrums)) {
            out.writeInt(a.size); a.forEach { out.writeFloat(it) }
        }
        out.writeInt(sections.size); sections.forEach { out.writeInt(it) }
        out.writeInt(phraseOffset); out.writeInt(introEndBar); out.writeInt(outroStartBar)
        out.writeInt(camelot ?: 0); out.writeInt(when (minor) { null -> 0; true -> 1; false -> 2 })
        out.writeFloat(keyConfidence)
        out.writeDouble(analysedFromMs); out.writeDouble(analysedToMs); out.writeBoolean(complete)
    }

    companion object {
        private const val VERSION = 1

        fun read(input: DataInputStream): TrackProfile? {
            if (input.readInt() != VERSION) return null
            val beat = input.readDouble(); val phase = input.readDouble(); val conf = input.readFloat()
            val hasDown = input.readBoolean(); val down = input.readDouble(); val downConf = input.readFloat()
            val first = input.readDouble()
            val arrays = List(4) { FloatArray(input.readInt()) { input.readFloat() } }
            val sections = IntArray(input.readInt()) { input.readInt() }
            val phrase = input.readInt(); val intro = input.readInt(); val outro = input.readInt()
            val camelot = input.readInt().takeIf { it in 1..12 }
            val minor = when (input.readInt()) { 1 -> true; 2 -> false; else -> null }
            val keyConf = input.readFloat()
            return TrackProfile(beat, phase, conf, if (hasDown) down else null, downConf, first,
                arrays[0], arrays[1], arrays[2], arrays[3], sections, phrase, intro, outro,
                camelot, minor, keyConf, input.readDouble(), input.readDouble(), input.readBoolean())
        }
    }
}

/**
 * Profiles by track key, in memory and on disk. A complete (whole-file)
 * profile is never replaced by a partial live one.
 */
object ProfileStore {
    private val memory = object : LinkedHashMap<String, TrackProfile>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackProfile>?) = size > 48
    }
    private var dir: File? = null
    @Volatile var version = 0
        private set

    fun attach(base: File) {
        synchronized(this) { if (dir == null) dir = File(base, "automix-profiles").apply { mkdirs() } }
    }

    private fun file(key: String): File? = dir?.let { File(it, sha1(key)) }

    fun get(key: String): TrackProfile? {
        synchronized(this) { memory[key]?.let { return it } }
        val f = file(key)?.takeIf { it.exists() } ?: return null
        val p = runCatching { DataInputStream(f.inputStream().buffered()).use { TrackProfile.read(it) } }.getOrNull()
            ?: return null
        synchronized(this) { memory[key] = p }
        return p
    }

    /** False when an existing complete profile outranks [p]. */
    fun put(key: String, p: TrackProfile): Boolean {
        val old = get(key)
        if (old != null && old.complete && !p.complete) return false
        synchronized(this) { memory[key] = p }
        version++
        file(key)?.let { f ->
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { p.write(it) }
                tmp.renameTo(f)
            }
        }
        return true
    }

    private fun sha1(s: String): String = java.security.MessageDigest.getInstance("SHA-1")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
