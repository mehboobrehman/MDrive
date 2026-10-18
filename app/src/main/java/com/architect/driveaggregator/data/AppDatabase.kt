package com.architect.driveaggregator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AppDatabase acts as the Room Database singleton for MDrive (v4 schema),
 * housing VirtualFileEntity, FileShardEntity, PrimaryAccountSessionEntity, and SmartRecommendationEntity
 * with WAL mode enabled and seamless v3 to v4 migration.
 */
@Database(
    entities = [
        VirtualFileEntity::class,
        FileShardEntity::class,
        PrimaryAccountSessionEntity::class,
        SmartRecommendationEntity::class,
        com.architect.driveaggregator.sharing.SharingLinkEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sagaTransactionDao(): SagaTransactionDao
    abstract fun primaryAccountSessionDao(): PrimaryAccountSessionDao
    abstract fun smartRecommendationDao(): SmartRecommendationDao
    abstract fun virtualFileDao(): VirtualFileDao
    abstract fun sharingLinkDao(): com.architect.driveaggregator.sharing.SharingLinkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DATABASE_NAME = "mdrive_saga_ledger.db"

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
                .addMigrations(MIGRATION_3_4, MIGRATION_1_4, MIGRATION_4_5)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
