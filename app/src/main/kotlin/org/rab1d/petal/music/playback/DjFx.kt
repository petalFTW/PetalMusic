/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.tan

/**
 * v2 deck effects, driven by [DeckAutomation] on the deck's media clock:
 * four band gains, loop roll, resonant high/low-pass (TPT state-variable
 * filters, stable under fast modulation) and a damped tempo-synced echo.
 * Parameters are evaluated once per [BLOCK] frames and ramped linearly inside
 * it, so an "instant" kill is a click-free ~1.5 ms ramp. Allocation happens
 * only in the constructor; the audio thread never allocates.
 */
internal class DjFx(private val rate: Int, private val channels: Int) {
    companion object {
        const val BLOCK = 64
        private const val ROLL_EDGE = 48

        fun highPassHz(v: Float) = 10.0 * 800.0.pow(v.toDouble())
        fun lowPassHz(v: Float) = 20000.0 * (150.0 / 20000.0).pow(v.toDouble())
        private val STEMS = arrayOf(DjTransitionPlan.P.DRUMS, DjTransitionPlan.P.BASSLINE,
            DjTransitionPlan.P.VOX, DjTransitionPlan.P.OTHER)
    }

    private class Svf {
        var ic1 = 0.0
        var ic2 = 0.0
        fun reset() { ic1 = 0.0; ic2 = 0.0 }
    }

    private class Coefficients {
        var a1 = 0.0; var a2 = 0.0; var a3 = 0.0; var k = 1.0
        fun set(hz: Double, q: Double, rate: Int) {
            val g = tan(PI * hz.coerceIn(5.0, rate * .45) / rate)
            k = 1.0 / q
            a1 = 1.0 / (1.0 + g * (g + k))
            a2 = g * a1
            a3 = g * a2
        }
    }

    private val hp = Array(channels) { Svf() }
    private val lp = Array(channels) { Svf() }
    private val hpc = Coefficients()
    private val lpc = Coefficients()
    private var hpOn = false
    private var lpOn = false

    private val echoLength = (rate * 2.5).toInt()
    private val echo = Array(channels) { FloatArray(echoLength) }
    private val damp = FloatArray(channels)
    private val dampK = (1.0 - kotlin.math.exp(-2.0 * PI * 4500.0 / rate)).toFloat()
    private var echoWrite = 0
    private var echoDelay = 1

    private val rollBuffer = Array(channels) { FloatArray(rate * 2) }
    // Bar loops: up to two bars at 50 BPM.
    private val loopBuffer = Array(channels) { FloatArray(rate * 10) }
    private val loopTail = Array(channels) { FloatArray(ROLL_EDGE) }
    private var loopAnchor = -1L
    private var loopLength = 0
    private var loopEnd = -1L
    private var rollAnchor = -1L
    private var rollLength = 0

    // Turntable moves read the deck's own recent output at a varying rate.
    private val historyLength = rate * 3
    private val history = Array(channels) { FloatArray(historyLength) }
    private var historyWrite = 0
    private var spinStart = -1L
    private var spinLength = 0L
    private var spinKind = DjTransitionPlan.SpinKind.BACKSPIN
    private var spinPos = -1.0
    private var spinGain = 1f

    /** Band gains for the current frame; callers read these before [process]. */
    val gain = FloatArray(4)
    private val gainStep = FloatArray(4)
    /** Stem gains (drums, bass line, vocals, other) and the sample gain, per frame. */
    val stem = FloatArray(4) { 1f }
    private val stemStep = FloatArray(4)
    var sample = 0f
        private set
    private var sampleStep = 0f
    private var dry = 1f; private var dryStep = 0f
    private var send = 0f; private var sendStep = 0f
    private var feedback = .5f; private var feedbackStep = 0f
    private var wet = 1f; private var wetStep = 0f
    private var primed = false

    fun reset() {
        hp.forEach { it.reset() }
        lp.forEach { it.reset() }
        echo.forEach { it.fill(0f) }
        damp.fill(0f)
        echoWrite = 0
        rollAnchor = -1L
        rollLength = 0
        loopAnchor = -1L
        loopLength = 0
        loopEnd = -1L
        stem.fill(1f)
        sample = 0f
        history.forEach { it.fill(0f) }
        historyWrite = 0
        spinStart = -1L
        spinPos = -1.0
        primed = false
    }

