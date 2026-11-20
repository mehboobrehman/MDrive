package com.architect.driveaggregator.data

import androidx.room.withTransaction
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager handling transactional entity versioning, snapshot storage, automatic version increments,
 * and point-in-time rollback for MDrive entities (e.g., VirtualFileEntity).
 *
 * ## Architectural Justification: Snapshot-Based Versioning vs. Delta-Based
 * 1. **O(1) Read & Rollback Performance**: Accessing any historical version or rolling back state requires
 *    a single indexed SQL lookup (`SELECT * FROM entity_histories WHERE entity_id = :id AND version = :v`),
 *    achieving O(1) time complexity. Delta-based versioning requires traversing and replaying an O(N) patch chain
 *    from origin or latest snapshot, degrading performance as history depth increases.
 * 2. **No O(N) Patch Chain Recomputation in SQLite**: SQLite lacks native JSON patch application functions
 *    across arbitrary arrays/versions without complex recursive CTEs or fragile application-level JSON diff patches.
 * 3. **Minimal Storage Overhead on Mobile**: Virtual file metadata entities are compact (~200–500 bytes as JSON).
 *    Storing 100 full snapshots requires ~30KB of storage, which is negligible on modern mobile devices while
 *    providing instantaneous, infallible recovery and audit trails without CPU overhead or disk fragmentation.
 */
@Singleton
class EntityVersioningManager @Inject constructor(
    private val database: AppDatabase,
    private val virtualFileDao: VirtualFileDao,
    private val entityHistoryDao: EntityHistoryDao
) {
    private val gson = Gson()

    /**
     * Inserts a new virtual file with version = 1 and records its initial snapshot in history
     * atomically within a database transaction.
     */
    suspend fun insertWithVersion(
        file: VirtualFileEntity,
        changedBy: String = "system",
        changeReason: String = "Initial creation"
    ) {
        database.withTransaction {
            val fileToInsert = file.copy(
                version = 1,
                updatedAt = System.currentTimeMillis()
            )
            virtualFileDao.insertFile(fileToInsert)

            val snapshot = gson.toJson(fileToInsert)
            val history = EntityHistoryEntity(
                entityId = fileToInsert.virtualFileId,
                version = 1,
                snapshotJson = snapshot,
                changedBy = changedBy,
                changeReason = changeReason,
                createdAt = System.currentTimeMillis()
            )
            entityHistoryDao.insertHistory(history)
        }
    }

    /**
     * Updates an existing virtual file, atomically incrementing its version, persisting the update,
     * and recording an immutable snapshot history entry.
     */
    suspend fun updateWithVersion(
        file: VirtualFileEntity,
        changedBy: String = "system",
        changeReason: String = "Entity update"
    ) {
        database.withTransaction {
            val existing = virtualFileDao.getFileById(file.virtualFileId)
            val currentVersion = existing?.version ?: entityHistoryDao.getLatestVersion(file.virtualFileId) ?: 0
            val newVersion = currentVersion + 1

            val updatedFile = file.copy(
                version = newVersion,
                updatedAt = System.currentTimeMillis()
            )
            virtualFileDao.insertFile(updatedFile)

            val snapshot = gson.toJson(updatedFile)
            val history = EntityHistoryEntity(
                entityId = updatedFile.virtualFileId,
                version = newVersion,
                snapshotJson = snapshot,
                changedBy = changedBy,
                changeReason = changeReason,
                createdAt = System.currentTimeMillis()
            )
            entityHistoryDao.insertHistory(history)
        }
    }

    /**
     * Rolls back a virtual file to a specific target version. Creates a new version (latest + 1)
     * containing the state of the target version, ensuring a complete, auditable history trail.
     */
    suspend fun rollbackToVersion(
        entityId: String,
        targetVersion: Int,
        changedBy: String = "system",
        changeReason: String = "Rollback to version $targetVersion"
    ): VirtualFileEntity {
        return database.withTransaction {
            val historyRecord = entityHistoryDao.getHistoryAtVersion(entityId, targetVersion)
                ?: throw IllegalArgumentException("History record not found for entity $entityId at version $targetVersion")

            val targetFile = gson.fromJson(historyRecord.snapshotJson, VirtualFileEntity::class.java)
            val latestVersion = entityHistoryDao.getLatestVersion(entityId) ?: targetVersion
            val newVersion = latestVersion + 1

            val rolledBackFile = targetFile.copy(
                version = newVersion,
                updatedAt = System.currentTimeMillis()
            )
            virtualFileDao.insertFile(rolledBackFile)

            val snapshot = gson.toJson(rolledBackFile)
            val newHistory = EntityHistoryEntity(
                entityId = entityId,
                version = newVersion,
                snapshotJson = snapshot,
                changedBy = changedBy,
                changeReason = changeReason,
                createdAt = System.currentTimeMillis()
            )
            entityHistoryDao.insertHistory(newHistory)

            rolledBackFile
        }
    }

    /**
     * Retrieves the complete immutable version history list for an entity, ordered newest first.
     */
    suspend fun getEntityHistory(entityId: String): List<EntityHistoryEntity> {
        return entityHistoryDao.getHistoryForEntity(entityId)
    }

    /**
     * Retrieves a specific version snapshot of an entity.
     */
    suspend fun getSnapshotAtVersion(entityId: String, version: Int): VirtualFileEntity? {
        val history = entityHistoryDao.getHistoryAtVersion(entityId, version) ?: return null
        return gson.fromJson(history.snapshotJson, VirtualFileEntity::class.java)
    }
}
