package com.architect.driveaggregator.ui.model

import com.architect.driveaggregator.provider.CloudFile

sealed interface ExplorerItem {
    data class VirtualFile(val virtualFile: VirtualFileItem) : ExplorerItem
    data class CloudFile(val cloudFile: com.architect.driveaggregator.provider.CloudFile, val providerType: CloudProviderType) : ExplorerItem
}

data class VirtualFileItem(
    val virtualFileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val sagaState: String, // e.g. "PENDING", "COMPLETED", "UPLOADING", "DOWNLOADING", "ERROR"
    val uploadedShardsCount: Int,
    val totalShardsCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

data class TransferProgressItem(
    val transferId: String,
    val virtualFileId: String,
    val fileName: String,
    val transferType: TransferType, // UPLOAD, DOWNLOAD, SYNC
    val status: TransferStatus,     // PENDING, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED
    val progressPercent: Float,     // 0.0 to 1.0
    val bytesTransferred: Long,
    val totalBytes: Long,
    val currentShardIndex: Int? = null,
    val totalShards: Int? = null,
    val errorMessage: String? = null
)

enum class TransferType {
    UPLOAD, DOWNLOAD, SYNC
}

enum class TransferStatus {
    PENDING, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED
}
