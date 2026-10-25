package io.github.drolevar.audiotales.data.library

import io.github.drolevar.audiotales.data.local.LibraryDao
import io.github.drolevar.audiotales.data.local.BookEntity
import io.github.drolevar.audiotales.data.local.PlaybackProgressEntity
import io.github.drolevar.audiotales.data.local.TrackEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository @Inject constructor(
    private val dao: LibraryDao,
) : LibraryRepository {
    override fun observeBooks(): Flow<List<LibraryBook>> = dao.observeBooks().map { books ->
        books.map(BookEntity::toLibraryBook)
    }

    override fun observeCollection(): Flow<List<LibraryCollectionEntry>> = dao.observeCollection().map { entries ->
        entries.map { entry ->
            LibraryCollectionEntry(
                book = entry.book.toLibraryBook(),
                tracks = entry.tracks.sortedWith(compareBy({ it.sequenceIndex }, { it.id }))
                    .map(TrackEntity::toLibraryTrack),
                progress = entry.progress.singleOrNull()?.let {
                    ListeningProgress(it.bookId, it.trackId, it.positionMs, it.updatedAtEpochMs)
                },
            )
        }
    }

    override suspend fun getBook(bookId: String): LibraryBook? = dao.getBook(bookId)?.toLibraryBook()

    override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition {
        val cover = validArtworkUrl(book.coverUrl)
        val entity = BookEntity(
            id = book.id, title = book.title, author = book.author,
            providerId = book.providerId, providerBookId = book.providerBookId, sourceUrl = book.sourceUrl,
            coverUrl = cover, coverReferer = cover?.let { validArtworkUrl(book.coverReferer) },
        )
        val inserted = dao.insertOnlineBookIfAbsent(entity, book.tracks.map { track ->
            TrackEntity(
                id = track.id, bookId = book.id, title = track.title, uri = track.uri,
                sequenceIndex = track.sequenceIndex, durationMs = track.durationMs,
                providerTrackRef = track.providerTrackRef,
                streamReferer = track.streamReferer, downloadReferer = track.downloadReferer,
            )
        })
        val stored = requireNotNull(dao.getProviderBook(book.providerId, book.providerBookId))
        return LibraryAddition(stored.toLibraryBook(), added = inserted)
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
    providerTrackRef = providerTrackRef,
    streamReferer = streamReferer, downloadReferer = downloadReferer,
)

private fun BookEntity.toLibraryBook() = LibraryBook(
    id = id, title = title, author = author,
    providerId = providerId, providerBookId = providerBookId, sourceUrl = sourceUrl, coverUrl = coverUrl, coverReferer = coverReferer,
)
