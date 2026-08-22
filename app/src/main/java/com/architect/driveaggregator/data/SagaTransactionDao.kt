package com.architect.driveaggregator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for managing Saga Transaction Ledger operations (VirtualFiles and FileShards)
 * ensuring atomic multi-account write-ahead consistency and resumable upload tracking.
 */
@Dao
interface SagaTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVirtualFile(virtualFile: VirtualFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileShards(shards: List<FileShardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileShard(shard: FileShardEntity)

    /**
     * Atomically inserts a virtual file along with its associated file shards.
     */
    @Transaction
    suspend fun insertSagaTransaction(virtualFile: VirtualFileEntity, shards: List<FileShardEntity>) {
        insertVirtualFile(virtualFile)
        insertFileShards(shards)
    }

    @Query("SELECT * FROM virtual_files WHERE virtualFileId = :virtualFileId")
    suspend fun getVirtualFile(virtualFileId: String): VirtualFileEntity?

    @Query("SELECT * FROM virtual_files WHERE virtualFileId = :virtualFileId")
    fun observeVirtualFile(virtualFileId: String): Flow<VirtualFileEntity?>

    @Query("SELECT * FROM file_shards WHERE virtualFileId = :virtualFileId ORDER BY shardIndex ASC")
    suspend fun getShardsForVirtualFile(virtualFileId: String): List<FileShardEntity>

    @Query("SELECT * FROM file_shards WHERE virtualFileId = :virtualFileId ORDER BY shardIndex ASC")
    fun observeShardsForVirtualFile(virtualFileId: String): Flow<List<FileShardEntity>>

    @Query("UPDATE virtual_files SET sagaState = :newState, updatedAt = :timestamp WHERE virtualFileId = :virtualFileId")
    suspend fun updateSagaState(virtualFileId: String, newState: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE file_shards SET uploadState = :newState, updatedAt = :timestamp WHERE shardId = :shardId")
    suspend fun updateShardState(shardId: String, newState: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE file_shards SET remoteFileId = :remoteFileId, resumableSessionUri = :sessionUri, lastKnownUploadedOffset = :offset, updatedAt = :timestamp WHERE shardId = :shardId")
    suspend fun updateShardProgress(
        shardId: String,
        remoteFileId: String?,
        sessionUri: String?,
        offset: Long,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE file_shards SET lastKnownUploadedOffset = :offset, updatedAt = :timestamp WHERE shardId = :shardId")
    suspend fun updateShardOffset(shardId: String, offset: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM virtual_files WHERE sagaState IN ('PENDING', 'SHARDING', 'ROLLBACK')")
    suspend fun getIncompleteSagaTransactions(): List<VirtualFileEntity>

    @Query("DELETE FROM virtual_files WHERE virtualFileId = :virtualFileId")
    suspend fun deleteVirtualFile(virtualFileId: String)
}
