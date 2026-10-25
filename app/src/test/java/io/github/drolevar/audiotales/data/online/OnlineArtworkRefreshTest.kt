package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import io.github.drolevar.audiotales.data.local.BookEntity
import io.github.drolevar.audiotales.data.local.LibraryDao
import io.github.drolevar.audiotales.data.local.OnlineLibraryEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class OnlineArtworkRefreshTest {
    @Test fun refreshFetchesOnlyMetadataAndChangesOnlyArtwork() = runTest {
        val fixture = Fixture(1) { metadata(it) }
        val original = fixture.books.getValue("1")
        val updated = fixture.online.refreshBookArtwork("1")
        assertEquals(original.copy(coverUrl = "https://archive.org/1.jpg"), updated)
        assertEquals(listOf("1"), fixture.requests.map { it.queryParameter("id") })
        assertEquals("/api/feed/audiobooks/", fixture.requests.single().encodedPath)
        assertNull(fixture.requests.single().queryParameter("extended"))
        assertEquals("1", fixture.requests.single().queryParameter("coverart"))
    }

    @Test fun missingArtworkRetainsKnownCoverAndOtherwiseReturnsNull() = runTest {
        val fixture = Fixture(2) { """{"books":[{"id":"$it","title":"Book"}]}""" }
        fixture.books["1"] = fixture.books.getValue("1").copy(coverUrl = "https://archive.org/known.jpg", coverReferer = "https://library.example/")
        assertEquals(fixture.books.getValue("1"), fixture.online.refreshBookArtwork("1"))
        assertNull(fixture.online.refreshBookArtwork("2"))
        assertNull(fixture.online.refreshBookArtwork("unknown"))
        assertEquals(0, fixture.updates)
    }

    @Test fun mismatchedRemoteIdentityIsAProtocolFailureAndDoesNotUpdateArtwork() = runTest {
        val fixture = Fixture(1) { metadata("999") }
        try {
            fixture.online.refreshBookArtwork("1")
            fail("Expected mismatched identity to fail")
        } catch (error: OnlineBookException) {
            assertEquals(OnlineBookError.UNEXPECTED_FORMAT, error.reason)
        }
        assertNull(fixture.books.getValue("1").coverUrl)
        assertEquals(0, fixture.updates)
    }

    @Test fun backfillIsBoundedAndOneProtocolFailureDoesNotBlockLaterBooks() = runTest {
        val fixture = Fixture(25) { if (it == "1") "{}" else metadata(it) }
        fixture.online.refreshMissingArtwork()
        assertEquals(20, fixture.requestedLimit)
        val orderedIds = (1..25).map(Int::toString).sorted()
        assertEquals(orderedIds.take(20), fixture.requests.map { it.queryParameter("id") })
        assertNull(fixture.books.getValue("1").coverUrl)
        assertEquals("https://archive.org/20.jpg", fixture.books.getValue("20").coverUrl)
        assertNull(fixture.books.getValue(orderedIds[20]).coverUrl)
        assertEquals(19, fixture.updates)
    }

    @Test fun coverlessFirstBatchCannotStarveLaterBooksAcrossInvocations() = runTest {
        val orderedIds = (1..25).map(Int::toString).sorted()
        val coverless = orderedIds.take(20).toSet()
        val fixture = Fixture(25) { id ->
            if (id in coverless) """{"books":[{"id":"$id","title":"No cover"}]}""" else metadata(id)
        }
        fixture.online.refreshMissingArtwork()
        assertEquals(orderedIds.take(20), fixture.requests.map { it.queryParameter("id") })
        assertEquals(0, fixture.updates)

        fixture.online.refreshMissingArtwork()
        val secondPass = fixture.requests.drop(20).map { it.queryParameter("id") }
        assertEquals(20, secondPass.size)
        assertEquals(orderedIds.takeLast(5) + orderedIds.take(15), secondPass)
        assertEquals(5, fixture.updates)
        orderedIds.takeLast(5).forEach { id ->
            assertEquals("https://archive.org/$id.jpg", fixture.books.getValue(id).coverUrl)
        }
        coverless.forEach { assertNull(fixture.books.getValue(it).coverUrl) }
    }

    @Test fun backfillHonorsCancellationWithoutRequestingLaterBooks() = runTest {
        val fixture = Fixture(3) { metadata(it) }
        fixture.cancelOnBookId = "1"
        try {
            fixture.online.refreshMissingArtwork()
            fail("Expected cancellation to propagate")
        } catch (_: CancellationException) { /* Caller owns cancellation. */ }
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.updates)
        fixture.cancelOnBookId = null
        fixture.online.refreshMissingArtwork()
        assertEquals(listOf("1", "2", "3"), fixture.requests.map { it.queryParameter("id") })
    }

    private class Fixture(count: Int, response: (String) -> String) {
        val books = (1..count).associate { id ->
            id.toString() to LibraryBook(id.toString(), "Saved title", "Saved author", "org", id.toString(), "https://librivox.org/book/")
        }.toMutableMap()
        val requests = mutableListOf<HttpUrl>()
        var updates = 0
        var requestedLimit: Int? = null
        var cancelOnBookId: String? = null
        private val repository = object : LibraryRepository {
            override suspend fun getBook(bookId: String): LibraryBook? {
                if (cancelOnBookId == bookId) throw CancellationException("Cancelled by caller")
                return books[bookId]
            }
            override fun observeBooks(): Flow<List<LibraryBook>> = error("Unexpected library observation")
            override fun observeCollection(): Flow<List<LibraryCollectionEntry>> = error("Unexpected collection observation")
            override suspend fun getTracks(bookId: String): List<LibraryTrack> = error("Artwork must not read tracks")
            override suspend fun getTrack(trackId: String): LibraryTrack? = error("Artwork must not read tracks")
            override suspend fun getProgress(bookId: String): ListeningProgress? = error("Artwork must not read progress")
            override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long): Unit = error("Artwork must not save progress")
            override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("Artwork must not re-save books")
        }
        private val dao = Proxy.newProxyInstance(LibraryDao::class.java.classLoader, arrayOf(LibraryDao::class.java)) { _, method, args ->
            when (method.name) {
                "getOnlineLibrary" -> OnlineLibraryEntity("org", "https://librivox.org/", "LibriVox", "LIBRIVOX")
                "getBooksMissingArtwork" -> {
                    val limit = args[0] as Int
                    requestedLimit = limit
                    val after = args[1] as String?
                    books.values.filter { it.coverUrl == null }
                        .sortedWith(compareBy<LibraryBook> { if (after == null || it.id > after) 0 else 1 }.thenBy { it.id })
                        .take(limit).map {
                        BookEntity(it.id, it.title, it.author, it.providerId, it.providerBookId, it.sourceUrl)
                    }
                }
                "updateBookArtwork" -> {
                    val id = args[0] as String
                    val book = books.getValue(id)
                    require(book.providerId == args[1] && book.providerBookId == args[2])
                    books[id] = book.copy(coverUrl = args[3] as String, coverReferer = args[4] as String?)
                    updates++
                    1
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url
            val id = requireNotNull(request.url.queryParameter("id"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response(id).toResponseBody()).build()
        }.build()
        val online = OnlineLibraries(dao, repository, ProviderHttpClient(client))
    }

    companion object {
        private fun metadata(id: String) = """{"books":[{"id":"$id","title":"Remote title","coverart_jpg":"https://archive.org/$id.jpg"}]}"""
    }
}
