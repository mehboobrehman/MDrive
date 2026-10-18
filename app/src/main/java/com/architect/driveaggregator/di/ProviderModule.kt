package com.architect.driveaggregator.di

import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.provider.StorageProvider
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideStorageProviders(): Map<CloudProviderType, StorageProvider> {
        return emptyMap()
    }
}
