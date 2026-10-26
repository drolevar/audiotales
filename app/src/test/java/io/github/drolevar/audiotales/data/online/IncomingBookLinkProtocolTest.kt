package io.github.drolevar.audiotales.data.online

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
import org.junit.Assert.*
import org.junit.Test

class IncomingBookLinkProtocolTest {
    @Test fun librivoxPageResolutionUsesOneSameHostRssIdAndMetadataWithoutSavingOrLoadingAudio() = runTest {
        val f = Fixture()
        assertEquals("42", f.online.resolveBookPageId("historical", "https://librivox.org/a-book/"))
        assertEquals(listOf("/a-book/", "/api/feed/audiobooks/"), f.requests.map { it.encodedPath })
        assertEquals("42", f.requests.last().queryParameter("id"))
        assertNull(f.requests.last().queryParameter("extended"))
        assertEquals(0, f.registrations)
    }

    @Test fun librivoxPageRejectsExternalAmbiguousAndTransportRssBeforeMetadataLookup() = runTest {
        for (html in listOf(
            "<a href='https://evil.example/rss/42'>RSS</a>",
            "<a href='/rss/42'>RSS</a><a href='/rss/43'>RSS</a>",
            "<a href='https://user@librivox.org/rss/42'>RSS</a>",
            "<a href='/rss/42?url=https://evil.example'>RSS</a>",
            "<a href='/rss/42#file'>RSS</a>",
        )) {
            val f = Fixture(); f.html = html
            try { f.online.resolveBookPageId("historical", "https://librivox.org/a-book/"); fail(html) }
            catch (_: OnlineBookException) { }
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun librivoxMetadataMustVerifyCanonicalPageAndRssRecordingIdentity() = runTest {
        for (body in listOf(
            """{"books":[{"id":"42","title":"Book","url_librivox":"https://librivox.org/other/"}]}""",
            """{"books":[{"id":"99","title":"Book","url_librivox":"https://librivox.org/a-book/"}]}""",
        )) {
            val f = Fixture(); f.metadata = body
            try { f.online.resolveBookPageId("historical", "https://librivox.org/a-book/"); fail(body) }
            catch (error: OnlineBookException) { assertEquals(OnlineBookError.UNEXPECTED_FORMAT, error.reason) }
            assertEquals(0, f.registrations)
        }
    }

    @Test fun removedAndHiddenRegistrationProbesCompatibilityBeforeRestoringExistingIdentity() = runTest {
        for (row in listOf(
            OnlineLibraryEntity("historical", "https://librivox.org/", "Custom name", "LIBRIVOX", isRegistered = false),
            OnlineLibraryEntity("historical", "https://librivox.org/", "Custom name", "LIBRIVOX", isSelected = false),
        )) {
            val f = Fixture(row)
            assertEquals("historical", f.online.addLibrary(row.baseUrl).id)
            assertEquals(listOf("probe", "register"), f.events)
            assertEquals("Custom name", f.row.displayName)
            assertTrue(f.row.isSelected)
        }
    }

    @Test fun incompatibleRegistrationDoesNotChangeRetainedSource() = runTest {
        val f = Fixture(OnlineLibraryEntity("historical", "https://librivox.org/", "Custom name", "LIBRIVOX", isRegistered = false))
        f.metadata = "<html>Not a catalogue</html>"
        val before = f.row
        try { f.online.addLibrary(f.row.baseUrl); fail("Should reject HTML") }
        catch (_: OnlineBookException) { }
        assertEquals(before, f.row)
        assertEquals(0, f.registrations)
    }

    private class Fixture(var row: OnlineLibraryEntity = OnlineLibraryEntity("historical", "https://librivox.org/", "Library", "LIBRIVOX")) {
        var html = "<a href='/rss/42'>RSS</a>"
        var metadata = """{"books":[{"id":"42","title":"Book","url_librivox":"https://librivox.org/a-book/"}]}"""
        var registrations = 0
        val requests = mutableListOf<HttpUrl>()
        val events = mutableListOf<String>()
        val dao = Proxy.newProxyInstance(LibraryDao::class.java.classLoader, arrayOf(LibraryDao::class.java)) { _, method, _ ->
            when (method.name) {
                "getOnlineLibrary", "getOnlineLibraryByBaseUrl" -> row
                "registerOnlineLibrary" -> { events += "register"; registrations++; row = row.copy(isRegistered = true, isSelected = true); row }
                else -> error("Unexpected database mutation: ${method.name}")
            }
        } as LibraryDao
        private val repository = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
            error("Link resolution must not access or save book data: ${method.name}")
        } as LibraryRepository
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            requests += url
            if (url.encodedPath == "/api/feed/audiobooks/") events += "probe"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (url.encodedPath == "/a-book/") html else metadata).toResponseBody()).build()
        }.build()
        val online = OnlineLibraries(dao, repository, ProviderHttpClient(client), "client=fixture")
    }
}
