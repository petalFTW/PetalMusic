/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Mirrors the serial download queue into a single ongoing notification so the
 * user can watch progress (and see what is still queued) without opening the
 * app. Disappears once the queue drains.
 */
class DownloadNotifier(
    private val context: Context,
    private val manager: DownloadManager,
) {
    companion object {
        const val CHANNEL_ID = "petal_downloads"
        const val NOTIFICATION_ID = 0x9E11
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        ensureChannel()
        scope.launch {
            manager.progress.collectLatest { render(it) }
        }
    }

    private fun render(map: Map<String, DownloadManager.Progress>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = map.values.filter {
            it.state == DownloadManager.State.QUEUED ||
                it.state == DownloadManager.State.DOWNLOADING
        }
        if (active.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val current = active.firstOrNull { it.state == DownloadManager.State.DOWNLOADING }
            ?: active.first()
        val percent = (current.fraction * 100).toInt().coerceIn(0, 100)
        val queued = active.size - 1
        val line = buildString {
            append(current.quality.ifBlank { "Downloading" })
            if (current.state == DownloadManager.State.DOWNLOADING) append(" · $percent%")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(current.title)
            .setContentText(line)
            .setSubText(if (queued > 0) "$queued more queued" else "Offline library")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                100,
                percent,
                current.state != DownloadManager.State.DOWNLOADING,
            )
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Offline download progress"
                    setShowBadge(false)
                },
            )
        }
    }
}
