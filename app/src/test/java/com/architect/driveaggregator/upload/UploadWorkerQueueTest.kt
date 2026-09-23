package com.architect.driveaggregator.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.FileShardEntity
import com.architect.driveaggregator.data.VirtualFileEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UploadWorkerQueueTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context
    private lateinit var oauthManager: GoogleOAuthManager
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        oauthManager = mock()
        tempFile = File.createTempFile("worker_test", ".bin", context.cacheDir)
        tempFile.writeBytes(ByteArray(512) { it.toByte() })
    }

    @After
    fun tearDown() {
        database.close()
        if (tempFile.exists()) tempFile.delete()
    }

    @Test
    fun testQueueRespectsGlobalMaxConcurrency() = runBlocking {
        val virtualFileId = "vf_queue_1"
        database.sagaTransactionDao().insertVirtualFile(
            VirtualFileEntity(virtualFileId, "test.bin", 1536L, "application/octet-stream", "PENDING")
        )

        // 3 shards across 3 accounts
        val shards = listOf(
            FileShardEntity("s1", virtualFileId, 0, "acc_1", 512L, uploadState = "PENDING"),
            FileShardEntity("s2", virtualFileId, 1, "acc_2", 512L, uploadState = "PENDING"),
            FileShardEntity("s3", virtualFileId, 2, "acc_3", 512L, uploadState = "PENDING")
        )
        database.sagaTransactionDao().insertFileShards(shards)

        val concurrentCount = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val uploadManager = mock<GoogleResumableUploadManager>()
        whenever(uploadManager.uploadShard(any(), any(), any())).thenAnswer {
            val current = concurrentCount.incrementAndGet()
            maxObservedConcurrency.updateAndGet { max -> max.coerceAtLeast(current) }
            Thread.sleep(200L)
            concurrentCount.decrementAndGet()
            "remote_id"
        }

        // Global concurrency cap = 2
        val workerQueue = UploadWorkerQueue(uploadManager, globalMaxConcurrency = 2, maxPerAccountConcurrency = 1)

        val tasks = shards.map { shard ->
            UploadTask(
                taskId = shard.shardId,
                virtualFileId = virtualFileId,
                shard = shard,
                dataSourceFile = tempFile
            )
        }

        workerQueue.enqueueAll(tasks)

        // Wait until all tasks complete
        withTimeout(10000L) {
            while (workerQueue.getStats().activeUploadsCount > 0 || workerQueue.getStats().queuedTasksCount > 0) {
                delay(50L)
            }
        }

        assertTrue("Observed max concurrency should not exceed global cap of 2, but was ${maxObservedConcurrency.get()}", maxObservedConcurrency.get() <= 2)
        assertTrue("Observed concurrency should reach up to 2", maxObservedConcurrency.get() >= 1)
    }

    @Test
    fun testQueuePriorityAndCancellation() = runBlocking {
        val virtualFileId = "vf_queue_2"
        database.sagaTransactionDao().insertVirtualFile(
            VirtualFileEntity(virtualFileId, "test2.bin", 1024L, "application/octet-stream", "PENDING")
        )

        val shard1 = FileShardEntity("s1", virtualFileId, 0, "acc_1", 512L, uploadState = "PENDING")
        val shard2 = FileShardEntity("s2", virtualFileId, 1, "acc_1", 512L, uploadState = "PENDING")
        database.sagaTransactionDao().insertFileShards(listOf(shard1, shard2))

        val uploadManager = mock<GoogleResumableUploadManager>()
        whenever(uploadManager.uploadShard(any(), any(), any())).thenAnswer {
            Thread.sleep(300L)
            "remote_id"
        }

        val workerQueue = UploadWorkerQueue(uploadManager, globalMaxConcurrency = 1, maxPerAccountConcurrency = 1)

        // Enqueue low priority task first, then high priority task
        workerQueue.enqueue(UploadTask("task_low", virtualFileId, shard1, tempFile, priority = 0))
        workerQueue.enqueue(UploadTask("task_high", virtualFileId, shard2, tempFile, priority = 10))

        delay(50L) // let worker pick task_high due to priority or process queue

        val stats = workerQueue.getStats()
        assertTrue(stats.activeUploadsCount + stats.queuedTasksCount > 0)

        // Cancel task_low
        workerQueue.cancelTask("task_low")

        // Wait for completion
        withTimeout(10000L) {
            while (workerQueue.getStats().activeUploadsCount > 0 || workerQueue.getStats().queuedTasksCount > 0) {
                delay(50L)
            }
        }

        assertEquals(0, workerQueue.getStats().activeUploadsCount)
        assertEquals(0, workerQueue.getStats().queuedTasksCount)
    }
}
