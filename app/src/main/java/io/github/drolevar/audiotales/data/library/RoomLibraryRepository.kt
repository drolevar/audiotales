package io.github.drolevar.audiotales.data.library

import io.github.drolevar.audiotales.data.local.LibraryDao
import io.github.drolevar.audiotales.data.local.PlaybackProgressEntity
import io.github.drolevar.audiotales.data.local.TrackEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository @Inject constructor(
    private val dao: LibraryDao,
) : LibraryRepository {
    override fun observeBooks(): Flow<List<LibraryBook>> = dao.observeBooks().map { books ->
        books.map { LibraryBook(id = it.id, title = it.title, author = it.author) }
    }

    override suspend fun getBook(bookId: String): LibraryBook? = dao.getBook(bookId)?.let {
        LibraryBook(id = it.id, title = it.title, author = it.author)
    }

    override suspend fun getTracks(bookId: String): List<LibraryTrack> =
        dao.getTracks(bookId).map(TrackEntity::toLibraryTrack)

    override suspend fun getTrack(trackId: String): LibraryTrack? =
        dao.getTrack(trackId)?.toLibraryTrack()

    override suspend fun getProgress(bookId: String): ListeningProgress? =
        dao.getProgress(bookId)?.let {
            ListeningProgress(it.bookId, it.trackId, it.positionMs, it.updatedAtEpochMs)
        }

    override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) {
        require(positionMs >= 0) { "Playback position must not be negative" }
        val track = requireNotNull(dao.getTrack(trackId)) { "Track is not in the library" }
        require(track.bookId == bookId) { "Track does not belong to this book" }
        dao.saveProgress(
            PlaybackProgressEntity(bookId, trackId, positionMs, System.currentTimeMillis()),
        )
    }
}

private fun TrackEntity.toLibraryTrack() = LibraryTrack(
    id = id,
    bookId = bookId,
    title = title,
    uri = uri,
    sequenceIndex = sequenceIndex,
    durationMs = durationMs,
)
