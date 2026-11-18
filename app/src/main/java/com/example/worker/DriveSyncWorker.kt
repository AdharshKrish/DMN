package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.SynapseApplication
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        return ForegroundNotificationHelper.createForegroundInfo(
            context = applicationContext,
            notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DRIVE,
            title = "Synapse Google Drive Sync",
            contentText = "Comparing vault metadata manifest..."
        )
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as SynapseApplication
        val settings = app.settingsRepo.currentSettings()
        if (!settings.isVaultConfigured || !settings.isDriveConfigured) {
            return Result.failure()
        }

        val foregroundActive = runCatching {
            setForeground(
                ForegroundNotificationHelper.createForegroundInfo(
                    context = applicationContext,
                    notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DRIVE,
                    title = "Synapse Google Drive Sync",
                    contentText = "Comparing vault metadata manifest..."
                )
            )
            true
        }.getOrDefault(false)

        val acceptingUpdates = AtomicBoolean(true)

        return coroutineScope {
            val watcher = launch {
                app.pipelineOrchestrator.liveStatusMessage
                    .collect { detail ->
                        if (!acceptingUpdates.get() || detail.isNullOrBlank()) return@collect
                        if (foregroundActive) {
                            runCatching {
                                setForeground(
                                    ForegroundNotificationHelper.createForegroundInfo(
                                        context = applicationContext,
                                        notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DRIVE,
                                        title = "Synapse Google Drive Sync",
                                        contentText = detail
                                    )
                                )
                            }
                        } else {
                            ForegroundNotificationHelper.updateProgressNotification(
                                context = applicationContext,
                                notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DRIVE,
                                title = "Synapse Google Drive Sync",
                                contentText = detail,
                                ongoing = false
                            )
                        }
                    }
            }
            try {
                val outcome = app.pipelineOrchestrator.runDriveSync()
                if (outcome.isSuccess) {
                    Result.success()
                } else {
                    val errMsg = outcome.exceptionOrNull()?.message.orEmpty()
                    val needsReauth = errMsg.contains("expired", ignoreCase = true) ||
                        errMsg.contains("consent", ignoreCase = true)
                    if (!needsReauth && runAttemptCount < 2) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            } finally {
                acceptingUpdates.set(false)
                withContext(NonCancellable) {
                    watcher.cancelAndJoin()
                    ForegroundNotificationHelper.cancelNotification(
                        applicationContext,
                        ForegroundNotificationHelper.NOTIFICATION_ID_DRIVE
                    )
                }
            }
        }
    }
}
