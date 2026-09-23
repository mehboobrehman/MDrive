package com.architect.driveaggregator.di

import android.content.Context
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.SagaTransactionDao
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
    fun provideSagaTransactionDao(
        database: AppDatabase
    ): SagaTransactionDao {
        return database.sagaTransactionDao()
    }
}
