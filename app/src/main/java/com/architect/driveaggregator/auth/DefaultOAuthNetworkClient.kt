package com.architect.driveaggregator.auth

import com.architect.driveaggregator.BuildConfig
import com.architect.driveaggregator.telemetry.TelemetryInterceptor
import com.architect.driveaggregator.security.SecureTokenVault
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

import javax.inject.Inject

/**
 * Default implementation of OAuthNetworkClient for real HTTP communications with Google OAuth2 endpoints.
 */
class DefaultOAuthNetworkClient @Inject constructor(
    private val tokenVault: SecureTokenVault,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(TelemetryInterceptor())
        .build()
) : OAuthNetworkClient {

    companion object {
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
        private const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"
    }

    override suspend fun exchangeCodeForTokens(authCode: String, codeVerifier: String?): NetworkTokenResponse = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authCode)
            .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
            .add("redirect_uri", GoogleOAuthManager.REDIRECT_URI)

        if (!codeVerifier.isNullOrBlank()) {
            formBuilder.add("code_verifier", codeVerifier)
        }

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBuilder.build())
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("Failed to exchange code for tokens: HTTP ${response.code} - $errorBody")
            }

            val responseBodyString = response.body?.string()
                ?: throw IOException("Empty response body from token endpoint")

            val json = JSONObject(responseBodyString)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() }
                ?: json.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiresInSeconds = json.optLong("expires_in", 3600L)
            val idToken = json.optString("id_token").takeIf { it.isNotBlank() }

            val userInfo = extractUserInfo(idToken, accessToken)
            // Prefer email as accountId if valid, otherwise fallback to sub or generated ID
            val accountId = userInfo.email ?: userInfo.sub ?: "account_${System.currentTimeMillis()}"

            NetworkTokenResponse(
                accountId = accountId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresInSeconds,
                email = userInfo.email,
                displayName = userInfo.name
            )
        }
    }

    override suspend fun refreshAccessToken(refreshToken: String): NetworkRefreshResponse = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBuilder.build())
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("Failed to refresh access token: HTTP ${response.code} - $errorBody")
            }

            val responseBodyString = response.body?.string()
                ?: throw IOException("Empty response body from token refresh endpoint")

            val json = JSONObject(responseBodyString)
            val accessToken = json.getString("access_token")
            val expiresInSeconds = json.optLong("expires_in", 3600L)
            val newRefreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }

            NetworkRefreshResponse(
                accessToken = accessToken,
                expiresInSeconds = expiresInSeconds,
                refreshToken = newRefreshToken
            )
        }
    }

    override suspend fun revokeToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("token", token)

        val request = Request.Builder()
            .url(REVOKE_ENDPOINT)
            .post(formBuilder.build())
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private data class UserInfo(val sub: String? = null, val email: String? = null, val name: String? = null)

    private fun extractUserInfo(idToken: String?, accessToken: String): UserInfo {
        var sub: String? = null
        var email: String? = null
        var name: String? = null

        if (!idToken.isNullOrBlank()) {
            val json = decodeJwtPayload(idToken)
            if (json != null) {
                sub = json.optString("sub", "").takeIf { it.isNotBlank() }
                email = json.optString("email", "").takeIf { it.isNotBlank() }
                name = json.optString("name", "").takeIf { it.isNotBlank() }
            }
        }

        // If data is missing, attempt to fetch from userinfo endpoint
        if (sub == null || email == null || name == null) {
            try {
                val request = Request.Builder()
                    .url(USERINFO_ENDPOINT)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            if (sub == null) sub = json.optString("sub", "").takeIf { it.isNotBlank() }
                            if (email == null) email = json.optString("email", "").takeIf { it.isNotBlank() }
                            if (name == null) name = json.optString("name", "").takeIf { it.isNotBlank() }
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore network errors for profile info; we already have tokens
            }
        }

        return UserInfo(sub, email, name)
    }

    private fun decodeJwtPayload(idToken: String): JSONObject? {
        try {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                var payload = parts[1]
                while (payload.length % 4 != 0) {
                    payload += "="
                }
                val decodedBytes = Base64.getUrlDecoder().decode(payload)
                return JSONObject(String(decodedBytes, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }
}
