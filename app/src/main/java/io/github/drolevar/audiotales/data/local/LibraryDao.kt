package io.github.drolevar.audiotales.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE, id")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY sequenceIndex, id")
    suspend fun getTracks(bookId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrack(trackId: String): TrackEntity?

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: String): PlaybackProgressEntity?

    @Upsert
    suspend fun saveProgress(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAtEpochMs, id")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Insert
    suspend fun insertBook(book: BookEntity)

    @Insert
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Transaction
    suspend fun insertBookWithTracks(book: BookEntity, tracks: List<TrackEntity>) {
        require(book.id.isNotBlank())
        require(book.title.isNotBlank())
        require(tracks.all { it.bookId == book.id && it.id.isNotBlank() })
        require(tracks.all { it.sequenceIndex >= 0 && it.uri.isNotBlank() })
        require(tracks.all { it.durationMs == null || it.durationMs >= 0 })
        insertBook(book)
        insertTracks(tracks)
    }
}
