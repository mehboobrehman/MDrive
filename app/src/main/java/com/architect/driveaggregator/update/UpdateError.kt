package com.architect.driveaggregator.update

enum class UpdateError {
    NO_NETWORK,
    GITHUB_RATE_LIMIT,
    NO_PUBLISHED_RELEASE,
    CHECKSUM_MISMATCH,
    SIGNATURE_MISMATCH,
    INSTALLER_UNAVAILABLE,
    INVALID_RESPONSE,
    DOWNLOAD_FAILED,
    UNKNOWN
}
