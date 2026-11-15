# Release Smoke Testing Protocol - v1.0.25

After a successful deployment, the following steps must be performed to ensure the release is stable in production.

## 1. Version Verification
- [ ] Open the MDrive application.
- [ ] Navigate to **Settings** -> **About**.
- [ ] Verify that the version number is displayed as `1.0.25`.
- [ ] Verify that the build date matches the release date.

## 2. Authentication & Account Management
- [ ] Ensure existing Google Drive accounts are still linked and accessible.
- [ ] Attempt to link a new Google Drive account.
- [ ] Verify the OAuth flow completes successfully and the account appears in the "Linked Accounts" screen.

## 3. Storage Aggregation
- [ ] Verify the "Total Storage" dashboard displays the correct aggregated capacity across all accounts.
- [ ] Ensure individual account quotas are correctly fetched and displayed.

## 4. File Operations
- [ ] List files from multiple accounts.
- [ ] Upload a small test file to one of the accounts.
- [ ] Download a file and verify its integrity.
- [ ] Delete the test file and ensure it is removed from the provider.

## 5. Telemetry & Monitoring
- [ ] Check the backend logs/telemetry dashboard for any `OAUTH_MISCONFIGURED` or `API_ERROR` events.
- [ ] Verify that `OAUTH_INIT` events are being logged during authentication.

## 6. Secure In-App Update
- [ ] (If applicable) Trigger a version check to ensure the app correctly identifies it is on the latest version.
- [ ] Verify that no "Update Available" prompt appears for the current version.

## 7. Secondary Provider Checks (Placeholders)
- [ ] Verify the UI elements for OneDrive/Dropbox/Box are visible (if implemented in this version's UI).
- [ ] Ensure clicking on these providers shows the correct "Coming Soon" or "Link Account" dialog.
