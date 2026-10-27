package com.architect.driveaggregator.auth

import java.util.Base64
import com.architect.driveaggregator.BuildConfig
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.telemetry.AgentLogger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * GoogleOAuthManager handles the OAuth 2.0 flow using PKCE for secure authentication
 * with the limited 'drive.file' scope.
 */
class GoogleOAuthManager(
    private val tokenVault: SecureTokenVault,
    private val networkClient: OAuthNetworkClient,
    private val clientId: String = BuildConfig.GOOGLE_CLIENT_ID
) {
    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val REDIRECT_URI = "com.architect.driveaggregator:/oauth2redirect"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val REFRESH_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes

        private const val CLIENT_ID_PLACEHOLDER = "your-google-client-id.apps.googleusercontent.com"
        private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

        /**
         * Shown to the user when GOOGLE_CLIENT_ID is blank, still the placeholder from
         * local.properties.example, or otherwise not a real Google OAuth client id.
         */
        const val OAUTH_MISCONFIGURED_MESSAGE =
            "OAuth misconfigured: GOOGLE_CLIENT_ID is missing/placeholder in local.properties — see project docs"

        /**
         * Returns the reason a client id cannot be used with Google's authorization
         * endpoint, or null if the id looks valid.
         */
        internal fun validateClientId(clientId: String): ClientIdIssue? = when {
            clientId.isBlank() -> ClientIdIssue.BLANK
            clientId == CLIENT_ID_PLACEHOLDER -> ClientIdIssue.PLACEHOLDER
            !clientId.endsWith(CLIENT_ID_SUFFIX) -> ClientIdIssue.INVALID_SUFFIX
            else -> null
        }
    }

    internal enum class ClientIdIssue {
        BLANK,
        PLACEHOLDER,
        INVALID_SUFFIX
    }

    /**
     * Generates a URL for the user to authorize the app.
     * Implements PKCE (Proof Key for Code Exchange) by generating a challenge and storing the verifier.
     */
    fun getAuthorizationUrl(): String {
        // Guard against a blank/placeholder client id being sent to Google's authorization
        // endpoint, which fails with HTTP 401 invalid_client ("The OAuth client not found").
        val trimmedClientId = clientId.trim()
        val clientIdIssue = validateClientId(trimmedClientId)
        if (clientIdIssue != null) {
            AgentLogger.logEvent(
                level = "ERROR",
                category = "OAUTH",
                event = "OAUTH_MISCONFIGURED",
                message = OAUTH_MISCONFIGURED_MESSAGE,
                payload = mapOf(
                    "clientIdState" to clientIdIssue.name,
                    "client_id" to "[REDACTED]"
                )
            )
            throw IllegalStateException(OAUTH_MISCONFIGURED_MESSAGE)
        }

        val state = UUID.randomUUID().toString()
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        
        tokenVault.storePkceVerifier(state, codeVerifier)

        return "$AUTH_ENDPOINT?" +
                "client_id=$trimmedClientId&" +
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
            AgentLogger.logEvent(
                "INFO", "OAUTH", "OAUTH_TOKEN_EXCHANGE_START",
                "Exchanging authorization code for tokens",
                mapOf("flow" to "authorization_code")
            )
            val response = networkClient.exchangeCodeForTokens(code, codeVerifier)
            val expiryTimeMs = System.currentTimeMillis() + (response.expiresInSeconds * 1000L)
            
            tokenVault.storeAccessToken(response.accountId, response.accessToken, expiryTimeMs)
            response.refreshToken?.let {
                tokenVault.storeRefreshToken(response.accountId, it)
            }
            response.email?.let {
                tokenVault.storeAccountEmail(response.accountId, it)
            }
            response.displayName?.let {
                tokenVault.storeAccountDisplayName(response.accountId, it)
            }

            AgentLogger.logEvent(
                "INFO", "OAUTH", "OAUTH_TOKEN_EXCHANGE_SUCCESS",
                "Token exchange succeeded",
                mapOf("flow" to "authorization_code")
            )
            Result.success(response.accountId)
        } catch (e: Exception) {
            AgentLogger.logEvent(
                "ERROR", "OAUTH", "OAUTH_TOKEN_EXCHANGE_FAILED",
                "Token exchange failed: ${e.message}",
                mapOf("flow" to "authorization_code", "failure" to (e.message ?: "unknown"))
            )
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
            AgentLogger.logEvent(
                "INFO", "OAUTH", "OAUTH_TOKEN_EXCHANGE_START",
                "Refreshing access token",
                mapOf("flow" to "refresh_token")
            )
            val response = networkClient.refreshAccessToken(refreshToken)
            val newExpiryTimeMs = System.currentTimeMillis() + (response.expiresInSeconds * 1000L)
            
            tokenVault.storeAccessToken(accountId, response.accessToken, newExpiryTimeMs)
            response.refreshToken?.let {
                tokenVault.storeRefreshToken(accountId, it)
            }

            AgentLogger.logEvent(
                "INFO", "OAUTH", "OAUTH_TOKEN_EXCHANGE_SUCCESS",
                "Token refresh succeeded",
                mapOf("flow" to "refresh_token")
            )
            response.accessToken
        } catch (e: Exception) {
            AgentLogger.logEvent(
                "ERROR", "OAUTH", "OAUTH_TOKEN_EXCHANGE_FAILED",
                "Token refresh failed: ${e.message}",
                mapOf("flow" to "refresh_token", "failure" to (e.message ?: "unknown"))
            )
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
