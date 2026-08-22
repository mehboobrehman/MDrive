package com.architect.driveaggregator.engine

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Metadata and checksum representing a single chunk (shard) of a file.
 */
data class ChunkMeta(
    val chunkIndex: Int,
    val offset: Long,
    val size: Long,
    val sha256Checksum: String,
    val dataBytes: ByteArray? = null // Optional in-memory bytes for testing or small files
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkMeta

        if (chunkIndex != other.chunkIndex) return false
        if (offset != other.offset) return false
        if (size != other.size) return false
        if (sha256Checksum != other.sha256Checksum) return false
        if (dataBytes != null) {
            if (other.dataBytes == null) return false
            if (!dataBytes.contentEquals(other.dataBytes)) return false
        } else if (other.dataBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkIndex
        result = 31 * result + offset.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + sha256Checksum.hashCode()
        result = 31 * result + (dataBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Representation of an available linked Google Drive account with its storage capacity metrics.
 */
data class LinkedAccount(
    val accountId: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val isAvailable: Boolean = true
) {
    val freeBytes: Long
        get() = (totalBytes - usedBytes).coerceAtLeast(0L)
}

/**
 * Result of pool capacity aggregation across all linked accounts.
 */
data class PoolCapacity(
    val totalCapacityBytes: Long,
    val totalUsedBytes: Long,
    val totalFreeBytes: Long,
    val activeAccountsCount: Int
)

/**
 * Target account allocation mapping for a specific chunk index.
 */
data class ChunkAllocation(
    val chunkIndex: Int,
    val chunkMeta: ChunkMeta,
    val targetAccountId: String
)

/**
 * Allocation strategy algorithm interface for distributing file chunks across linked Google Drive accounts.
 */
interface AllocationStrategy {
    fun allocateChunks(chunks: List<ChunkMeta>, accounts: List<LinkedAccount>): List<ChunkAllocation>
}

/**
 * AvailableSpaceWeightedAllocationStrategy implements weighted or largest-free-space-first allocation
 * to distribute chunks across available linked accounts based on available free space.
 */
class AvailableSpaceWeightedAllocationStrategy : AllocationStrategy {
    override fun allocateChunks(chunks: List<ChunkMeta>, accounts: List<LinkedAccount>): List<ChunkAllocation> {
        val validAccounts = accounts.filter { it.isAvailable && it.freeBytes > 0 }.toMutableList()
        require(validAccounts.isNotEmpty()) { "No available Google Drive accounts with free space for allocation." }

        val allocations = mutableListOf<ChunkAllocation>()
        // Track dynamic free space during allocation assignment
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

            // Deduct allocated size from account's remaining dynamic free space
            val currentFree = accountFreeSpaceMap[targetAccountId] ?: 0L
            accountFreeSpaceMap[targetAccountId] = (currentFree - chunk.size).coerceAtLeast(0L)
        }

        return allocations
    }
}

/**
 * RoundRobinAllocationStrategy distributes chunks cyclically across available accounts.
 */
class RoundRobinAllocationStrategy : AllocationStrategy {
    override fun allocateChunks(chunks: List<ChunkMeta>, accounts: List<LinkedAccount>): List<ChunkAllocation> {
        val validAccounts = accounts.filter { it.isAvailable }
        require(validAccounts.isNotEmpty()) { "No available Google Drive accounts for allocation." }

        return chunks.mapIndexed { index, chunk ->
            val account = validAccounts[index % validAccounts.size]
            ChunkAllocation(
                chunkIndex = chunk.chunkIndex,
                chunkMeta = chunk,
                targetAccountId = account.accountId
            )
        }
    }
}

/**
 * VirtualDriveEngine coordinates file chunking, cryptographic SHA-256 integrity verification,
 * storage pool capacity calculation, chunk allocation planning, and transaction ledger integration via SagaTransactionDao.
 */
class VirtualDriveEngine(
    private val sagaTransactionDao: com.architect.driveaggregator.data.SagaTransactionDao,
    private val allocationStrategy: AllocationStrategy = AvailableSpaceWeightedAllocationStrategy(),
    private val defaultChunkSize: Long = 8L * 1024 * 1024 // 8MB default atomic chunks
) {

    /**
     * Calculates aggregate storage capacity across a list of linked accounts.
     */
    fun calculatePoolCapacity(accounts: List<LinkedAccount>): PoolCapacity {
        var totalCap = 0L
        var totalUsed = 0L
        var activeCount = 0

        for (account in accounts) {
            if (account.isAvailable) {
                totalCap += account.totalBytes
                totalUsed += account.usedBytes
                activeCount++
            }
        }

        val totalFree = (totalCap - totalUsed).coerceAtLeast(0L)
        return PoolCapacity(
            totalCapacityBytes = totalCap,
            totalUsedBytes = totalUsed,
            totalFreeBytes = totalFree,
            activeAccountsCount = activeCount
        )
    }

    /**
     * Splits a file into atomic chunks of specified chunk size (default 8MB) and computes SHA-256 checksums per chunk.
     */
    fun planFileChunking(file: File, chunkSize: Long = defaultChunkSize): List<ChunkMeta> {
        require(file.exists() && file.isFile) { "File does not exist or is not a valid file: ${file.absolutePath}" }
        require(chunkSize > 0) { "Chunk size must be greater than zero." }

        val chunks = mutableListOf<ChunkMeta>()
        file.inputStream().buffered().use { input ->
            var chunkIndex = 0
            var offset = 0L
            val buffer = ByteArray(chunkSize.toInt())

            while (true) {
                var bytesRead = 0
                while (bytesRead < buffer.size) {
                    val read = input.read(buffer, bytesRead, buffer.size - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }

                if (bytesRead == 0) break

                val chunkData = if (bytesRead == buffer.size) {
                    buffer.copyOf()
                } else {
                    buffer.copyOfRange(0, bytesRead)
                }

                val checksum = computeSha256(chunkData)
                chunks.add(
                    ChunkMeta(
                        chunkIndex = chunkIndex,
                        offset = offset,
                        size = bytesRead.toLong(),
                        sha256Checksum = checksum,
                        dataBytes = chunkData
                    )
                )

                offset += bytesRead
                chunkIndex++
                if (bytesRead < buffer.size) break
            }
        }

        return chunks
    }

    /**
     * Splits input stream data into atomic chunks and computes SHA-256 checksums per chunk.
     */
    fun planStreamChunking(inputStream: InputStream, chunkSize: Long = defaultChunkSize): List<ChunkMeta> {
        require(chunkSize > 0) { "Chunk size must be greater than zero." }

        val chunks = mutableListOf<ChunkMeta>()
        inputStream.buffered().use { input ->
            var chunkIndex = 0
            var offset = 0L
            val buffer = ByteArray(chunkSize.toInt())

            while (true) {
                var bytesRead = 0
                while (bytesRead < buffer.size) {
                    val read = input.read(buffer, bytesRead, buffer.size - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }

                if (bytesRead == 0) break

                val chunkData = if (bytesRead == buffer.size) {
                    buffer.copyOf()
                } else {
                    buffer.copyOfRange(0, bytesRead)
                }

                val checksum = computeSha256(chunkData)
                chunks.add(
                    ChunkMeta(
                        chunkIndex = chunkIndex,
                        offset = offset,
                        size = bytesRead.toLong(),
                        sha256Checksum = checksum,
                        dataBytes = chunkData
                    )
                )

                offset += bytesRead
                chunkIndex++
                if (bytesRead < buffer.size) break
            }
        }

        return chunks
    }

    /**
     * Generates a chunk allocation plan mapping chunks to target accounts using the configured allocation strategy.
     */
    fun generateAllocationPlan(chunks: List<ChunkMeta>, accounts: List<LinkedAccount>): List<ChunkAllocation> {
        return allocationStrategy.allocateChunks(chunks, accounts)
    }

    /**
     * Orchestrates preparing and recording a virtual file upload transaction in Room DB via SagaTransactionDao.
     * Inserts the VirtualFileEntity (with state SHARDING/PENDING) and associated FileShardEntity rows.
     */
    suspend fun prepareVirtualFileUploadTransaction(
        virtualFileId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        allocations: List<ChunkAllocation>
    ) {
        val virtualFile = com.architect.driveaggregator.data.VirtualFileEntity(
            virtualFileId = virtualFileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            sagaState = "SHARDING"
        )

        val shardEntities = allocations.map { allocation ->
            com.architect.driveaggregator.data.FileShardEntity(
                shardId = "${virtualFileId}_shard_${allocation.chunkIndex}",
                virtualFileId = virtualFileId,
                shardIndex = allocation.chunkIndex,
                targetAccountId = allocation.targetAccountId,
                shardSize = allocation.chunkMeta.size,
                uploadState = "PENDING"
            )
        }

        sagaTransactionDao.insertSagaTransaction(virtualFile, shardEntities)
        sagaTransactionDao.updateSagaState(virtualFileId, "PENDING")
    }

    /**
     * Computes SHA-256 hexadecimal checksum for a byte array.
     */
    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