    /**
     * Evaluate the lanes for frames [frame, frame + BLOCK). [originMs] is the
     * processor's media position of frame 0.
     */
    fun beginBlock(a: DeckAutomation, frame: Long, originMs: Double) {
        val msPerFrame = 1000.0 / rate
        val endMs = originMs + (frame + BLOCK) * msPerFrame
        val end = a.bar(endMs)
        val deck = a.deck
        if (!primed) {
            val start = a.bar(originMs + frame * msPerFrame)
            for (b in 0..3) gain[b] = deck.at(DjTransitionPlan.P.entries[b], start)
            dry = deck.at(DjTransitionPlan.P.DRY, start)
            send = deck.at(DjTransitionPlan.P.SEND, start)
            feedback = deck.at(DjTransitionPlan.P.FEEDBACK, start)
            wet = deck.at(DjTransitionPlan.P.WET, start)
            for (k in 0..3) stem[k] = deck.at(STEMS[k], start)
            sample = deck.at(DjTransitionPlan.P.SAMPLE, start)
            primed = true
        }
        for (b in 0..3) gainStep[b] = (deck.at(DjTransitionPlan.P.entries[b], end).coerceIn(0f, 1f) - gain[b]) / BLOCK
        dryStep = (deck.at(DjTransitionPlan.P.DRY, end).coerceIn(0f, 1f) - dry) / BLOCK
        sendStep = (deck.at(DjTransitionPlan.P.SEND, end).coerceIn(0f, 1f) - send) / BLOCK
        feedbackStep = (deck.at(DjTransitionPlan.P.FEEDBACK, end).coerceIn(0f, .9f) - feedback) / BLOCK
        wetStep = (deck.at(DjTransitionPlan.P.WET, end).coerceIn(0f, 1f) - wet) / BLOCK
        for (k in 0..3) stemStep[k] = (deck.at(STEMS[k], end).coerceIn(0f, 1.5f) - stem[k]) / BLOCK
        sampleStep = (deck.at(DjTransitionPlan.P.SAMPLE, end).coerceIn(0f, 1.5f) - sample) / BLOCK

        val h = deck.at(DjTransitionPlan.P.HPF, end)
        if (h > 1e-4f) {
            hpc.set(highPassHz(h), .75 + .6 * h, rate)
            hpOn = true
        } else if (hpOn) { hpOn = false; hp.forEach { it.reset() } }
        val l = deck.at(DjTransitionPlan.P.LPF, end)
        if (l > 1e-4f) {
            lpc.set(lowPassHz(l), .85, rate)
            lpOn = true
        } else if (lpOn) { lpOn = false; lp.forEach { it.reset() } }

        echoDelay = (deck.echoBeats * a.beatMs * rate / 1000).toInt().coerceIn(1, echoLength - 1)
        // Block end, so a roll that begins inside this block records from its anchor.
        val roll = deck.roll(end)
        if (roll == null) {
            rollAnchor = -1L
            rollLength = 0
        } else {
            val (anchorBar, perBeat) = roll
            rollAnchor = ((a.originMs + anchorBar * a.barMs - originMs) / msPerFrame).roundToLong()
            val beatFrames = (a.beatMs / msPerFrame).roundToInt()
            rollLength = (beatFrames / perBeat).coerceIn(ROLL_EDGE * 2, rollBuffer[0].size)
        }
        // Any loop overlapping this block: it may start inside it (anchor) or end inside it (loopEnd).
        val blockStart = a.bar(originMs + frame * msPerFrame)
        val loop = deck.loops.firstOrNull { end >= it.at && blockStart < it.until }
        if (loop == null) { loopAnchor = -1L; loopLength = 0; loopEnd = -1L } else {
            loopAnchor = ((a.originMs + loop.at * a.barMs - originMs) / msPerFrame).roundToLong()
            loopLength = (loop.beats * a.beatMs / msPerFrame).roundToInt().coerceIn(ROLL_EDGE * 2, loopBuffer[0].size)
            loopEnd = ((a.originMs + loop.until * a.barMs - originMs) / msPerFrame).roundToLong()
        }
        val startBar = a.bar(originMs + frame * msPerFrame)
        val spin = deck.spins.firstOrNull { end >= it.at && startBar < it.at + it.beats / 4f }
        if (spin == null) spinStart = -1L else {
            val start = ((a.originMs + spin.at * a.barMs - originMs) / msPerFrame).roundToLong()
            if (start != spinStart) spinPos = -1.0
            spinStart = start
            spinLength = (spin.beats * a.beatMs / msPerFrame).roundToLong().coerceAtLeast(1)
            spinKind = spin.kind
        }
    }

