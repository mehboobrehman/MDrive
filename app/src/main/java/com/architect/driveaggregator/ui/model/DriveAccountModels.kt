package com.architect.driveaggregator.ui.model

data class DriveAccountInfo(
    val accountId: String,
    val email: String,
    val displayName: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val status: AccountStatus,
    val avatarUrl: String? = null
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
