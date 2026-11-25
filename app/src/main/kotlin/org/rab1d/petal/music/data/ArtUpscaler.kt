/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Album art at 2x: fetches the largest artwork a source serves, then upscales it
 * twofold (separable Lanczos-3 with a light unsharp mask) and caches a JPEG. The
 * cached copy is exposed through a FileProvider so the system media controls,
 * lock screen and SystemUI island can read it, and is what the player shows.
 *
 * Art already at least [SKIP_PX] on its longest side is kept as is, and output is
 * capped at [MAX_PX]: beyond that the system controls only downsample.
 */
object ArtUpscaler {
    private const val TAG = "PetalMusic/Art2x"
    private const val SKIP_PX = 1600
    private const val MAX_PX = 2400
    private const val DIR = "art2x"
    private const val AUTHORITY_SUFFIX = ".art2x"
    private val consumers = listOf("com.android.systemui")

    @Volatile var enabled = true
    private val memory = ConcurrentHashMap<String, Uri>()
    private val pending = ConcurrentHashMap<String, Future<Uri?>>()
    private val worker = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "PetalArt2x").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }
    @Volatile private var app: Context? = null

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /** The 2x copy of [source] if it is already cached; never blocks on work. */
    fun cached(source: String?): Uri? {
        if (!enabled || source == null) return null
        memory[source]?.let { return it }
        val context = app ?: return null
        val file = fileFor(context, source).takeIf { it.exists() && it.length() > 0 } ?: return null
        return expose(context, file).also { memory[source] = it }
    }

    /** Start (or join) the upscale of [source]; [onReady] runs on a worker thread. */
    fun request(source: String?, onReady: ((Uri?) -> Unit)? = null): Future<Uri?>? {
        if (!enabled || source == null) return null
        val context = app ?: return null
        // A 2x Lanczos pass is seconds of CPU per cover: wait until battery saver is off.
        // The original is shown meanwhile, and the copy is made the next time it's seen.
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (power?.isPowerSaveMode == true) return null
        cached(source)?.let { uri -> onReady?.invoke(uri); return null }
        val job = pending.computeIfAbsent(source) {
            worker.submit<Uri?> {
                try { produce(context, source) } finally { pending.remove(source) }
            }
        }
        if (onReady != null) worker.execute { onReady(runCatching { job.get() }.getOrNull()) }
        return job
    }

    /** Block up to [timeoutMs] for the 2x copy; null (use the original) when not ready. */
    fun await(source: String?, timeoutMs: Long): Uri? {
        cached(source)?.let { return it }
        val job = request(source) ?: return cached(source)
        return runCatching { job.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun fileFor(context: Context, source: String): File {
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIR).apply { mkdirs() }, "$name.jpg")
    }

    private fun expose(context: Context, file: File): Uri {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        for (pkg in consumers) runCatching {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return uri
    }

    private fun produce(context: Context, source: String): Uri? {
        val started = android.os.SystemClock.elapsedRealtime()
        val bytes = largest(context, source) ?: return null
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longest = maxOf(original.width, original.height)
        val scale = when {
            longest >= SKIP_PX -> 1.0
            else -> minOf(2.0, MAX_PX.toDouble() / longest)
        }
        val out = if (scale > 1.0) sharpen(lanczos(original, (original.width * scale).roundToInt(),
            (original.height * scale).roundToInt())) else original
        val file = fileFor(context, source)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        if (!tmp.renameTo(file)) return null
        Log.i(TAG, "${original.width}x${original.height} → ${out.width}x${out.height} in " +
            "${android.os.SystemClock.elapsedRealtime() - started}ms ($source)")
        if (out !== original) out.recycle()
        original.recycle()
        return expose(context, file).also { memory[source] = it }
    }

    /** The source's own largest rendition when one exists, else the original. */
    private fun largest(context: Context, source: String): ByteArray? {
        for (candidate in listOf(nativeLarge(source), org.rab1d.petal.music.model.highQualityArtwork(source), source)
            .distinct()) {
            val bytes = runCatching { read(context, candidate) }.getOrNull()
            if (bytes != null && bytes.size > 512) return bytes
        }
        return null
    }

    /** Sources serving fixed sizes: ask for their largest. */
    private fun nativeLarge(uri: String): String = when {
        uri.contains("resources.tidal.com/images/") ->
            uri.replace(Regex("/\\d+x\\d+\\.jpg$"), "/1280x1280.jpg")
        uri.contains("dzcdn.net/images/") ->
            uri.replace(Regex("/\\d+x\\d+-"), "/1000x1000-")
        uri.contains("i.scdn.co/image/ab67616d0000") ->
            uri.replace(Regex("ab67616d0000(4851|1e02)"), "ab67616d0000b273")
        else -> uri
    }

    private fun read(context: Context, uri: String): ByteArray? = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> {
            val connection = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            try {
                if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
            } finally {
                connection.disconnect()
            }
        }
        uri.startsWith("/") -> File(uri).readBytes()
        else -> context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    }

    // ---- resampling -------------------------------------------------------------------

    private fun kernel(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= 3) return 0.0
        val px = PI * x
        return 3 * sin(px) * sin(px / 3) / (px * px)
    }

    /** Separable Lanczos-3, premultiplied-free (album art is opaque). */
    internal fun lanczos(src: Bitmap, w: Int, h: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val input = IntArray(sw * sh).also { src.getPixels(it, 0, sw, 0, 0, sw, sh) }
        // Horizontal pass into float channels.
        val mid = FloatArray(w * sh * 3)
        resample(sw, w) { x, taps, weights ->
            for (y in 0 until sh) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (k in taps.indices) {
                    val p = input[y * sw + taps[k]]
                    val wt = weights[k]
                    r += (p shr 16 and 0xFF) * wt; g += (p shr 8 and 0xFF) * wt; b += (p and 0xFF) * wt
                }
                val o = (y * w + x) * 3
                mid[o] = r.toFloat(); mid[o + 1] = g.toFloat(); mid[o + 2] = b.toFloat()
            }
        }
        val output = IntArray(w * h)
        resample(sh, h) { y, taps, weights ->
            for (x in 0 until w) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (k in taps.indices) {
                    val o = (taps[k] * w + x) * 3
                    val wt = weights[k]
                    r += mid[o] * wt; g += mid[o + 1] * wt; b += mid[o + 2] * wt
                }
                output[y * w + x] = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888)
    }

    private inline fun resample(from: Int, to: Int, apply: (Int, IntArray, DoubleArray) -> Unit) {
        val ratio = from.toDouble() / to
        for (i in 0 until to) {
            val centre = (i + .5) * ratio - .5
            val lo = ceil(centre - 3).toInt()
            val hi = floor(centre + 3).toInt()
            val taps = IntArray(hi - lo + 1) { (lo + it).coerceIn(0, from - 1) }
            val weights = DoubleArray(taps.size) { kernel(centre - (lo + it)) }
            val sum = weights.sum()
            for (k in weights.indices) weights[k] /= sum
            apply(i, taps, weights)
        }
    }

    /** Light unsharp mask (3×3 blur, amount 0.35) to restore edge contrast. */
    internal fun sharpen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val p = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val q = p[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                r += q shr 16 and 0xFF; g += q shr 8 and 0xFF; b += q and 0xFF
            }
            val c = p[y * w + x]
            fun ch(v: Int, blur: Int) = clamp(v + .35 * (v - blur / 9.0))
            out[y * w + x] = (0xFF shl 24) or (ch(c shr 16 and 0xFF, r) shl 16) or
                (ch(c shr 8 and 0xFF, g) shl 8) or ch(c and 0xFF, b)
        }
        src.recycle()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(v: Double) = v.roundToInt().coerceIn(0, 255)
}
