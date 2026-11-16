package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.SynapseApplication

class DmnProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SynapseApplication
        val settings = app.settingsRepo.currentSettings()
        if (!settings.isVaultConfigured || !settings.isGeminiConfigured) {
            return Result.success()
        }
        val outcome = app.pipelineOrchestrator.processInboxQueue()
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }
}
