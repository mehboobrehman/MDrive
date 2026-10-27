package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.ui.model.AccountStatus
import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.ui.model.DriveAccountInfo
import com.architect.driveaggregator.ui.model.PooledStorageOverview
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.provider.GoogleDriveStorageProvider
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
                    val providerType = determineProviderType(id)
                    
                    // Prefer cached values from vault
                    val cachedEmail = vault.getAccountEmail(id) 
                        ?: if (providerType == CloudProviderType.GOOGLE_DRIVE) "$id@gmail.com" else "$id@${providerType.providerId}.cloud"
                    
                    val rawCachedName = vault.getAccountDisplayName(id)
                    val displayName = if (rawCachedName != null) {
                        "${providerType.displayName} ($rawCachedName)"
                    } else {
                        "${providerType.displayName} ($id)"
                    }

                    DriveAccountInfo(
                        accountId = id,
                        email = cachedEmail,
                        displayName = displayName,
                        totalBytes = providerType.defaultQuotaBytes,
                        usedBytes = 0L,
                        freeBytes = providerType.defaultQuotaBytes,
                        status = AccountStatus.SYNCING,
                        providerType = providerType
                    )
                }.toMutableList()

                // Initial UI update with cached/default info
                refreshPooledOverview(accountsList)

                // Asynchronously fetch live quota for each account
                accountIds.forEachIndexed { index, id ->
                    launch {
                        val providerType = determineProviderType(id)
                        if (providerType == CloudProviderType.GOOGLE_DRIVE && oauthManager != null) {
                            try {
                                val accessToken = oauthManager.getValidAccessToken(id)
                                if (accessToken != null) {
                                    val provider = GoogleDriveStorageProvider(
                                        providerId = id,
                                        accessTokenProvider = { accessToken }
                                    )
                                    val details = provider.getQuotaAndUser()
                                    
                                    // Cache live profile info
                                    details.email?.let { vault.storeAccountEmail(id, it) }
                                    details.displayName?.let { vault.storeAccountDisplayName(id, it) }

                                    val freshDisplayName = if (details.displayName != null) {
                                        "${providerType.displayName} (${details.displayName})"
                                    } else {
                                        accountsList[index].displayName
                                    }

                                    val updated = accountsList[index].copy(
                                        email = details.email ?: accountsList[index].email,
                                        displayName = freshDisplayName,
                                        totalBytes = details.totalBytes,
                                        usedBytes = details.usedBytes,
                                        freeBytes = details.freeBytes,
                                        status = AccountStatus.ACTIVE
                                    )
                                    accountsList[index] = updated
                                    refreshPooledOverview(accountsList)
                                } else {
                                    accountsList[index] = accountsList[index].copy(status = AccountStatus.EXPIRED_TOKEN)
                                    refreshPooledOverview(accountsList)
                                }
                            } catch (e: Exception) {
                                accountsList[index] = accountsList[index].copy(status = AccountStatus.ERROR)
                                refreshPooledOverview(accountsList)
                            }
                        } else {
                            // Non-Google accounts remain simulated for now but set as Active
                            val defaultCapacity = providerType.defaultQuotaBytes
                            accountsList[index] = accountsList[index].copy(
                                status = AccountStatus.ACTIVE,
                                usedBytes = defaultCapacity / 4, // simulation for non-google
                                freeBytes = defaultCapacity - (defaultCapacity / 4)
                            )
                            refreshPooledOverview(accountsList)
                        }
                    }
                }

                _uiState.update { it.copy(isLoading = false) }
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

    private fun determineProviderType(accountId: String): CloudProviderType {
        return when {
            accountId.startsWith("onedrive_") -> CloudProviderType.ONEDRIVE
            accountId.startsWith("dropbox_") -> CloudProviderType.DROPBOX
            accountId.startsWith("mega_") -> CloudProviderType.MEGA
            accountId.startsWith("box_") -> CloudProviderType.BOX
            accountId.startsWith("terabox_") -> CloudProviderType.TERABOX
            else -> CloudProviderType.GOOGLE_DRIVE
        }
    }

    private fun refreshPooledOverview(accounts: List<DriveAccountInfo>) {
        val totalCapacity = accounts.sumOf { it.totalBytes }
        val totalUsed = accounts.sumOf { it.usedBytes }
        val totalFree = accounts.sumOf { it.freeBytes }

        val overview = PooledStorageOverview(
            totalCapacityBytes = totalCapacity,
            totalUsedBytes = totalUsed,
            totalFreeBytes = totalFree,
            accounts = accounts.toList()
        )

        _uiState.update { it.copy(pooledStorage = overview) }
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
