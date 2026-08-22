package com.architect.driveaggregator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AppDatabase acts as the Room Database singleton for MDrive,
 * housing VirtualFileEntity and FileShardEntity with WAL mode enabled.
 */
@Database(
    entities = [VirtualFileEntity::class, FileShardEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sagaTransactionDao(): SagaTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DATABASE_NAME = "mdrive_saga_ledger.db"

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
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Enable WAL mode explicitly on database creation
                        db.execSQL("PRAGMA journal_mode=WAL;")
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Ensure WAL mode remains enabled across connections
                        db.execSQL("PRAGMA journal_mode=WAL;")
                    }
                })
                .build()
        }
    }
}
