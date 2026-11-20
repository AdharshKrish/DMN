package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "synapse_vault_prefs")

enum class ConflictStrategy(val label: String) {
    KEEP_NEWER("Keep Newer Version"),
    KEEP_BOTH("Keep Conflict Copy")
}

enum class SyncDirection(val label: String) {
    PHONE_TO_DRIVE("Phone → Drive"),
    TWO_WAY("Two-Way Sync")
}

enum class VoiceTranscriptionMode(val label: String, val shortLabel: String) {
    LIVE("Live Transcribe", "Live"),
    BATCH_RAMBLER("Batch + Rambler", "Batch")
}

data class VaultSettings(
    val vaultTreeUri: String? = null,
    val vaultDisplayName: String = "",
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val transcriptionModel: String = DEFAULT_TRANSCRIPTION_MODEL,
    val liveTranscriptionModel: String = DEFAULT_LIVE_TRANSCRIPTION_MODEL,
    val voiceTranscriptionMode: VoiceTranscriptionMode = VoiceTranscriptionMode.LIVE,
    val autoProcessAudio: Boolean = true,
    val driveConnectedEmail: String? = null,
    val driveFolderId: String? = null,
    val driveFolderName: String = "ObsidianVault",
    val autoDriveSync: Boolean = true,
    val syncWifiOnly: Boolean = false,
    val syncDirection: SyncDirection = SyncDirection.PHONE_TO_DRIVE,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.KEEP_NEWER,
    val lastDriveSyncMs: Long = 0L,
    val darkTheme: Boolean = true,
    val audioRetentionDays: Int = DEFAULT_AUDIO_RETENTION_DAYS
) {
    val isVaultConfigured: Boolean get() = !vaultTreeUri.isNullOrBlank()
    val isGeminiConfigured: Boolean get() = geminiApiKey.isNotBlank()
    val isDriveConfigured: Boolean get() = !driveConnectedEmail.isNullOrBlank() && !driveFolderId.isNullOrBlank()

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
        const val DEFAULT_TRANSCRIPTION_MODEL = "gemini-3.5-transcribe"
        const val DEFAULT_LIVE_TRANSCRIPTION_MODEL = "gemini-3.5-transcribe-live"
        const val DEFAULT_AUDIO_RETENTION_DAYS = 0 // 0 = Keep Forever
    }
}

class VaultSettingsRepository(private val context: Context) {

    private object Keys {
        val VAULT_TREE_URI = stringPreferencesKey("vault_tree_uri")
        val VAULT_DISPLAY_NAME = stringPreferencesKey("vault_display_name")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val TRANSCRIPTION_MODEL = stringPreferencesKey("transcription_model")
        val LIVE_TRANSCRIPTION_MODEL = stringPreferencesKey("live_transcription_model")
        val VOICE_TRANSCRIPTION_MODE = stringPreferencesKey("voice_transcription_mode")
        val AUTO_PROCESS_AUDIO = booleanPreferencesKey("auto_process_audio")
        val DRIVE_CONNECTED_EMAIL = stringPreferencesKey("drive_connected_email")
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        val AUTO_DRIVE_SYNC = booleanPreferencesKey("auto_drive_sync")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val SYNC_DIRECTION = stringPreferencesKey("sync_direction")
        val CONFLICT_STRATEGY = stringPreferencesKey("conflict_strategy")
        val LAST_DRIVE_SYNC_MS = longPreferencesKey("last_drive_sync_ms")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AUDIO_RETENTION_DAYS = intPreferencesKey("audio_retention_days")
    }

