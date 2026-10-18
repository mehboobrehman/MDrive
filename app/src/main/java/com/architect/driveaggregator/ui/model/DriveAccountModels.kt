package com.architect.driveaggregator.ui.model

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

data class DriveAccountInfo(
    val accountId: String,
    val email: String,
    val displayName: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val status: AccountStatus,
    val avatarUrl: String? = null,
    val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE
) {
    val usagePercentage: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()) else 0f
}

enum class AccountStatus {
    ACTIVE,
    SYNCING,
    ERROR,
    EXPIRED_TOKEN
}

data class PooledStorageOverview(
    val totalCapacityBytes: Long,
    val totalUsedBytes: Long,
    val totalFreeBytes: Long,
    val accounts: List<DriveAccountInfo>
) {
    val overallUsagePercentage: Float
        get() = if (totalCapacityBytes > 0) (totalUsedBytes.toFloat() / totalCapacityBytes.toFloat()) else 0f
}
