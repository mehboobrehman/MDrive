package com.architect.driveaggregator.update

import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(
        val release: GitHubRelease,
        val apkAsset: ReleaseAsset,
        val checksumAsset: ReleaseAsset
    ) : UpdateState
    data class Downloading(
        val progress: Float, // 0.0 to 1.0
        val bytesRead: Long,
        val totalBytes: Long
    ) : UpdateState
    data class Verified(
        val apkFile: File,
        val release: GitHubRelease
    ) : UpdateState
    data class Installing(
        val apkFile: File
    ) : UpdateState
    data class Error(
        val error: UpdateError,
        val message: String
    ) : UpdateState
}
