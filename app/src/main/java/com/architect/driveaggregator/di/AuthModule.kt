package com.architect.driveaggregator.di

import android.content.Context
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.auth.OAuthNetworkClient
import com.architect.driveaggregator.security.SecureTokenVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideSecureTokenVault(
        @ApplicationContext context: Context
    ): SecureTokenVault {
        return SecureTokenVault(context)
    }

    @Provides
    @Singleton
    fun provideGoogleOAuthManager(
        tokenVault: SecureTokenVault,
        networkClient: OAuthNetworkClient
    ): GoogleOAuthManager {
        return GoogleOAuthManager(tokenVault, networkClient)
    }
}
