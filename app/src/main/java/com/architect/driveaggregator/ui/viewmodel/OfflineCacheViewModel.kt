package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.cache.CacheManager
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OfflineCacheUiState(
    val isLoading: Boolean = false,
    val pinnedFiles: List<VirtualFileEntity> = emptyList(),
    val cacheSize: Long = 0L,
    val maxSize: Long = 50 * 1024 * 1024L,
    val cacheUsagePercentage: Float = 0f,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class OfflineCacheViewModel @Inject constructor(
    application: Application,
    private val cacheManager: CacheManager,
    private val virtualFileDao: VirtualFileDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OfflineCacheUiState(isLoading = true))
    val uiState: StateFlow<OfflineCacheUiState> = _uiState.asStateFlow()

    init {
        loadOfflineCacheData()
    }

    fun loadOfflineCacheData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val pinned = virtualFileDao.getOfflinePinnedFiles()
                val currentSize = cacheManager.getCacheSize()
                val max = cacheManager.getMaxSize()
                val percentage = if (max > 0) (currentSize.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pinnedFiles = pinned,
                        cacheSize = currentSize,
                        maxSize = max,
                        cacheUsagePercentage = percentage
                    )
                }
                AgentLogger.logEvent("INFO", "CACHE", "CACHE_LOADED", "Loaded ${pinned.size} pinned files. Cache size: $currentSize / $max bytes")
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "CACHE", "CACHE_LOAD_ERROR", "Error loading offline cache data: ${e.message}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to load offline cache data"
                    )
                }
            }
        }
    }

    fun togglePin(fileId: String, isPinned: Boolean) {
        viewModelScope.launch {
            try {
                virtualFileDao.setOfflinePinned(fileId, isPinned)
                val action = if (isPinned) "pinned" else "unpinned"
                AgentLogger.logEvent("INFO", "CACHE", "PIN_TOGGLED", "File $fileId $action successfully")
                loadOfflineCacheData()
                _uiState.update { it.copy(successMessage = "File successfully $action") }
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "CACHE", "PIN_TOGGLE_ERROR", "Failed to toggle pin for $fileId: ${e.message}")
                _uiState.update { it.copy(errorMessage = "Failed to update pin state: ${e.message}") }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                cacheManager.clearCache()
                AgentLogger.logEvent("INFO", "CACHE", "CACHE_CLEARED", "DiskLruCache cleared successfully")
                loadOfflineCacheData()
                _uiState.update { it.copy(successMessage = "Offline cache successfully cleared") }
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "CACHE", "CACHE_CLEAR_ERROR", "Failed to clear cache: ${e.message}")
                _uiState.update { it.copy(errorMessage = "Failed to clear cache: ${e.message}") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
