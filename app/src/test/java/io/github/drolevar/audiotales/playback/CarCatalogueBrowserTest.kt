package io.github.drolevar.audiotales.playback

import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.CataloguePage
import io.github.drolevar.audiotales.data.online.LibraryProtocol
import io.github.drolevar.audiotales.data.online.OnlineCatalogue
import io.github.drolevar.audiotales.data.online.OnlineLibrary
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarCatalogueBrowserTest {
    @Test
    fun searchesOnlySelectedSourcesWithoutSavingAndUsesIssuedOpaqueIds() = runTest {
        val catalogue = FakeCatalogue(listOf(source("a"), source("b", selected = false)))
        val car = CarCatalogueBrowser(catalogue)
        val result = car.search("  ушко  ").single() as CarCatalogueItem.Book

        assertEquals(listOf(Request("a", "ушко", 0)), catalogue.requests)
        assertEquals(book("a", "0"), result.book)
        assertEquals(result, car.item(result.mediaId))
        assertEquals(result.book, car.selectedBook(result.mediaId))
        assertTrue(result.mediaId.startsWith("audiotales:catalogue:"))
        assertFalse(result.mediaId.contains("https://"))
        assertEquals(0, catalogue.saveCalls)
        assertNull(car.children(result.mediaId))
    }

    @Test
    fun noSelectedSourcesAndBlankQueriesDoNotBrowse() = runTest {
        val catalogue = FakeCatalogue(listOf(source("a", selected = false)))
        val car = CarCatalogueBrowser(catalogue)
        assertTrue(car.search("  \t ").isEmpty())
        assertTrue(car.search("").isEmpty())
        assertTrue(car.search("anything").isEmpty())
        assertTrue(catalogue.requests.isEmpty())
    }

    @Test
    fun boundsTrimmedQueriesAndPassesCaseAndUnicodeToProviders() = runTest {
        val catalogue = FakeCatalogue()
        val car = CarCatalogueBrowser(catalogue)
        expectFailure<IllegalArgumentException> { car.search("q".repeat(301)) }
        assertTrue(catalogue.requests.isEmpty())
        car.search("  " + "q".repeat(300) + "  ")
        car.search("УШКО")
        assertEquals(listOf("q".repeat(300), "УШКО"), catalogue.requests.map { it.query })
    }

    @Test
    fun paginationUsesProviderOffsetLazilyDeduplicatesAndCachesFolderContents() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { id, _, offset -> when (offset) {
            0 -> CataloguePage(listOf(book(id, "one"), book(id, "one")), 40)
            40 -> CataloguePage(listOf(book(id, "one"), book(id, "two")), 40)
            else -> error("Unexpected provider offset")
        } }
        val car = CarCatalogueBrowser(catalogue)
        val first = car.search("query")
        assertEquals(1, catalogue.requests.size)
        val more = first.filterIsInstance<CarCatalogueItem.Folder>().single()
        assertEquals("More from Source a", more.title)
        assertEquals(listOf("one"), first.filterIsInstance<CarCatalogueItem.Book>().map { it.book.remoteId })

        val second = requireNotNull(car.children(more.mediaId))
        assertEquals(listOf("two"), second.filterIsInstance<CarCatalogueItem.Book>().map { it.book.remoteId })
        assertTrue(second.none { it is CarCatalogueItem.Folder })
        assertEquals(second, car.children(more.mediaId))
        assertEquals(listOf(0, 40), catalogue.requests.map { it.offset })
        assertEquals(0, catalogue.saveCalls)
    }

    @Test
    fun zeroResultsAreSuccessfulAndPartialFailureOffersRetryWithoutDiscardingSuccess() = runTest {
        val catalogue = FakeCatalogue(listOf(source("a"), source("b")))
        var failB = true
        catalogue.page = { id, _, _ ->
            if (id == "b" && failB) throw IOException("offline")
            CataloguePage(if (id == "a") emptyList() else listOf(book(id, "remote")), null)
        }
        val car = CarCatalogueBrowser(catalogue)
        val retry = car.search("query").single() as CarCatalogueItem.Folder
        assertEquals("Retry Source b", retry.title)
        expectFailure<IOException> { car.children(retry.mediaId) }
        assertEquals(retry, car.item(retry.mediaId))
        failB = false
        val recovered = requireNotNull(car.children(retry.mediaId))
        assertEquals("b", (recovered.single() as CarCatalogueItem.Book).book.libraryId)
        assertEquals(recovered, car.children(retry.mediaId))
        catalogue.page = { _, _, _ -> CataloguePage(emptyList(), null) }
        assertTrue(car.search("no matches").isEmpty())
    }

    @Test
    fun allFailuresAreErrorsAndDoNotRegisterFakeEmptySuccess() = runTest {
        val catalogue = FakeCatalogue(listOf(source("a"), source("b")))
        catalogue.page = { _, _, _ -> throw IOException("offline") }
        expectFailure<IOException> { CarCatalogueBrowser(catalogue).search("query") }
        assertEquals(setOf("a", "b"), catalogue.requests.map { it.id }.toSet())
    }

    @Test
    fun timeoutIsOrdinaryFailureWhileOtherSourcesCanStillReturnResults() = runTest {
        val catalogue = FakeCatalogue(listOf(source("a"), source("b")))
        catalogue.page = { id, _, _ ->
            if (id == "a") delay(60_000)
            CataloguePage(listOf(book(id, "remote")), null)
        }
        val before = testScheduler.currentTime
        val result = CarCatalogueBrowser(catalogue).search("query")
        assertTrue(testScheduler.currentTime - before <= 10_000)
        assertEquals(listOf("b"), result.filterIsInstance<CarCatalogueItem.Book>().map { it.book.libraryId })
        assertEquals("Retry Source a", result.filterIsInstance<CarCatalogueItem.Folder>().single().title)
        catalogue.libraries.value = listOf(source("a"))
        expectFailure<IOException> { CarCatalogueBrowser(catalogue).search("query") }
    }

    @Test
    fun manySlowSourcesHaveBoundedConcurrencyAndOverallDuration() = runTest {
        val catalogue = FakeCatalogue((0..9).map { source(it.toString()) })
        var active = 0
        var maximum = 0
        catalogue.page = { id, _, _ ->
            active++
            maximum = maxOf(maximum, active)
            try {
                if (id != "0") delay(60_000)
                CataloguePage(listOf(book(id, "remote")), null)
            } finally { active-- }
        }
        val before = testScheduler.currentTime
        val result = CarCatalogueBrowser(catalogue).search("query")
        assertTrue(testScheduler.currentTime - before <= 10_000)
        assertTrue(maximum <= 3)
        assertEquals(0, active)
        assertEquals(1, result.filterIsInstance<CarCatalogueItem.Book>().size)
        assertEquals(9, result.filterIsInstance<CarCatalogueItem.Folder>().size)
    }

    @Test
    fun externalCancellationPropagatesAndLeavesNoPartialRegistry() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { id, _, _ -> delay(60_000); CataloguePage(listOf(book(id, "remote")), null) }
        val car = CarCatalogueBrowser(catalogue)
        val request = async { car.search("query") }
        runCurrent()
        request.cancel()
        expectFailure<CancellationException> { request.await() }
        catalogue.page = { id, _, _ -> CataloguePage(listOf(book(id, "remote")), null) }
        assertEquals(1, car.search("query").size)
    }

    @Test
    fun forgedUnknownFolderAndChangedSourceIdsCannotAuthorizePlayback() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { id, _, _ -> CataloguePage(listOf(book(id, "remote")), 20) }
        val car = CarCatalogueBrowser(catalogue)
        val result = car.search("query")
        val item = result.filterIsInstance<CarCatalogueItem.Book>().single()
        val folder = result.filterIsInstance<CarCatalogueItem.Folder>().single()
        assertNull(car.item("audiotales:catalogue:forged"))
        assertNull(car.children("https://caller.invalid/book"))
        expectFailure<IllegalArgumentException> { car.selectedBook("audiotales:catalogue:forged") }
        expectFailure<IllegalArgumentException> { car.selectedBook(folder.mediaId) }
        catalogue.libraries.value = listOf(source("a").copy(baseUrl = "https://replacement.invalid/"))
        expectFailure<IllegalArgumentException> { car.selectedBook(item.mediaId) }
        assertNull(car.item(item.mediaId))
        assertEquals(0, catalogue.saveCalls)
    }

    @Test
    fun deselectedAndRemovedSourcesInvalidatePreviouslyIssuedBookIds() = runTest {
        for (replacement in listOf(listOf(source("a", selected = false)), emptyList())) {
            val catalogue = FakeCatalogue()
            val car = CarCatalogueBrowser(catalogue)
            val item = car.search("query").single()
            catalogue.libraries.value = replacement
            expectFailure<IllegalArgumentException> { car.selectedBook(item.mediaId) }
        }
    }

    @Test
    fun inconsistentProviderIdentityIsRejectedInsteadOfIssuingAnAuthoritativeId() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { _, _, _ -> CataloguePage(listOf(book("other-source", "remote")), null) }
        expectFailure<IOException> { CarCatalogueBrowser(catalogue).search("query") }
    }

    @Test
    fun largeListsUseCachedContinuationWithoutAnotherProviderRequest() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { id, _, _ -> CataloguePage((0..249).map { book(id, it.toString()) }, null) }
        val car = CarCatalogueBrowser(catalogue)
        var page = car.search("query")
        val ids = mutableListOf<String>()
        while (true) {
            assertTrue(page.size <= 100)
            ids.addAll(page.filterIsInstance<CarCatalogueItem.Book>().map { it.book.remoteId })
            val more = page.filterIsInstance<CarCatalogueItem.Folder>().singleOrNull() ?: break
            assertEquals("More results", more.title)
            page = requireNotNull(car.children(more.mediaId))
        }
        assertEquals((0..249).map(Int::toString), ids)
        assertEquals(1, catalogue.requests.size)
    }

    @Test
    fun sessionEvictionAndExplicitRefreshExpireOldIds() = runTest {
        val catalogue = FakeCatalogue()
        val car = CarCatalogueBrowser(catalogue)
        val first = car.search("zero").single()
        val second = car.search("one").single()
        car.search("two")
        car.search("three")
        assertEquals(first, car.item(first.mediaId)) // LRU access keeps this session.
        car.search("four")
        assertNull(car.item(second.mediaId))
        expectFailure<IllegalArgumentException> { car.selectedBook(second.mediaId) }
        val refreshed = car.search("zero").single()
        assertFalse(first.mediaId == refreshed.mediaId)
        assertNull(car.item(first.mediaId))
    }

    @Test
    fun perSessionCapacityFailureExpiresWholeSessionRatherThanReturningUnresolvableIds() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { id, _, offset ->
            val start = if (offset == 0) 0 else 400
            CataloguePage((start until start + 400).map { book(id, it.toString()) }, if (offset == 0) 400 else null)
        }
        val car = CarCatalogueBrowser(catalogue)
        var page = car.search("query")
        val original = page.filterIsInstance<CarCatalogueItem.Book>().first()
        // Follow cached chunks until reaching the provider continuation.
        while (true) {
            val more = page.filterIsInstance<CarCatalogueItem.Folder>().single()
            if (more.title.startsWith("More from")) {
                expectFailure<IOException> { car.children(more.mediaId) }
                break
            }
            page = requireNotNull(car.children(more.mediaId))
        }
        assertNull(car.item(original.mediaId))
    }

    @Test
    fun concurrentReentryDoesNotDuplicateFolderFetchOrInvalidateReturnedIds() = runTest {
        val catalogue = FakeCatalogue()
        catalogue.page = { id, _, offset ->
            if (offset > 0) delay(100)
            CataloguePage(listOf(book(id, offset.toString())), if (offset == 0) 20 else null)
        }
        val car = CarCatalogueBrowser(catalogue)
        val folder = car.search("query").filterIsInstance<CarCatalogueItem.Folder>().single()
        val first = async { car.children(folder.mediaId) }
        val second = async { car.children(folder.mediaId) }
        assertEquals(first.await(), second.await())
        assertEquals(listOf(0, 20), catalogue.requests.map { it.offset })
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: Throwable) { failure = error }
        assertTrue("Expected ${T::class.simpleName}, got $failure", failure is T)
    }

    private data class Request(val id: String, val query: String, val offset: Int)
    private class FakeCatalogue(initial: List<OnlineLibrary> = listOf(source("a"))) : OnlineCatalogue {
        val libraries = MutableStateFlow(initial)
        val requests = mutableListOf<Request>()
        var saveCalls = 0
        var page: suspend (String, String, Int) -> CataloguePage = { id, _, offset ->
            CataloguePage(listOf(book(id, offset.toString())), null)
        }
        override fun observeLibraries() = libraries
        override suspend fun browse(libraryId: String, query: String, offset: Int): CataloguePage {
            requests.add(Request(libraryId, query, offset))
            return page(libraryId, query, offset)
        }
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition {
            saveCalls++
            error("Search/browsing must never save a book")
        }
        override suspend fun addLibrary(input: String): OnlineLibrary = error("Not used")
        override suspend fun setLibrarySelected(id: String, selected: Boolean) = error("Not used")
        override suspend fun renameLibrary(id: String, name: String) = error("Not used")
        override suspend fun removeLibrary(id: String) = error("Not used")
    }
    private companion object {
        fun source(id: String, selected: Boolean = true) =
            OnlineLibrary(id, "https://$id.example/", "Source $id", LibraryProtocol.PARTNER, selected)
        fun book(id: String, remote: String) = CatalogueBook(id, remote, "Title $remote", "Author",
            coverUrl = null, sourceUrl = "https://$id.example/book/$remote")
    }
}
