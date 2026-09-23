package com.architect.driveaggregator.sync

import android.content.Context
import android.util.Log
import com.architect.driveaggregator.telemetry.AgentLogger
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.engine.VirtualDriveEngine

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncOrchestrator: SyncOrchestrator,
    private val virtualDriveEngine: VirtualDriveEngine,
    private val database: AppDatabase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val startTime = System.currentTimeMillis()
        val operationType = inputData.getString("operationType")
        val virtualFileId = inputData.getString("virtualFileId")

        if (operationType == null || virtualFileId == null) {
            AgentLogger.logEvent(
                level = "ERROR",
                category = "EXECUTION",
                event = "WORKER_ERROR",
                message = "SyncWorker missing required input parameters",
                payload = mapOf("operationType" to operationType, "virtualFileId" to virtualFileId)
            )
            return Result.failure()
        }

        AgentLogger.logEvent(
            level = "INFO",
            category = "EXECUTION",
            event = "WORKER_START",
            message = "SyncWorker execution started",
            payload = mapOf("operationType" to operationType, "virtualFileId" to virtualFileId)
        )

        val result = when (operationType) {
            "UPLOAD" -> {
                val filePath = inputData.getString("filePath")
                if (filePath == null) {
                    AgentLogger.logEvent(
                        level = "ERROR",
                        category = "EXECUTION",
                        event = "WORKER_ERROR",
                        message = "Upload filePath is null for $virtualFileId",
                        payload = mapOf("virtualFileId" to virtualFileId)
                    )
                    Result.failure()
                } else {
                    try {
                        syncOrchestrator.coordinateSync(filePath, virtualFileId) { progress, message ->
                            setProgress(workDataOf("progress" to progress, "message" to message))
                            AgentLogger.logEvent(
                                level = "DEBUG",
                                category = "EXECUTION",
                                event = "WORKER_PROGRESS",
                                message = "Worker progress: $progress% - $message",
                                payload = mapOf(
                                    "virtualFileId" to virtualFileId,
                                    "operationType" to operationType,
                                    "progress" to progress,
                                    "message" to message
                                )
                            )
                        }
                        Result.success()
                    } catch (e: Exception) {
                        Log.e("SyncWorker", "Upload failed for $virtualFileId", e)
                        AgentLogger.logEvent(
                            level = "ERROR",
                            category = "EXECUTION",
                            event = "WORKER_ERROR",
                            message = "Upload failed for $virtualFileId: ${e.message}",
                            payload = mapOf("operationType" to operationType, "virtualFileId" to virtualFileId),
                            stack = e.stackTraceToString()
                        )
                        Result.retry()
                    }
                }
            }
            "DOWNLOAD" -> {
                try {
                    val shards = database.sagaTransactionDao().getShardsForVirtualFile(virtualFileId)
                    if (shards.isEmpty()) {
                        Log.e("SyncWorker", "No shards found for virtualFileId: $virtualFileId")
                        AgentLogger.logEvent(
                            level = "ERROR",
                            category = "EXECUTION",
                            event = "WORKER_ERROR",
                            message = "No shards found for virtualFileId: $virtualFileId",
                            payload = mapOf("virtualFileId" to virtualFileId)
                        )
                        Result.failure()
                    } else {
                        virtualDriveEngine.reconstructAndCacheFile(virtualFileId, shards) { _ ->
                            object : java.io.InputStream() {
                                override fun read(): Int = -1
                            }
                        }
                        Result.success()
                    }
                } catch (e: Exception) {
                    Log.e("SyncWorker", "Download failed for $virtualFileId", e)
                    AgentLogger.logEvent(
                        level = "ERROR",
                        category = "EXECUTION",
                        event = "WORKER_ERROR",
                        message = "Download failed for $virtualFileId: ${e.message}",
                        payload = mapOf("operationType" to operationType, "virtualFileId" to virtualFileId),
                        stack = e.stackTraceToString()
                    )
                    Result.retry()
                }
            }
            else -> {
                Log.e("SyncWorker", "Unknown operation type: $operationType")
                AgentLogger.logEvent(
                    level = "ERROR",
                    category = "EXECUTION",
                    event = "WORKER_ERROR",
                    message = "Unknown operation type: $operationType",
                    payload = mapOf("operationType" to operationType, "virtualFileId" to virtualFileId)
                )
                Result.failure()
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val level = if (result is Result.Success) "INFO" else "ERROR"
        AgentLogger.logEvent(
            level = level,
            category = "EXECUTION",
            event = "WORKER_COMPLETE",
            message = "SyncWorker finished: ${result::class.simpleName}",
            payload = mapOf(
                "operationType" to operationType,
                "virtualFileId" to virtualFileId,
                "duration_ms" to duration,
                "result_status" to result.toString()
            )
        )
        return result
    }
}
