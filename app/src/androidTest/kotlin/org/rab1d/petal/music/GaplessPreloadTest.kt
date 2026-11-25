/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.playback.CrossfadePlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GaplessPreloadTest {
    @Test
    fun reusedDeckStaysAtStartUntilPromoted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.cacheDir, "gapless-preload-test.wav")
        val pcmBytes = 44_100 * 2 * 4
        audio.writeBytes(ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(44_100); putInt(88_200)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmBytes)
        }.array())
        fun item(key: String) = MediaItem.Builder()
            .setMediaId(key).setUri(audio.toURI().toString()).build()
        lateinit var player: CrossfadePlayer
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun awaitReady(key: String? = null) {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                var ready = false
                onMain {
                    ready = if (key == null) player.playbackState == Player.STATE_READY
                    else player.hasPreloaded(key)
                }
                if (ready) return
                Thread.sleep(20)
            }
            throw AssertionError("Deck did not become ready: $key")
        }
        onMain {
            player = CrossfadePlayer(context)
            player.setMediaItem(item("first"))
            player.play()
        }
        try {
            awaitReady()
            // Two handoffs exercise both the unused deck and a previously
            // playing deck whose stop() retains playWhenReady.
            for (key in listOf("second", "third")) {
                onMain {
                    val next = item(key)
                    player.addMediaItem(next)
                    player.preload(next)
                }
                awaitReady(key)
                Thread.sleep(600)
                onMain {
                    assertTrue(player.promotePreloaded())
                    assertEquals(key, player.currentMediaItem?.mediaId)
                    assertTrue("Preload advanced silently: ${player.currentPosition}",
                        player.currentPosition < 150)
                }
                val playDeadline = System.currentTimeMillis() + 5_000
                var advanced = false
                while (!advanced && System.currentTimeMillis() < playDeadline) {
                    Thread.sleep(20)
                    onMain { advanced = player.currentPosition >= 150 }
                }
                onMain {
                    assertTrue("Promoted deck never played: state=${player.playbackState} " +
                        "error=${player.playerError}", advanced)
                }
            }
        } finally {
            onMain { player.release() }
            audio.delete()
        }
    }
}
