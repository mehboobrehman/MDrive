package com.architect.driveaggregator.provider

import org.junit.Assert.*
import org.junit.Test

class GoogleDriveStorageProviderTest {

    @Test
    fun testGoogleDriveStorageProviderInstantiation() {
        val provider = GoogleDriveStorageProvider(
            providerId = "gdrive_test",
            accessTokenProvider = { "fake_access_token" }
        )
        assertEquals("gdrive_test", provider.providerId)
    }
}
