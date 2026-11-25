/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Separated stems over one media range of a deck: drums, bass line, vocals and the
 * rest, 16-bit interleaved at the deck's own sample rate. The processor mixes them
 * as `original + Σ (gain − 1) · stem`, so at unity gains the deck plays its
 * original audio sample for sample and separation artefacts are heard only on a
 * stem that is actually turned down.
 */
class StemWindow(
    /** Media position of frame 0. */
    val startMs: Double,
    val rate: Int,
    val channels: Int,
    val frames: Int,
    /** [DRUMS], [BASS], [VOCALS], [OTHER], each `frames × channels` interleaved. */
    val stems: Array<ShortArray>,
    /** Which separator made them ("hpss", "onnx:<model>"). */
    val source: String,
) {
    val endMs: Double get() = startMs + frames * 1000.0 / rate

    companion object {
        const val DRUMS = 0
        const val BASS = 1
        const val VOCALS = 2
        const val OTHER = 3
    }
}

/** A short stereo clip (an incoming vocal hook) voiced on another deck. */
class StemClip(val rate: Int, val channels: Int, val frames: Int, val pcm: ShortArray, val source: String)

/** Splits PCM into the four [StemWindow] stems. Runs on a background thread. */
interface StemSeparator {
    val name: String
    /** [pcm] is `frames × channels` interleaved floats; returns four arrays of the same shape. */
    fun separate(pcm: FloatArray, frames: Int, rate: Int, channels: Int, shouldCancel: () -> Boolean = { false }): Array<FloatArray>?
}

