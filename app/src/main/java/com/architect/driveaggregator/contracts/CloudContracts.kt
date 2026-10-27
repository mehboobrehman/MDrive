package com.architect.driveaggregator.contracts

/**
 * Enumeration of supported cloud storage providers in MDrive.
 */
enum class CloudProviderType(
    val providerId: String,
    val displayName: String,
    val iconResName: String,
    val defaultQuotaBytes: Long
) {
    GOOGLE_DRIVE("google_drive", "Google Drive", "ic_google_drive", 15L * 1024 * 1024 * 1024),
    ONEDRIVE("onedrive", "Microsoft OneDrive", "ic_onedrive", 5L * 1024 * 1024 * 1024),
    DROPBOX("dropbox", "Dropbox", "ic_dropbox", 2L * 1024 * 1024 * 1024),
    MEGA("mega", "MEGA", "ic_mega", 20L * 1024 * 1024 * 1024),
    BOX("box", "Box", "ic_box", 10L * 1024 * 1024 * 1024),
    TERABOX("terabox", "TeraBox", "ic_terabox", 1024L * 1024 * 1024 * 1024) // 1 TB
}

/**
 * Universal account session model linked to the primary Google ID OAuth account.
 */
data class LinkedAccountContract(
    val accountId: String,
    val providerType: CloudProviderType,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val isPrimaryGoogleAccount: Boolean,
    val tokenExpiresAt: Long,
    val totalQuotaBytes: Long,
    val usedQuotaBytes: Long,
    val isConnected: Boolean,
    val linkedAtTimestamp: Long
)

/**
 * Token vault contract for securely storing and retrieving access and refresh tokens.
 */
data class TokenVaultContract(
    val accountId: String,
    val providerType: CloudProviderType,
    val accessTokenEncrypted: String,
    val refreshTokenEncrypted: String?,
    val tokenExpiresAt: Long,
    val scopes: List<String>
)

/**
 * Federated cross-provider search query parameters and filters.
 */
data class SearchQueryContract(
    val query: String,
    val providerTypes: List<CloudProviderType>? = null,
    val mimeTypes: List<String>? = null,
    val createdAfterTimestamp: Long? = null,
    val createdBeforeTimestamp: Long? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val includeOfflineOnly: Boolean = false,
    val limit: Int = 50,
    val offset: Int = 0
)

/**
 * Faceted search result representation combining results across multiple providers.
 */
data class SearchResultContract(
    val fileId: String,
    val name: String,
    val providerType: CloudProviderType,
    val remoteId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedTimestamp: Long,
    val downloadUrl: String?,
    val isCachedOffline: Boolean,
    val matchScore: Float
)

/**
 * AI-powered smart organization classification contract.
 */
data class SmartOrganizationCategoryContract(
    val categoryId: String,
    val categoryName: String,
    val description: String,
    val suggestedAction: String,
    val fileCount: Int,
    val totalSizeBytes: Long
)

/**
 * Secure sharing and collaboration link contract.
 */
data class SharingLinkContract(
    val linkId: String,
    val fileId: String,
    val providerType: CloudProviderType,
    val url: String,
    val isPasswordProtected: Boolean,
    val expiresAtTimestamp: Long?,
    val downloadCount: Int,
    val maxDownloads: Int?,
    val createdAtTimestamp: Long
)

/**
 * Offline caching status contract.
 */
data class OfflineCacheStatusContract(
    val fileId: String,
    val isPinned: Boolean,
    val localFilePath: String?,
    val cachedBytes: Long,
    val lastSyncedTimestamp: Long,
    val syncStatus: CacheSyncStatus
)

enum class CacheSyncStatus {
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    CONFLICT,
    ERROR
}
