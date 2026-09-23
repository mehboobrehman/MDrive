package com.architect.driveaggregator.di

import android.content.Context
import com.jakewharton.disklrucache.DiskLruCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @Provides
    @Singleton
    fun provideDiskLruCache(@ApplicationContext context: Context): DiskLruCache {
        val cacheDir = File(context.cacheDir, "reconstructed_files")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val appVersion = 1
        val valueCount = 1
        val maxSize = 100L * 1024 * 1024 // 100MB
        return DiskLruCache.open(cacheDir, appVersion, valueCount, maxSize)
    }
}
