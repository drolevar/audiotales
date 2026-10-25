package io.github.drolevar.audiotales.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import io.github.drolevar.audiotales.data.library.validArtworkUrl
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM online_libraries WHERE isRegistered = 1 ORDER BY displayName COLLATE NOCASE, id")
    fun observeOnlineLibraries(): Flow<List<OnlineLibraryEntity>>

    @Query("SELECT * FROM online_libraries WHERE id = :id")
    suspend fun getOnlineLibrary(id: String): OnlineLibraryEntity?

    @Query("SELECT * FROM online_libraries WHERE baseUrl = :baseUrl")
    suspend fun getOnlineLibraryByBaseUrl(baseUrl: String): OnlineLibraryEntity?

    @Query("UPDATE online_libraries SET isRegistered = 1, isSelected = 1 WHERE id = :id")
    suspend fun restoreOnlineLibrary(id: String)

    @Query("UPDATE online_libraries SET isSelected = :selected WHERE id = :id AND isRegistered = 1")
    suspend fun setOnlineLibrarySelected(id: String, selected: Boolean): Int

    @Query("UPDATE online_libraries SET displayName = :name WHERE id = :id AND isRegistered = 1")
    suspend fun renameOnlineLibrary(id: String, name: String): Int

    @Query("UPDATE online_libraries SET isRegistered = 0, isSelected = 0 WHERE id = :id AND isRegistered = 1")
    suspend fun removeOnlineLibrary(id: String)

    /** Restore registration without replacing stable identity or a listener's custom name. */
    @Transaction
    suspend fun registerOnlineLibrary(candidate: OnlineLibraryEntity): OnlineLibraryEntity {
        insertOnlineLibrary(candidate)
        val existing = requireNotNull(getOnlineLibraryByBaseUrl(candidate.baseUrl))
        restoreOnlineLibrary(existing.id)
        return requireNotNull(getOnlineLibrary(existing.id))
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOnlineLibrary(library: OnlineLibraryEntity)

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE, id")
    fun observeBooks(): Flow<List<BookEntity>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE, id")
    fun observeCollection(): Flow<List<LibraryCollectionEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE providerId = :providerId AND providerBookId = :providerBookId")
    suspend fun getProviderBook(providerId: String, providerBookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE coverUrl IS NULL AND providerId IS NOT NULL AND providerBookId IS NOT NULL ORDER BY CASE WHEN :afterBookId IS NULL OR id > :afterBookId THEN 0 ELSE 1 END, id LIMIT :limit")
    suspend fun getBooksMissingArtwork(limit: Int, afterBookId: String? = null): List<BookEntity>

    @Query("UPDATE books SET coverUrl = :coverUrl, coverReferer = :coverReferer WHERE id = :bookId AND providerId = :providerId AND providerBookId = :providerBookId")
    suspend fun updateBookArtwork(bookId: String, providerId: String, providerBookId: String, coverUrl: String, coverReferer: String?): Int

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY sequenceIndex, id")
    suspend fun getTracks(bookId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrack(trackId: String): TrackEntity?

    @Query("UPDATE tracks SET uri = :uri, streamReferer = :streamReferer, downloadReferer = :downloadReferer WHERE id = :trackId AND bookId = :bookId")
    suspend fun updateTrackTransport(trackId: String, bookId: String, uri: String, streamReferer: String?, downloadReferer: String?): Int

    /** Source renewal changes transport only, never identity, order, progress or bookmarks. */
    @Transaction
    suspend fun refreshTrackSources(bookId: String, replacements: List<TrackEntity>) {
        val stored = getTracks(bookId)
        val byReference = replacements.associateBy { it.providerTrackRef }
        require(stored.isNotEmpty() && replacements.size == stored.size)
        require(byReference.size == replacements.size && byReference.keys.none { it.isNullOrBlank() })
        require(stored.map { it.providerTrackRef }.toSet() == byReference.keys)
        stored.forEach { track ->
            val refreshed = requireNotNull(byReference[track.providerTrackRef])
            require(updateTrackTransport(track.id, bookId, refreshed.uri, refreshed.streamReferer, refreshed.downloadReferer) == 1)
        }
    }

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookIfAbsent(book: BookEntity): Long

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

    @Transaction
    suspend fun insertOnlineBookIfAbsent(book: BookEntity, tracks: List<TrackEntity>): Boolean {
        require(!book.providerId.isNullOrBlank() && !book.providerBookId.isNullOrBlank())
        require(!book.sourceUrl.isNullOrBlank() && book.title.isNotBlank())
        require(tracks.isNotEmpty() && tracks.all { it.bookId == book.id })
        require(tracks.all { !it.providerTrackRef.isNullOrBlank() })
        if (insertBookIfAbsent(book) == -1L) {
            val existing = getProviderBook(book.providerId, book.providerBookId)
            val cover = validArtworkUrl(book.coverUrl)
            if (existing != null && cover != null) {
                updateBookArtwork(existing.id, book.providerId, book.providerBookId, cover, validArtworkUrl(book.coverReferer))
            }
            return false
        }
        insertTracks(tracks)
        return true
    }
}
