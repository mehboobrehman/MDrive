package com.architect.driveaggregator.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.data.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VirtualDriveEngineTest {

    private lateinit var database: AppDatabase
    private lateinit var engine: VirtualDriveEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = VirtualDriveEngine(database.sagaTransactionDao(), AvailableSpaceWeightedAllocationStrategy())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testCalculatePoolCapacity_aggregatesCorrectly() {
        val accounts = listOf(
            LinkedAccount(accountId = "acc1", totalBytes = 100L, usedBytes = 30L, isAvailable = true),
            LinkedAccount(accountId = "acc2", totalBytes = 200L, usedBytes = 50L, isAvailable = true),
            LinkedAccount(accountId = "acc3", totalBytes = 500L, usedBytes = 500L, isAvailable = false) // Unavailable
        )

        val pool = engine.calculatePoolCapacity(accounts)

        assertEquals(300L, pool.totalCapacityBytes)
        assertEquals(80L, pool.totalUsedBytes)
        assertEquals(220L, pool.totalFreeBytes)
        assertEquals(2, pool.activeAccountsCount)
    }

    @Test
    fun testStreamChunkingAndSha256Integrity() {
        // Create 20 bytes of test data with a small chunk size of 8 bytes
        val testData = "01234567890123456789".toByteArray()
        val inputStream = ByteArrayInputStream(testData)

        val chunks = engine.planStreamChunking(inputStream, chunkSize = 8L)

        assertEquals(3, chunks.size)
        // Chunk 0: 8 bytes
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(0L, chunks[0].offset)
        assertEquals(8L, chunks[0].size)
        assertNotNull(chunks[0].sha256Checksum)
        assertEquals(64, chunks[0].sha256Checksum.length) // SHA-256 hex string length

        // Chunk 1: 8 bytes
        assertEquals(1, chunks[1].chunkIndex)
        assertEquals(8L, chunks[1].offset)
        assertEquals(8L, chunks[1].size)

        // Chunk 2: 4 bytes (remainder)
        assertEquals(2, chunks[2].chunkIndex)
        assertEquals(16L, chunks[2].offset)
        assertEquals(4L, chunks[2].size)
    }

    @Test
    fun testAvailableSpaceWeightedAllocationStrategy() {
        val accounts = listOf(
            LinkedAccount(accountId = "small_acc", totalBytes = 10L, usedBytes = 5L), // 5 bytes free
            LinkedAccount(accountId = "large_acc", totalBytes = 100L, usedBytes = 10L) // 90 bytes free
        )

        val chunks = listOf(
            ChunkMeta(0, 0L, 20L, "hash0", ByteArray(20)),
            ChunkMeta(1, 20L, 20L, "hash1", ByteArray(20))
        )

        val allocations = engine.generateAllocationPlan(chunks, accounts)

        assertEquals(2, allocations.size)
        // Both chunks should be allocated to large_acc because small_acc only has 5 bytes free
        assertEquals("large_acc", allocations[0].targetAccountId)
        assertEquals("large_acc", allocations[1].targetAccountId)
    }

    @Test
    fun testRoundRobinAllocationStrategy() {
        val rrEngine = VirtualDriveEngine(database.sagaTransactionDao(), RoundRobinAllocationStrategy())
        val accounts = listOf(
            LinkedAccount(accountId = "accA", totalBytes = 100L, usedBytes = 0L),
            LinkedAccount(accountId = "accB", totalBytes = 100L, usedBytes = 0L)
        )

        val chunks = listOf(
            ChunkMeta(0, 0L, 10L, "hash0"),
            ChunkMeta(1, 10L, 10L, "hash1"),
            ChunkMeta(2, 20L, 10L, "hash2")
        )

        val allocations = rrEngine.generateAllocationPlan(chunks, accounts)

        assertEquals(3, allocations.size)
        assertEquals("accA", allocations[0].targetAccountId)
        assertEquals("accB", allocations[1].targetAccountId)
        assertEquals("accA", allocations[2].targetAccountId)
    }

    @Test
    fun testPrepareVirtualFileUploadTransaction_persistsToSagaLedger() = runBlocking {
        val virtualFileId = "vf_test_123"
        val fileName = "document.pdf"
        val fileSize = 15L
        val mimeType = "application/pdf"

        val chunks = listOf(
            ChunkMeta(0, 0L, 8L, "hash0", ByteArray(8)),
            ChunkMeta(1, 8L, 7L, "hash1", ByteArray(7))
        )
        val accounts = listOf(
            LinkedAccount(accountId = "drive_1", totalBytes = 1000L, usedBytes = 100L),
            LinkedAccount(accountId = "drive_2", totalBytes = 1000L, usedBytes = 200L)
        )

        val allocations = engine.generateAllocationPlan(chunks, accounts)

        // Perform transaction prep
        engine.prepareVirtualFileUploadTransaction(virtualFileId, fileName, fileSize, mimeType, allocations)

        // Verify VirtualFileEntity persisted correctly
        val storedFile = database.sagaTransactionDao().getVirtualFile(virtualFileId)
        assertNotNull(storedFile)
        assertEquals(fileName, storedFile?.fileName)
        assertEquals(fileSize, storedFile?.fileSize)
        assertEquals("PENDING", storedFile?.sagaState)

        // Verify FileShardEntity rows persisted correctly
        val storedShards = database.sagaTransactionDao().getShardsForVirtualFile(virtualFileId)
        assertEquals(2, storedShards.size)
        assertEquals(0, storedShards[0].shardIndex)
        assertEquals(8L, storedShards[0].shardSize)
        assertEquals("PENDING", storedShards[0].uploadState)

        assertEquals(1, storedShards[1].shardIndex)
        assertEquals(7L, storedShards[1].shardSize)
        assertEquals("PENDING", storedShards[1].uploadState)
    }
}
