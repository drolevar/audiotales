package io.github.drolevar.audiotales.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.RoomLibraryRepository
import io.github.drolevar.audiotales.data.local.AudioTalesDatabase
import io.github.drolevar.audiotales.data.local.LibraryDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudioTalesDatabase =
        Room.databaseBuilder<AudioTalesDatabase>(context, "audiotales.db")
            .setDriver(AndroidSQLiteDriver())
            .build()

    @Provides
    fun provideLibraryDao(database: AudioTalesDatabase): LibraryDao = database.libraryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryBindings {
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(repository: RoomLibraryRepository): LibraryRepository
}
