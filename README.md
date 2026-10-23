# MDrive - Universal Cloud Storage Aggregator

MDrive is a state-of-the-art Android application designed to aggregate and manage storage across multiple cloud providers. It offers a unified interface for accessing, organizing, and sharing files, enhanced with AI-powered features, advanced offline capabilities, and robust enterprise-grade security.

---

## Key Features

### 1. Primary Account Universal Login & Vault
* **PKCE Google ID OAuth Master Session**: Anchors all secondary cloud provider sessions securely using Proof Key for Code Exchange (PKCE) with the non-restricted `drive.file` scope.
* **Encrypted Credential Management**: Leverages an AES-256-GCM encrypted `SecureTokenVault` for robust credential storage and lifecycle management.

### 2. Universal Multi-Provider Expansion
* **Seamless Cloud Integration**: Fully integrated with Google Drive, Microsoft OneDrive, Dropbox, MEGA, Box, and TeraBox.
* **Chunked & Resumable Transfers**: Supports chunked uploads/downloads and resumable HTTP PUT clients for efficient, robust data transfer over unreliable networks.
* **Unified Quota Tracking**: Provides real-time, consolidated storage quota tracking across all linked cloud accounts.

### 3. Room v4 Migration & Federated Search
* **Room Database v4 Schema**: Upgraded database schema supporting offline pinning, AI categorization, duplicate hashing, sharing URLs, primary account session state, and smart recommendations.
* **Federated Search**: Combines local SQLite full-text and metadata indexes with concurrent remote queries across connected providers.

### 4. Offline Caching & Sharing
* **Encrypted LRU Disk Caching**: Built-in encrypted least-recently-used (LRU) disk caching via `CacheManager` for rapid offline file access.
* **Secure Collaboration**: Generates secure sharing links with customizable permission levels and expiration controls across all integrated providers.

### 5. AI Smart Organization
* **On-Device Categorization**: Intelligent file classification for automated organization.
* **SHA-256 Duplicate Detection**: Optimizes available cloud storage by identifying and handling duplicate files.
* **Automated Cleanup**: Provides actionable storage cleanup recommendations.

### 6. Material Design 3 (MD3) UI/UX Overhaul
* **Polished Jetpack Compose Interfaces**: Built strictly to MD3 specifications for a modern, accessible user experience.
* **Core Activities & Screens**: Features `LinkedAccountsActivity`, `AdvancedSearchActivity`, `OfflineCachingActivity`, `SharingManagementActivity`, and `AiSmartDashboardActivity`.

### 7. Release & Deployment
* **Production Ready**: Successfully compiles and packages release APKs (`assembleRelease`).

---

## Architecture Highlights

* **Secrets & Environment Configuration**: Utilizes `local.properties` and `BuildConfig` for secure management of secrets (e.g., `GOOGLE_CLIENT_ID`) and configuration variables (e.g., `MAX_PARALLEL_UPLOADS`, `CHUNK_SIZE_BYTES`).
* **OAuth Management**: Implements `GoogleOAuthManager` for OAuth 2.0 multi-account authorization, automatic token refresh (< 5 min expiry threshold), account unlinking, and Google Drive API credential provisioning. Leverages PKCE flow and `drive.file` scope.
* **Telemetry**: Features a robust `AgentLogger` with a test environment guard to prevent live server telemetry from Robolectric unit tests. Includes detailed OAuth telemetry events (`OAUTH_INIT`, `OAUTH_URL_OPENED`, `OAUTH_REDIRECT_RECEIVED`, `OAUTH_TOKEN_EXCHANGE`, etc.) for debugging.
* **Database**: Migrated to Room Database v4 with robust migration support and unit-tested DAOs.

---

## Tech Stack

* **Platform**: Android (Min SDK 24+, Target SDK 34+)
* **UI Framework**: Jetpack Compose (Material Design 3)
* **Database**: Room Persistence Library v4
* **Cloud Integrations**: Google Drive API, Microsoft OneDrive API, Dropbox API, MEGA API, Box API, TeraBox API
* **Authentication**: OAuth 2.0 with PKCE
* **Build System**: Gradle (Kotlin DSL)

---

## Setup and Installation

### Prerequisites
* Android Studio Iguana or newer / Android SDK Build-Tools 34+
* JDK 17 or higher
* Active Google Cloud Console project configured with OAuth 2.0 credentials for Android/Web.

### Configuration
1. Copy the template properties file:
   ```bash
   cp local.properties.example local.properties
   ```
2. Open `local.properties` and configure your credentials:
   ```properties
   GOOGLE_CLIENT_ID=your_actual_google_client_id.apps.googleusercontent.com
   MAX_PARALLEL_UPLOADS=4
   CHUNK_SIZE_BYTES=5242880
   ```

### Building and Testing
* **Build Debug APK**:
  ```bash
  ./gradlew assembleDebug
  ```
* **Build Release APK**:
  ```bash
  ./gradlew assembleRelease
  ```
* **Run Unit & Robolectric Tests**:
  ```bash
  ./gradlew test
  ```

---

## Contributing

We welcome contributions to MDrive! Please follow these guidelines:
1. Fork the repository and create your feature branch from `main`.
2. Ensure all tests pass (`./gradlew test`) and no new LSP errors or lint warnings are introduced.
3. Adhere strictly to existing coding styles, Material Design 3 guidelines, and architectural decisions (logged in `DECISIONS.md`).
4. Submit a detailed pull request describing your changes and verification steps.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
