package com.architect.driveaggregator.version

import android.content.Context
import android.os.Build
import android.util.Log
import com.architect.driveaggregator.BuildConfig
import com.architect.driveaggregator.telemetry.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppVersionInfo(
    val version: String,
    val fullVersion: String,
    val commitHash: String,
    val buildTimestamp: String,
    val environment: String,
    val versionCode: Int
) {
    companion object {
        val current: AppVersionInfo
            get() = AppVersionInfo(
                version = BuildConfig.APP_VERSION,
                fullVersion = BuildConfig.FULL_VERSION_STRING,
                commitHash = BuildConfig.GIT_COMMIT_HASH,
                buildTimestamp = BuildConfig.BUILD_TIMESTAMP,
                environment = BuildConfig.ENVIRONMENT,
                versionCode = BuildConfig.VERSION_CODE
            )
    }
}

data class VersionCheckResult(
    val runningVersion: AppVersionInfo,
    val latestVersion: AppVersionInfo?,
    val isStale: Boolean,
    val updateMessage: String?
)

/**
 * Manages checking for newer build deployments by comparing the running build version
 * against packaged assets or a remote/local server endpoint version.json.
 */
class VersionCheckManager(private val context: Context) {

    companion object {
        private const val TAG = "VersionCheckManager"
        const val PREF_NAME = "mdrive_version_prefs"
        const val KEY_LAST_CHECKED = "last_checked_timestamp"
        const val KEY_DISMISSED_VERSION = "dismissed_version_code"
    }

    /**
     * Reads the version info from bundled assets (version.json generated at build time).
     */
    suspend fun getAssetVersionInfo(): AppVersionInfo = withContext(Dispatchers.IO) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("version.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            AppVersionInfo(
                version = json.optString("version", BuildConfig.APP_VERSION),
                fullVersion = json.optString("fullVersion", BuildConfig.FULL_VERSION_STRING),
                commitHash = json.optString("commitHash", BuildConfig.GIT_COMMIT_HASH),
                buildTimestamp = json.optString("buildTimestamp", BuildConfig.BUILD_TIMESTAMP),
                environment = json.optString("environment", BuildConfig.ENVIRONMENT),
                versionCode = json.optInt("versionCode", BuildConfig.VERSION_CODE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read version.json from assets, falling back to BuildConfig", e)
            AppVersionInfo.current
        }
    }

    /**
     * Checks for a newer build version by fetching from a server endpoint or local cache/assets.
     * If serverUrl is provided (e.g. telemetry server endpoint or update server), attempts HTTP fetch.
     * Otherwise compares against asset version and stored version metadata.
     */
    suspend fun checkForUpdates(serverVersionUrl: String? = null): VersionCheckResult = withContext(Dispatchers.IO) {
        val running = AppVersionInfo.current
        var latest: AppVersionInfo? = null

        if (!serverVersionUrl.isNullOrBlank()) {
            try {
                val url = URL(serverVersionUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    latest = AppVersionInfo(
                        version = json.optString("version", running.version),
                        fullVersion = json.optString("fullVersion", running.fullVersion),
                        commitHash = json.optString("commitHash", running.commitHash),
                        buildTimestamp = json.optString("buildTimestamp", running.buildTimestamp),
                        environment = json.optString("environment", running.environment),
                        versionCode = json.optInt("versionCode", running.versionCode)
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not fetch remote version from $serverVersionUrl: ${e.message}")
            }
        }

        // Fallback to asset version if remote check failed or wasn't provided
        val resolvedLatest = latest ?: getAssetVersionInfo()

        val isStale = resolvedLatest.versionCode > running.versionCode || 
            (resolvedLatest.versionCode == running.versionCode && resolvedLatest.commitHash != running.commitHash && resolvedLatest.buildTimestamp > running.buildTimestamp)

        val updateMessage = if (isStale) {
            "A newer build of MDrive is available (${resolvedLatest.fullVersion}). Running version is ${running.fullVersion}."
        } else {
            null
        }

        if (isStale) {
            AgentLogger.logEvent(
                level = "INFO",
                category = "VERSION",
                event = "STALE_CLIENT_DETECTED",
                message = "Stale client detected: running ${running.fullVersion}, latest is ${resolvedLatest.fullVersion}",
                payload = mapOf(
                    "runningVersionCode" to running.versionCode,
                    "latestVersionCode" to resolvedLatest.versionCode,
                    "runningCommit" to running.commitHash,
                    "latestCommit" to resolvedLatest.commitHash
                )
            )
        }

        VersionCheckResult(
            runningVersion = running,
            latestVersion = resolvedLatest,
            isStale = isStale,
            updateMessage = updateMessage
        )
    }

    fun saveLastCheckedTimestamp() {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply()
    }

    fun getLastCheckedTimestamp(): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_CHECKED, 0L)
    }

    fun setDismissedVersionCode(versionCode: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DISMISSED_VERSION, versionCode).apply()
    }

    fun getDismissedVersionCode(): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DISMISSED_VERSION, 0)
    }
}
