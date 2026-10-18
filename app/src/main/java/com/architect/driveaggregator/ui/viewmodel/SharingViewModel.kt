package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.sharing.ShareOptions
import com.architect.driveaggregator.sharing.SharingLinkEntity
import com.architect.driveaggregator.sharing.SharingManager
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharingUiState(
    val isLoading: Boolean = false,
    val activeLinks: List<SharingLinkEntity> = emptyList(),
    val selectedFileId: String? = null,
    val selectedProviderId: String? = null,
    val selectedProviderFileId: String? = null,
    val selectedFileName: String? = null,
    val isSharingSheetOpen: Boolean = false,
    val isManagementScreenOpen: Boolean = false,
    val lastGeneratedLink: SharingLinkEntity? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class SharingViewModel @Inject constructor(
    application: Application,
    private val sharingManager: SharingManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SharingUiState())
    val uiState: StateFlow<SharingUiState> = _uiState.asStateFlow()

    fun openSharingSheet(virtualFileId: String, providerId: String, providerFileId: String, fileName: String) {
        _uiState.update {
            it.copy(
                selectedFileId = virtualFileId,
                selectedProviderId = providerId,
                selectedProviderFileId = providerFileId,
                selectedFileName = fileName,
                isSharingSheetOpen = true,
                errorMessage = null,
                successMessage = null
            )
        }
        loadActiveLinksForFile(virtualFileId)
    }

    fun closeSharingSheet() {
        _uiState.update { it.copy(isSharingSheetOpen = false) }
    }

    fun loadActiveLinksForFile(virtualFileId: String) {
        viewModelScope.launch {
            try {
                val links = sharingManager.getActiveLinks(virtualFileId)
                _uiState.update { it.copy(activeLinks = links) }
            } catch (e: Exception) {
                AgentLogger.logEvent("ERROR", "SHARING", "LOAD_LINKS_ERROR", "Failed to load active links: ${e.message}")
            }
        }
    }

    fun generateLink(permission: String, isPublic: Boolean, expirationDurationMs: Long) {
        val state = _uiState.value
        val fileId = state.selectedFileId ?: return
        val providerId = state.selectedProviderId ?: return
        val providerFileId = state.selectedProviderFileId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val options = ShareOptions(
                    permission = permission,
                    isPublic = isPublic,
                    expirationDurationMs = expirationDurationMs
                )
                val result = sharingManager.generateShareLink(fileId, providerId, providerFileId, options)
                result.fold(
                    onSuccess = { link ->
                        val updatedLinks = sharingManager.getActiveLinks(fileId)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                lastGeneratedLink = link,
                                activeLinks = updatedLinks,
                                successMessage = "Sharing link generated successfully!"
                            )
                        }
                        AgentLogger.logEvent("INFO", "SHARING", "LINK_GENERATED", "Generated link for file $fileId with permission $permission")
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Failed to generate link: ${err.message}"
                            )
                        }
                        AgentLogger.logEvent("ERROR", "SHARING", "LINK_GEN_FAIL", "Failed to generate link: ${err.message}")
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Exception generating link: ${e.message}"
                    )
                }
            }
        }
    }

    fun revokeLink(linkId: String) {
        viewModelScope.launch {
            try {
                val result = sharingManager.revokeShareLink(linkId)
                result.fold(
                    onSuccess = {
                        val fileId = _uiState.value.selectedFileId
                        val updatedLinks = if (fileId != null) sharingManager.getActiveLinks(fileId) else emptyList()
                        _uiState.update {
                            it.copy(
                                activeLinks = updatedLinks,
                                successMessage = "Sharing link revoked successfully."
                            )
                        }
                        AgentLogger.logEvent("INFO", "SHARING", "LINK_REVOKED", "Revoked link $linkId")
                    },
                    onFailure = { err ->
                        _uiState.update { it.copy(errorMessage = "Failed to revoke link: ${err.message}") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Exception revoking link: ${e.message}") }
            }
        }
    }

    fun loadAllActiveLinks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val fileId = _uiState.value.selectedFileId
                val links = if (fileId != null) sharingManager.getActiveLinks(fileId) else emptyList()
                _uiState.update { it.copy(isLoading = false, activeLinks = links) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
