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
    private val storageProviders: Map<CloudProviderType, @JvmSuppressWildcards StorageProvider>
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

        entities.map { entity ->
            FederatedSearchResult(
                fileId = entity.virtualFileId,
                name = entity.fileName,
                mimeType = entity.mimeType,
                sizeBytes = entity.fileSize,
                providerId = "LOCAL",
                providerType = filter.providerType ?: CloudProviderType.GOOGLE_DRIVE,
                downloadUrl = entity.shareUrl,
                createdAt = entity.createdAt,
                aiCategory = entity.aiCategory,
                source = FederatedSearchResult.SearchSource.LOCAL_ROOM
            )
        }
    }

    /**
     * Concurrently queries remote cloud providers for live results matching advanced filtering criteria.
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
                    remoteFiles.filter { file ->
                        if (file.isFolder) return@filter false
                        val matchesQuery = filter.query.isBlank() || file.name.contains(filter.query, ignoreCase = true)
                        val matchesMime = filter.mimeType.isBlank() || file.mimeType.contains(filter.mimeType, ignoreCase = true)
                        val matchesMinSize = filter.minSize <= 0L || file.sizeBytes >= filter.minSize
                        val matchesMaxSize = filter.maxSize <= 0L || file.sizeBytes <= filter.maxSize
                        val matchesStart = filter.startTime <= 0L || file.createdAt >= filter.startTime
                        val matchesEnd = filter.endTime <= 0L || file.createdAt <= filter.endTime
                        matchesQuery && matchesMime && matchesMinSize && matchesMaxSize && matchesStart && matchesEnd
                    }.map { cloudFile ->
                        FederatedSearchResult(
                            fileId = cloudFile.id,
                            name = cloudFile.name,
                            mimeType = cloudFile.mimeType,
                            sizeBytes = cloudFile.sizeBytes,
                            providerId = provider.providerId,
                            providerType = providerType,
                            downloadUrl = cloudFile.downloadUrl,
                            createdAt = cloudFile.createdAt,
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
