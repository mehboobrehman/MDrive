package com.architect.driveaggregator.di

import android.content.Context
import com.architect.driveaggregator.ai.AiSmartOrganizer
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.PrimaryAccountSessionDao
import com.architect.driveaggregator.data.SagaTransactionDao
import com.architect.driveaggregator.data.SmartRecommendationDao
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.sharing.SharingLinkDao
import com.architect.driveaggregator.sharing.SharingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun providePrimaryAccountSessionDao(database: AppDatabase): PrimaryAccountSessionDao {
        return database.primaryAccountSessionDao()
    }

    @Provides
    fun provideSagaTransactionDao(database: AppDatabase): SagaTransactionDao {
        return database.sagaTransactionDao()
    }

    @Provides
    @Singleton
    fun provideVirtualFileDao(database: AppDatabase): VirtualFileDao {
        return database.virtualFileDao()
    }

    @Provides
    @Singleton
    fun provideSmartRecommendationDao(database: AppDatabase): SmartRecommendationDao {
        return database.smartRecommendationDao()
    }

    @Provides
    @Singleton
    fun provideSharingLinkDao(database: AppDatabase): SharingLinkDao {
        return database.sharingLinkDao()
    }

    @Provides
    @Singleton
    fun provideSharingManager(
        @ApplicationContext context: Context,
        sharingLinkDao: SharingLinkDao
    ): SharingManager {
        return SharingManager(context, sharingLinkDao) { providerId ->
            // Default token provider fallback
            ""
        }
    }

    @Provides
    @Singleton
    fun provideAiSmartOrganizer(
        @ApplicationContext context: Context,
        virtualFileDao: VirtualFileDao,
        smartRecommendationDao: SmartRecommendationDao
    ): AiSmartOrganizer {
        return AiSmartOrganizer(context, virtualFileDao, smartRecommendationDao)
    }
}
