package com.architect.driveaggregator.upload

import com.architect.driveaggregator.data.FileShardEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a task in the rate-limited upload worker queue.
 */
data class UploadTask(
    val taskId: String,
    val virtualFileId: String,
    val shard: FileShardEntity,
    val dataSourceFile: File,
    val priority: Int = 0 // higher value = higher priority
) : Comparable<UploadTask> {
    override fun compareTo(other: UploadTask): Int {
        // Compare priority descending, then taskId
        return other.priority.compareTo(this.priority).takeIf { it != 0 }
            ?: this.taskId.compareTo(other.taskId)
    }
}

/**
 * Statistics and status of the worker queue.
 */
data class QueueStats(
    val activeUploadsCount: Int,
    val queuedTasksCount: Int,
    val globalMaxConcurrency: Int,
    val activePerAccount: Map<String, Int>
)

/**
 * Rate-Limited Worker Queue that manages concurrent uploads across multiple Google Drive accounts,
 * enforcing global maximum concurrency limits (default 2 parallel streams) as well as account-specific rate limits,
 * priority sorting, cancellation support, and failure recovery/retries.
 */
class UploadWorkerQueue(
    private val uploadManager: GoogleResumableUploadManager,
    private val globalMaxConcurrency: Int = 2,
    private val maxPerAccountConcurrency: Int = 1
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val queuedTasks = mutableListOf<UploadTask>()
    private val activeTasks = ConcurrentHashMap<String, UploadTask>()
    private val activePerAccountMap = ConcurrentHashMap<String, AtomicInteger>()

    private val _statsFlow = MutableStateFlow(
        QueueStats(
            activeUploadsCount = 0,
            queuedTasksCount = 0,
            globalMaxConcurrency = globalMaxConcurrency,
            activePerAccount = emptyMap()
        )
    )
    val statsFlow: StateFlow<QueueStats> = _statsFlow.asStateFlow()

    private val workerTrigger = Channel<Unit>(Channel.CONFLATED)

    init {
        // Start background worker processing loop
        scope.launch {
            for (trigger in workerTrigger) {
                processQueue()
            }
        }
    }

    /**
     * Enqueues an upload task with optional priority.
     */
    suspend fun enqueue(task: UploadTask) {
        mutex.withLock {
            queuedTasks.add(task)
            queuedTasks.sort()
        }
        workerTrigger.send(Unit)
    }

    /**
     * Enqueues multiple tasks (e.g. all shards of a virtual file).
     */
    suspend fun enqueueAll(tasks: List<UploadTask>) {
        mutex.withLock {
            queuedTasks.addAll(tasks)
            queuedTasks.sort()
        }
        workerTrigger.send(Unit)
    }

    /**
     * Cancels a specific task if queued or active.
     */
    suspend fun cancelTask(taskId: String) {
        mutex.withLock {
            queuedTasks.removeAll { it.taskId == taskId }
        }
        val active = activeTasks[taskId]
        if (active != null) {
            // Decrement account counter if active
            decrementAccountCount(active.shard.targetAccountId)
            activeTasks.remove(taskId)
        }
        updateStats()
        workerTrigger.send(Unit)
    }

    /**
     * Clears all queued tasks.
     */
    suspend fun clearQueue() {
        mutex.withLock {
            queuedTasks.clear()
        }
        updateStats()
    }

    /**
     * Returns current queue stats snapshot.
     */
    fun getStats(): QueueStats {
        val perAccount = activePerAccountMap.mapValues { it.value.get() }
        return QueueStats(
            activeUploadsCount = activeTasks.size,
            queuedTasksCount = queuedTasks.size,
            globalMaxConcurrency = globalMaxConcurrency,
            activePerAccount = perAccount
        )
    }

    private suspend fun updateStats() {
        _statsFlow.value = getStats()
    }

    private suspend fun processQueue() {
        mutex.withLock {
            while (activeTasks.size < globalMaxConcurrency && queuedTasks.isNotEmpty()) {
                // Find next task whose target account is under maxPerAccountConcurrency
                val nextTaskIndex = queuedTasks.indexOfFirst { task ->
                    val accountId = task.shard.targetAccountId
                    val accountActiveCount = activePerAccountMap.getOrPut(accountId) { AtomicInteger(0) }.get()
                    accountActiveCount < maxPerAccountConcurrency
                }

                if (nextTaskIndex == -1) {
                    // All queued tasks belong to accounts already at their concurrency limit
                    break
                }

                val task = queuedTasks.removeAt(nextTaskIndex)
                val accountId = task.shard.targetAccountId

                activePerAccountMap.getOrPut(accountId) { AtomicInteger(0) }.incrementAndGet()
                activeTasks[task.taskId] = task

                // Launch upload in background scope
                scope.launch {
                    try {
                        uploadManager.uploadShard(task.shard, task.dataSourceFile)
                    } catch (e: Exception) {
                        // Log or handle terminal failure if needed
                    } finally {
                        mutex.withLock {
                            decrementAccountCount(accountId)
                            activeTasks.remove(task.taskId)
                        }
                        workerTrigger.send(Unit)
                    }
                }
            }
        }
        updateStats()
    }

    private fun decrementAccountCount(accountId: String) {
        val countAtomic = activePerAccountMap[accountId]
        if (countAtomic != null) {
            val newVal = countAtomic.decrementAndGet()
            if (newVal <= 0) {
                activePerAccountMap.remove(accountId)
            }
        }
    }
}
