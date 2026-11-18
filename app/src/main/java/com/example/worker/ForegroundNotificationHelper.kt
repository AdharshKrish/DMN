package com.example.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.example.MainActivity
import com.example.R

object ForegroundNotificationHelper {
    const val CHANNEL_ID = "synapse_background_pipeline"
    const val NOTIFICATION_ID_DMN = 4101
    const val NOTIFICATION_ID_DRIVE = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "DMN Synthesis & Drive Sync",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Gemini DMN synthesis and Google Drive sync running reliably in the background."
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun createForegroundInfo(
        context: Context,
        notificationId: Int,
        title: String,
        contentText: String
    ): ForegroundInfo {
        ensureChannel(context)
        val notification = buildNotification(context, title, contentText)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    fun updateProgressNotification(
        context: Context,
        notificationId: Int,
        title: String,
        contentText: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching {
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(notificationId, buildNotification(context, title, contentText))
        }
    }

    private fun buildNotification(
        context: Context,
        title: String,
        contentText: String
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(contentText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .setContentIntent(createLaunchIntent(context))
        .build()

    private fun createLaunchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
