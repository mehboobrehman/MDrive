package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.version.AppVersionInfo
import com.architect.driveaggregator.version.VersionCheckManager
import com.architect.driveaggregator.version.VersionCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AboutVersionUiState(
    val runningVersion: AppVersionInfo = AppVersionInfo.current,
    val latestVersion: AppVersionInfo? = null,
    val isStale: Boolean = false,
    val updateMessage: String? = null,
    val isChecking: Boolean = false,
    val showAboutDialog: Boolean = false,
    val showUpdateDialog: Boolean = false,
    val checkErrorMessage: String? = null
)

@HiltViewModel
class AboutVersionViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val versionManager = VersionCheckManager(application)

    private val _uiState = MutableStateFlow(AboutVersionUiState())
    val uiState: StateFlow<AboutVersionUiState> = _uiState.asStateFlow()

    init {
        checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isChecking = true, checkErrorMessage = null) }
            }
            try {
                // Check against server endpoint if available or asset/fallback
                val serverUrl = com.architect.driveaggregator.telemetry.AgentLogger.getServerUrl()?.let { "$it/version.json" }
                val result = versionManager.checkForUpdates(serverUrl)

                val dismissedCode = versionManager.getDismissedVersionCode()
                val showUpdatePrompt = result.isStale && (result.latestVersion?.versionCode ?: 0) > dismissedCode

                _uiState.update {
                    it.copy(
                        runningVersion = result.runningVersion,
                        latestVersion = result.latestVersion,
                        isStale = result.isStale,
                        updateMessage = result.updateMessage,
                        isChecking = false,
                        showUpdateDialog = showUpdatePrompt
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        checkErrorMessage = e.localizedMessage ?: "Failed to check for updates"
                    )
                }
            }
        }
    }

    fun setShowAboutDialog(show: Boolean) {
        _uiState.update { it.copy(showAboutDialog = show) }
    }

    fun setShowUpdateDialog(show: Boolean) {
        _uiState.update { it.copy(showUpdateDialog = show) }
    }

    fun dismissUpdateForCurrentVersion() {
        _uiState.value.latestVersion?.let { latest ->
            versionManager.setDismissedVersionCode(latest.versionCode)
        }
        _uiState.update { it.copy(showUpdateDialog = false) }
    }
}
