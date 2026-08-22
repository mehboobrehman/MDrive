package com.architect.driveaggregator.auth

import java.util.Base64
import com.architect.driveaggregator.BuildConfig
import com.architect.driveaggregator.security.SecureTokenVault
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * GoogleOAuthManager handles the OAuth 2.0 flow using PKCE for secure authentication
 * with the limited 'drive.file' scope.
 */
class GoogleOAuthManager(
    private val tokenVault: SecureTokenVault,
    private val networkClient: OAuthNetworkClient
) {
    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val REDIRECT_URI = "com.architect.driveaggregator:/oauth2redirect"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val REFRESH_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
    }

    /**
     * Generates a URL for the user to authorize the app.
     * Implements PKCE (Proof Key for Code Exchange) by generating a challenge and storing the verifier.
     */
    fun getAuthorizationUrl(): String {
        val state = UUID.randomUUID().toString()
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        
        tokenVault.storePkceVerifier(state, codeVerifier)

        return "$AUTH_ENDPOINT?" +
                "client_id=${BuildConfig.GOOGLE_CLIENT_ID}&" +
                "redirect_uri=$REDIRECT_URI&" +
                "response_type=code&" +
                "scope=$SCOPE&" +
                "state=$state&" +
                "code_challenge=$codeChallenge&" +
                "code_challenge_method=S256&" +
                "access_type=offline&" +
                "prompt=consent"
    }

    /**
     * Exchanges the authorization code for access and refresh tokens.
     * Uses the stored PKCE verifier associated with the provided state.
     */
    suspend fun handleAuthorizationCode(code: String, state: String): Result<String> {
        val codeVerifier = tokenVault.getAndRemovePkceVerifier(state)
            ?: return Result.failure(Exception("Invalid state or missing PKCE verifier"))

        return try {
            val response = networkClient.exchangeCodeForTokens(code, codeVerifier)
            val expiryTimeMs = System.currentTimeMillis() + (response.expiresInSeconds * 1000L)
            
            tokenVault.storeAccessToken(response.accountId, response.accessToken, expiryTimeMs)
            response.refreshToken?.let {
                tokenVault.storeRefreshToken(response.accountId, it)
            }
            
            Result.success(response.accountId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves a valid access token for the given account ID.
     * Automatically refreshes the token if it is expired or nearing expiration.
     */
    suspend fun getValidAccessToken(accountId: String): String? {
        val currentToken = tokenVault.getAccessToken(accountId)
        val expiry = tokenVault.getAccessTokenExpiry(accountId) ?: 0L
        
        // Return current token if it's still valid and not within the refresh threshold
        if (currentToken != null && (expiry - System.currentTimeMillis() > REFRESH_THRESHOLD_MS)) {
            return currentToken
        }

        // Try to refresh the token
        val refreshToken = tokenVault.getRefreshToken(accountId) ?: return null
        
        return try {
            val response = networkClient.refreshAccessToken(refreshToken)
            val newExpiryTimeMs = System.currentTimeMillis() + (response.expiresInSeconds * 1000L)
            
            tokenVault.storeAccessToken(accountId, response.accessToken, newExpiryTimeMs)
            response.refreshToken?.let {
                tokenVault.storeRefreshToken(accountId, it)
            }
            
            response.accessToken
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Revokes access for the specified account and clears it from the secure vault.
     */
    suspend fun revokeAccount(accountId: String): Boolean {
        val accessToken = tokenVault.getAccessToken(accountId)
        val refreshToken = tokenVault.getRefreshToken(accountId)
        
        var success = true
        if (refreshToken != null) {
            success = networkClient.revokeToken(refreshToken)
        } else if (accessToken != null) {
            success = networkClient.revokeToken(accessToken)
        }
        
        tokenVault.removeAccount(accountId)
        return success
    }

    private fun generateCodeVerifier(): String {
        val sr = SecureRandom()
        val code = ByteArray(32)
        sr.nextBytes(code)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(code)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
