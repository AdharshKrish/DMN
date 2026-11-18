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

class DmnProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SINGLE_TARGET_URI = "single_target_uri"
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        return ForegroundNotificationHelper.createForegroundInfo(
            context = applicationContext,
            notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DMN,
            title = "Synapse DMN Processing",
            contentText = "Synthesizing audio & text notes with Gemini..."
        )
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as SynapseApplication
        val settings = app.settingsRepo.currentSettings()
        if (!settings.isVaultConfigured || !settings.isGeminiConfigured) {
            return Result.failure()
        }

        val foregroundActive = runCatching {
            setForeground(
                ForegroundNotificationHelper.createForegroundInfo(
                    context = applicationContext,
                    notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DMN,
                    title = "Synapse DMN Processing",
                    contentText = "Synthesizing audio & text notes with Gemini..."
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
                                        notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DMN,
                                        title = "Synapse DMN Processing",
                                        contentText = detail
                                    )
                                )
                            }
                        } else {
                            ForegroundNotificationHelper.updateProgressNotification(
                                context = applicationContext,
                                notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DMN,
                                title = "Synapse DMN Processing",
                                contentText = detail,
                                ongoing = false
                            )
                        }
                    }
            }
            try {
                val singleUri = inputData.getString(KEY_SINGLE_TARGET_URI)
                val outcome = app.pipelineOrchestrator.processInboxQueue(
                    singleTargetUri = singleUri,
                    awaitAutoSync = true
                )
                if (outcome.isSuccess) {
                    Result.success()
                } else if (runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            } finally {
                acceptingUpdates.set(false)
                withContext(NonCancellable) {
                    watcher.cancelAndJoin()
                    ForegroundNotificationHelper.cancelNotification(
                        applicationContext,
                        ForegroundNotificationHelper.NOTIFICATION_ID_DMN
                    )
                }
            }
        }
    }
}
