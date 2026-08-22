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

@RunWith(RobolectricTestRunner::class)
class SagaTransactionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SagaTransactionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.sagaTransactionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testInsertAndRetrieveVirtualFileAndShards() = runBlocking {
        val virtualFile = VirtualFileEntity(
            virtualFileId = "vf_123",
            fileName = "large_document.pdf",
            fileSize = 15000000L,
            mimeType = "application/pdf",
            sagaState = "PENDING"
        )

        val shards = listOf(
            FileShardEntity(
                shardId = "shard_1",
                virtualFileId = "vf_123",
                shardIndex = 0,
                targetAccountId = "account_a",
                shardSize = 7500000L,
                uploadState = "PENDING"
            ),
            FileShardEntity(
                shardId = "shard_2",
                virtualFileId = "vf_123",
                shardIndex = 1,
                targetAccountId = "account_b",
                shardSize = 7500000L,
                uploadState = "PENDING"
            )
        )

        dao.insertSagaTransaction(virtualFile, shards)

        // Verify Virtual File
        val retrievedFile = dao.getVirtualFile("vf_123")
        assertNotNull(retrievedFile)
        assertEquals("large_document.pdf", retrievedFile?.fileName)
        assertEquals("PENDING", retrievedFile?.sagaState)

        // Verify Shards
        val retrievedShards = dao.getShardsForVirtualFile("vf_123")
        assertEquals(2, retrievedShards.size)
        assertEquals("account_a", retrievedShards[0].targetAccountId)
        assertEquals(0, retrievedShards[0].shardIndex)
        assertEquals("account_b", retrievedShards[1].targetAccountId)
        assertEquals(1, retrievedShards[1].shardIndex)
    }

    @Test
    fun testSagaStateTransitions() = runBlocking {
        val virtualFile = VirtualFileEntity(
            virtualFileId = "vf_456",
            fileName = "video.mp4",
            fileSize = 50000000L,
            mimeType = "video/mp4",
            sagaState = "PENDING"
        )
        dao.insertVirtualFile(virtualFile)

        dao.updateSagaState("vf_456", "SHARDING")
        var file = dao.getVirtualFile("vf_456")
        assertEquals("SHARDING", file?.sagaState)

        dao.updateSagaState("vf_456", "COMMITTED")
        file = dao.getVirtualFile("vf_456")
        assertEquals("COMMITTED", file?.sagaState)

        dao.updateSagaState("vf_456", "ROLLBACK")
        file = dao.getVirtualFile("vf_456")
        assertEquals("ROLLBACK", file?.sagaState)

        dao.updateSagaState("vf_456", "FAILED")
        file = dao.getVirtualFile("vf_456")
        assertEquals("FAILED", file?.sagaState)
    }

    @Test
    fun testShardProgressTracking() = runBlocking {
        val virtualFile = VirtualFileEntity(
            virtualFileId = "vf_789",
            fileName = "archive.zip",
            fileSize = 10000000L,
            mimeType = "application/zip",
            sagaState = "SHARDING"
        )
        val shard = FileShardEntity(
            shardId = "shard_789_1",
            virtualFileId = "vf_789",
            shardIndex = 0,
            targetAccountId = "account_c",
            shardSize = 10000000L,
            uploadState = "PENDING"
        )
        dao.insertSagaTransaction(virtualFile, listOf(shard))

        dao.updateShardState("shard_789_1", "UPLOADING")
        dao.updateShardProgress("shard_789_1", "remote_file_abc", "https://drive.google.com/resumable/123", 5000000L)

        val shards = dao.getShardsForVirtualFile("vf_789")
        assertEquals(1, shards.size)
        assertEquals("UPLOADING", shards[0].uploadState)
        assertEquals("remote_file_abc", shards[0].remoteFileId)
        assertEquals("https://drive.google.com/resumable/123", shards[0].resumableSessionUri)
        assertEquals(5000000L, shards[0].lastKnownUploadedOffset)
    }

    @Test
    fun testCascadeDeleteOnVirtualFile() = runBlocking {
        val virtualFile = VirtualFileEntity(
            virtualFileId = "vf_del",
            fileName = "temp.txt",
            fileSize = 100L,
            mimeType = "text/plain",
            sagaState = "COMMITTED"
        )
        val shard = FileShardEntity(
            shardId = "shard_del",
            virtualFileId = "vf_del",
            shardIndex = 0,
            targetAccountId = "account_a",
            shardSize = 100L,
            uploadState = "COMPLETED"
        )
        dao.insertSagaTransaction(virtualFile, listOf(shard))

        assertEquals(1, dao.getShardsForVirtualFile("vf_del").size)

        dao.deleteVirtualFile("vf_del")

        assertNull(dao.getVirtualFile("vf_del"))
        assertTrue(dao.getShardsForVirtualFile("vf_del").isEmpty())
    }

    @Test
    fun testIncompleteSagaTransactionsQuery() = runBlocking {
        val f1 = VirtualFileEntity("vf_1", "file1.txt", 100L, "text/plain", "PENDING")
        val f2 = VirtualFileEntity("vf_2", "file2.txt", 100L, "text/plain", "SHARDING")
        val f3 = VirtualFileEntity("vf_3", "file3.txt", 100L, "text/plain", "COMMITTED")
        val f4 = VirtualFileEntity("vf_4", "file4.txt", 100L, "text/plain", "ROLLBACK")
        val f5 = VirtualFileEntity("vf_5", "file5.txt", 100L, "text/plain", "FAILED")

        dao.insertVirtualFile(f1)
        dao.insertVirtualFile(f2)
        dao.insertVirtualFile(f3)
        dao.insertVirtualFile(f4)
        dao.insertVirtualFile(f5)

        val incomplete = dao.getIncompleteSagaTransactions()
        assertEquals(3, incomplete.size)
        val ids = incomplete.map { it.virtualFileId }.toSet()
        assertTrue(ids.contains("vf_1"))
        assertTrue(ids.contains("vf_2"))
        assertTrue(ids.contains("vf_4"))
    }
}
