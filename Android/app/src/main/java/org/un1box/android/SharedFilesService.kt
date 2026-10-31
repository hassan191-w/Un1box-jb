package org.un1box.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Runs while a device runs, with a notification, so Android keeps the app's process, and the sync that lives in it
 * ([SharedFilesAuto]), alive when the app is left. It does no work itself.
 */
class SharedFilesService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Shared files", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SharedFilesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Un1box shared files")
            .setContentText("Keeping the Un1box folder in step with the iOS device")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val CHANNEL = "shared_files"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SharedFilesService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SharedFilesService::class.java))
        }
    }
}
