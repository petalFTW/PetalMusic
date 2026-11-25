/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop

import org.rab1d.petal.music.desktop.link.DeviceLinkServer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpscaleEndpointTest {
    @Test
    fun `upscale endpoint returns a full wav for a stereo chunk`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = DeviceLinkServer()
        server.onLog = { println("[test] $it") }
        assertTrue(server.start(port))
        try {
            val frames = 44_100 * 10
            val pcm = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * 2) {
                val v = (kotlin.math.sin(2.0 * Math.PI * 440.0 * (i / 2) / 44_100.0) * 8000).toInt()
                pcm.putShort(v.toShort())
            }
            val conn = (URL("http://127.0.0.1:$port/v1/upscale?rate=44100&channels=2&bits=16&width=0.5").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(pcm.array().size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            conn.outputStream.use { it.write(pcm.array()) }
            assertEquals(200, conn.responseCode)
            assertEquals("Petal DSP", conn.getHeaderField("X-Petal-Engine"))
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            val bytes = out.toByteArray()
            assertTrue(bytes.size > 44)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        } finally {
            server.stop()
        }
    }
}
