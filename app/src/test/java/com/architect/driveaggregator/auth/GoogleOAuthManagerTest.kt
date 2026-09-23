package com.architect.driveaggregator.auth

import com.architect.driveaggregator.security.SecureTokenVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GoogleOAuthManagerTest {

    private lateinit var mockVault: SecureTokenVault
    private lateinit var mockNetworkClient: OAuthNetworkClient
    private lateinit var oauthManager: GoogleOAuthManager

    // A realistic-looking client id used to exercise the happy path without
    // hitting the placeholder guard in getAuthorizationUrl().
    private val validClientId = "123456789012-abcdefghijklmnop.apps.googleusercontent.com"

    @Before
    fun setUp() {
        mockVault = mock(SecureTokenVault::class.java)
        mockNetworkClient = mock(OAuthNetworkClient::class.java)
        oauthManager = GoogleOAuthManager(mockVault, mockNetworkClient, validClientId)
    }

    @Test
    fun getAuthorizationUrl_includesPkceParametersAndDriveFileScope() {
        val url = oauthManager.getAuthorizationUrl()

        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=https://www.googleapis.com/auth/drive.file"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=$validClientId"))
        verify(mockVault).storePkceVerifier(anyString(), anyString())
    }

    @Test
    fun getAuthorizationUrl_rejectsBlankClientId() {
        val manager = GoogleOAuthManager(mockVault, mockNetworkClient, "   ")
        val ex = assertThrows(IllegalStateException::class.java) { manager.getAuthorizationUrl() }
        assertTrue(ex.message!!.contains("GOOGLE_CLIENT_ID"))
        verify(mockVault, never()).storePkceVerifier(anyString(), anyString())
    }

    @Test
    fun getAuthorizationUrl_rejectsPlaceholderClientId() {
        val manager = GoogleOAuthManager(mockVault, mockNetworkClient, "your-google-client-id.apps.googleusercontent.com")
        val ex = assertThrows(IllegalStateException::class.java) { manager.getAuthorizationUrl() }
        assertTrue(ex.message!!.contains("GOOGLE_CLIENT_ID"))
        verify(mockVault, never()).storePkceVerifier(anyString(), anyString())
    }

    @Test
    fun getAuthorizationUrl_rejectsClientIdWithoutGoogleSuffix() {
        val manager = GoogleOAuthManager(mockVault, mockNetworkClient, "12345-not-a-real-client")
        val ex = assertThrows(IllegalStateException::class.java) { manager.getAuthorizationUrl() }
        assertTrue(ex.message!!.contains("GOOGLE_CLIENT_ID"))
        verify(mockVault, never()).storePkceVerifier(anyString(), anyString())
    }

    @Test
    fun getAuthorizationUrl_acceptsRealisticClientId() {
        val manager = GoogleOAuthManager(mockVault, mockNetworkClient, "987654321098-zyxwvutsrqponmlk.apps.googleusercontent.com")
        val url = manager.getAuthorizationUrl()
        assertTrue(url.contains("client_id=987654321098-zyxwvutsrqponmlk.apps.googleusercontent.com"))
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
