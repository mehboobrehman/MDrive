package com.architect.driveaggregator.sharing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for managing sharing links in Room database.
 */
@Dao
interface SharingLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: SharingLinkEntity)

    @Query("SELECT * FROM sharing_links WHERE linkId = :linkId")
    suspend fun getLink(linkId: String): SharingLinkEntity?

    @Query("SELECT * FROM sharing_links WHERE virtualFileId = :virtualFileId AND isRevoked = 0")
    suspend fun getActiveLinksForFile(virtualFileId: String): List<SharingLinkEntity>

    @Query("SELECT * FROM sharing_links")
    suspend fun getAllLinks(): List<SharingLinkEntity>

    @Update
    suspend fun updateLink(link: SharingLinkEntity)

    @Query("UPDATE sharing_links SET isRevoked = 1 WHERE linkId = :linkId")
    suspend fun revokeLink(linkId: String)
}
