package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class JobStatus(val displayLabel: String) {
    QUEUED("Queued"),
    UPLOADING("Uploading Audio"),
    PROCESSING_REMOTE("Indexing Audio"),
    TRANSCRIBING("Transcribing Speech"),
    SYNTHESIZING("Synthesizing Graph"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

@Entity(tableName = "processing_jobs")
data class ProcessingJobEntity(
    @PrimaryKey val audioUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val status: String = JobStatus.QUEUED.name,
    val errorMessage: String? = null,
    val outputNotePath: String? = null,
    val archivedAudioName: String? = null,
    val linkedNodesCsv: String? = null,
    val precomputedTranscript: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "voice_drafts")
data class VoiceDraftEntity(
    @PrimaryKey val id: String,
    val title: String,
    val transcriptText: String,
    val pcmFilePath: String,
    val durationMs: Long = 0L,
    val totalPcmBytes: Long = 0L,
    val transcribedByteOffset: Long = 0L,
    val lastModeLabel: String = "Draft",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val untranscribedBytes: Long
        get() = (totalPcmBytes - transcribedByteOffset).coerceAtLeast(0L)
}

@Entity(tableName = "sync_records")
data class SyncRecordEntity(
    @PrimaryKey val relativePath: String,
    val driveFileId: String,
    val mimeType: String,
    val localModifiedMs: Long,
    val localSizeBytes: Long,
    val remoteModifiedMs: Long,
    val remoteMd5: String?,
    val syncState: String,
    val lastSyncedMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val category: String, // "GEMINI", "DRIVE", "VAULT"
    val level: String,    // "INFO", "SUCCESS", "ERROR"
    val message: String,
    val details: String? = null
)
