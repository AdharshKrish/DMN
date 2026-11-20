package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProcessingJobEntity::class,
        VoiceDraftEntity::class,
        SyncRecordEntity::class,
        SyncLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processingJobDao(): ProcessingJobDao
    abstract fun voiceDraftDao(): VoiceDraftDao
    abstract fun syncRecordDao(): SyncRecordDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_logs ADD COLUMN details TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN precomputedTranscript TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `voice_drafts` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `transcriptText` TEXT NOT NULL,
                        `pcmFilePath` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `totalPcmBytes` INTEGER NOT NULL,
                        `transcribedByteOffset` INTEGER NOT NULL,
                        `lastModeLabel` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "synapse_dmn.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
