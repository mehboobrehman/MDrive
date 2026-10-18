package com.architect.driveaggregator.search

import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.provider.StorageProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search filter options for federated search across local Room and remote cloud providers.
 */
data class SearchFilter(
    val query: String = "",
    val mimeType: String = "",
    val providerId: String = "",
    val providerType: CloudProviderType? = null,
    val minSize: Long = 0L,
    val maxSize: Long = 0L,
    val startTime: Long = 0L,
    val endTime: Long = 0L
)

/**
 * Unified search result item representation.
 */
data class FederatedSearchResult(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val providerId: String,
    val providerType: CloudProviderType,
    val downloadUrl: String?,
    val createdAt: Long,
    val aiCategory: String?,
    val source: SearchSource
) {
    enum class SearchSource {
        LOCAL_ROOM,
        REMOTE_PROVIDER
    }
}

@Singleton
class FederatedSearchManager @Inject constructor(
    private val virtualFileDao: VirtualFileDao,
    private val storageProviders: Map<CloudProviderType, StorageProvider>
) {

    /**
     * Instantly queries local Room DB for cached virtual files matching advanced filters.
     */
    suspend fun searchLocal(filter: SearchFilter): List<FederatedSearchResult> = withContext(Dispatchers.IO) {
        val entities = virtualFileDao.searchFilesAdvanced(
            query = filter.query,
            mimeType = filter.mimeType,
            minSize = filter.minSize,
            maxSize = filter.maxSize,
            startTime = filter.startTime,
            endTime = filter.endTime
        )
        // If providerId filter is specified, filter locally as well
        val filteredEntities = if (filter.providerId.isNotBlank()) {
            entities.filter { it.providerId == filter.providerId }
        } else {
            entities
        }

        filteredEntities.map { entity ->
            FederatedSearchResult(
                fileId = entity.virtualFileId,
                name = entity.fileName,
                mimeType = entity.mimeType,
                sizeBytes = entity.fileSize,
                providerId = entity.providerId.ifBlank { "LOCAL" },
                providerType = filter.providerType ?: CloudProviderType.GOOGLE_DRIVE,
                downloadUrl = entity.shareUrl,
                createdAt = entity.createdAt,
                aiCategory = entity.aiCategory,
                source = FederatedSearchResult.SearchSource.LOCAL_ROOM
            )
        }
    }

    /**
     * Concurrently queries remote cloud providers (or specific providers if filtered) for live results,
     * applying advanced filtering criteria.
     */
    suspend fun searchRemoteConcurrently(
        filter: SearchFilter,
        activeProviders: List<CloudProviderType> = storageProviders.keys.toList()
    ): List<FederatedSearchResult> = coroutineScope {
        val targetProviders = if (filter.providerType != null) {
            listOf(filter.providerType)
        } else {
            activeProviders
        }

        val deferredResults = targetProviders.map { providerType ->
            val provider = storageProviders[providerType]
            async(Dispatchers.IO) {
                if (provider == null) return@async emptyList<FederatedSearchResult>()
                try {
                    val remoteFiles = provider.listFiles("")
                    remoteFiles.mapNotNull { cloudFile ->
                        val mimeType = cloudFile.mimeType ?: ""
                        val size = cloudFile.sizeBytes
                        val created = cloudFile.createdAt

                        if (filter.query.isNotBlank() && !cloudFile.name.contains(filter.query, ignoreCase = true)) {
                            return@mapNotNull null
                        }
                        if (filter.mimeType.isNotBlank() && !mimeType.contains(filter.mimeType, ignoreCase = true)) {
                            return@mapNotNull null
                        }
                        if (filter.minSize > 0 && size < filter.minSize) {
                            return@mapNotNull null
                        }
                        if (filter.maxSize > 0 && size > filter.maxSize) {
                            return@mapNotNull null
                        }
                        if (filter.startTime > 0 && created < filter.startTime) {
                            return@mapNotNull null
                        }
                        if (filter.endTime > 0 && created > filter.endTime) {
                            return@mapNotNull null
                        }

                        FederatedSearchResult(
                            fileId = cloudFile.id,
                            name = cloudFile.name,
                            mimeType = mimeType,
                            sizeBytes = size,
                            providerId = providerType.name,
                            providerType = providerType,
                            downloadUrl = cloudFile.downloadUrl,
                            createdAt = created,
                            aiCategory = null,
                            source = FederatedSearchResult.SearchSource.REMOTE_PROVIDER
                        )
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        deferredResults.awaitAll().flatten()
    }

    /**
     * Executes federated search: first yields/returns local cached results instantly,
     * and concurrently queries remote providers for live delta results.
     */
    suspend fun searchFederated(
        filter: SearchFilter,
        activeProviders: List<CloudProviderType> = storageProviders.keys.toList()
    ): List<FederatedSearchResult> = coroutineScope {
        val localDeferred = async(Dispatchers.IO) { searchLocal(filter) }
        val remoteDeferred = async(Dispatchers.IO) { searchRemoteConcurrently(filter, activeProviders) }

        val localResults = localDeferred.await()
        val remoteResults = remoteDeferred.await()

        val resultMap = LinkedHashMap<String, FederatedSearchResult>()
        for (item in localResults) {
            resultMap[item.providerId + "_" + item.fileId] = item
        }
        for (item in remoteResults) {
            resultMap[item.providerId + "_" + item.fileId] = item
        }

        resultMap.values.toList()
    }
}
