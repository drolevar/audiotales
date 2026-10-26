package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.local.LibraryDao
import io.github.drolevar.audiotales.data.local.OnlineLibraryEntity
import java.lang.reflect.Proxy
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

class OnlineBookDetailsSourceTest {
    @Test fun metadataInspectionMakesOneNarrowRequestAndNeverReadsOrWritesSavedMedia() = runTest {
        val fixture = Fixture(fullBook)
        val result = fixture.source.inspectBookMetadata("org", "42")
        assertEquals("org:42", result.id)
        assertEquals("Remote title", result.title)
        assertEquals("A & B", result.description)
        assertEquals("org", result.providerId)
        assertEquals("42", result.providerBookId)
        assertNull(fixture.requests.single().queryParameter("extended"))
        assertEquals(0, fixture.bookReads)
        assertEquals(0, fixture.updates)
    }

    @Test fun audioInspectionUsesRegisteredProviderIdentityAndCanonicalTracksForHistoricalSavedId() = runTest {
        val fixture = Fixture(fullBook)
        val result = fixture.source.inspectBookAudio(fixture.book.copy(sourceUrl = "https://attacker.example/arbitrary"))
        assertEquals("org:42", result.id)
        assertEquals("org:42", result.tracks.single().bookId)
        assertEquals("librivox.org", fixture.requests.single().host)
        assertEquals("42", fixture.requests.single().queryParameter("id"))
        assertEquals("1", fixture.requests.single().queryParameter("extended"))
        assertEquals(0, fixture.bookReads)
        assertEquals(0, fixture.updates)
    }