    val settingsFlow: Flow<VaultSettings> = context.vaultDataStore.data.map { prefs ->
        val strategyName = prefs[Keys.CONFLICT_STRATEGY] ?: ConflictStrategy.KEEP_NEWER.name
        val strategy = runCatching { ConflictStrategy.valueOf(strategyName) }
            .getOrDefault(ConflictStrategy.KEEP_NEWER)
        val directionName = prefs[Keys.SYNC_DIRECTION] ?: SyncDirection.PHONE_TO_DRIVE.name
        val direction = runCatching { SyncDirection.valueOf(directionName) }
            .getOrDefault(SyncDirection.PHONE_TO_DRIVE)
        val voiceModeName = prefs[Keys.VOICE_TRANSCRIPTION_MODE] ?: VoiceTranscriptionMode.LIVE.name
        val voiceMode = runCatching { VoiceTranscriptionMode.valueOf(voiceModeName) }
            .getOrDefault(VoiceTranscriptionMode.LIVE)
        VaultSettings(
            vaultTreeUri = prefs[Keys.VAULT_TREE_URI],
            vaultDisplayName = prefs[Keys.VAULT_DISPLAY_NAME] ?: "",
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: "",
            geminiModel = prefs[Keys.GEMINI_MODEL]?.takeIf { it.isNotBlank() }
                ?: VaultSettings.DEFAULT_GEMINI_MODEL,
            transcriptionModel = prefs[Keys.TRANSCRIPTION_MODEL]?.takeIf { it.isNotBlank() }
                ?: VaultSettings.DEFAULT_TRANSCRIPTION_MODEL,
            liveTranscriptionModel = prefs[Keys.LIVE_TRANSCRIPTION_MODEL]?.takeIf { it.isNotBlank() }
                ?: VaultSettings.DEFAULT_LIVE_TRANSCRIPTION_MODEL,
            voiceTranscriptionMode = voiceMode,
            autoProcessAudio = prefs[Keys.AUTO_PROCESS_AUDIO] ?: true,
            driveConnectedEmail = prefs[Keys.DRIVE_CONNECTED_EMAIL],
            driveFolderId = prefs[Keys.DRIVE_FOLDER_ID],
            driveFolderName = prefs[Keys.DRIVE_FOLDER_NAME] ?: "ObsidianVault",
            autoDriveSync = prefs[Keys.AUTO_DRIVE_SYNC] ?: true,
            syncWifiOnly = prefs[Keys.SYNC_WIFI_ONLY] ?: false,
            syncDirection = direction,
            conflictStrategy = strategy,
            lastDriveSyncMs = prefs[Keys.LAST_DRIVE_SYNC_MS] ?: 0L,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            audioRetentionDays = (prefs[Keys.AUDIO_RETENTION_DAYS] ?: VaultSettings.DEFAULT_AUDIO_RETENTION_DAYS).coerceAtLeast(0)
        )
    }

    suspend fun currentSettings(): VaultSettings = settingsFlow.first()

    suspend fun setVaultFolder(uri: String, displayName: String) {
        context.vaultDataStore.edit {
            it[Keys.VAULT_TREE_URI] = uri
            it[Keys.VAULT_DISPLAY_NAME] = displayName
        }
    }

    suspend fun setGeminiConfig(
        apiKey: String,
        model: String = VaultSettings.DEFAULT_GEMINI_MODEL,
        transcriptionModel: String? = null,
        liveTranscriptionModel: String? = null
    ) {
        context.vaultDataStore.edit {
            it[Keys.GEMINI_API_KEY] = apiKey.trim()
            it[Keys.GEMINI_MODEL] = model.trim().ifBlank { VaultSettings.DEFAULT_GEMINI_MODEL }
            if (transcriptionModel != null) {
                it[Keys.TRANSCRIPTION_MODEL] = transcriptionModel.trim()
                    .ifBlank { VaultSettings.DEFAULT_TRANSCRIPTION_MODEL }
            }
            if (liveTranscriptionModel != null) {
                it[Keys.LIVE_TRANSCRIPTION_MODEL] = liveTranscriptionModel.trim()
                    .ifBlank { VaultSettings.DEFAULT_LIVE_TRANSCRIPTION_MODEL }
            }
        }
    }

    suspend fun setAutoProcessAudio(enabled: Boolean) {
        context.vaultDataStore.edit { it[Keys.AUTO_PROCESS_AUDIO] = enabled }
    }

    suspend fun setDriveAccount(email: String?) {
        context.vaultDataStore.edit {
            if (email.isNullOrBlank()) {
                it.remove(Keys.DRIVE_CONNECTED_EMAIL)
            } else {
                it[Keys.DRIVE_CONNECTED_EMAIL] = email
            }
        }
    }

    suspend fun setDriveFolder(folderId: String?, folderName: String) {
        context.vaultDataStore.edit {
            if (folderId.isNullOrBlank()) {
                it.remove(Keys.DRIVE_FOLDER_ID)
            } else {
                it[Keys.DRIVE_FOLDER_ID] = folderId
            }
            it[Keys.DRIVE_FOLDER_NAME] = folderName
        }
    }

    suspend fun setSyncOptions(
        autoSync: Boolean,
        wifiOnly: Boolean,
        syncDirection: SyncDirection,
        conflictStrategy: ConflictStrategy
    ) {
        context.vaultDataStore.edit {
            it[Keys.AUTO_DRIVE_SYNC] = autoSync
            it[Keys.SYNC_WIFI_ONLY] = wifiOnly
            it[Keys.SYNC_DIRECTION] = syncDirection.name
            it[Keys.CONFLICT_STRATEGY] = conflictStrategy.name
        }
    }

    suspend fun setLastDriveSyncMs(timestampMs: Long) {
        context.vaultDataStore.edit { it[Keys.LAST_DRIVE_SYNC_MS] = timestampMs }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.vaultDataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun setAudioRetentionDays(days: Int) {
        context.vaultDataStore.edit { it[Keys.AUDIO_RETENTION_DAYS] = days.coerceAtLeast(0) }
    }

    suspend fun setVoiceTranscriptionMode(mode: VoiceTranscriptionMode) {
        context.vaultDataStore.edit { it[Keys.VOICE_TRANSCRIPTION_MODE] = mode.name }
    }
}
