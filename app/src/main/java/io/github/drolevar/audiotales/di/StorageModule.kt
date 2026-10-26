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
import io.github.drolevar.audiotales.data.local.LibraryMigrations
import io.github.drolevar.audiotales.data.online.OnlineCatalogue
import io.github.drolevar.audiotales.data.online.OnlineBookDetailsSource
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudioTalesDatabase =
        Room.databaseBuilder<AudioTalesDatabase>(context, "audiotales.db")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(LibraryMigrations.MIGRATION_1_2, LibraryMigrations.MIGRATION_2_3, LibraryMigrations.MIGRATION_3_4, LibraryMigrations.MIGRATION_4_5, LibraryMigrations.MIGRATION_5_6, LibraryMigrations.MIGRATION_6_7, LibraryMigrations.MIGRATION_7_8)
            .build()

    @Provides
    fun provideLibraryDao(database: AudioTalesDatabase): LibraryDao = database.libraryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryBindings {
    @Binds
    @Singleton
    internal abstract fun bindOnlineCatalogue(catalogue: OnlineLibraries): OnlineCatalogue

    @Binds
    @Singleton
    internal abstract fun bindOnlineBookDetailsSource(source: OnlineLibraries): OnlineBookDetailsSource

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(repository: RoomLibraryRepository): LibraryRepository
}
