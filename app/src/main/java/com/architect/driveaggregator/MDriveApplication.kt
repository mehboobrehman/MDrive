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
        if (BuildConfig.DEBUG) {
            com.architect.driveaggregator.telemetry.AgentLogger.initDynamicConfig(this)
            
            com.architect.driveaggregator.telemetry.AgentLogger.logEvent(
                level = "INFO",
                category = "STARTUP",
                event = "BOOT_START",
                message = "MDriveApplication onCreate started"
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
