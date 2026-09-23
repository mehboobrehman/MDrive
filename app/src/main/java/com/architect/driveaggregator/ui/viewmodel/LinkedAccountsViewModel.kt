package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.ui.model.AccountStatus
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
    // oauthManager can be provided or initialized when network client is available

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

                // For demonstration or live accounts, let's load accounts info.
                // If vault has account IDs, we query their quota or mock representative info if offline/mocked.
                val accountsList = accountIds.map { id ->
                    // In a full integration, we query Drive About.get API using the valid access token.
                    // Here we provide account details backed by vault presence and stored or simulated quota stats.
                    DriveAccountInfo(
                        accountId = id,
                        email = "$id@gmail.com",
                        displayName = "Google Account $id",
                        totalBytes = 15L * 1024 * 1024 * 1024, // 15 GB free tier default
                        usedBytes = 4L * 1024 * 1024 * 1024,  // 4 GB used
                        freeBytes = 11L * 1024 * 1024 * 1024, // 11 GB free
                        status = AccountStatus.ACTIVE
                    )
                }

                // If no accounts in vault yet, let's provide sample accounts or empty list based on real state.
                // To make the UI fully explorable and rich out-of-the-box, if accountIds is empty, we can show sample accounts or empty.
                // Let's support real vault items primarily, but if none, show empty or default sample list if desired. Let's keep it strictly tied to vault.
                
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

    fun triggerLinkNewAccount() {
        if (oauthManager != null) {
            AgentLogger.logEvent(
                level = "INFO",
                category = "OAUTH",
                event = "OAUTH_INIT",
                message = "OAuth sign-in flow initiated from Linked Accounts screen",
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
            // Fallback for UI testing / when oauthManager isn't directly injected
            // Simulate adding a new account for testing/demo
            val newId = "acc_" + (System.currentTimeMillis() % 10000)
            vault.storeRefreshToken(newId, "dummy_refresh_token")
            vault.storeAccessToken(newId, "dummy_access_token", System.currentTimeMillis() + 3600000L)
            loadAccounts()
        }
    }

    /**
     * Displays a user-facing error that includes the Google OAuth error code
     * (e.g. invalid_client) so misconfiguration is self-explanatory.
     */
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

    fun linkMockAccount() {
        val mockAccountId = "mock_acc_${java.util.UUID.randomUUID().toString().take(8)}"
        AgentLogger.logEvent(
            level = "INFO",
            category = "ACCOUNT",
            event = "MOCK_ACCOUNT_LINKED",
            message = "Mock account linked: $mockAccountId",
            payload = mapOf("accountId" to mockAccountId)
        )
        vault.storeRefreshToken(mockAccountId, "mock_refresh_token")
        vault.storeAccessToken(mockAccountId, "mock_access_token", System.currentTimeMillis() + 3600000)
        loadAccounts()
    }

    fun unlinkAccount(accountId: String) {
        viewModelScope.launch {
            try {
                if (oauthManager != null) {
                    oauthManager.revokeAccount(accountId)
                } else {
                    vault.removeAccount(accountId)
                }
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
