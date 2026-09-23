package com.architect.driveaggregator.provider

import com.architect.driveaggregator.engine.ChunkAllocation
import com.architect.driveaggregator.engine.ChunkMeta
import com.architect.driveaggregator.engine.LinkedAccount
import com.architect.driveaggregator.engine.VirtualDriveEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MultiProviderIntegrationTest {

    @Test
    fun testStorageQuotaDataClass() {
        val quota = StorageQuota(1000L, 400L, 600L)
        assertEquals(1000L, quota.totalBytes)
        assertEquals(400L, quota.usedBytes)
        assertEquals(600L, quota.freeBytes)
    }

    @Test
    fun testChunkDataEquality() {
        val bytes1 = byteArrayOf(1, 2, 3, 4)
        val bytes2 = byteArrayOf(1, 2, 3, 4)
        val chunk1 = ChunkData(0, bytes1, 0L, 4L)
        val chunk2 = ChunkData(0, bytes2, 0L, 4L)

        assertEquals(chunk1, chunk2)
        assertEquals(chunk1.hashCode(), chunk2.hashCode())
    }

    @Test
    fun testMultiProviderAllocationStrategy() {
        val providers = mapOf(
            "onedrive_1" to OneDriveStorageProvider("onedrive_1", { "mock_token" }),
            "dropbox_1" to DropboxStorageProvider("dropbox_1", { "mock_token" }),
            "box_1" to BoxStorageProvider("box_1", { "mock_token" })
        )

        val strategy = MultiProviderAllocationStrategy(providers)
        val accounts = listOf(
            LinkedAccount("onedrive_1", 50L * 1024 * 1024, 10L * 1024 * 1024),
            LinkedAccount("dropbox_1", 100L * 1024 * 1024, 20L * 1024 * 1024),
            LinkedAccount("box_1", 200L * 1024 * 1024, 50L * 1024 * 1024)
        )

        val chunks = listOf(
            ChunkMeta(0, 0L, 5L * 1024 * 1024, "hash0"),
            ChunkMeta(1, 5L * 1024 * 1024, 5L * 1024 * 1024, "hash1"),
            ChunkMeta(2, 10L * 1024 * 1024, 5L * 1024 * 1024, "hash2")
        )

        val allocations = strategy.allocateChunks(chunks, accounts)
        assertEquals(3, allocations.size)
        // Should allocate to box_1 first (highest free space: 150MB)
        assertEquals("box_1", allocations[0].targetAccountId)
    }
}
