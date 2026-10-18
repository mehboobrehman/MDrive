package com.architect.driveaggregator.di

import android.content.Context
import com.architect.driveaggregator.security.EncryptionManager
import com.architect.driveaggregator.security.SecureTokenVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideEncryptionManager(
        @ApplicationContext context: Context,
        secureTokenVault: SecureTokenVault
    ): EncryptionManager {
        return EncryptionManager(context, secureTokenVault)
    }
}