    /** One channel's band-summed sample at processor [frame]. */
    fun process(channel: Int, x: Float, frame: Long): Float {
        var y = x
        if (loopLength > 0 && frame >= loopAnchor && frame < loopEnd) {
            val rel = frame - loopAnchor
            val i = (rel % loopLength).toInt()
            val buffer = loopBuffer[channel]
            if (rel < loopLength) buffer[i] = x
            else {
                // The seam: the head fades in over what originally followed the loop's
                // end (recorded on the first pass), so the wrap continues the waveform.
                val ext = loopTail[channel]
                if (rel < loopLength + ROLL_EDGE) ext[i] = x
                val toEnd = (loopEnd - frame).toInt()
                y = if (i < ROLL_EDGE) {
                    val k = i / ROLL_EDGE.toFloat()
                    buffer[i] * k + ext[i] * (1 - k)
                } else buffer[i]
                // Hand back to the live audio over the last ROLL_EDGE frames.
                if (toEnd < ROLL_EDGE) { val k = toEnd / ROLL_EDGE.toFloat(); y = y * k + x * (1 - k) }
            }
        }
        if (rollLength > 0 && frame >= rollAnchor) {
            val rel = frame - rollAnchor
            val i = (rel % rollLength).toInt()
            val buffer = rollBuffer[channel]
            if (rel < rollLength) buffer[i] = x
            else {
                // A short window at each slice edge removes the repeat click.
                val edge = minOf(i, rollLength - 1 - i).coerceAtMost(ROLL_EDGE) / ROLL_EDGE.toFloat()
                y = buffer[i] * edge
            }
        }
        history[channel][historyWrite] = y
        if (spinStart >= 0 && frame >= spinStart && frame < spinStart + spinLength) {
            if (spinPos < 0) spinPos = historyWrite.toDouble()
            val i = kotlin.math.floor(spinPos).toInt()
            val f = (spinPos - i).toFloat()
            val h = history[channel]
            y = (h[i.mod(historyLength)] * (1 - f) + h[(i + 1).mod(historyLength)] * f) * spinGain
        }
        if (hpOn) y = svf(hp[channel], hpc, y.toDouble(), high = true).toFloat()
        if (lpOn) y = svf(lp[channel], lpc, y.toDouble(), high = false).toFloat()
        val line = echo[channel]
        val delayed = line[(echoWrite - echoDelay + echoLength) % echoLength]
        damp[channel] += dampK * (delayed - damp[channel])
        line[echoWrite] = (send * y + feedback * damp[channel]).coerceIn(-2f, 2f)
        return dry * y + wet * delayed
    }

    /** Advance the shared per-frame state after every channel of [frame] was processed. */
    fun endFrame(frame: Long) {
        if (spinPos >= 0 && frame >= spinStart && frame < spinStart + spinLength) {
            val u = ((frame - spinStart).toFloat() / spinLength).coerceIn(0f, 1f)
            val remain = 1f - u
            val rate = when (spinKind) {
                // Snap into reverse, spinning down: reads back about one beat.
                DjTransitionPlan.SpinKind.BACKSPIN -> -3.0 * remain * remain
                // Platter losing power: pitch and speed fall to a stop.
                DjTransitionPlan.SpinKind.BRAKE -> remain.toDouble()
                // Motor start: 0 → 1.33× → 1, reading exactly as much as was written,
                // so the record lands on its beat with no lag.
                DjTransitionPlan.SpinKind.SPINUP -> (4.0 * u - 3.0 * u * u)
            }
            spinGain = when (spinKind) {
                DjTransitionPlan.SpinKind.BACKSPIN -> remain * kotlin.math.sqrt(remain)
                DjTransitionPlan.SpinKind.BRAKE -> 1f - u * u
                DjTransitionPlan.SpinKind.SPINUP -> minOf(1f, kotlin.math.sqrt(u * 2f))
            }
            spinPos = (spinPos + rate).mod(historyLength.toDouble())
        } else spinGain = 1f
        historyWrite = (historyWrite + 1) % historyLength
        for (b in 0..3) gain[b] += gainStep[b]
        dry += dryStep
        send += sendStep
        feedback += feedbackStep
        wet += wetStep
        for (k in 0..3) stem[k] += stemStep[k]
        sample += sampleStep
        echoWrite = (echoWrite + 1) % echoLength
    }

    /** Zavalishin topology-preserving SVF step. */
    private fun svf(s: Svf, c: Coefficients, x: Double, high: Boolean): Double {
        val v3 = x - s.ic2
        val v1 = c.a1 * s.ic1 + c.a2 * v3
        val v2 = s.ic2 + c.a2 * s.ic1 + c.a3 * v3
        s.ic1 = 2 * v1 - s.ic1
        s.ic2 = 2 * v2 - s.ic2
        return if (high) x - c.k * v1 - v2 else v2
    }
}
