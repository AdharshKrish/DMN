package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.SynapseApplication
import kotlinx.coroutines.launch

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

        runCatching {
            setForeground(
                ForegroundNotificationHelper.createForegroundInfo(
                    context = applicationContext,
                    notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DMN,
                    title = "Synapse DMN Processing",
                    contentText = "Synthesizing audio & text notes with Gemini..."
                )
            )
        }

        val statusObserverJob = kotlinx.coroutines.coroutineScope {
            val watcher = launch {
                app.pipelineOrchestrator.liveStatusMessage.collect { detail ->
                    if (!detail.isNullOrBlank()) {
                        ForegroundNotificationHelper.updateProgressNotification(
                            context = applicationContext,
                            notificationId = ForegroundNotificationHelper.NOTIFICATION_ID_DMN,
                            title = "Synapse DMN Processing",
                            contentText = detail
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
                watcher.cancel()
            }
        }

        return statusObserverJob
    }
}
