package com.architect.driveaggregator.di

import android.content.Context
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.SagaTransactionDao
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.security.EncryptionManager
import com.architect.driveaggregator.engine.SecureDistributedEngine
import com.architect.driveaggregator.engine.VirtualDriveEngine
import com.architect.driveaggregator.sync.SyncOrchestrator
import com.architect.driveaggregator.upload.GoogleResumableUploadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideVirtualDriveEngine(
        sagaTransactionDao: SagaTransactionDao,
        cacheManager: com.architect.driveaggregator.cache.CacheManager
    ): VirtualDriveEngine {
        return VirtualDriveEngine(
            sagaTransactionDao = sagaTransactionDao,
            cacheManager = cacheManager
        )
    }

    @Provides
    @Singleton
    fun provideSecureDistributedEngine(): SecureDistributedEngine {
        return SecureDistributedEngine()
    }

    @Provides
    @Singleton
    fun provideGoogleResumableUploadManager(
        sagaTransactionDao: SagaTransactionDao,
        oauthManager: GoogleOAuthManager,
        okHttpClient: OkHttpClient
    ): GoogleResumableUploadManager {
        return GoogleResumableUploadManager(sagaTransactionDao, oauthManager, okHttpClient)
    }

    @Provides
    @Singleton
    fun provideSyncOrchestrator(
        database: AppDatabase,
        virtualDriveEngine: VirtualDriveEngine,
        uploadManager: GoogleResumableUploadManager,
        encryptionManager: EncryptionManager,
        @ApplicationContext context: Context
    ): SyncOrchestrator {
        return SyncOrchestrator(database, virtualDriveEngine, uploadManager, encryptionManager, context)
    }
}
