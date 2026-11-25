/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Runs [EqMixer] inside ExoPlayer's audio chain.
 *
 * Media3 only runs a custom [androidx.media3.common.audio.AudioProcessorChain]
 * on the integer path (float output replaces the chain), so this accepts the
 * integer encodings a decoder can emit, converts to float for the mixer, and
 * writes the result back in the same encoding.
 */
class EqCrossfadeProcessor(
    private val mixer: EqMixer,
) : BaseAudioProcessor() {

    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        android.util.Log.i(
            "PetalEqMix",
            "configure enc=${inputAudioFormat.encoding} rate=${inputAudioFormat.sampleRate} " +
                "ch=${inputAudioFormat.channelCount}",
        )
        if (!isSupported(inputAudioFormat.encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        mixer.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val enc = inputAudioFormat.encoding
        val channels = inputAudioFormat.channelCount
        val bytesPerSample = bytesPerSample(enc) ?: return
        val count = inputBuffer.remaining() / bytesPerSample
        val frames = count / channels
        if (frames <= 0) return

        if (floats.size < count) floats = FloatArray(count)
        readToFloat(inputBuffer, floats, count, enc)
        mixer.process(floats, frames)

        val out = replaceOutputBuffer(count * bytesPerSample)
        writeFromFloat(floats, count, out, enc)
        // Typed views (asShortBuffer/asFloatBuffer/...) keep independent
        // positions, so advance the ByteBuffer explicitly before flipping.
        out.position(count * bytesPerSample)
        out.flip()
    }

    // ExoPlayer flushes the audio sink as it advances to the next media item.
    // The mixer must survive that flush — the crossfade is precisely what
    // bridges the two items — so onFlush deliberately leaves EqMixer alone.
    // Only a full reset (new session/format) clears the retained tail.

    override fun onReset() {
        mixer.reset()
    }

    private fun isSupported(encoding: Int): Boolean = bytesPerSample(encoding) != null

    private fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> null
    }

    private fun readToFloat(input: ByteBuffer, out: FloatArray, count: Int, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = input.asShortBuffer()
                for (i in 0 until count) out[i] = sb.get(i) / 32768f
                input.position(input.position() + count * 2)
            }
            C.ENCODING_PCM_FLOAT -> {
                val fb = input.asFloatBuffer()
                fb.get(out, 0, count)
                input.position(input.position() + count * 4)
            }
            C.ENCODING_PCM_32BIT -> {
                val ib = input.asIntBuffer()
                for (i in 0 until count) out[i] = ib.get(i) / 2147483648f
                input.position(input.position() + count * 4)
            }
            C.ENCODING_PCM_24BIT -> {
                val pos = input.position()
                for (i in 0 until count) {
                    val b = pos + i * 3
                    val v = ((input.get(b + 2).toInt() shl 16) or
                        ((input.get(b + 1).toInt() and 0xFF) shl 8) or
                        (input.get(b).toInt() and 0xFF))
                    // sign-extend 24-bit
                    out[i] = ((v shl 8) shr 8) / 8388608f
                }
                input.position(pos + count * 3)
            }
        }
    }

    private fun writeFromFloat(inF: FloatArray, count: Int, out: ByteBuffer, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = out.asShortBuffer()
                for (i in 0 until count) {
                    sb.put((inF[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                out.asFloatBuffer().put(inF, 0, count)
            }
            C.ENCODING_PCM_32BIT -> {
                val ib = out.asIntBuffer()
                for (i in 0 until count) {
                    ib.put((inF[i].coerceIn(-1f, 1f) * 2147483647f).toLong().toInt())
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until count) {
                    val v = (inF[i].coerceIn(-1f, 1f) * 8388607f).toInt()
                    out.put((v and 0xFF).toByte())
                    out.put(((v shr 8) and 0xFF).toByte())
                    out.put(((v shr 16) and 0xFF).toByte())
                }
            }
        }
    }
}
