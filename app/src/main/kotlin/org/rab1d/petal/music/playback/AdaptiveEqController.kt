/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.sqrt
import kotlin.math.exp

/** Select held combinations from both decks, then adapt levels to live collisions.
 * The arrangement freezes when selected; subsequent frames cannot rewrite its
 * past. Gain history contains the actual commands delivered to the renderer.
 */
internal class AdaptiveEqController(
    private val context: EqTransition.Context,
    durationMs: Int,
) {
    private val duration = durationMs.coerceAtLeast(500).toFloat()
    private val closing = MixTiming.closingWindow(duration, context.beatMs)
    private var closingOut: FloatArray? = null
    private var closingIn: FloatArray? = null
    private val fallback = EqTransition.Band(EqTransition.Window(0f, 1f), EqTransition.Window(0f, 1f))
    private var arrangement: PartExchangePlan? = null
    private val profileOut = Profile()
    private val profileIn = Profile()
    private var analysisMs = 0f
    private val referenceOut = FloatArray(4)
    private val referenceIn = FloatArray(4)
    private val outgoing = FloatArray(4) { 1f }
    private val incoming = FloatArray(4)
    private val history = ArrayList<EqTransition.Point>()
    private var lastProgress = 0f
    private var measured = false
    private var fresh = false
    private var protectingSilence = false
    private var clearingQuietOutro = false
    private var masterTrim = 1f
    private val bandTrimOut = FloatArray(4) { 1f }
    private val bandTrimIn = FloatArray(4) { 1f }
    private var releaseStart = closing.start
    private var releaseEnd = closing.end
    private var audibleEntryMs = 0f
    private var incomingWasQuiet = true

    init { history.add(EqTransition.Point(0f, BandLevels.ONE, BandLevels.ZERO)) }

    fun update(
        progress: Float,
        elapsedMs: Float,
        out: MixAudioMonitor.Frame?,
        next: MixAudioMonitor.Frame?,
    ): EqTransition.Point {
        val t = progress.coerceIn(lastProgress, 1f)
        if (elapsedMs <= 0f || t == lastProgress) return history.last()
        fresh = out != null && next != null && out.confidence >= .5f && next.confidence >= .5f
        val audiblePair = fresh && out!!.rms > SILENCE && next!!.rms > SILENCE
        // A quiet intro may reveal its first real note well into the overlap.
        // Re-open gently then too, rather than taking a whole band in 180 ms.
        if (next != null && next.confidence >= .5f) {
            if (next.rms <= SILENCE) {
                incomingWasQuiet = true
                audibleEntryMs = 0f
            } else {
                if (incomingWasQuiet) audibleEntryMs = 0f
                incomingWasQuiet = false
                audibleEntryMs += elapsedMs
            }
        } else audibleEntryMs += elapsedMs
        val entry = MixTiming.entryGain(audibleEntryMs, duration.toInt(), context.beatMs)
        if (audiblePair && arrangement == null && t < closing.start) {
            profileOut.add(out!!)
            profileIn.add(next!!)
            analysisMs += elapsedMs
            // Average several dry windows so one kick does not choose the mix.
            if (analysisMs >= minOf(500f, duration * .07f)) {
                measured = true
                val a = profileOut.mean()
                val b = profileIn.mean()
                arrangement = PartExchangePlan.analyse(a, b, context, duration)
                for (band in 0..3) {
                    referenceOut[band] = a.bands[band]
                    referenceIn[band] = b.bands[band]
                }
            }
        }
        // Quiet intro / an unexpected break: retain the outgoing while there is
        // time, rather than blindly removing it under a silent incoming deck.
        protectingSilence = fresh && next!!.rms < maxOf(SILENCE, out!!.rms * .01f) &&
            out.rms > SILENCE && t < .95f
        clearingQuietOutro = fresh && out!!.rms < maxOf(SILENCE, next!!.rms * .03f) &&
            next.rms > SILENCE && t < releaseStart
        if (protectingSilence && closingOut == null && t >= releaseStart) {
            // A quiet intro must not make the outgoing disappear just because a
            // preselected closing cue arrived. Keep a bounded emergency handoff.
            releaseStart = minOf(.95f, t + .02f)
            releaseEnd = maxOf(closing.end, minOf(1f, releaseStart + .08f))
        }
        val maxStep = elapsedMs / minOf(180f, duration * .15f)
        val releasing = t >= releaseStart
        if (audiblePair) {
            val target = (out!!.rms / next!!.rms.coerceAtLeast(SILENCE)).coerceIn(.55f, 1f)
            val tau = if (target < masterTrim) 400f else 1500f
            masterTrim += (1f - exp(-elapsedMs / tau)) * (target - masterTrim)
        }
        if (releasing && closingOut == null) {
            // Freeze actual applied levels: changing measurements and rhythmic
            // returns cannot bring a fading part back after the closing cue.
            closingOut = outgoing.copyOf()
            closingIn = incoming.copyOf()
        }
        for (b in 0..3) {
            var z = arrangement?.incoming(b, t) ?: fallback.incomingGain(t)
            var a = 1f - z
            if (protectingSilence) {
                a = 1f
                z = 0f
            } else if (clearingQuietOutro) {
                // Don't keep an inaudible bass/body "carrier" while suppressing
                // the corresponding instruments on the new record.
                a = 0f
                z = 1f
            } else if (audiblePair && measured) {
                // A new instrument/drop can arrive after timing has committed.
                // Catch that band's level rise independently of broadband RMS;
                // release the incoming trim as it takes ownership of the mix.
                val bandTrim = (referenceIn[b] / next!!.bands[b].coerceAtLeast(.001f)).coerceIn(.5f, 1f)
                val outTrim = (referenceOut[b] / out!!.bands[b].coerceAtLeast(.001f)).coerceIn(.6f, 1f)
                val blend = 1f - exp(-elapsedMs / 350f)
                bandTrimIn[b] += blend * (bandTrim - bandTrimIn[b])
                bandTrimOut[b] += blend * (outTrim - bandTrimOut[b])
                z *= bandTrimIn[b] + (1f - bandTrimIn[b]) * smooth(t)
                a *= bandTrimOut[b]
                val ea = out.bands[b] * a
                val ez = next.bands[b] * z
                val collision = minOf(ea, ez) / maxOf(ea, ez, .001f)
                val density = sqrt(out.share(b) * next.share(b)).coerceIn(0f, 1f)
                // Two busy midranges need the most room; a retained drum layer is
                // the next worst offender, so the outgoing top ducks harder.
                val amount = collision * density * when (b) { 0, 1 -> .65f; 2 -> .8f; else -> .5f }
                if (a >= z) z *= 1f - amount else a *= 1f - amount
                // Tame a louder incoming master during overlap, release the trim
                // as it becomes the sole track. Never boost a quiet source.
                z *= masterTrim + (1f - masterTrim) * smooth(t)
            }
            if (!releasing && z > entry) {
                z = entry
                // Keep an audible tail underneath the softened first note.
                if (!clearingQuietOutro) a = maxOf(a, 1f - z)
            }
            if (releasing) {
                val u = EqTransition.Window(releaseStart, releaseEnd).gain(t)
                outgoing[b] = closingOut!![b] * (1f - u)
                incoming[b] = closingIn!![b] + (1f - closingIn!![b]) * u
            } else {
                outgoing[b] = approach(outgoing[b], a, maxStep)
                incoming[b] = approach(incoming[b], z, maxStep)
            }
            val sum = maxOf(1f, outgoing[b] + incoming[b])
            outgoing[b] /= sum
            incoming[b] /= sum
            if (t >= releaseEnd) {
                outgoing[b] = 0f
                incoming[b] = 1f
            }
        }
        lastProgress = t
        val point = EqTransition.Point(t, BandLevels.of(outgoing), BandLevels.of(incoming), out?.rms ?: 0f, next?.rms ?: 0f)
        // Keep history bounded for unusually long overlaps. Preserve the start,
        // finish and chronological shape; normal <=32 s overlaps don't decimate.
        if (history.size >= 2048) {
            val reduced = history.filterIndexed { index, _ -> index % 2 == 0 }
            history.clear()
            history.addAll(reduced)
        }
        history.add(point)
        return point
    }

    fun snapshot(): EqTransition {
        val label = when {
            protectingSilence -> "Adaptive EQ · holding through quiet intro"
            clearingQuietOutro -> "Adaptive EQ · clearing quiet outro"
            !measured -> "Listening to both tracks · gentle blend"
            !fresh -> "Adaptive EQ · holding measured plan"
            else -> "Part exchange · ${arrangement?.description}"
        }
        return EqTransition(label, history.toList())
    }

    private class Profile {
        private val sums = FloatArray(13)
        private var count = 0
        fun add(f: MixAudioMonitor.Frame) {
            for (b in 0..3) {
                sums[b] += f.bands[b]
                sums[b + 4] += f.baseline[b]
                sums[b + 8] += f.onset[b]
            }
            sums[12] += f.rms
            count++
        }
        fun mean(): MixAudioMonitor.Frame {
            fun bands(offset: Int) = BandLevels(sums[offset] / count, sums[offset + 1] / count,
                sums[offset + 2] / count, sums[offset + 3] / count)
            return MixAudioMonitor.Frame(0, 1000, sums[12] / count, bands(0), bands(4), bands(8))
        }
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun approach(value: Float, target: Float, amount: Float) =
        value + (target - value).coerceIn(-amount, amount)

    companion object { private const val SILENCE = .0005f }
}
