package com.architect.driveaggregator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for querying and indexing VirtualFileEntity records in Room DB.
 */
@Dao
interface VirtualFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<VirtualFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: VirtualFileEntity)

    @Query("SELECT * FROM virtual_files WHERE fileName LIKE '%' || :query || '%' OR ai_category LIKE '%' || :query || '%'")
    suspend fun searchFiles(query: String): List<VirtualFileEntity>

    @Query("""
        SELECT * FROM virtual_files 
        WHERE (:query = '' OR fileName LIKE '%' || :query || '%' OR ai_category LIKE '%' || :query || '%')
        AND (:mimeType = '' OR mimeType LIKE '%' || :mimeType || '%')
        AND (fileSize >= :minSize AND (:maxSize = 0 OR fileSize <= :maxSize))
        AND (createdAt >= :startTime AND (:endTime = 0 OR createdAt <= :endTime))
    """)
    suspend fun searchFilesAdvanced(
        query: String,
        mimeType: String,
        minSize: Long,
        maxSize: Long,
        startTime: Long,
        endTime: Long
    ): List<VirtualFileEntity>

    @Query("SELECT * FROM virtual_files")
    suspend fun getAllFiles(): List<VirtualFileEntity>

    @Query("DELETE FROM virtual_files WHERE virtualFileId = :fileId")
    suspend fun deleteFile(fileId: String)
}
