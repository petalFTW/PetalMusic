/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * AutoMix diagnostics that survive reboots and logcat rotation: mix plans,
 * analysis results, clock corrections and beat-sync measurements, appended to
 * `Android/data/<package>/files/automix.log` (rolled at 2 MB). Also mirrored
 * to logcat under the same tag.
 */
object AutomixLog {
    private const val MAX_BYTES = 2L shl 20
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PetalAutomixLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun attach(context: Context) {
        if (file != null) return
        file = context.getExternalFilesDir(null)?.let { File(it, "automix.log") }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        val target = file ?: return
        val line = "${synchronized(time) { time.format(Date()) }} $tag: $message\n"
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, "automix.1.log"))
                target.appendText(line)
            }
        }
    }
}
