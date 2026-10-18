package com.architect.driveaggregator.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContractsTest {

    @Test
    fun testCloudProviderTypes() {
        val providers = CloudProviderType.values()
        assertEquals(6, providers.size)
        assertTrue(providers.contains(CloudProviderType.GOOGLE_DRIVE))
        assertTrue(providers.contains(CloudProviderType.ONEDRIVE))
        assertTrue(providers.contains(CloudProviderType.DROPBOX))
        assertTrue(providers.contains(CloudProviderType.MEGA))
        assertTrue(providers.contains(CloudProviderType.BOX))
        assertTrue(providers.contains(CloudProviderType.TERABOX))
    }

    @Test
    fun testLinkedAccountContractInstantiation() {
        val account = LinkedAccountContract(
            accountId = "acc_123",
            providerType = CloudProviderType.GOOGLE_DRIVE,
            email = "user@gmail.com",
            displayName = "Primary User",
            avatarUrl = "https://example.com/avatar.png",
            isPrimaryGoogleAccount = true,
            tokenExpiresAt = 1800000000000L,
            totalQuotaBytes = 15000000000L,
            usedQuotaBytes = 5000000000L,
            isConnected = true,
            linkedAtTimestamp = System.currentTimeMillis()
        )
        assertEquals("acc_123", account.accountId)
        assertEquals(CloudProviderType.GOOGLE_DRIVE, account.providerType)
        assertTrue(account.isPrimaryGoogleAccount)
    }

    @Test
    fun testTokenVaultContractInstantiation() {
        val vault = TokenVaultContract(
            accountId = "acc_456",
            providerType = CloudProviderType.ONEDRIVE,
            accessTokenEncrypted = "encrypted_access",
            refreshTokenEncrypted = "encrypted_refresh",
            tokenExpiresAt = 1800000000000L,
            scopes = listOf("Files.Read.All", "offline_access")
        )
        assertEquals("acc_456", vault.accountId)
        assertEquals(CloudProviderType.ONEDRIVE, vault.providerType)
        assertEquals(2, vault.scopes.size)
    }

    @Test
    fun testSearchContractsInstantiation() {
        val query = SearchQueryContract(
            query = "quarterly report",
            providerTypes = listOf(CloudProviderType.GOOGLE_DRIVE, CloudProviderType.DROPBOX),
            mimeTypes = listOf("application/pdf"),
            minSizeBytes = 1000L,
            limit = 20
        )
        assertEquals("quarterly report", query.query)
        assertEquals(2, query.providerTypes?.size)

        val result = SearchResultContract(
            fileId = "file_1",
            name = "report.pdf",
            providerType = CloudProviderType.GOOGLE_DRIVE,
            remoteId = "remote_1",
            mimeType = "application/pdf",
            sizeBytes = 2048L,
            lastModifiedTimestamp = System.currentTimeMillis(),
            downloadUrl = "https://example.com/download",
            isCachedOffline = true,
            matchScore = 0.95f
        )
        assertEquals("report.pdf", result.name)
        assertTrue(result.isCachedOffline)
    }

    @Test
    fun testSmartOrganizationAndSharingContracts() {
        val category = SmartOrganizationCategoryContract(
            categoryId = "cat_docs",
            categoryName = "Documents",
            description = "PDF and Word files",
            suggestedAction = "Archive or compress",
            fileCount = 15,
            totalSizeBytes = 50000000L
        )
        assertEquals("Documents", category.categoryName)

        val sharing = SharingLinkContract(
            linkId = "link_789",
            fileId = "file_1",
            providerType = CloudProviderType.DROPBOX,
            url = "https://db.tt/share123",
            isPasswordProtected = true,
            expiresAtTimestamp = System.currentTimeMillis() + 86400000L,
            downloadCount = 3,
            maxDownloads = 10,
            createdAtTimestamp = System.currentTimeMillis()
        )
        assertEquals("link_789", sharing.linkId)
        assertTrue(sharing.isPasswordProtected)
    }

    @Test
    fun testOfflineCacheStatusContractInstantiation() {
        val cacheStatus = OfflineCacheStatusContract(
            fileId = "file_1",
            isPinned = true,
            localFilePath = "/cache/file_1.dat",
            cachedBytes = 2048L,
            lastSyncedTimestamp = System.currentTimeMillis(),
            syncStatus = CacheSyncStatus.SYNCED
        )
        assertEquals("file_1", cacheStatus.fileId)
        assertTrue(cacheStatus.isPinned)
        assertEquals(CacheSyncStatus.SYNCED, cacheStatus.syncStatus)
    }
}
