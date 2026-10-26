package io.github.drolevar.audiotales.data.local

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import io.github.drolevar.audiotales.data.library.RoomLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedBookDeletionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deletionCascadesDurablyPreservesOtherBooksAndProviderAndReAddingStartsFresh() = runTest {
        val name = "saved-book-deletion-test.db"
        context.deleteDatabase(name)
        try {
            val deleted = book("remove")
            val retained = book("keep")
            val provider = OnlineLibraryEntity("library", "https://library.example/", "My library", "PARTNER")
            val keptProgress = PlaybackProgressEntity(retained.id, retained.tracks.last().id, 4_000, 100)
            val keptBookmark = BookmarkEntity("keep-mark", retained.id, retained.tracks.first().id, 500, "Keep", 90)
            withDatabase(name) { database ->
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                dao.insertOnlineLibrary(provider)
                repository.addOnlineBook(deleted)
                repository.addOnlineBook(retained)
                dao.saveProgress(PlaybackProgressEntity(deleted.id, deleted.tracks.last().id, 8_000, 110))
                deleted.tracks.forEachIndexed { index, track ->
                    dao.insertBookmark(BookmarkEntity("remove-mark-$index", deleted.id, track.id, 700, "Remove", 80))
                }
                dao.saveProgress(keptProgress)
                dao.insertBookmark(keptBookmark)
                assertEquals(2, dao.observeBookmarks(deleted.id).first().size)

                repository.deleteBook(deleted.id)
                repository.deleteBook(deleted.id)
                repository.deleteBook("already-absent")

                assertNull(repository.getBook(deleted.id))
                assertTrue(repository.getTracks(deleted.id).isEmpty())
                deleted.tracks.forEach { assertNull(repository.getTrack(it.id)) }
                assertNull(repository.getProgress(deleted.id))
                assertTrue(dao.observeBookmarks(deleted.id).first().isEmpty())
                assertEquals(listOf(retained.id), repository.observeCollection().first().map { it.book.id })
            }

            withDatabase(name) { database ->
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                assertNull(repository.getBook(deleted.id))
                assertEquals(retained.title, repository.getBook(retained.id)?.title)
                assertEquals(retained.tracks, repository.getTracks(retained.id))
                assertEquals(keptProgress, dao.getProgress(retained.id))
                assertEquals(listOf(keptBookmark), dao.observeBookmarks(retained.id).first())
                assertEquals(provider, dao.getOnlineLibrary(provider.id))

                assertTrue(repository.addOnlineBook(deleted).added)
                assertEquals(deleted.tracks, repository.getTracks(deleted.id))
                assertNull(repository.getProgress(deleted.id))
                assertTrue(dao.observeBookmarks(deleted.id).first().isEmpty())
                assertEquals(keptProgress, dao.getProgress(retained.id))
                assertEquals(provider, dao.getOnlineLibrary(provider.id))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun openDatabase(name: String): AudioTalesDatabase =
        Room.databaseBuilder<AudioTalesDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .build()

    private suspend fun withDatabase(name: String, block: suspend (AudioTalesDatabase) -> Unit) {
        val database = openDatabase(name)
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun book(id: String) = ResolvedOnlineBook(
        id = id,
        providerId = "library",
        providerBookId = id,
        sourceUrl = "https://library.example/books/$id",
        title = "Book $id",
        author = "Author",
        tracks = (0..1).map { index ->
            LibraryTrack(
                id = "$id-track-$index",
                bookId = id,
                title = "Chapter $index",
                uri = "https://cdn.example/$id-$index.mp3",
                sequenceIndex = index,
                providerTrackRef = "/$id-$index.mp3",
                durationMs = 10_000,
            )
        },
    )
}
