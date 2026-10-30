package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.data.dao.LogDao
import com.architect.driveaggregator.data.entities.LogEntity
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val isLoading: Boolean = false,
    val serverUrl: String? = null,
    val isConnected: Boolean = false,
    val logs: List<LogEntity> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    application: Application,
    private val logDao: LogDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DebugUiState(isLoading = true))
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    init {
        loadDebugData()
    }

    fun loadDebugData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val serverUrl = AgentLogger.getServerUrl()
                val isConnected = AgentLogger.isConfigured()
                // Fetch last 50 log entries from the database
                val recentLogs = logDao.getRecent(50)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverUrl = serverUrl ?: "Not Configured",
                        isConnected = isConnected,
                        logs = recentLogs
                    )
                }
                AgentLogger.logEvent("INFO", "DEBUG_DRAWER", "DEBUG_DATA_LOADED", "Loaded ${recentLogs.size} logs. Server: $serverUrl")
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "DEBUG_DRAWER", "DEBUG_LOAD_ERROR", "Failed to load debug data: ${e.message}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to load debug data"
                    )
                }
            }
        }
    }

    fun clearData() {
        viewModelScope.launch {
            try {
                logDao.clearAll()
                AgentLogger.logEvent("INFO", "DEBUG_DRAWER", "LOGS_CLEARED", "All local debug logs cleared from Room DB")
                loadDebugData()
                _uiState.update { it.copy(successMessage = "All debug logs successfully cleared") }
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "DEBUG_DRAWER", "CLEAR_ERROR", "Failed to clear logs: ${e.message}")
                _uiState.update { it.copy(errorMessage = "Failed to clear logs: ${e.message}") }
            }
        }
    }

    fun triggerSync() {
        viewModelScope.launch {
            try {
                AgentLogger.syncBufferedLogs()
                AgentLogger.logEvent("INFO", "DEBUG_DRAWER", "SYNC_TRIGGERED", "Manual telemetry sync triggered from DebugScreen")
                // Wait briefly for sync to process
                kotlinx.coroutines.delay(500)
                loadDebugData()
                _uiState.update { it.copy(successMessage = "Telemetry sync triggered successfully") }
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "DEBUG_DRAWER", "SYNC_ERROR", "Failed to trigger sync: ${e.message}")
                _uiState.update { it.copy(errorMessage = "Failed to trigger sync: ${e.message}") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
