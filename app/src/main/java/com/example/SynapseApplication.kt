package com.example

import android.app.Application
import com.example.audio.DmnAudioPlayer
import com.example.audio.DmnAudioRecorder
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.drive.GoogleDriveSyncService
import com.example.data.gemini.GeminiDmnService
import com.example.data.local.AppDatabase
import com.example.data.pipeline.DmnPipelineOrchestrator
import com.example.data.prefs.VaultSettingsRepository
import com.example.data.vault.ObsidianVaultRepository

class SynapseApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settingsRepo: VaultSettingsRepository by lazy { VaultSettingsRepository(this) }
    val vaultRepo: ObsidianVaultRepository by lazy { ObsidianVaultRepository(this) }
    val geminiService: GeminiDmnService by lazy { GeminiDmnService(this) }
    val driveAuthManager: GoogleDriveAuthManager by lazy { GoogleDriveAuthManager(this) }
    val driveSyncService: GoogleDriveSyncService by lazy {
        GoogleDriveSyncService(
            vaultRepo = vaultRepo,
            syncRecordDao = database.syncRecordDao(),
            syncLogDao = database.syncLogDao()
        )
    }
    val pipelineOrchestrator: DmnPipelineOrchestrator by lazy {
        DmnPipelineOrchestrator(
            context = this,
            settingsRepo = settingsRepo,
            vaultRepo = vaultRepo,
            geminiService = geminiService,
            driveAuthManager = driveAuthManager,
            driveSyncService = driveSyncService,
            jobDao = database.processingJobDao(),
            syncRecordDao = database.syncRecordDao(),
            logDao = database.syncLogDao()
        )
    }
    val audioRecorder: DmnAudioRecorder by lazy { DmnAudioRecorder(this) }
    val audioPlayer: DmnAudioPlayer by lazy { DmnAudioPlayer(this) }
}
