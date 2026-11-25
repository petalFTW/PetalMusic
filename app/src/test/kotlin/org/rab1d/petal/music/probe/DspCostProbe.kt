/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.probe

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Test
import org.rab1d.petal.music.playback.EqBendProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** How much CPU the always-on deck chain costs per minute of audio (JVM, this machine). */
class DspCostProbe {
    @Test fun eqBendCostPerMinute() {
        val p = EqBendProcessor().apply { configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); flush() }
        val frames = 4096
        val block = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames * 2) block.putShort((8000 * sin(i * .013)).toInt().toShort())
        fun minute() {
            repeat(48000 * 60 / frames) {
                block.rewind(); p.queueInput(block); p.output
            }
        }
        repeat(3) { minute() } // JIT warm-up
        val t = System.nanoTime(); repeat(3) { minute() }
        val msPerMinute = (System.nanoTime() - t) / 3e6
        println("EqBendProcessor: %.0f ms CPU per minute of 48 kHz stereo (%.2f %% of one core)".format(msPerMinute, msPerMinute / 600))
    }
}
