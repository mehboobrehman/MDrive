package com.architect.driveaggregator

import android.app.Application
import com.architect.driveaggregator.BuildConfig
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MDriveApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        val startTime = System.currentTimeMillis()
        super.onCreate()
        
        android.util.Log.i(
            "MDriveApplication",
            "BOOT_VERSION: semver=${BuildConfig.SEMVER_VERSION}, commit_hash=${BuildConfig.GIT_COMMIT_HASH}, environment=${BuildConfig.ENVIRONMENT}, build_timestamp=${BuildConfig.BUILD_TIMESTAMP}, full_version=${BuildConfig.FULL_VERSION_STRING}"
        )

        if (BuildConfig.DEBUG) {
            com.architect.driveaggregator.telemetry.AgentLogger.initDynamicConfig(this)

            com.architect.driveaggregator.telemetry.AgentLogger.logEvent(
                level = "INFO",
                category = "STARTUP",
                event = "BOOT_START",
                message = "MDriveApplication onCreate started",
                payload = mapOf(
                    "full_version" to BuildConfig.FULL_VERSION_STRING,
                    "semver" to BuildConfig.SEMVER_VERSION,
                    "commit_hash" to BuildConfig.GIT_COMMIT_HASH,
                    "build_timestamp" to BuildConfig.BUILD_TIMESTAMP,
                    "environment" to BuildConfig.ENVIRONMENT,
                    "x_app_version" to BuildConfig.SEMVER_VERSION,
                    "x_build_id" to BuildConfig.GIT_COMMIT_HASH
                )
            )

            com.architect.driveaggregator.telemetry.AgentLogger.logEvent(
                level = "INFO",
                category = "STARTUP",
                event = "BOOT_VERSION",
                message = "App Version Details: semver=${BuildConfig.SEMVER_VERSION}, commit=${BuildConfig.GIT_COMMIT_HASH}, env=${BuildConfig.ENVIRONMENT}, timestamp=${BuildConfig.BUILD_TIMESTAMP}",
                payload = mapOf(
                    "full_version" to BuildConfig.FULL_VERSION_STRING,
                    "semver" to BuildConfig.SEMVER_VERSION,
                    "commit_hash" to BuildConfig.GIT_COMMIT_HASH,
                    "build_timestamp" to BuildConfig.BUILD_TIMESTAMP,
                    "environment" to BuildConfig.ENVIRONMENT,
                    "x_app_version" to BuildConfig.SEMVER_VERSION,
                    "x_build_id" to BuildConfig.GIT_COMMIT_HASH
                )
            )
            
            val isDiSuccessful = this::workerFactory.isInitialized
            com.architect.driveaggregator.telemetry.AgentLogger.logEvent(
                level = if (isDiSuccessful) "INFO" else "ERROR",
                category = "STARTUP",
                event = "DI_SETUP",
                message = if (isDiSuccessful) "Dependency injection setup successful" else "Dependency injection setup failed",
                payload = mapOf("di_active" to isDiSuccessful)
            )

            val duration = System.currentTimeMillis() - startTime
            com.architect.driveaggregator.telemetry.AgentLogger.logEvent(
                level = "INFO",
                category = "STARTUP",
                event = "BOOT_COMPLETE",
                message = "MDriveApplication bootstrap complete",
                payload = mapOf("duration_ms" to duration)
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
