package com.architect.driveaggregator.provider

import com.architect.driveaggregator.engine.AllocationStrategy
import com.architect.driveaggregator.engine.ChunkAllocation
import com.architect.driveaggregator.engine.ChunkMeta
import com.architect.driveaggregator.engine.LinkedAccount

/**
 * MultiProviderAllocationStrategy integrates registered [StorageProvider]s into [VirtualDriveEngine]
 * allocation strategies ([AvailableSpaceWeightedAllocationStrategy] equivalent or dynamic provider quota integration).
 */
class MultiProviderAllocationStrategy(
    private val providers: Map<String, StorageProvider>
) : AllocationStrategy {

    override fun allocateChunks(chunks: List<ChunkMeta>, accounts: List<LinkedAccount>): List<ChunkAllocation> {
        val validAccounts = accounts.filter { it.isAvailable && it.freeBytes > 0 && providers.containsKey(it.accountId) }.toMutableList()
        require(validAccounts.isNotEmpty()) { "No available storage providers with free space for allocation." }

        val allocations = mutableListOf<ChunkAllocation>()
        val accountFreeSpaceMap = validAccounts.associate { it.accountId to it.freeBytes }.toMutableMap()

        for (chunk in chunks) {
            val targetEntry = accountFreeSpaceMap.entries
                .filter { it.value >= chunk.size }
                .maxByOrNull { it.value }
                ?: accountFreeSpaceMap.entries.maxByOrNull { it.value }
                ?: accountFreeSpaceMap.entries.first()

            val targetAccountId = targetEntry.key

            allocations.add(
                ChunkAllocation(
                    chunkIndex = chunk.chunkIndex,
                    chunkMeta = chunk,
                    targetAccountId = targetAccountId
                )
            )

            val currentFree = accountFreeSpaceMap[targetAccountId] ?: 0L
            accountFreeSpaceMap[targetAccountId] = (currentFree - chunk.size).coerceAtLeast(0L)
        }

        return allocations
    }
}
