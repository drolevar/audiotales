package io.github.drolevar.audiotales.data.library

import kotlinx.coroutines.flow.Flow

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String? = null,
)

data class LibraryTrack(
    val id: String,
    val bookId: String,
    val title: String,
    val uri: String,
    val sequenceIndex: Int,
    val durationMs: Long? = null,
)

data class ListeningProgress(
    val bookId: String,
    val trackId: String,
    val positionMs: Long,
    val updatedAtEpochMs: Long,
)

interface LibraryRepository {
    fun observeBooks(): Flow<List<LibraryBook>>
    suspend fun getBook(bookId: String): LibraryBook?
    suspend fun getTracks(bookId: String): List<LibraryTrack>
    suspend fun getTrack(trackId: String): LibraryTrack?
    suspend fun getProgress(bookId: String): ListeningProgress?
    suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long)
}
