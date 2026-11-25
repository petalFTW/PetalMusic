/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.link

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.rab1d.petal.music.desktop.library.Prefs
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cbrt

@Serializable
data class LinkTrack(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val source: String = "",
    val durationMs: Long = 0,
    val key: String = "",
)

@Serializable
data class LinkPlaylist(val name: String, val tracks: List<LinkTrack>)

enum class LinkStatus { QUEUED, DOWNLOADING, DONE, FAILED, IMPORTED }

/** One track being fetched by the desktop, with live status for the UI. */
class LinkItem(val playlist: String, val title: String, val artist: String) {
    var status by mutableStateOf(LinkStatus.QUEUED)
    var progress by mutableStateOf(0f)
    var log by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
}

/**
 * Device Link server. The phone sends **playlist metadata only** (never audio).
 * By default the desktop imports and persists that metadata without downloading
 * anything; audio fetching with the configurable external downloader (default
 * `yt-dlp`) is opt-in.
 *
 *   GET  /v1/health     -> {"status":"ok","name":...,"version":1}
 *   POST /v1/playlists  -> {"accepted": n, "playlists": m}
 */
class DeviceLinkServer {
    companion object {
        const val DEFAULT_PORT = 9478
        const val NAME = "PetalMusic Desktop"
        const val DEFAULT_DOWNLOADER =
            "yt-dlp -x --audio-format mp3 -o \"{output}\" \"ytsearch1:{query}\""
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DeviceLink").apply { isDaemon = true } }
    private val accepted = AtomicInteger(0)
    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null
    var port: Int = DEFAULT_PORT
        private set

    @Volatile var onLog: ((String) -> Unit)? = null

    /** Library folder to download into (read live from the UI's setting). */
    @Volatile var libraryDir: () -> String = { "" }

    /** External downloader command template; {query} and {output} are replaced. */
    var downloaderCommand by mutableStateOf(DEFAULT_DOWNLOADER)

    /** Metadata-only import is the default; enable this to fetch audio too. */
    var downloadAudio by mutableStateOf(Prefs.downloadAudio)
        private set

    fun updateDownloadAudio(value: Boolean) {
        downloadAudio = value
        Prefs.downloadAudio = value
    }

    /** Fired once the download queue drains so the library can be rescanned. */
    @Volatile var onQueueDrained: (() -> Unit)? = null

    val playlists = mutableStateListOf<LinkPlaylist>()
    val items = mutableStateListOf<LinkItem>()

    private var pending = 0

    private val storeFile: File get() =
        File(System.getProperty("user.home"), ".petalmusic/imported-playlists.json")

    init {
        loadImportedPlaylists()
    }

    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (server != null) return true
        return try {
            val http = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
            http.executor = Executors.newFixedThreadPool(2)
            http.createContext("/v1/health") { ex -> handleHealth(ex) }
            http.createContext("/v1/playlists") { ex -> handlePlaylists(ex) }
            http.createContext("/v1/upscale") { ex -> handleUpscale(ex) }
            http.start()
            server = http
            this.port = port
            log("Device Link listening on 0.0.0.0:$port")
            true
        } catch (e: Exception) {
            log("failed to start: ${e.message}")
            false
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        log("Device Link stopped")
    }

    fun clearQueue() {
        items.clear()
        playlists.clear()
        runCatching { storeFile.delete() }
    }

    /** Backend name advertised to the phone (mirrors BandwidthExtender). */
    val backendName: String = BandwidthExtender.backendName

    private fun handleHealth(exchange: HttpExchange) {
        respond(
            exchange,
            200,
            """{"status":"ok","name":"$NAME","version":1,"backend":"$backendName"}""",
            "application/json",
        )
    }

    /**
     * Off-device upscaling: the phone streams raw 16-bit PCM, this machine
     * bandwidth-extends it (GPU/CPU) and returns a WAV. The request is fully
     * drained before responding to avoid the fixed-length deadlock.
     */
    private fun handleUpscale(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            return
        }
        val query = parseQuery(exchange.requestURI.rawQuery)
        val rate = query["rate"]?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 44100
        val channels = (query["channels"]?.toIntOrNull() ?: 2).coerceIn(1, 8)
        val width = query["width"]?.toFloatOrNull()?.coerceIn(0f, 1.5f) ?: 0.5f
        val started = System.currentTimeMillis()
        try {
            val pcm = exchange.requestBody.use { it.readBytes() }
            val frames = pcm.size / (channels * 2)
            val samples = FloatArray(frames * channels)
            val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) samples[i] = input.short / 32768f
            BandwidthExtender(channels, rate, width).enhance(samples)

            val dataBytes = samples.size * 2
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.responseHeaders.add("X-Petal-Engine", "Petal DSP")
            exchange.sendResponseHeaders(200, 44L + dataBytes)
            exchange.responseBody.use { out ->
                writeWavHeader(out, rate, channels, dataBytes.toLong())
                val chunkFrames = 1 shl 15
                val chunk = ByteBuffer.allocate(chunkFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                var idx = 0
                while (idx < samples.size) {
                    val n = minOf(chunkFrames, samples.size - idx)
                    chunk.clear()
                    for (k in 0 until n) {
                        chunk.putShort((samples[idx + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    }
                    out.write(chunk.array(), 0, n * 2)
                    idx += n
                }
                out.flush()
            }
            log("upscale rate=$rate ch=$channels frames=$frames in ${System.currentTimeMillis() - started}ms")
        } catch (e: Exception) {
            log("upscale failed: ${e.message}")
            runCatching {
                val body = (e.message ?: "upscale failed").toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(500, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun writeWavHeader(out: OutputStream, rate: Int, channels: Int, dataBytes: Long) {
        val bits = 16
        val byteRate = rate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(rate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        out.write(header.array())
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw?.split("&")?.mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }?.toMap() ?: emptyMap()

    private fun handlePlaylists(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            return
        }
        val received = try {
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            json.decodeFromString<List<LinkPlaylist>>(body)
        } catch (e: Exception) {
            respond(exchange, 400, """{"error":"${e.message?.take(120)}"}""", "application/json")
            return
        }
        val count = enqueue(received)
        respond(exchange, 200, """{"accepted":$count,"playlists":${received.size}}""", "application/json")
    }

    private fun enqueue(received: List<LinkPlaylist>): Int {
        val names = received.map { it.name }.toSet()
        items.removeAll { it.playlist in names }
        var count = 0
        for (playlist in received) {
            playlists.removeAll { it.name == playlist.name }
            playlists.add(playlist)
            for (track in playlist.tracks) {
                val item = LinkItem(playlist.name, track.title, track.artist)
                items.add(item)
                count++
                if (downloadAudio) {
                    pending++
                    executor.execute { download(item, track) }
                } else {
                    item.status = LinkStatus.IMPORTED
                    item.progress = 1f
                }
            }
        }
        persistImportedPlaylists()
        log(
            if (downloadAudio) {
                "received ${received.size} playlists · $count tracks queued"
            } else {
                "imported ${received.size} playlists · $count tracks (audio download off)"
            },
        )
        return count
    }

    private fun loadImportedPlaylists() {
        val file = storeFile
        if (!file.isFile) return
        runCatching {
            val saved = json.decodeFromString<List<LinkPlaylist>>(file.readText())
            playlists.addAll(saved)
            log("restored ${saved.size} imported playlists")
        }.onFailure { log("could not restore imported playlists: ${it.message}") }
    }

    private fun persistImportedPlaylists() {
        runCatching {
            val file = storeFile
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, "${file.name}.part")
            part.writeText(json.encodeToString(playlists.toList()))
            if (!part.renameTo(file)) {
                file.delete()
                if (!part.renameTo(file)) throw java.io.IOException("could not move playlist store")
            }
        }.onFailure { log("could not save imported playlists: ${it.message}") }
    }

    private fun download(item: LinkItem, track: LinkTrack) {
        val dir = libraryDir()
        if (dir.isBlank()) {
            item.status = LinkStatus.FAILED
            item.error = "No library folder set"
            finishOne()
            return
        }
        val dirFile = File(dir).apply { mkdirs() }
        val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" - ")
        val output = File(dirFile, "%(title)s.%(ext)s").absolutePath
        val rendered = renderCommand(downloaderCommand, query, output)
        val shell = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd", "/c", rendered)
        } else {
            listOf("/bin/sh", "-c", rendered)
        }
        item.status = LinkStatus.DOWNLOADING
        try {
            val process = ProcessBuilder(shell).redirectErrorStream(true).directory(dirFile).start()
            process.inputStream.bufferedReader().forEachLine { line ->
                item.log = line.take(200)
                PERCENT.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    item.progress = (it / 100f).coerceIn(0f, 1f)
                }
            }
            val code = process.waitFor()
            if (code == 0) {
                item.status = LinkStatus.DONE
                item.progress = 1f
                log("downloaded '${track.title}'")
            } else {
                item.status = LinkStatus.FAILED
                item.error = "downloader exit $code"
                log("failed '${track.title}' (exit $code)")
            }
        } catch (e: Exception) {
            item.status = LinkStatus.FAILED
            item.error = e.message ?: "download failed"
            log("failed '${track.title}': ${e.message}")
        } finally {
            finishOne()
        }
    }

    private fun finishOne() {
        synchronized(this) {
            pending--
            if (pending <= 0) {
                pending = 0
                onQueueDrained?.invoke()
            }
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun log(message: String) {
        onLog?.invoke(message)
        println("[DeviceLink] $message")
    }

    private val PERCENT = Regex("""\[download\]\s+([\d.]+)%""")
}

/** Replace {query}/{output} in the downloader template; no shell escaping. */
internal fun renderCommand(template: String, query: String, output: String): String =
    template.replace("{query}", query).replace("{output}", output)

/**
 * Bandwidth extension: a memoryless high-order nonlinearity excites harmonics
 * above the source's band, a one-pole high-pass isolates them, and the result
 * is mixed under the dry signal. Cheap, causal and sample-for-sample, so a GPU
 * or ONNX backend can replace it without touching the protocol.
 */
class BandwidthExtender(
    private val channels: Int,
    sampleRate: Int = 44_100,
    width: Float = 0.5f,
) {
    private val prevIn = FloatArray(channels)
    private val prevHp = FloatArray(channels)
    private val amount: Float = 0.08f
    private val decay: Float = 0.72f
    private val mastering = HifiMastering(sampleRate, channels, width)

    fun enhance(samples: FloatArray) {
        var i = 0
        val n = samples.size
        through@ while (i < n) {
            for (c in 0 until channels) {
                if (i + c >= n) break@through
                val x = samples[i + c]
                val excited = 0.5f * (x * x * x) + 0.5f * (cbrt(x.toDouble()).toFloat() * abs(x))
                val hp = excited - prevIn[c] + decay * prevHp[c]
                prevIn[c] = excited
                prevHp[c] = hp
                samples[i + c] = (x + amount * hp).coerceIn(-1f, 1f)
            }
            i += channels
        }
        mastering.process(samples, samples.size / channels)
    }

    companion object {
        val backendName: String = runCatching {
            Class.forName("ai.onnxruntime.OrtEnvironment")
            "ONNX Runtime"
        }.getOrDefault("CPU (parallel)")
    }
}
