package com.architect.driveaggregator.update

import android.content.Context
import com.architect.driveaggregator.BuildConfig
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val gson: Gson = Gson()
    companion object {
        private const val TAG = "UpdateRepository"
        const val DEFAULT_GITHUB_API_URL = "https://api.github.com/repos/mehboobrehman/MDrive/releases/latest"
    }

    private val activeCall = AtomicReference<okhttp3.Call?>()

    suspend fun fetchLatestRelease(apiUrl: String = DEFAULT_GITHUB_API_URL): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            val response = okHttpClient.newCall(request).execute()
            when {
                response.code == 403 || response.header("X-RateLimit-Remaining") == "0" -> {
                    return@withContext Result.failure(UpdateRepoException(UpdateError.GITHUB_RATE_LIMIT, "GitHub API rate limit exceeded"))
                }
                response.code == 404 -> {
                    return@withContext Result.failure(UpdateRepoException(UpdateError.NO_PUBLISHED_RELEASE, "No published release found"))
                }
                !response.isSuccessful -> {
                    return@withContext Result.failure(UpdateRepoException(UpdateError.INVALID_RESPONSE, "Failed to fetch release: HTTP ${response.code}"))
                }
            }

            val bodyString = response.body?.string() ?: return@withContext Result.failure(UpdateRepoException(UpdateError.INVALID_RESPONSE, "Empty response body"))
            val releaseDto = gson.fromJson(bodyString, GitHubReleaseDto::class.java) ?: return@withContext Result.failure(UpdateRepoException(UpdateError.INVALID_RESPONSE, "Malformed release JSON"))
            val release = releaseDto.toDomain() ?: return@withContext Result.failure(UpdateRepoException(UpdateError.INVALID_RESPONSE, "Invalid release data"))

            try {
                SemVer.parse(release.tagName)
            } catch (e: Exception) {
                return@withContext Result.failure(UpdateRepoException(UpdateError.INVALID_RESPONSE, "Malformed version tag: ${release.tagName}"))
            }

            Result.success(release)
        } catch (e: IOException) {
            Result.failure(UpdateRepoException(UpdateError.NO_NETWORK, "Network error: ${e.localizedMessage}", e))
        } catch (e: Exception) {
            Result.failure(UpdateRepoException(UpdateError.UNKNOWN, "Unexpected error: ${e.localizedMessage}", e))
        }
    }

    fun validateAssets(release: GitHubRelease): Pair<ReleaseAsset, ReleaseAsset>? {
        var apkAsset: ReleaseAsset? = null
        var checksumAsset: ReleaseAsset? = null

        for (asset in release.assets) {
            val url = asset.downloadUrl
            val isHttps = url.startsWith("https://", ignoreCase = true) || 
                          (BuildConfig.DEBUG && (url.startsWith("http://10.0.2.2") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")))
            if (!isHttps) continue

            val name = asset.name.lowercase()
            if (name.endsWith(".apk") && (name.contains("mdrive") || name.contains("mdrive"))) {
                apkAsset = asset
            } else if (name == "sha256sums" || name == "sha256sums.txt") {
                checksumAsset = asset
            }
        }

        if (apkAsset != null && checksumAsset != null) {
            return Pair(apkAsset, checksumAsset)
        }
        return null
    }

    fun downloadAndVerify(
        apkAsset: ReleaseAsset,
        checksumAsset: ReleaseAsset,
        release: GitHubRelease
    ): Flow<UpdateState> = callbackFlow {
        try {
            trySend(UpdateState.Downloading(0f, 0L, apkAsset.size))

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val uniquePartFile = File(updatesDir, "mdrive_${System.currentTimeMillis()}.apk.part")
            val targetApkFile = File(updatesDir, "MDrive-${release.tagName}.apk")

            val checksumRequest = Request.Builder().url(checksumAsset.downloadUrl).build()
            val checksumResponse = okHttpClient.newCall(checksumRequest).execute()
            if (!checksumResponse.isSuccessful) {
                trySend(UpdateState.Error(UpdateError.DOWNLOAD_FAILED, "Failed to download SHA256SUMS: HTTP ${checksumResponse.code}"))
                close()
                return@callbackFlow
            }
            val checksumsContent = checksumResponse.body?.string() ?: ""

            val apkRequest = Request.Builder().url(apkAsset.downloadUrl).build()
            val call = okHttpClient.newCall(apkRequest)
            activeCall.set(call)

            val apkResponse = call.execute()
            if (!apkResponse.isSuccessful) {
                trySend(UpdateState.Error(UpdateError.DOWNLOAD_FAILED, "Failed to download APK: HTTP ${apkResponse.code}"))
                close()
                return@callbackFlow
            }

            val body = apkResponse.body ?: run {
                trySend(UpdateState.Error(UpdateError.DOWNLOAD_FAILED, "Empty APK response body"))
                close()
                return@callbackFlow
            }

            val contentLength = body.contentLength().let { if (it <= 0) apkAsset.size else it }
            var bytesReadTotal = 0L

            FileOutputStream(uniquePartFile).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isClosedForSend) {
                            uniquePartFile.delete()
                            return@callbackFlow
                        }
                        fos.write(buffer, 0, read)
                        bytesReadTotal += read
                        val progress = if (contentLength > 0) bytesReadTotal.toFloat() / contentLength.toFloat() else 0f
                        trySend(UpdateState.Downloading(progress, bytesReadTotal, contentLength))
                    }
                }
            }

            if (targetApkFile.exists()) targetApkFile.delete()
            if (!uniquePartFile.renameTo(targetApkFile)) {
                uniquePartFile.copyTo(targetApkFile, overwrite = true)
                uniquePartFile.delete()
            }

            val isChecksumValid = ChecksumVerifier.verify(targetApkFile, checksumsContent, apkAsset.name)
            if (!isChecksumValid) {
                targetApkFile.delete()
                trySend(UpdateState.Error(UpdateError.CHECKSUM_MISMATCH, "SHA-256 checksum verification failed"))
                close()
                return@callbackFlow
            }

            val isSignatureValid = SignatureVerifier.verifySignature(context, targetApkFile)
            if (!isSignatureValid) {
                targetApkFile.delete()
                trySend(UpdateState.Error(UpdateError.SIGNATURE_MISMATCH, "APK signing certificate does not match installed app"))
                close()
                return@callbackFlow
            }

            trySend(UpdateState.Verified(targetApkFile, release))
        } catch (e: IOException) {
            trySend(UpdateState.Error(UpdateError.NO_NETWORK, "Download network error: ${e.localizedMessage}"))
        } catch (e: Exception) {
            trySend(UpdateState.Error(UpdateError.UNKNOWN, "Download error: ${e.localizedMessage}"))
        } finally {
            activeCall.set(null)
            close()
        }

        awaitClose {
            activeCall.get()?.cancel()
        }
    }

    fun cancelDownload() {
        activeCall.get()?.cancel()
    }
}

class UpdateRepoException(val error: UpdateError, message: String, cause: Throwable? = null) : Exception(message, cause)
