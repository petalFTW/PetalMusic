/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.io.BufferedInputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signal facts used to route a download before any model runs. This is not a
 * codec detector: it measures the spectrum that is actually present, so a FLAC
 * that is really a transcoded low-bandwidth source is routed like the lossy
 * file it is.
 */
data class AudioProfile(
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    /** Highest frequency still carrying programme material after the spectral drop. */
    val cutoffHz: Double,
    val hf16Db: Double,
    val hf18Db: Double,
    val hf20Db: Double,
    /** Pearson correlation of L/R over the analysed frames. */
    val lrCorrelation: Double,
    /** Side energy relative to mid energy, in dB. */
    val sideMidDb: Double,
    val rmsDb: Double,
    val crestDb: Double,
    /** Sample peak before any processing; true peak is not oversampled here. */
    val peak: Double,
    val clippedSamples: Long,
    val character: AudioCharacter,
) {
    val isFullBand: Boolean get() = character == AudioCharacter.FULL_BAND
    val isSilent: Boolean get() = character == AudioCharacter.SILENT

    /** The low band is preserved below this point; Apollo is then blended above it. */
    val apolloCutoffHz: Double
        get() = cutoffHz.coerceIn(5_000.0, sampleRate / 2.0 - 500.0)

    fun summary(): String = buildString {
        append(character)
        append(" · cutoff ")
        append(cutoffHz.roundToInt())
        append(" Hz · HF18 ")
        append("%.0f".format(hf18Db))
        append(" dB · L/R ")
        append("%.2f".format(lrCorrelation))
        append(" · side/mid ")
        append("%.1f".format(sideMidDb))
        append(" dB · crest ")
        append("%.1f".format(crestDb))
        append(" dB")
        if (clippedSamples > 0) append(" · clipped $clippedSamples")
    }
}

enum class AudioCharacter {
    SILENT,
    SEVERELY_BAND_LIMITED,
    BAND_LIMITED,
    FULL_BAND,
}

internal object AudioAnalyzer {
    private const val FFT_SIZE = 4096
    private const val HOP = FFT_SIZE / 2
    private const val BLOCK = 8192
    private const val MAX_WINDOWS = 240
    private const val EPS = 1e-12

    fun analyze(
        pcm: File,
        frames: Long,
        sampleRate: Int,
        channels: Int,
        shouldCancel: () -> Boolean = { false },
    ): AudioProfile {
        require(sampleRate > 0 && channels > 0 && frames > 0)
        val fft = Radix2Fft(FFT_SIZE)
        val window = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
        }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val mono = FloatArray(FFT_SIZE)
        var monoFill = 0
        val spectrum = DoubleArray(FFT_SIZE / 2 + 1)
        var spectrumWindows = 0
        var sumL2 = 0.0
        var sumR2 = 0.0
        var sumLR = 0.0
        var peak = 0.0
        var clipped = 0L
        var processedFrames = 0L

        val data = Array(channels) { FloatArray(BLOCK) }
        val bytes = ByteArray(BLOCK * channels * 2)
        BufferedInputStream(pcm.inputStream(), 1 shl 16).use { input ->
            var remaining = frames
            while (remaining > 0) {
                if (shouldCancel()) throw UpscaleCancelledException()
                val count = min(BLOCK.toLong(), remaining).toInt()
                Pcm16Io.readChannels(input, bytes, data, count, 1f)
                for (i in 0 until count) {
                    val l = data[0][i]
                    val r = if (channels > 1) data[1][i] else l
                    sumL2 += l.toDouble() * l
                    sumR2 += r.toDouble() * r
                    sumLR += l.toDouble() * r
                    val mid = (l + r) * 0.5f
                    val p = max(abs(l), abs(r)).toDouble()
                    if (p > peak) peak = p
                    if (abs(l) >= 0.999f) clipped++
                    if (abs(r) >= 0.999f) clipped++
                    mono[monoFill++] = mid
                    if (monoFill == FFT_SIZE) {
                        if (spectrumWindows < MAX_WINDOWS) {
                            for (b in 0 until FFT_SIZE) {
                                re[b] = mono[b] * window[b]
                                im[b] = 0f
                            }
                            fft.transform(re, im)
                            for (b in 0..FFT_SIZE / 2) {
                                spectrum[b] += re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
                            }
                            spectrumWindows++
                        }
                        System.arraycopy(mono, HOP, mono, 0, FFT_SIZE - HOP)
                        monoFill = FFT_SIZE - HOP
                    }
                }
                processedFrames += count
                remaining -= count
            }
        }

