package com.architect.driveaggregator.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.ConcurrentModificationException

@RunWith(RobolectricTestRunner::class)
class DatabaseV7VersioningTest {

    private lateinit var database: AppDatabase
    private lateinit var virtualFileDao: VirtualFileDao
    private lateinit var entityHistoryDao: EntityHistoryDao
    private lateinit var versioningManager: EntityVersioningManager

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        virtualFileDao = database.virtualFileDao()
        entityHistoryDao = database.entityHistoryDao()
        versioningManager = EntityVersioningManager(database, virtualFileDao, entityHistoryDao)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testInsertAndVersionIncrement() = runBlocking {
        val file = VirtualFileEntity(
            virtualFileId = "vf_ver_1",
            fileName = "document.pdf",
            fileSize = 1024L,
            mimeType = "application/pdf",
            sagaState = "COMMITTED"
        )

        versioningManager.insertWithVersion(file, changedBy = "user_abc", changeReason = "Initial upload")

        val retrieved = virtualFileDao.getFileById("vf_ver_1")
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.version)

        val history = versioningManager.getEntityHistory("vf_ver_1")
        assertEquals(1, history.size)
        assertEquals(1, history[0].version)
        assertEquals("user_abc", history[0].changedBy)
        assertEquals("Initial upload", history[0].changeReason)

        // Update file
        val updatedFile = retrieved.copy(fileName = "document_v2.pdf", fileSize = 2048L)
        versioningManager.updateWithVersion(updatedFile, changedBy = "user_abc", changeReason = "Renamed file")

        val retrievedUpdated = virtualFileDao.getFileById("vf_ver_1")
        assertNotNull(retrievedUpdated)
        assertEquals(2, retrievedUpdated!!.version)
        assertEquals("document_v2.pdf", retrievedUpdated.fileName)
        assertEquals(2048L, retrievedUpdated.fileSize)

        val historyUpdated = versioningManager.getEntityHistory("vf_ver_1")
        assertEquals(2, historyUpdated.size)
        // Ordered newest first (version 2 then version 1)
        assertEquals(2, historyUpdated[0].version)
        assertEquals(1, historyUpdated[1].version)
    }

    @Test
    fun testPointInTimeRollback() = runBlocking {
        val file = VirtualFileEntity(
            virtualFileId = "vf_rollback_1",
            fileName = "v1.txt",
            fileSize = 100L,
            mimeType = "text/plain",
            sagaState = "COMMITTED"
        )

        // Version 1
        versioningManager.insertWithVersion(file, changedBy = "alice", changeReason = "Created v1")

        // Version 2
        val fileV2 = virtualFileDao.getFileById("vf_rollback_1")!!.copy(fileName = "v2.txt", fileSize = 200L)
        versioningManager.updateWithVersion(fileV2, changedBy = "bob", changeReason = "Updated to v2")

        // Version 3
        val fileV3 = virtualFileDao.getFileById("vf_rollback_1")!!.copy(fileName = "v3.txt", fileSize = 300L)
        versioningManager.updateWithVersion(fileV3, changedBy = "charlie", changeReason = "Updated to v3")

        assertEquals(3, virtualFileDao.getFileById("vf_rollback_1")!!.version)

        // Rollback to version 1
        val rolledBack = versioningManager.rollbackToVersion("vf_rollback_1", targetVersion = 1, changedBy = "admin", changeReason = "Reverting to stable v1")

        // Version should now be 4, with content matching version 1
        assertEquals(4, rolledBack.version)
        assertEquals("v1.txt", rolledBack.fileName)
        assertEquals(100L, rolledBack.fileSize)

        // Verify history has 4 records
        val fullHistory = versioningManager.getEntityHistory("vf_rollback_1")
        assertEquals(4, fullHistory.size)
        assertEquals(4, fullHistory[0].version) // Rollback record
        assertEquals(1, fullHistory[3].version) // Original v1 record

        // Verify snapshot at version 1
        val snapshotV1 = versioningManager.getSnapshotAtVersion("vf_rollback_1", 1)
        assertNotNull(snapshotV1)
        assertEquals("v1.txt", snapshotV1!!.fileName)
    }

    @Test
    fun testHistoryImmutabilityAndAppendOnly() = runBlocking {
        val file = VirtualFileEntity(
            virtualFileId = "vf_immut_1",
            fileName = "immutable.dat",
            fileSize = 500L,
            mimeType = "application/octet-stream",
            sagaState = "COMMITTED"
        )

        versioningManager.insertWithVersion(file, changedBy = "owner", changeReason = "Create")

        val fileV2 = virtualFileDao.getFileById("vf_immut_1")!!.copy(fileSize = 600L)
        versioningManager.updateWithVersion(fileV2, changedBy = "owner", changeReason = "Update size")

        // Re-fetch history for version 1 to ensure it remained completely unmodified (immutable)
        val h1AfterUpdate = versioningManager.getSnapshotAtVersion("vf_immut_1", 1)
        assertNotNull(h1AfterUpdate)
        assertEquals(500L, h1AfterUpdate!!.fileSize)
    }

    @Test
    fun testOptimisticLockingConflictOnStaleVersionWrite() = runBlocking {
        val file = VirtualFileEntity(
            virtualFileId = "vf_conflict_1",
            fileName = "conflict.txt",
            fileSize = 100L,
            mimeType = "text/plain",
            sagaState = "COMMITTED"
        )

        versioningManager.insertWithVersion(file, changedBy = "alice", changeReason = "Initial insert")

        // Client A reads version 1
        val clientAFile = virtualFileDao.getFileById("vf_conflict_1")!!
        assertEquals(1, clientAFile.version)

        // Client B reads version 1 and updates first (moves DB to version 2)
        val clientBFile = clientAFile.copy(fileName = "updated_by_b.txt")
        versioningManager.updateWithVersion(clientBFile, changedBy = "bob", changeReason = "Concurrent update by B")

        assertEquals(2, virtualFileDao.getFileById("vf_conflict_1")!!.version)

        // Client A attempts to update using stale version 1 -> should throw ConcurrentModificationException
        val staleUpdate = clientAFile.copy(fileName = "updated_by_a.txt")
        var exceptionThrown = false
        try {
            versioningManager.updateWithVersion(staleUpdate, changedBy = "alice", changeReason = "Stale update by A")
        } catch (e: ConcurrentModificationException) {
            exceptionThrown = true
            assertTrue(e.message!!.contains("Optimistic locking conflict"))
        }

        assertTrue("Expected ConcurrentModificationException on stale version write", exceptionThrown)
    }
}
