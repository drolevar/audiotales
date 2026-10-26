package io.github.drolevar.audiotales.data.library

import kotlinx.coroutines.flow.Flow

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String? = null,
    val providerId: String? = null,
    val providerBookId: String? = null,
    val sourceUrl: String? = null,
    val coverUrl: String? = null,
    val coverReferer: String? = null,
    val description: String? = null,
    val language: String? = null,
) {
    val displayTitle: String get() = bookDisplayTitle(title, author)
}

data class LibraryTrack(
    val id: String,
    val bookId: String,
    val title: String,
    val uri: String,
    val sequenceIndex: Int,
    val durationMs: Long? = null,
    val providerTrackRef: String? = null,
    val streamReferer: String? = null,
    val downloadReferer: String? = null,
    val sourceStartMs: Long = 0,
    val sourceEndMs: Long? = null,
    val audioResourceId: String? = null,
    /** Null means unavailable; an empty list is an explicit absence of credits. */
    val readerCredits: List<ReaderCredit>? = null,
) {
    /** Sections of one recording share one complete audio download and cache key. */
    val downloadId: String get() = audioResourceId ?: id
}

data class ListeningProgress(
    val bookId: String,
    val trackId: String,
    val positionMs: Long,
    val updatedAtEpochMs: Long,
)

interface LibraryRepository {
    fun observeBooks(): Flow<List<LibraryBook>>
    fun observeCollection(): Flow<List<LibraryCollectionEntry>>
    suspend fun getBook(bookId: String): LibraryBook?
    suspend fun getTracks(bookId: String): List<LibraryTrack>
    suspend fun getTrack(trackId: String): LibraryTrack?
    suspend fun getProgress(bookId: String): ListeningProgress?
    suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long)
    suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition
    /** Delete the saved book and its listening data after playback/downloads have been stopped. */
    suspend fun deleteBook(bookId: String)
}

data class LibraryCollectionEntry(
    val book: LibraryBook,
    val tracks: List<LibraryTrack>,
    val progress: ListeningProgress?,
)

data class ResolvedOnlineBook(
    val id: String,
    val providerId: String,
    val providerBookId: String,
    val sourceUrl: String,
    val title: String,
    val author: String?,
    val tracks: List<LibraryTrack>,
    val coverUrl: String? = null,
    val coverReferer: String? = null,
    val description: String? = null,
    val language: String? = null,
)

data class LibraryAddition(val book: LibraryBook, val added: Boolean)
