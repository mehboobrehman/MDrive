package com.architect.driveaggregator.update

import android.content.Context
import androidx.work.*
import com.architect.driveaggregator.BuildConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateWorkerEntryPoint {
        fun updateRepository(): UpdateRepository
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val appContext = applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(appContext, UpdateWorkerEntryPoint::class.java)
            val repository = entryPoint.updateRepository()

            val result = repository.fetchLatestRelease()
            if (result.isSuccess) {
                val release = result.getOrNull()
                if (release != null && SemVerComparator.isGreater(release.tagName, BuildConfig.APP_VERSION)) {
                    // Newer release found in background check
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "MDriveSecureUpdatePeriodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
