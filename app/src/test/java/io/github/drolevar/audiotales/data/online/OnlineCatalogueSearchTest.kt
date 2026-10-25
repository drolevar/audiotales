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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineCatalogueSearchTest {
    @Test fun titleAndAuthorResultsMergeByIdentityAndContinueAtRawPageOffset() = runTest {
        val fixture = Fixture { url ->
            val firstPage = url.queryParameter("offset") == "0"
            when {
                firstPage && url.queryParameter("title") != null -> (1..20).toList()
                firstPage -> (11..30).toList()
                url.queryParameter("title") != null -> listOf(31)
                else -> listOf(32)
            }
        }
        val first = fixture.online.browse("org", "  Baker  ")
        assertEquals((1..30).map(Int::toString), first.books.map { it.remoteId })
        assertEquals(20, first.nextOffset)
        assertEquals(listOf("Baker", null), fixture.requests.map { it.queryParameter("title") })
        assertEquals(listOf(null, "Baker"), fixture.requests.map { it.queryParameter("author") })
        assertEquals(listOf("0", "0"), fixture.requests.map { it.queryParameter("offset") })
        assertEquals(listOf("20", "20"), fixture.requests.map { it.queryParameter("limit") })

        val second = fixture.online.browse("org", "Baker", requireNotNull(first.nextOffset))
        assertEquals(listOf("31", "32"), second.books.map { it.remoteId })
        assertNull(second.nextOffset)
        assertEquals(listOf("20", "20"), fixture.requests.drop(2).map { it.queryParameter("offset") })
    }

    @Test fun fullAuthorPageContinuesAfterTitleSearchHasNoMatches() = runTest {
        val fixture = Fixture { url -> if (url.queryParameter("title") != null) emptyList() else (1..20).toList() }
        val page = fixture.online.browse("org", "Baker", 40)
        assertEquals((1..20).map(Int::toString), page.books.map { it.remoteId })
        assertEquals(60, page.nextOffset)
        assertEquals(listOf("40", "40"), fixture.requests.map { it.queryParameter("offset") })
    }

    @Test fun shortTitleAndMissingAuthorMatchesEndSearch() = runTest {
        val fixture = Fixture { url -> if (url.queryParameter("title") != null) listOf(7) else emptyList() }
        val page = fixture.online.browse("org", "Baker")
        assertEquals(listOf("7"), page.books.map { it.remoteId })
        assertNull(page.nextOffset)
    }

    @Test fun emptySearchUsesOneDefaultCatalogueRequest() = runTest {
        val fixture = Fixture { (1..20).toList() }
        val page = fixture.online.browse("org", "   ", 20)
        assertEquals(40, page.nextOffset)
        assertEquals(1, fixture.requests.size)
        val request = fixture.requests.single()
        assertNull(request.queryParameter("title"))
        assertNull(request.queryParameter("author"))
        assertEquals("desc", request.queryParameter("sort_order"))
        assertEquals("20", request.queryParameter("offset"))
    }

    private class Fixture(rows: (HttpUrl) -> List<Int>) {
        val requests = mutableListOf<HttpUrl>()
        private val dao = Proxy.newProxyInstance(LibraryDao::class.java.classLoader, arrayOf(LibraryDao::class.java)) { _, method, _ ->
            when (method.name) {
                "getOnlineLibrary" -> OnlineLibraryEntity("org", "https://librivox.org/", "LibriVox", "LIBRIVOX")
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
        private val repository = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, _ ->
            error("Browsing must not access saved media: ${method.name}")
        } as LibraryRepository
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url
            val ids = rows(request.url)
            val body = if (ids.isEmpty()) """{"error":"Audiobooks could not be found"}"""
                else ids.joinToString(prefix = "{\"books\":[", postfix = "]}") { """{"id":"$it","title":"Book $it"}""" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (ids.isEmpty()) 404 else 200).message("Response").body(body.toResponseBody()).build()
        }.build()
        val online = OnlineLibraries(dao, repository, ProviderHttpClient(client))
    }
}
