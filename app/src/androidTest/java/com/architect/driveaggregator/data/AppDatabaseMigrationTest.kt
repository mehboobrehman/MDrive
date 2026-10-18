package com.architect.driveaggregator.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.support.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        androidx.instrumentation.InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        var db = helper.createDatabase(TEST_DB, 3).apply {
            // Insert test data in v3 format if needed
            execSQL("INSERT INTO virtual_files (virtualFileId, fileName, fileSize, mimeType, sagaState, createdAt, updatedAt) VALUES ('test_id', 'test.txt', 100, 'text/plain', 'COMMITTED', 1000, 2000)")
            close()
        }

        // Open database with migration 3 to 4
        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        // Verify new columns and tables exist and data migrated successfully
        val cursor = db.query("SELECT is_offline_pinned, ai_category, sha256_hash, share_url, last_synced_timestamp FROM virtual_files WHERE virtualFileId = 'test_id'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0)) // is_offline_pinned default 0
        assertNull(cursor.getString(1)) // ai_category
        assertNull(cursor.getString(2)) // sha256_hash
        assertNull(cursor.getString(3)) // share_url
        assertEquals(0L, cursor.getLong(4)) // last_synced_timestamp default 0
        cursor.close()

        // Verify primary_account_session table exists
        val sessionCursor = db.query("SELECT * FROM primary_account_session")
        assertNotNull(sessionCursor)
        sessionCursor.close()

        // Verify smart_recommendations table exists
        val recCursor = db.query("SELECT * FROM smart_recommendations")
        assertNotNull(recCursor)
        recCursor.close()
    }
}
