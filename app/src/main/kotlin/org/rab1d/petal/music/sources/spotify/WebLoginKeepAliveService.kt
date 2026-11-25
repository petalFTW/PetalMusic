/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Keeps the app running while the browser covers it during Spotify sign-in.
 * Android freezes background apps within seconds, and a frozen app can't answer
 * the redirect on its loopback socket, so the login page would just hang.
 */
class WebLoginKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sign-in", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Waiting for Spotify sign-in")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0,
        )
        return START_NOT_STICKY
    }

    // Short services get three minutes, Android crashes the app if we overstay.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "petal_sign_in"
        private const val NOTIFICATION_ID = 7461

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, WebLoginKeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebLoginKeepAliveService::class.java))
        }
    }
}
