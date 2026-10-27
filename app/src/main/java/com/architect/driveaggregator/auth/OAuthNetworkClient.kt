package com.architect.driveaggregator.auth

/**
 * Network response after exchanging authorization code for tokens.
 */
data class NetworkTokenResponse(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val email: String? = null,
    val displayName: String? = null
)

/**
 * Network response after refreshing an access token.
 */
data class NetworkRefreshResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String? = null
)

/**
 * Mockable network layer for OAuth 2.0 operations.
 */
interface OAuthNetworkClient {
    /**
     * Exchanges an authorization code for access and refresh tokens.
     */
    suspend fun exchangeCodeForTokens(authCode: String, codeVerifier: String? = null): NetworkTokenResponse

    /**
     * Refreshes an access token using a refresh token.
     */
    suspend fun refreshAccessToken(refreshToken: String): NetworkRefreshResponse

    /**
     * Revokes a refresh token or access token on the Google authorization server.
     * Returns true if revocation succeeded, false otherwise.
     */
    suspend fun revokeToken(token: String): Boolean
}
