package io.github.drolevar.audiotales.data.local

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        TrackEntity::class,
        PlaybackProgressEntity::class,
        BookmarkEntity::class,
        OnlineLibraryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AudioTalesDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
