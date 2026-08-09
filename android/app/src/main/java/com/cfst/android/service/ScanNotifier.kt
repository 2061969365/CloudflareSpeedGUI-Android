package com.cfst.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cfst.android.MainActivity
import com.cfst.android.R

class ScanNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "scan_progress"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.cfst.android.action.CANCEL_SCAN"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "扫描进度",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(pct: Int, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ScanService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("CF测速")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "取消", cancelIntent)
            .setContentIntent(contentIntent)
            .build()
    }

    fun notifyProgress(pct: Int, text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(pct, text))
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
