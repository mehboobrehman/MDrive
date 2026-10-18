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

## Telemetry: test-env guard in AgentLogger
**When**: 2026-09-14 15:26:36

Robolectric unit tests were POSTing telemetry to the live server (manufacturer/model=robolectric flood in device-stream.log). AgentLogger now detects test env via Build.FINGERPRINT/MANUFACTURER/MODEL containing "robolectric" or system property agentlogger.posting=false, and mirrors to Logcat only — no queueing/network. initDynamicConfig skips discovery & metrics monitoring; setServerAddress keeps cachedServerUrl for assertions but skips POST.

**Impact**: AgentLogger.kt, device-stream.log, all Robolectric unit tests

## OAuth: validate GOOGLE_CLIENT_ID + OAUTH telemetry events
**When**: 2026-09-15 09:23:39

Root-caused invalid_client (401) to the placeholder client id compiled from local.properties.example. getAuthorizationUrl() now throws a descriptive IllegalStateException when the id is blank/placeholder/invalid-looking (never sent to Google), and the OAuth pipeline logs OAUTH_INIT/OAUTH_URL_OPENED/OAUTH_REDIRECT_RECEIVED (parses error+error_description)/OAUTH_TOKEN_EXCHANGE_{START,SUCCESS,FAILED}/OAUTH_MISCONFIGURED. Raw client id is never logged; only "[REDACTED]". UI surfaces Google error cod…

**Impact**: GoogleOAuthManager.kt, MainActivity.kt, LinkedAccountsViewModel.kt, LinkedAccountsScreen.kt, GoogleOAuthManagerTest.kt

## Room Database v4 Schema Migration
**When**: 2026-10-18 08:57:51

Upgraded Room database from version 3 to 4, adding offline pinning, AI categories, duplicate hash tracking, sharing URLs, primary account session table, and smart recommendations table with robust migration support and unit testing.

**Impact**: AppDatabase.kt, VirtualFileEntity.kt, PrimaryAccountSessionEntity.kt, SmartRecommendationEntity.kt, DAOs, Dagger DatabaseModule