        val total = processedFrames.toDouble().coerceAtLeast(1.0)
        val rms = sqrt((sumL2 + sumR2) / (2.0 * total))
        val rmsDb = 20.0 * log10(rms.coerceAtLeast(EPS))
        val crestDb = 20.0 * log10(peak.coerceAtLeast(EPS) / rms.coerceAtLeast(EPS))
        val correlation = if (sumL2 > EPS && sumR2 > EPS) {
            (sumLR / sqrt(sumL2 * sumR2)).coerceIn(-1.0, 1.0)
        } else 1.0
        val midEnergy = (sumL2 + 2 * sumLR + sumR2) / 4.0
        val sideEnergy = (sumL2 - 2 * sumLR + sumR2) / 4.0
        val sideMidDb = 10.0 * log10(sideEnergy.coerceAtLeast(EPS) / midEnergy.coerceAtLeast(EPS))

        if (spectrumWindows == 0 || sumL2 + sumR2 < EPS) {
            return AudioProfile(
                sampleRate, channels, processedFrames, 0.0,
                -120.0, -120.0, -120.0, correlation, sideMidDb,
                rmsDb, crestDb, peak, clipped, AudioCharacter.SILENT,
            )
        }

        val bins = FFT_SIZE / 2 + 1
        val avg = DoubleArray(bins) { spectrum[it] / spectrumWindows }
        val totalPower = avg.drop(1).sum().coerceAtLeast(EPS)
        val hf16 = ratioDb(avg, sampleRate, 16_000.0, totalPower)
        val hf18 = ratioDb(avg, sampleRate, 18_000.0, totalPower)
        val hf20 = ratioDb(avg, sampleRate, 20_000.0, totalPower)
        val cutoff = estimateCutoff(avg, sampleRate, FFT_SIZE)
        val character = when {
            cutoff < 14_000.0 -> AudioCharacter.SEVERELY_BAND_LIMITED
            cutoff < 19_000.0 -> AudioCharacter.BAND_LIMITED
            else -> AudioCharacter.FULL_BAND
        }
        return AudioProfile(
            sampleRate, channels, processedFrames, cutoff,
            hf16, hf18, hf20, correlation, sideMidDb,
            rmsDb, crestDb, peak, clipped, character,
        )
    }

    private fun ratioDb(power: DoubleArray, sampleRate: Int, fromHz: Double, total: Double): Double {
        val binHz = sampleRate.toDouble() / ((power.size - 1) * 2)
        val first = (fromHz / binHz).roundToInt().coerceIn(1, power.lastIndex)
        val hf = power.copyOfRange(first, power.size).sum()
        return 10.0 * log10(hf.coerceAtLeast(EPS) / total)
    }

    /**
     * Locate the low-pass edge by the largest 1 kHz power drop, not by a fixed
     * HF threshold. A natural gentle roll-off stays FULL_BAND; a codec cliff
     * at 16 or 18 kHz is routed to restoration. Adapted from the UniverSR cutoff
     * estimator, which uses the same measurement.
     */
    internal fun estimateCutoff(power: DoubleArray, sampleRate: Int, fftSize: Int): Double {
        val bins = power.size
        if (bins < 8 || power.sum() < EPS) return 0.0
        val db = DoubleArray(bins) { 10.0 * log10(power[it].coerceAtLeast(EPS)) }
        val smoothed = DoubleArray(bins)
        val radius = 2
        for (i in 0 until bins) {
            var sum = 0.0
            var n = 0
            for (j in max(0, i - radius)..min(bins - 1, i + radius)) {
                sum += db[j]
                n++
            }
            smoothed[i] = sum / n
        }
        val ref = smoothed.copyOfRange(
            (500.0 / (sampleRate.toDouble() / fftSize)).roundToInt().coerceIn(1, bins - 1),
            (4_000.0 / (sampleRate.toDouble() / fftSize)).roundToInt().coerceIn(2, bins),
        ).maxOrNull() ?: return 0.0
        val window = max(2, (1_000.0 * fftSize / sampleRate).roundToInt())
        val minBin = (2_000.0 * fftSize / sampleRate).roundToInt().coerceIn(1, bins - 1)
        var best = -Double.MAX_VALUE
        var bestAt = -1
        for (i in minBin until bins - window) {
            var before = 0.0
            var after = 0.0
            for (j in i - window until i) before += smoothed[j]
            for (j in i until i + window) after += smoothed[j]
            val drop = (before - after) / window
            if (drop > best) {
                best = drop
                bestAt = i
            }
        }
        if (bestAt < 0 || best < 20.0) return sampleRate / 2.0
        var edge = bestAt
        val floor = ref - 6.0
        while (edge > minBin && smoothed[edge] < floor) edge--
        return (edge.toDouble() * sampleRate / fftSize).coerceIn(2_000.0, sampleRate / 2.0)
    }
}

/** Iterative radix-2 FFT; power is all the analyser needs, so scaling is irrelevant. */
internal class Radix2Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(-2.0 * PI * it / n).toFloat() }
    private val reverse = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n > 1 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size >= n && im.size >= n)
        for (i in 0 until n) {
            val j = reverse[i]
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
                    val tpre = re[l] * cosTable[k] - im[l] * sinTable[k]
                    val tpim = re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}