internal fun toShorts(x: FloatArray): ShortArray = ShortArray(x.size) {
    (x[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
}

/**
 * Model-free separation for both flavours: harmonic/percussive separation by median
 * filtering (Fitzgerald 2010) with soft masks, then the harmonic part split by
 * frequency (bass line below ~150–250 Hz) and by how centred it is between the
 * channels (lead vocals are almost always mixed dead centre; Avendano & Jot 2004).
 *
 * This is an approximation, not a source-separation model: drums are "what is
 * transient", vocals are "what is sustained, centred and in the voice range", so a
 * centred synth lead lands in vocals and sustained 808s in the bass line. It is good
 * enough to take a groove out from under a record or hear only its hats; a model
 * behind [StemSeparator] does better.
 */
class HpssStemSeparator(
    private val fftSize: Int = 2048,
    private val kernel: Int = 15,
) : StemSeparator {
    override val name = "hpss"

    private val hop = fftSize / 4
    private val bins = fftSize / 2 + 1
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / fftSize)).toFloat() }
    // Hann analysis and synthesis at 75 % overlap sum to 1.5.
    private val norm = 1f / 1.5f

    override fun separate(pcm: FloatArray, frames: Int, rate: Int, channels: Int, shouldCancel: () -> Boolean): Array<FloatArray>? {
        if (frames < fftSize || channels < 1) return null
        val ch = channels
        val fft = Fft(fftSize)
        // Frames centred every hop from 0; enough of them that the tail overlaps fully.
        val total = frames / hop + 4
        val half = kernel / 2
        val hzPerBin = rate.toFloat() / fftSize
        // Bass below 150 Hz, voice range from 250 Hz, crossfaded between; above 6 kHz
        // centred material is mostly cymbals and air, so the voice weight eases off.
        val low = FloatArray(bins) { k -> val hz = k * hzPerBin; ((250f - hz) / 100f).coerceIn(0f, 1f) }
        val voice = FloatArray(bins) { k ->
            val hz = k * hzPerBin
            val rise = 1f - low[k]
            if (hz <= 6000f) rise else rise * (1f - .7f * ((hz - 6000f) / 6000f).coerceIn(0f, 1f))
        }
        val out = Array(4) { FloatArray(frames * ch) }
        // Chunks of STFT frames with a median-kernel halo each side: memory is one chunk,
        // not the whole spectrogram (~110 MB for 45 s otherwise).
        val chunk = 192
        val span = chunk + 2 * half
        val re = Array(ch) { Array(span) { FloatArray(bins) } }
        val im = Array(ch) { Array(span) { FloatArray(bins) } }
        val mag = Array(span) { FloatArray(bins) }
        val bufR = FloatArray(fftSize)
        val bufI = FloatArray(fftSize)
        val scratch = FloatArray(kernel)
        val masks = Array(4) { FloatArray(bins) }
        var f0 = 0
        while (f0 < total) {
            if (shouldCancel()) return null
            val f1 = minOf(total, f0 + chunk)
            val h0 = f0 - half
            val count = (f1 + half) - h0
            for (j in 0 until count) {
                val f = h0 + j
                for (c in 0 until ch) {
                    if (f < 0 || f >= total) { re[c][j].fill(0f); im[c][j].fill(0f); continue }
                    val start = f * hop - fftSize / 2
                    for (i in 0 until fftSize) {
                        val n = start + i
                        bufR[i] = if (n in 0 until frames) pcm[n * ch + c] * window[i] else 0f
                        bufI[i] = 0f
                    }
                    fft.transform(bufR, bufI)
                    System.arraycopy(bufR, 0, re[c][j], 0, bins)
                    System.arraycopy(bufI, 0, im[c][j], 0, bins)
                }
                // Masks from the mid (mono) magnitude so both channels stay coherent.
                for (k in 0 until bins) {
                    var sr = 0f; var si = 0f
                    for (c in 0 until ch) { sr += re[c][j][k]; si += im[c][j][k] }
                    mag[j][k] = sqrt(sr * sr + si * si) / ch
                }
            }
            for (f in f0 until f1) {
                val j = f - h0
                for (k in 0 until bins) {
                    var n = 0
                    for (d in -half..half) { val g = f + d; if (g in 0 until total) scratch[n++] = mag[j + d][k] }
                    val harm = median(scratch, n)
                    n = 0
                    for (d in -half..half) { val q = k + d; if (q in 0 until bins) scratch[n++] = mag[j][q] }
                    val perc = median(scratch, n)
                    val h2 = harm * harm
                    val p2 = perc * perc
                    val sum = h2 + p2 + 1e-12f
                    val mh = h2 / sum
                    val mp = p2 / sum
                    // Centredness: 1 when both channels carry the same bin, 0 when one side only.
                    val centre = if (ch < 2) .5f else {
                        val lr = re[0][j][k]; val li = im[0][j][k]
                        val rr = re[1][j][k]; val ri = im[1][j][k]
                        val x = lr * rr + li * ri
                        val y = li * rr - lr * ri
                        (2f * sqrt(x * x + y * y) / (lr * lr + li * li + rr * rr + ri * ri + 1e-12f)).coerceIn(0f, 1f)
                    }
                    val c4 = centre * centre * centre * centre
                    masks[StemWindow.DRUMS][k] = mp
                    masks[StemWindow.BASS][k] = mh * low[k]
                    masks[StemWindow.VOCALS][k] = mh * voice[k] * c4
                    masks[StemWindow.OTHER][k] = (mh * (1f - low[k] - voice[k] * c4)).coerceAtLeast(0f)
                }
                val start = f * hop - fftSize / 2
                val scale = norm / fftSize
                for (s in 0 until 4) for (c in 0 until ch) {
                    val m = masks[s]
                    val r = re[c][j]
                    val i = im[c][j]
                    // Inverse by conjugation: ifft(X) = conj(fft(conj(X))) / n, Hermitian-filled.
                    for (k in 0 until bins) { bufR[k] = r[k] * m[k]; bufI[k] = -i[k] * m[k] }
                    for (k in bins until fftSize) { val q = fftSize - k; bufR[k] = bufR[q]; bufI[k] = -bufI[q] }
                    fft.transform(bufR, bufI)
                    val dst = out[s]
                    for (n in 0 until fftSize) {
                        val at = start + n
                        if (at in 0 until frames) dst[at * ch + c] += bufR[n] * scale * window[n]
                    }
                }
            }
            f0 = f1
        }
        return out
    }

    private fun median(a: FloatArray, n: Int): Float {
        // Insertion sort: n ≤ 15.
        for (i in 1 until n) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
            a[j + 1] = v
        }
        return if (n == 0) 0f else a[n / 2]
    }
}

/** In-place radix-2 complex FFT (forward, e^{-iωt}). */
internal class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { kotlin.math.sin(-2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init { require(n > 1 && n and (n - 1) == 0) }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val tr = re[l] * cosT[k] - im[l] * sinT[k]
                    val ti = re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
