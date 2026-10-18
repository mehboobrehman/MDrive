package com.architect.driveaggregator.di

import android.content.Context
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.PrimaryAccountSessionDao
import com.architect.driveaggregator.data.SagaTransactionDao
import com.architect.driveaggregator.data.SmartRecommendationDao
import com.architect.driveaggregator.data.VirtualFileDao
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
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSharingLinkDao(
        database: AppDatabase
    ): com.architect.driveaggregator.sharing.SharingLinkDao {
        return database.sharingLinkDao()
    }

    @Provides
    @Singleton
    fun provideSharingManager(
        @ApplicationContext context: Context,
        sharingLinkDao: com.architect.driveaggregator.sharing.SharingLinkDao
    ): com.architect.driveaggregator.sharing.SharingManager {
        return com.architect.driveaggregator.sharing.SharingManager(
            context = context,
            sharingLinkDao = sharingLinkDao,
            tokenProvider = { providerId ->
                "mock_access_token_${providerId}"
            }
        )
    }

    @Provides
    @Singleton
    fun provideSagaTransactionDao(
        database: AppDatabase
    ): SagaTransactionDao {
        return database.sagaTransactionDao()
    }

    @Provides
    @Singleton
    fun providePrimaryAccountSessionDao(
        database: AppDatabase
    ): PrimaryAccountSessionDao {
        return database.primaryAccountSessionDao()
    }

    @Provides
    @Singleton
    fun provideSmartRecommendationDao(
        database: AppDatabase
    ): SmartRecommendationDao {
        return database.smartRecommendationDao()
    }

    @Provides
    @Singleton
    fun provideVirtualFileDao(
        database: AppDatabase
    ): VirtualFileDao {
        return database.virtualFileDao()
    }

    @Provides
    @Singleton
    fun provideAiSmartOrganizer(
        @ApplicationContext context: Context,
        virtualFileDao: VirtualFileDao,
        smartRecommendationDao: SmartRecommendationDao
    ): com.architect.driveaggregator.ai.AiSmartOrganizer {
        return com.architect.driveaggregator.ai.AiSmartOrganizer(context, virtualFileDao, smartRecommendationDao)
    }
}
