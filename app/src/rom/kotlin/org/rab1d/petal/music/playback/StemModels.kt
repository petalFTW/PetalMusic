/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

/**
 * rom: a waveform source-separation model when one is installed, otherwise the
 * model-free separator. Install by placing two files in `files/stems/`:
 *
 *  - `separator.onnx`: input `[1, 2, segment]` float32 stereo at `sampleRate`,
 *    output `[1, S, 2, segment]` (S sources, in the order given by `sources`);
 *  - `separator.json`: `{"sampleRate":44100,"segment":343980,"sources":["drums","bass","other","vocals"]}`
 *    (the HTDemucs order and segment shown), optionally `"overlap": 0.25`.
 *
 * Segments overlap and are crossfaded linearly. Audio at another sample rate, or a
 * failing session, falls back to [HpssStemSeparator] for that call.
 */
internal object StemModels {
    fun separator(context: Context): StemSeparator {
        val dir = File(context.filesDir, "stems")
        val model = File(dir, "separator.onnx")
        val config = File(dir, "separator.json")
        val fallback = HpssStemSeparator()
        if (!model.isFile || !config.isFile) return fallback
        return runCatching { OnnxStemSeparator(model, JSONObject(config.readText()), fallback) }
            .onFailure { AutomixLog.i("PetalStems", "stem model not loaded: ${it.message}") }
            .getOrDefault(fallback)
    }
}

private class OnnxStemSeparator(model: File, config: JSONObject, private val fallback: StemSeparator) : StemSeparator {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model.absolutePath, OrtSession.SessionOptions())
    private val rate = config.getInt("sampleRate")
    private val segment = config.getInt("segment")
    private val overlap = (segment * config.optDouble("overlap", .25)).toInt().coerceIn(0, segment / 2)
    private val order: IntArray = config.getJSONArray("sources").let { a ->
        val names = List(a.length()) { a.getString(it).lowercase() }
        intArrayOf(names.indexOf("drums"), names.indexOf("bass"), names.indexOf("vocals"), names.indexOf("other"))
    }
    override val name = "onnx:${model.nameWithoutExtension}"

    init { require(order.all { it >= 0 }) { "sources must name drums, bass, vocals and other" } }

    override fun separate(pcm: FloatArray, frames: Int, rate: Int, channels: Int, shouldCancel: () -> Boolean): Array<FloatArray>? {
        if (rate != this.rate || channels != 2) return fallback.separate(pcm, frames, rate, channels, shouldCancel)
        return runCatching { run(pcm, frames, shouldCancel) }
            .onFailure { AutomixLog.i("PetalStems", "stem model failed, using hpss: ${it.message}") }
            .getOrNull() ?: fallback.separate(pcm, frames, rate, channels, shouldCancel)
    }

    private fun run(pcm: FloatArray, frames: Int, shouldCancel: () -> Boolean): Array<FloatArray>? {
        val out = Array(4) { FloatArray(frames * 2) }
        val weight = FloatArray(frames)
        val step = segment - overlap
        val input = FloatBuffer.allocate(2 * segment)
        var start = 0
        val inputName = session.inputNames.first()
        while (start < frames) {
            if (shouldCancel()) return null
            input.clear()
            for (c in 0 until 2) for (i in 0 until segment) {
                val n = start + i
                input.put(if (n < frames) pcm[n * 2 + c] else 0f)
            }
            input.rewind()
            OnnxTensor.createTensor(env, input, longArrayOf(1, 2, segment.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val y = (result[0].value as Array<Array<Array<FloatArray>>>)[0]
                    for (i in 0 until segment) {
                        val n = start + i
                        if (n >= frames) break
                        // Linear crossfade over the overlaps.
                        val w = minOf(1f, if (start > 0 && overlap > 0) (i + 1f) / overlap else 1f,
                            if (start + step < frames && overlap > 0) (segment - i).toFloat() / overlap else 1f)
                        weight[n] += w
                        for (s in 0 until 4) for (c in 0 until 2) out[s][n * 2 + c] += w * y[order[s]][c][i]
                    }
                }
            }
            start += step
        }
        for (n in 0 until frames) {
            val w = weight[n].takeIf { it > 1e-6f } ?: continue
            for (s in 0 until 4) for (c in 0 until 2) out[s][n * 2 + c] /= w
        }
        return out
    }
}
