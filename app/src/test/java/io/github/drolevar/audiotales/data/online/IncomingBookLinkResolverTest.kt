package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.local.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IncomingBookLinkResolverTest {
    private val request = IncomingBookLink("https://provider.example/", "42")
    private val row = OnlineLibraryEntity("historical-source", request.baseUrl, "My source", "PARTNER")
    private val book = BookEntity("historical-book", "Saved", providerId = row.id, providerBookId = "42")

    @Test fun unknownAndRemovedSourcesNeedConsentWithoutAnyProviderCallsOrWrites() = runTest {
        for (row in listOf(null, row.copy(isRegistered = false), row.copy(isRegistered = false, isSelected = false))) {
            val f = Fixture(row)
            assertEquals(IncomingBookResolution.NeedsRegistration(request.baseUrl), f.resolver.resolve(request))
            assertEquals(0, f.registrations)
            assertEquals(0, f.pageCalls)
        }
    }

    @Test fun canonicalSavedBookWinsEvenForRetainedRemovedOrHiddenSourceWithoutNetworkOrWrites() = runTest {
        for (row in listOf(row, row.copy(isRegistered = false), row.copy(isSelected = false))) {
            val f = Fixture(row, book)
            assertEquals(IncomingBookResolution.Ready(IncomingBookTarget(book.id, row.id, "42")), f.resolver.resolve(request))
            assertEquals(0, f.registrations)
            assertEquals(0, f.pageCalls)
        }
    }

    @Test fun enabledSourceNavigatesToIdentityWithoutSavingOrFetchingPlaylist() = runTest {
        val f = Fixture(row)
        assertEquals(IncomingBookResolution.Ready(IncomingBookTarget(null, row.id, "42")), f.resolver.resolve(request))
        assertEquals(0, f.registrations)
        assertEquals(0, f.pageCalls)
    }

    @Test fun registeredDeselectedSourceOpensUnsavedBookWithoutRegistrationOrChangingBrowseSelection() = runTest {
        val original = row.copy(isSelected = false)
        val f = Fixture(original)
        assertEquals(IncomingBookResolution.Ready(IncomingBookTarget(null, row.id, "42")), f.resolver.resolve(request))
        assertEquals(0, f.registrations)
        assertEquals(0, f.pageCalls)
        assertEquals(original, f.source)
        assertFalse(requireNotNull(f.source).isSelected)
    }

    @Test fun registrationIsExplicitAndPreservesExistingSourceIdentity() = runTest {
        val f = Fixture(row.copy(isRegistered = false, isSelected = false))
        f.resolver.register(request)
        assertEquals(1, f.registrations)
        assertEquals(IncomingBookResolution.Ready(IncomingBookTarget(null, row.id, "42")), f.resolver.resolve(request))
    }

    @Test fun librivoxPageSavedLocallyAvoidsPageLookupAndUnknownPageAwaitsConsent() = runTest {
        val page = IncomingBookLink(request.baseUrl, pageUrl = "https://provider.example/saved-page/")
        val f = Fixture(row.copy(isRegistered = false), book.copy(sourceUrl = page.pageUrl))
        assertEquals(IncomingBookResolution.Ready(IncomingBookTarget(book.id, row.id, "42")), f.resolver.resolve(page))
        assertEquals(0, f.pageCalls)
        assertTrue(f.resolver.resolve(page.copy(pageUrl = "https://provider.example/other/")) is IncomingBookResolution.NeedsRegistration)
        assertEquals(0, f.pageCalls)
    }

    @Test fun pageLookupReturnsProviderIdentityAndCanonicalSavedRowWithoutSaving() = runTest {
        val f = Fixture(row, book)
        assertEquals(IncomingBookResolution.Ready(IncomingBookTarget(book.id, row.id, "42")),
            f.resolver.resolve(IncomingBookLink(request.baseUrl, pageUrl = "https://provider.example/page/")))
        assertEquals(1, f.pageCalls)
    }

    private inner class Fixture(var source: OnlineLibraryEntity?, val savedBook: BookEntity? = null) {
        var registrations = 0
        var pageCalls = 0
        val dao = Proxy.newProxyInstance(LibraryDao::class.java.classLoader, arrayOf(LibraryDao::class.java)) { _, method, args ->
            when (method.name) {
                "getOnlineLibraryByBaseUrl" -> source
                "getProviderBook" -> savedBook?.takeIf { it.providerId == args!![0] && it.providerBookId == args[1] }
                "observeBooks" -> flowOf(listOfNotNull(savedBook))
                else -> error("Unexpected database access or mutation: ${method.name}")
            }
        } as LibraryDao
        val resolver = IncomingBookLinkResolver(dao, { base ->
            assertEquals(request.baseUrl, base)
            registrations++
            source = (source ?: row).copy(isRegistered = true, isSelected = true)
        }, { library, _ -> assertEquals(row.id, library); pageCalls++; "42" })
    }
}
