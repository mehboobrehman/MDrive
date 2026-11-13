package com.architect.driveaggregator.update

import com.google.gson.annotations.SerializedName

data class GitHubReleaseDto(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("assets") val assets: List<ReleaseAssetDto>?
) {
    fun toDomain(): GitHubRelease? {
        if (tagName.isNullOrBlank() || htmlUrl.isNullOrBlank()) return null
        return GitHubRelease(
            tagName = tagName,
            name = name ?: tagName,
            publishedAt = publishedAt ?: "",
            body = body ?: "",
            htmlUrl = htmlUrl,
            assets = assets?.mapNotNull { it.toDomain() } ?: emptyList()
        )
    }
}

data class ReleaseAssetDto(
    @SerializedName("name") val name: String?,
    @SerializedName("browser_download_url") val browserDownloadUrl: String?,
    @SerializedName("size") val size: Long?,
    @SerializedName("content_type") val contentType: String?
) {
    fun toDomain(): ReleaseAsset? {
        if (name.isNullOrBlank() || browserDownloadUrl.isNullOrBlank()) return null
        return ReleaseAsset(
            name = name,
            downloadUrl = browserDownloadUrl,
            size = size ?: 0L,
            contentType = contentType ?: ""
        )
    }
}
