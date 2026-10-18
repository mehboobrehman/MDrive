package com.architect.driveaggregator.provider

import com.architect.driveaggregator.engine.ChunkAllocation
import com.architect.driveaggregator.engine.ChunkMeta
import com.architect.driveaggregator.engine.LinkedAccount
import org.junit.Assert.*
import org.junit.Test

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
    fun testMultiProviderAllocationStrategyWithAllProviders() {
        val providers = mapOf(
            "onedrive_1" to OneDriveStorageProvider("onedrive_1", { "primary_google_anchor_token_1" }),
            "dropbox_1" to DropboxStorageProvider("dropbox_1", { "primary_google_anchor_token_1" }),
            "box_1" to BoxStorageProvider("box_1", { "primary_google_anchor_token_1" }),
            "mega_1" to MegaStorageProvider("mega_1", { "primary_google_anchor_token_1" }),
            "terabox_1" to TeraBoxStorageProvider("terabox_1", { "primary_google_anchor_token_1" })
        )

        assertEquals(5, providers.size)
        providers.values.forEach { provider ->
            assertNotNull(provider.providerId)
        }

        val strategy = MultiProviderAllocationStrategy(providers)
        val accounts = listOf(
            LinkedAccount("onedrive_1", 50L * 1024 * 1024, 10L * 1024 * 1024),
            LinkedAccount("dropbox_1", 100L * 1024 * 1024, 20L * 1024 * 1024),
            LinkedAccount("box_1", 200L * 1024 * 1024, 50L * 1024 * 1024),
            LinkedAccount("mega_1", 500L * 1024 * 1024, 100L * 1024 * 1024),
            LinkedAccount("terabox_1", 1000L * 1024 * 1024, 200L * 1024 * 1024)
        )

        val chunks = listOf(
            ChunkMeta(0, 0L, 5L * 1024 * 1024, "hash0"),
            ChunkMeta(1, 5L * 1024 * 1024, 5L * 1024 * 1024, "hash1"),
            ChunkMeta(2, 10L * 1024 * 1024, 5L * 1024 * 1024, "hash2"),
            ChunkMeta(3, 15L * 1024 * 1024, 5L * 1024 * 1024, "hash3"),
            ChunkMeta(4, 20L * 1024 * 1024, 5L * 1024 * 1024, "hash4")
        )

        val allocations = strategy.allocateChunks(chunks, accounts)
        assertEquals(5, allocations.size)
        // Highest free space is terabox_1 (800MB free)
        assertEquals("terabox_1", allocations[0].targetAccountId)
    }
}
