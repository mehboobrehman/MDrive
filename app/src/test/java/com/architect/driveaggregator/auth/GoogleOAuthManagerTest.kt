package com.architect.driveaggregator.auth

import com.architect.driveaggregator.security.SecureTokenVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GoogleOAuthManagerTest {

    private lateinit var mockVault: SecureTokenVault
    private lateinit var mockNetworkClient: OAuthNetworkClient
    private lateinit var oauthManager: GoogleOAuthManager

    @Before
    fun setUp() {
        mockVault = mock(SecureTokenVault::class.java)
        mockNetworkClient = mock(OAuthNetworkClient::class.java)
        oauthManager = GoogleOAuthManager(mockVault, mockNetworkClient)
    }

    @Test
    fun getAuthorizationUrl_includesPkceParametersAndDriveFileScope() {
        val url = oauthManager.getAuthorizationUrl()

        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=https://www.googleapis.com/auth/drive.file"))
        assertTrue(url.contains("response_type=code"))
        verify(mockVault).storePkceVerifier(anyString(), anyString())
    }

    @Test
    fun handleAuthorizationCode_usesVerifierAndStoresTokens() = runBlocking {
        val code = "auth_code"
        val state = "state_123"
        val verifier = "verifier_abc"
        val accountId = "account_id"
        val accessToken = "access_token"
        val refreshToken = "refresh_token"
        val expiresIn = 3600L

        whenever(mockVault.getAndRemovePkceVerifier(state)).thenReturn(verifier)
        whenever(mockNetworkClient.exchangeCodeForTokens(eq(code), eq(verifier))).thenReturn(
            NetworkTokenResponse(accountId, accessToken, refreshToken, expiresIn)
        )

        val result = oauthManager.handleAuthorizationCode(code, state)

        assertTrue(result.isSuccess)
        assertEquals(accountId, result.getOrNull())
        verify(mockVault).storeAccessToken(eq(accountId), eq(accessToken), any())
        verify(mockVault).storeRefreshToken(eq(accountId), eq(refreshToken))
    }

    @Test
    fun getValidAccessToken_refreshesWhenExpired() = runBlocking {
        val accountId = "account_id"
        val oldToken = "old_token"
        val newToken = "new_token"
        val refreshToken = "refresh_token"
        
        // Expired 10 minutes ago
        whenever(mockVault.getAccessToken(accountId)).thenReturn(oldToken)
        whenever(mockVault.getAccessTokenExpiry(accountId)).thenReturn(System.currentTimeMillis() - 600000L)
        whenever(mockVault.getRefreshToken(accountId)).thenReturn(refreshToken)
        whenever(mockNetworkClient.refreshAccessToken(refreshToken)).thenReturn(
            NetworkRefreshResponse(newToken, 3600L)
        )

        val token = oauthManager.getValidAccessToken(accountId)

        assertEquals(newToken, token)
        verify(mockVault).storeAccessToken(eq(accountId), eq(newToken), any())
    }
}
