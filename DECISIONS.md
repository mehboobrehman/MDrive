# Project Decisions

Architectural and design decisions made during development.
All agents should read this before starting work and log new decisions here.

## Secrets & Environment Configuration via local.properties and BuildConfig
**When**: 2026-08-22 09:02:35

Secrets (GOOGLE_CLIENT_ID) and config (MAX_PARALLEL_UPLOADS, CHUNK_SIZE_BYTES) are read from local.properties with env variable and fallback defaults, exposed via BuildConfig.

**Impact**: app/build.gradle.kts, local.properties, BuildConfig

## Auth: GoogleOAuthManager & Multi-Account Lifecycle
**When**: 2026-08-22 09:31:00

Implement GoogleOAuthManager supporting OAuth 2.0 multi-account authorization flow, automatic token refresh (< 5 min expiry threshold), account unlinking/revocation, and Google Drive API credential provider. Uses injectable OAuthNetworkClient for HTTP requests and mockable unit testing.

**Impact**: app/src/main/java/com/architect/driveaggregator/auth/GoogleOAuthManager.kt, SecureTokenVault, Google Drive API clients

## OAuth: Pivot to PKCE & drive.file scope
**When**: 2026-08-22 09:55:50

Pivoted OAuth to use PKCE flow and 'drive.file' scope. PKCE eliminates the need for a client secret in the native app, improving security. The 'drive.file' scope is non-restricted and safer for the app's purpose.

**Impact**: app/build.gradle.kts, GoogleOAuthManager.kt, local.properties.example, SecureTokenVault.kt
