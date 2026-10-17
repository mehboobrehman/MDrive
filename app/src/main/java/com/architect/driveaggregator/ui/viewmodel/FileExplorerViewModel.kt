package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.data.VirtualFileWithShardStats
import com.architect.driveaggregator.engine.VirtualDriveEngine
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.ui.model.TransferProgressItem
import com.architect.driveaggregator.ui.model.TransferStatus
import com.architect.driveaggregator.ui.model.TransferType
import com.architect.driveaggregator.ui.model.VirtualFileItem
import com.architect.driveaggregator.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.architect.driveaggregator.sync.SyncOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class FileExplorerUiState(
    val files: List<VirtualFileItem> = emptyList(),
    val transfers: List<TransferProgressItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val selectedFilterTab: Int = 0, // 0 = All Files, 1 = Active Transfers, 2 = Completed
    val snackbarMessage: String? = null
)

@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    application: Application,
    private val database: AppDatabase,
    private val virtualDriveEngine: VirtualDriveEngine,
    private val syncOrchestrator: com.architect.driveaggregator.sync.SyncOrchestrator
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FileExplorerUiState())
    val uiState: StateFlow<FileExplorerUiState> = _uiState.asStateFlow()

    private val transferJobs = mutableMapOf<String, Job>()
    private var loadFilesJob: Job? = null

    init {
        loadVirtualFiles()
    }

    fun loadVirtualFiles() {
        loadFilesJob?.cancel()
        loadFilesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                database.sagaTransactionDao().observeAllVirtualFilesWithStats().collect { results ->
                    try {
                        val items = results.map { result ->
                            val entity = result.virtualFile
                            VirtualFileItem(
                                virtualFileId = entity.virtualFileId,
                                fileName = entity.fileName,
                                fileSize = entity.fileSize,
                                mimeType = entity.mimeType,
                                sagaState = entity.sagaState,
                                uploadedShardsCount = result.completedShardsCount,
                                totalShardsCount = result.totalShardsCount.coerceAtLeast(1),
                                createdAt = entity.createdAt
                            )
                        }
                        AgentLogger.logEvent(
                            level = "INFO",
                            category = "UI",
                            event = "FILES_LOADED",
                            message = "Loaded ${items.size} virtual files",
                            payload = mapOf("fileCount" to items.size)
                        )
                        _uiState.update { it.copy(files = items, isLoading = false) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logFilesLoadError(e)
                        _uiState.update { it.copy(isLoading = false, snackbarMessage = "Failed to load files: ${e.localizedMessage}") }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logFilesLoadError(e)
                _uiState.update { it.copy(isLoading = false, snackbarMessage = "Failed to load files: ${e.localizedMessage}") }
            }
        }
    }

    private fun logFilesLoadError(e: Exception) {
        AgentLogger.logEvent(
            level = "ERROR",
            category = "UI",
            event = "FILES_LOAD_ERROR",
            message = "Failed to load virtual files: ${e.localizedMessage}",
            payload = mapOf(
                "exceptionClass" to e.javaClass.name,
                "stackTrace" to e.stackTraceToString()
            )
        )
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedFilterTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedFilterTab = tabIndex) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun initiateUpload(fileName: String, fileSize: Long, mimeType: String, filePath: String) {
        val transferId = "tx_up_" + UUID.randomUUID().toString().take(8)
        val virtualFileId = "vf_" + UUID.randomUUID().toString().take(8)
        val chunkSize = BuildConfig.CHUNK_SIZE_BYTES.coerceAtLeast(1)
        val totalShards = (fileSize / chunkSize).toInt().coerceAtLeast(1)

        AgentLogger.logEvent(
            level = "INFO",
            category = "UI",
            event = "UPLOAD_INITIATED",
            message = "Initiating upload for $fileName",
            payload = mapOf(
                "fileName" to fileName,
                "fileSize" to fileSize,
                "mimeType" to mimeType,
                "transferId" to transferId,
                "virtualFileId" to virtualFileId
            )
        )

        val newTransfer = TransferProgressItem(
            transferId = transferId,
            virtualFileId = virtualFileId,
            fileName = fileName,
            transferType = TransferType.UPLOAD,
            status = TransferStatus.PENDING,
            progressPercent = 0.0f,
            bytesTransferred = 0L,
            totalBytes = fileSize,
            currentShardIndex = 0,
            totalShards = totalShards
        )

        _uiState.update { state ->
            val updatedTransfers = listOf(newTransfer) + state.transfers
            state.copy(transfers = updatedTransfers, snackbarMessage = "Enqueued background sync: $fileName")
        }

        viewModelScope.launch {
            try {
                val finalFilePath = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    if (filePath.startsWith("content://")) {
                        val cacheFile = java.io.File(context.cacheDir, fileName)
                        context.contentResolver.openInputStream(android.net.Uri.parse(filePath))?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        cacheFile.absolutePath
                    } else {
                        val file = java.io.File(filePath)
                        if (!file.exists()) {
                            val cacheFile = java.io.File(context.cacheDir, fileName)
                            java.io.RandomAccessFile(cacheFile, "rw").use { raf ->
                                raf.setLength(fileSize)
                            }
                            cacheFile.absolutePath
                        } else {
                            filePath
                        }
                    }
                }

                AgentLogger.logEvent(
                    level = "DEBUG",
                    category = "UI",
                    event = "UPLOAD_FILE_PREPARED",
                    message = "Upload file prepared at $finalFilePath",
                    payload = mapOf(
                        "filePath" to finalFilePath,
                        "fileSize" to fileSize,
                        "virtualFileId" to virtualFileId
                    )
                )

                syncOrchestrator.enqueueSync("UPLOAD", finalFilePath, virtualFileId)

                AgentLogger.logEvent(
                    level = "INFO",
                    category = "UI",
                    event = "UPLOAD_WORK_ENQUEUED",
                    message = "Sync work enqueued for $virtualFileId",
                    payload = mapOf(
                        "virtualFileId" to virtualFileId,
                        "transferId" to transferId
                    )
                )

                observeWorkInfo(virtualFileId, transferId)
            } catch (e: Exception) {
                AgentLogger.logEvent(
                    level = "ERROR",
                    category = "UI",
                    event = "UPLOAD_INIT_ERROR",
                    message = "Failed to initiate upload for $virtualFileId: ${e.message}",
                    payload = mapOf("virtualFileId" to virtualFileId, "transferId" to transferId),
                    stack = e.stackTraceToString()
                )
                _uiState.update { state ->
                    val updatedTransfers = state.transfers.map { t ->
                        if (t.transferId == transferId) {
                            t.copy(status = TransferStatus.FAILED, errorMessage = e.localizedMessage)
                        } else {
                            t
                        }
                    }
                    state.copy(transfers = updatedTransfers, snackbarMessage = "Failed to enqueue sync: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun observeWorkInfo(virtualFileId: String, transferId: String) {
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosByTagFlow("sync_$virtualFileId")
                .collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect
                    
                    val progress = workInfo.progress.getInt("progress", 0)
                    val message = workInfo.progress.getString("message") ?: ""

                    AgentLogger.logEvent(
                        level = "DEBUG",
                        category = "WORK_MANAGER",
                        event = "WORK_STATE_CHANGED",
                        message = "WorkState changed to ${workInfo.state} for $virtualFileId (progress: $progress%)",
                        payload = mapOf(
                            "virtualFileId" to virtualFileId,
                            "transferId" to transferId,
                            "state" to workInfo.state.name,
                            "progress" to progress,
                            "message" to message
                        )
                    )
                    
                    _uiState.update { state ->
                        val updatedTransfers = state.transfers.map { t ->
                            if (t.transferId == transferId) {
                                t.copy(
                                    progressPercent = progress / 100f,
                                    status = when (workInfo.state) {
                                        WorkInfo.State.RUNNING -> TransferStatus.IN_PROGRESS
                                        WorkInfo.State.SUCCEEDED -> TransferStatus.COMPLETED
                                        WorkInfo.State.FAILED -> TransferStatus.FAILED
                                        WorkInfo.State.CANCELLED -> TransferStatus.CANCELLED
                                        WorkInfo.State.ENQUEUED -> TransferStatus.PENDING
                                        else -> TransferStatus.PAUSED
                                    }
                                )
                            } else {
                                t
                            }
                        }
                        state.copy(transfers = updatedTransfers)
                    }
                }
        }
    }

    fun initiateDownload(virtualFileItem: VirtualFileItem) {
        val transferId = "tx_down_" + UUID.randomUUID().toString().take(8)
        val totalShards = virtualFileItem.totalShardsCount

        val newTransfer = TransferProgressItem(
            transferId = transferId,
            virtualFileId = virtualFileItem.virtualFileId,
            fileName = virtualFileItem.fileName,
            transferType = TransferType.DOWNLOAD,
            status = TransferStatus.PENDING,
            progressPercent = 0.0f,
            bytesTransferred = 0L,
            totalBytes = virtualFileItem.fileSize,
            currentShardIndex = 0,
            totalShards = totalShards
        )

        _uiState.update { state ->
            state.copy(
                transfers = listOf(newTransfer) + state.transfers,
                snackbarMessage = "Enqueued background download: ${virtualFileItem.fileName}"
            )
        }

        viewModelScope.launch {
            try {
                // Enqueue SyncWorker for download operation
                syncOrchestrator.enqueueSync("DOWNLOAD", null, virtualFileItem.virtualFileId)
                observeWorkInfo(virtualFileItem.virtualFileId, transferId)
            } catch (e: Exception) {
                AgentLogger.logEvent(
                    level = "ERROR",
                    category = "UI",
                    event = "DOWNLOAD_INIT_ERROR",
                    message = "Failed to initiate download for ${virtualFileItem.virtualFileId}: ${e.localizedMessage}",
                    payload = mapOf(
                        "virtualFileId" to virtualFileItem.virtualFileId,
                        "exceptionClass" to e.javaClass.name
                    ),
                    stack = e.stackTraceToString()
                )
                _uiState.update { state ->
                    val updatedTransfers = state.transfers.map { t ->
                        if (t.transferId == transferId) {
                            t.copy(status = TransferStatus.FAILED, errorMessage = e.localizedMessage)
                        } else {
                            t
                        }
                    }
                    state.copy(transfers = updatedTransfers, snackbarMessage = "Failed to enqueue download: ${e.localizedMessage}")
                }
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        transferJobs[transferId]?.cancel()
        _uiState.update { state ->
            val updatedTransfers = state.transfers.map { t ->
                if (t.transferId == transferId && t.status == TransferStatus.IN_PROGRESS) {
                    t.copy(status = TransferStatus.PAUSED)
                } else {
                    t
                }
            }
            state.copy(transfers = updatedTransfers, snackbarMessage = "Transfer paused")
        }
    }

    fun resumeTransfer(transferId: String) {
        _uiState.update { state ->
            val updatedTransfers = state.transfers.map { t ->
                if (t.transferId == transferId && t.status == TransferStatus.PAUSED) {
                    t.copy(status = TransferStatus.IN_PROGRESS)
                } else {
                    t
                }
            }
            state.copy(transfers = updatedTransfers, snackbarMessage = "Transfer resumed")
        }
    }

    fun cancelTransfer(transferId: String) {
        transferJobs[transferId]?.cancel()
        _uiState.update { state ->
            val updatedTransfers = state.transfers.map { t ->
                if (t.transferId == transferId) {
                    t.copy(status = TransferStatus.CANCELLED)
                } else {
                    t
                }
            }
            state.copy(transfers = updatedTransfers, snackbarMessage = "Transfer cancelled")
        }
    }

    fun deleteVirtualFile(virtualFileId: String) {
        viewModelScope.launch {
            try {
                database.sagaTransactionDao().deleteVirtualFile(virtualFileId)
                _uiState.update { state ->
                    state.copy(
                        files = state.files.filter { it.virtualFileId != virtualFileId },
                        snackbarMessage = "Virtual file deleted"
                    )
                }
            } catch (e: Exception) {
                AgentLogger.logEvent(
                    level = "ERROR",
                    category = "UI",
                    event = "FILE_DELETE_ERROR",
                    message = "Failed to delete virtual file $virtualFileId: ${e.localizedMessage}",
                    payload = mapOf(
                        "virtualFileId" to virtualFileId,
                        "exceptionClass" to e.javaClass.name
                    ),
                    stack = e.stackTraceToString()
                )
                _uiState.update { state -> state.copy(snackbarMessage = "Failed to delete file: ${e.localizedMessage}") }
            }
        }
    }
}
