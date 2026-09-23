package com.architect.driveaggregator.version

import android.content.Context
import androidx.work.*
import com.architect.driveaggregator.telemetry.AgentLogger
import java.util.concurrent.TimeUnit

class VersionCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val versionManager = VersionCheckManager(applicationContext)
            versionManager.saveLastCheckedTimestamp()
            
            val serverUrl = AgentLogger.getServerUrl()?.let { "$it/version.json" }
            val result = versionManager.checkForUpdates(serverUrl)

            if (result.isStale) {
                AgentLogger.logEvent(
                    level = "INFO",
                    category = "VERSION",
                    event = "VERSION_CHECK_BACKGROUND",
                    message = "Background version check found newer build: ${result.latestVersion?.fullVersion}",
                    payload = mapOf("latest" to (result.latestVersion?.fullVersion ?: ""))
                )
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "MDriveVersionCheckPeriodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<VersionCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
