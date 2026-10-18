package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.ui.model.AccountStatus
import com.architect.driveaggregator.ui.model.CloudProviderType
import com.architect.driveaggregator.ui.model.DriveAccountInfo
import com.architect.driveaggregator.ui.model.PooledStorageOverview
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkedAccountsUiState(
    val isLoading: Boolean = false,
    val pooledStorage: PooledStorageOverview = PooledStorageOverview(0L, 0L, 0L, emptyList()),
    val errorMessage: String? = null,
    val authUrlToLaunch: String? = null
)

@HiltViewModel
class LinkedAccountsViewModel @Inject constructor(
    application: Application,
    private val oauthManager: GoogleOAuthManager? = null,
    private val tokenVault: SecureTokenVault? = null
) : AndroidViewModel(application) {

    private val vault: SecureTokenVault = tokenVault ?: SecureTokenVault(application)

    private val _uiState = MutableStateFlow(LinkedAccountsUiState(isLoading = true))
    val uiState: StateFlow<LinkedAccountsUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
    }

    fun loadAccounts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val accountIds = vault.getAllAccountIds()

                val accountsList = accountIds.map { id ->
                    val isGoogle = !id.startsWith("onedrive_") && !id.startsWith("dropbox_") && !id.startsWith("mega_") && !id.startsWith("box_") && !id.startsWith("terabox_")
                    val providerType = when {
                        id.startsWith("onedrive_") -> CloudProviderType.ONEDRIVE
                        id.startsWith("dropbox_") -> CloudProviderType.DROPBOX
                        id.startsWith("mega_") -> CloudProviderType.MEGA
                        id.startsWith("box_") -> CloudProviderType.BOX
                        id.startsWith("terabox_") -> CloudProviderType.TERABOX
                        else -> CloudProviderType.GOOGLE_DRIVE
                    }

                    val defaultCapacity = providerType.defaultQuotaBytes
                    DriveAccountInfo(
                        accountId = id,
                        email = if (isGoogle) "$id@gmail.com" else "$id@${providerType.providerId}.cloud",
                        displayName = "${providerType.displayName} ($id)",
                        totalBytes = defaultCapacity,
                        usedBytes = defaultCapacity / 4,  // 25% used simulation
                        freeBytes = defaultCapacity - (defaultCapacity / 4),
                        status = AccountStatus.ACTIVE,
                        providerType = providerType
                    )
                }

                val totalCapacity = accountsList.sumOf { it.totalBytes }
                val totalUsed = accountsList.sumOf { it.usedBytes }
                val totalFree = accountsList.sumOf { it.freeBytes }

                val overview = PooledStorageOverview(
                    totalCapacityBytes = totalCapacity,
                    totalUsedBytes = totalUsed,
                    totalFreeBytes = totalFree,
                    accounts = accountsList
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pooledStorage = overview
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to load linked accounts"
                    )
                }
            }
        }
    }

    fun triggerLinkNewAccount(providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE) {
        if (providerType == CloudProviderType.GOOGLE_DRIVE && oauthManager != null) {
            AgentLogger.logEvent(
                level = "INFO",
                category = "OAUTH",
                event = "OAUTH_INIT",
                message = "Google ID OAuth sign-in flow initiated from Linked Accounts screen",
                payload = mapOf("client_id" to "[REDACTED]")
            )
            try {
                val url = oauthManager.getAuthorizationUrl()
                AgentLogger.logEvent(
                    level = "INFO",
                    category = "OAUTH",
                    event = "OAUTH_URL_OPENED",
                    message = "Opening Google authorization URL",
                    payload = mapOf("client_id" to "[REDACTED]")
                )
                _uiState.update { it.copy(authUrlToLaunch = url) }
            } catch (e: IllegalStateException) {
                _uiState.update { it.copy(errorMessage = e.message ?: GoogleOAuthManager.OAUTH_MISCONFIGURED_MESSAGE) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to start linking: ${e.localizedMessage}") }
            }
        } else {
            // Bind secondary platform or fallback test binding
            val prefix = providerType.providerId
            val newId = "${prefix}_" + (System.currentTimeMillis() % 10000)
            vault.storeRefreshToken(newId, "dummy_${prefix}_refresh_token")
            vault.storeAccessToken(newId, "dummy_${prefix}_access_token", System.currentTimeMillis() + 3600000L)
            AgentLogger.logEvent(
                level = "INFO",
                category = "STORAGE_BINDING",
                event = "PROVIDER_LINKED",
                message = "Successfully bound secondary provider session",
                payload = mapOf("provider" to providerType.providerId, "account_id" to newId)
            )
            loadAccounts()
        }
    }

    fun showGoogleSignInError(errorCode: String?) {
        val code = errorCode ?: "unknown"
        AgentLogger.logEvent(
            level = "ERROR",
            category = "OAUTH",
            event = "OAUTH_ERROR_RECEIVED",
            message = "Google sign-in error surfaced: $code",
            payload = mapOf("error" to code, "client_id" to "[REDACTED]")
        )
        _uiState.update {
            it.copy(errorMessage = buildString {
                append("Google sign-in failed: ")
                append(code)
                if (code == "invalid_client") {
                    append(" — verify GOOGLE_CLIENT_ID in local.properties and the OAuth client in Google Cloud Console")
                }
            })
        }
    }

    fun onAuthUrlConsumed() {
        _uiState.update { it.copy(authUrlToLaunch = null) }
    }

    fun unlinkAccount(accountId: String) {
        viewModelScope.launch {
            try {
                if (oauthManager != null && !accountId.contains("_")) {
                    oauthManager.revokeAccount(accountId)
                } else {
                    vault.removeAccount(accountId)
                }
                AgentLogger.logEvent(
                    level = "INFO",
                    category = "STORAGE_BINDING",
                    event = "PROVIDER_UNLINKED",
                    message = "Successfully unlinked account session",
                    payload = mapOf("account_id" to accountId)
                )
                loadAccounts()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to unlink account: ${e.localizedMessage}") }
            }
        }
    }

    fun handleAuthCallback(code: String, state: String) {
        if (oauthManager != null) {
            viewModelScope.launch {
                val result = oauthManager.handleAuthorizationCode(code, state)
                if (result.isSuccess) {
                    loadAccounts()
                } else {
                    val failureMessage = result.exceptionOrNull()?.message
                    if (failureMessage?.contains("invalid_client") == true) {
                        showGoogleSignInError("invalid_client")
                    } else {
                        _uiState.update { it.copy(errorMessage = "Authorization failed: $failureMessage") }
                    }
                }
            }
        }
    }
}
