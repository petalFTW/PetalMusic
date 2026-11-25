/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.rab1d.petal.music.MainActivity
import org.rab1d.petal.music.PetalApp

/**
 * Mirrors the on-device upscale queue into one ongoing notification with a
 * progress bar and a Cancel action, so a long Apollo pass is visible and
 * abortable without the app in front.
 */
class UpscaleNotifier(
    private val context: Context,
    private val manager: UpscaleManager,
) {
    companion object {
        const val CHANNEL_ID = "petal_upscale"
        const val NOTIFICATION_ID = 0x9E12
        const val ACTION_CANCEL = "org.rab1d.petal.music.action.CANCEL_UPSCALE"
        const val EXTRA_KEY = "track_key"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        ensureChannel()
        scope.launch {
            manager.progress.collectLatest { render(it) }
        }
    }

    private fun render(map: Map<String, UpscaleManager.Progress>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = map.values.filter {
            it.state == UpscaleManager.State.QUEUED ||
                it.state == UpscaleManager.State.MODEL ||
                it.state == UpscaleManager.State.PROCESSING
        }
        if (active.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val current = active.firstOrNull { it.state == UpscaleManager.State.PROCESSING }
            ?: active.firstOrNull { it.state == UpscaleManager.State.MODEL }
            ?: active.first()
        val percent = (current.fraction * 100).toInt().coerceIn(0, 100)
        val queued = active.size - 1
        val stage = when (current.state) {
            UpscaleManager.State.MODEL -> "Preparing model"
            UpscaleManager.State.PROCESSING -> current.stage.ifBlank { "Upscaling" }
            else -> "Queued"
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, UpscaleActionReceiver::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_KEY, current.key)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(current.title)
            .setContentText("$stage · $percent%")
            .setSubText(if (queued > 0) "$queued more queued" else "Hi-Fi upscaling")
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                100,
                percent,
                current.state == UpscaleManager.State.QUEUED,
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
                    "Upscaling",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Hi-Fi audio restoration progress"
                    setShowBadge(false)
                },
            )
        }
    }
}

/** Handles the notification's Cancel action. */
class UpscaleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpscaleNotifier.ACTION_CANCEL) return
        val key = intent.getStringExtra(UpscaleNotifier.EXTRA_KEY) ?: return
        runCatching {
            (context.applicationContext as PetalApp).container.upscaleManager.cancelKey(key)
        }
    }
}
