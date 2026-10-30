package com.architect.driveaggregator.di

import com.architect.driveaggregator.telemetry.TelemetryInterceptor
import com.architect.driveaggregator.auth.DefaultOAuthNetworkClient
import com.architect.driveaggregator.auth.OAuthNetworkClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    @Singleton
    abstract fun bindOAuthNetworkClient(
        impl: DefaultOAuthNetworkClient
    ): OAuthNetworkClient

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val requestBuilder = original.newBuilder()
                    try {
                        requestBuilder.header("X-App-Version", com.architect.driveaggregator.BuildConfig.SEMVER_VERSION)
                        requestBuilder.header("X-Build-Id", com.architect.driveaggregator.BuildConfig.GIT_COMMIT_HASH)
                    } catch (_: Exception) {}
                    chain.proceed(requestBuilder.build())
                }
                .addInterceptor(TelemetryInterceptor())
                .build()
        }
    }
}
