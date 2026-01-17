package com.example.reviva.di

import com.example.reviva.data.repository.ScanRepositoryImpl
import com.example.reviva.data.storage.InMemoryScanStore
import com.example.reviva.domain.repository.ScanRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideScanStore(): InMemoryScanStore {
        return InMemoryScanStore()
    }

    @Provides
    @Singleton
    fun provideScanRepository(
        store: InMemoryScanStore
    ): ScanRepository {
        return ScanRepositoryImpl(store)
    }
}