    @Test fun invalidAudioProviderIdentityCannotIssueNetworkRequestsOrAccessSavedMedia() = runTest {
        val fixture = Fixture(fullBook)
        for (book in listOf(fixture.book.copy(providerId = "unknown"), fixture.book.copy(providerId = null),
            fixture.book.copy(providerBookId = "../42"), fixture.book.copy(providerBookId = null))) {
            try {
                fixture.source.inspectBookAudio(book)
                fail("Invalid provider identity must fail")
            } catch (_: OnlineBookException) { /* Expected. */ }
        }
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.bookReads)
        assertEquals(0, fixture.updates)
    }

    @Test fun partnerAudioRetryReusesMetadataAndNeverRepeatsReviewOrPersists() = runTest {
        val fixture = Fixture("", partner = true)
        var playlistAttempts = 0
        fixture.respond = { url -> when (url.queryParameter("func")) {
            "review" -> """{"id":"42","namebook":"Partner title","short_story":"Description"}"""
            "story" -> """{"file":"https://media.example/playlist.json"}"""
            else -> if (++playlistAttempts == 1) "[]" else """[{"title":"Track","file":"https://media.example/audio.mp3"}]"""
        } }
        val metadata = fixture.source.inspectBookMetadata("org", "42")
        try {
            fixture.source.inspectBookAudio(metadata)
            fail("An empty playlist must fail independently")
        } catch (error: OnlineBookException) { assertEquals(OnlineBookError.EMPTY_PLAYLIST, error.reason) }
        val result = fixture.source.inspectBookAudio(metadata.copy(id = "historical", sourceUrl = "https://attacker.example/"))
        assertEquals("org:42", result.id)
        assertEquals("Partner title", result.title)
        assertEquals("Description", result.description)
        assertEquals(listOf("review", "story", null, "story", null), fixture.requests.map { it.queryParameter("func") })
        assertEquals(listOf("provider.example", "provider.example", "media.example", "provider.example", "media.example"), fixture.requests.map { it.host })
        assertEquals(0, fixture.bookReads)
        assertEquals(0, fixture.updates)
    }

    @Test fun inspectionResolvesFullBookWithoutReadingOrSavingLibraryMedia() = runTest {
        val fixture = Fixture(fullBook)
        val result = fixture.source.inspectBook("org", "42")
        assertEquals("org:42", result.id)
        assertEquals("A & B", result.description)
        assertEquals("English", result.language)
        assertEquals("201", result.tracks.single().providerTrackRef)
        assertEquals(listOf("1"), fixture.requests.map { it.queryParameter("extended") })
        assertEquals(0, fixture.bookReads)
        assertEquals(0, fixture.updates)
    }

    @Test fun metadataRefreshMakesOneNarrowRequestEvenForRemovedLibrary() = runTest {
        val fixture = Fixture(fullBook)
        val original = fixture.book
        val updated = fixture.source.refreshBookMetadata(original.id)
        assertEquals(original.copy(description = "A & B", language = "English", coverUrl = "https://archive.org/cover.jpg", coverReferer = null), updated)
        assertEquals(1, fixture.requests.size)
        assertNull(fixture.requests.single().queryParameter("extended"))
        assertEquals(1, fixture.updates)
        assertEquals("org", fixture.source.getLibrary("org").id)
        assertEquals(false, fixture.source.getLibrary("org").isSelected)
    }

    @Test fun missingMetadataCannotEraseSavedDetailsOrCover() = runTest {
        val fixture = Fixture("""{"books":[{"id":"42","title":"Remote","coverart_jpg":"file:///bad.jpg"}]}""")
        val original = fixture.book
        assertEquals(original, fixture.source.refreshBookMetadata(original.id))
        fixture.response = """{"books":[{"id":"42","title":"Remote","description":""}]}"""
        assertEquals(original.copy(description = ""), fixture.source.refreshBookMetadata(original.id))
    }

    @Test fun wrongMetadataIdentityAndInvalidInspectionIdNeverUpdateSavedMedia() = runTest {
        val fixture = Fixture(fullBook.replace("\"id\":\"42\"", "\"id\":\"99\""))
        try {
            fixture.source.refreshBookMetadata(fixture.book.id)
            fail("A mismatched remote recording must fail")
        } catch (error: OnlineBookException) {
            assertEquals(OnlineBookError.UNEXPECTED_FORMAT, error.reason)
        }
        for (id in listOf("", "0", "../42", "42?x=y")) {
            try {
                fixture.source.inspectBook("org", id)
                fail("Invalid remote identity must fail")
            } catch (_: OnlineBookException) { /* Expected. */ }
        }
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.updates)
    }

    private class Fixture(var response: String, partner: Boolean = false) {
        var respond: (HttpUrl) -> String = { response }
        var book = LibraryBook("historical-book", "Saved title", "Saved author", "org", "42",
            "https://librivox.org/original/", "https://archive.org/known.jpg", "https://librivox.org/",
            "Saved description", "French")
        var bookReads = 0
        var updates = 0
        val requests = mutableListOf<HttpUrl>()
        private val repository = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader,
            arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getBook" -> { bookReads++; book.takeIf { it.id == args[0] } }
                else -> error("Details must not access tracks, progress, bookmarks or save books: ${method.name}")
            }
        } as LibraryRepository
        private val dao = Proxy.newProxyInstance(LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java)) { _, method, args ->
            when (method.name) {
                "getOnlineLibrary" -> if (args[0] != "org") null else OnlineLibraryEntity("org",
                    if (partner) "https://provider.example/" else "https://librivox.org/", "Library",
                    if (partner) "PARTNER" else "LIBRIVOX", isRegistered = false, isSelected = false)
                "updateBookMetadata" -> {
                    require(args[0] == book.id && args[1] == book.providerId && args[2] == book.providerBookId)
                    val cover = args[5] as String?
                    book = book.copy(description = args[3] as String? ?: book.description,
                        language = args[4] as String? ?: book.language, coverUrl = cover ?: book.coverUrl,
                        coverReferer = if (cover == null) book.coverReferer else args[6] as String?)
                    updates++
                    1
                }
                else -> error("Unexpected details DAO call: ${method.name}")
            }
        } as LibraryDao
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(chain.request().url).toResponseBody()).build()
        }.build()
        val source: OnlineBookDetailsSource = OnlineLibraries(dao, repository, ProviderHttpClient(client), "client=test-fixture")
    }

    companion object {
        private val fullBook = """{"books":[{"id":"42","title":"Remote title","description":"<p>A &amp; B</p>","language":"English",
            "url_librivox":"https://librivox.org/book/","coverart_jpg":"https://archive.org/cover.jpg","num_sections":"1","sections":[
            {"id":"201","section_number":"0","title":"Chapter","listen_url":"https://archive.org/chapter.mp3"}]}]}"""
    }
}
