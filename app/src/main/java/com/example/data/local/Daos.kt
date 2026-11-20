package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingJobDao {
    @Query("SELECT * FROM processing_jobs ORDER BY updatedAtMs DESC")
    fun observeAllJobs(): Flow<List<ProcessingJobEntity>>

    @Query("SELECT * FROM processing_jobs WHERE audioUri = :audioUri LIMIT 1")
    suspend fun getJobByUri(audioUri: String): ProcessingJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: ProcessingJobEntity)

    @Query("DELETE FROM processing_jobs WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM processing_jobs WHERE audioUri = :audioUri")
    suspend fun deleteJobByUri(audioUri: String)

    @Query("DELETE FROM processing_jobs WHERE archivedAudioName = :archivedName OR fileName = :fileName")
    suspend fun deleteJobsByFileOrArchive(archivedName: String, fileName: String)
}

@Dao
interface VoiceDraftDao {
    @Query("SELECT * FROM voice_drafts ORDER BY updatedAtMs DESC")
    fun observeAllDrafts(): Flow<List<VoiceDraftEntity>>

    @Query("SELECT * FROM voice_drafts WHERE id = :id LIMIT 1")
    suspend fun getDraftById(id: String): VoiceDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: VoiceDraftEntity)

    @Query("DELETE FROM voice_drafts WHERE id = :id")
    suspend fun deleteDraftById(id: String)
}

@Dao
interface SyncRecordDao {
    @Query("SELECT * FROM sync_records ORDER BY relativePath ASC")
    fun observeAllRecords(): Flow<List<SyncRecordEntity>>

    @Query("SELECT * FROM sync_records")
    suspend fun getAllRecords(): List<SyncRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: SyncRecordEntity)

    @Query("DELETE FROM sync_records WHERE relativePath = :relativePath")
    suspend fun deleteByPath(relativePath: String)

    @Query("DELETE FROM sync_records")
    suspend fun clearAll()
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY timestampMs DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insertLog(log: SyncLogEntity)

    @Query("DELETE FROM sync_logs")
    suspend fun clearLogs()
}
