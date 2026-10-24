package io.github.drolevar.audiotales.playback

import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackItemResolverTest {
    private val track = LibraryTrack(
        id = "track-1",
        bookId = "book-1",
        title = "Chapter 1",
        uri = "content://documents/library-owned-track",
        sequenceIndex = 0,
    )
    private val library = FakeLibrary(listOf(track))
    private val resolver = PlaybackItemResolver(library)

    @Test
    fun knownTrackResolvesToPersistedLocation() = runTest {
        val resolved = resolver.resolve(listOf(PlaybackRequest("track:track-1", false)))

        assertEquals(listOf(track), resolved)
        assertEquals(listOf("track-1"), library.requestedTrackIds)
    }

    @Test
    fun providedLocationIsRejectedEvenWithValidLibraryId() = runTest {
        assertRejected(listOf(PlaybackRequest("track:track-1", true)))

        assertTrue(library.requestedTrackIds.isEmpty())
    }

    @Test
    fun uriCannotBeUsedAsMediaId() = runTest {
        assertRejected(listOf(PlaybackRequest("content://documents/another-apps-file", false)))

        assertTrue(library.requestedTrackIds.isEmpty())
    }

    @Test
    fun unknownTrackRejectsWholeRequest() = runTest {
        assertRejected(
            listOf(
                PlaybackRequest("track:track-1", false),
                PlaybackRequest("track:unknown", false),
            ),
        )
    }

    private suspend fun assertRejected(requests: List<PlaybackRequest>) {
        var rejected = false
        try {
            resolver.resolve(requests)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Request must not produce a playable queue", rejected)
    }

    private class FakeLibrary(private val tracks: List<LibraryTrack>) : LibraryRepository {
        val requestedTrackIds = mutableListOf<String>()

        override fun observeBooks(): Flow<List<LibraryBook>> = flowOf(emptyList())
        override suspend fun getBook(bookId: String): LibraryBook? = null
        override suspend fun getTracks(bookId: String): List<LibraryTrack> = tracks.filter { it.bookId == bookId }
        override suspend fun getTrack(trackId: String): LibraryTrack? {
            requestedTrackIds += trackId
            return tracks.find { it.id == trackId }
        }
        override suspend fun getProgress(bookId: String): ListeningProgress? = null
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) = Unit
    }
}
