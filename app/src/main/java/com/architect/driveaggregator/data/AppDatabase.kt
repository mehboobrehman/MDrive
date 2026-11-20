package com.architect.driveaggregator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.architect.driveaggregator.sharing.SharingLinkEntity
import com.architect.driveaggregator.sharing.SharingLinkDao
import com.architect.driveaggregator.data.entities.LogEntity
import com.architect.driveaggregator.data.dao.LogDao

/**
 * AppDatabase acts as the Room Database singleton for MDrive (v7 schema),
 * housing VirtualFileEntity, FileShardEntity, PrimaryAccountSessionEntity, SmartRecommendationEntity,
 * LogEntity, and EntityHistoryEntity with WAL mode enabled and seamless migration.
 */
@Database(
    entities = [
        VirtualFileEntity::class,
        FileShardEntity::class,
        PrimaryAccountSessionEntity::class,
        SmartRecommendationEntity::class,
        SharingLinkEntity::class,
        LogEntity::class,
        EntityHistoryEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sagaTransactionDao(): SagaTransactionDao
    abstract fun primaryAccountSessionDao(): PrimaryAccountSessionDao
    abstract fun smartRecommendationDao(): SmartRecommendationDao
    abstract fun virtualFileDao(): VirtualFileDao
    abstract fun sharingLinkDao(): SharingLinkDao
    abstract fun logDao(): LogDao
    abstract fun entityHistoryDao(): EntityHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DATABASE_NAME = "mdrive_saga_ledger.db"

        /**
         * Migration from version 6 to version 7:
         * Adds `version` column to `virtual_files` table and creates `entity_histories` table with indexes.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE virtual_files ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `entity_histories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entity_id` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `snapshot_json` TEXT NOT NULL,
                        `changed_by` TEXT NOT NULL,
                        `change_reason` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_histories_entity_id` ON `entity_histories` (`entity_id`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_entity_histories_entity_id_version` ON `entity_histories` (`entity_id`, `version`)")
            }
        }

        /**
         * Migration from version 5 to version 6:
         * Adds `logs` table for offline diagnostics.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `level` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `metadata` TEXT NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        /**
         * Migration from version 4 to version 5:
         * Adds `sharing_links` table.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sharing_links` (
                        `linkId` TEXT NOT NULL,
                        `virtualFileId` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `providerFileId` TEXT NOT NULL,
                        `shareUrl` TEXT NOT NULL,
                        `permission` TEXT NOT NULL,
                        `isPublic` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `isRevoked` INTEGER NOT NULL,
                        `accessCount` INTEGER NOT NULL,
                        PRIMARY KEY(`linkId`)
                    )
                """)
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to virtual_files if not already present
                database.execSQL("ALTER TABLE virtual_files ADD COLUMN is_offline_pinned INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE virtual_files ADD COLUMN ai_category TEXT")
                database.execSQL("ALTER TABLE virtual_files ADD COLUMN sha256_hash TEXT")
                database.execSQL("ALTER TABLE virtual_files ADD COLUMN share_url TEXT")
                database.execSQL("ALTER TABLE virtual_files ADD COLUMN last_synced_timestamp INTEGER NOT NULL DEFAULT 0")

                // Create primary_account_session table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `primary_account_session` (
                        `accountId` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `displayName` TEXT,
                        `photoUrl` TEXT,
                        `accessToken` TEXT,
                        `refreshToken` TEXT,
                        `tokenExpiresAt` INTEGER NOT NULL,
                        `linkedProvidersJson` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`accountId`)
                    )
                """)

                // Create smart_recommendations table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `smart_recommendations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `potentialSavingsBytes` INTEGER NOT NULL,
                        `relatedFileIdsJson` TEXT,
                        `isDismissed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """)
            }
        }

        /**
         * Migration from version 1 to 4 (in case a test database starts at v1/v2).
         */
        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Check if columns exist or add them
                try {
                    database.execSQL("ALTER TABLE virtual_files ADD COLUMN is_offline_pinned INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE virtual_files ADD COLUMN ai_category TEXT")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE virtual_files ADD COLUMN sha256_hash TEXT")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE virtual_files ADD COLUMN share_url TEXT")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE virtual_files ADD COLUMN last_synced_timestamp INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `primary_account_session` (
                        `accountId` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `displayName` TEXT,
                        `photoUrl` TEXT,
                        `accessToken` TEXT,
                        `refreshToken` TEXT,
                        `tokenExpiresAt` INTEGER NOT NULL,
                        `linkedProvidersJson` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`accountId`)
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `smart_recommendations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `potentialSavingsBytes` INTEGER NOT NULL,
                        `relatedFileIdsJson` TEXT,
                        `isDismissed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_1_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
