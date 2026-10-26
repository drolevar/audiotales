package io.github.drolevar.audiotales.ui.catalogue

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.online.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueViewModelTest {
    @Test fun shownTitleAndAuthorTieSortingUseDisplayTitleWhileProviderOrderAndRawMetadataRemainUnchanged() {
        val prefixed = book("source", "prefixed").copy(title = "Same Author - Alpha", author = "Same Author")
        val plain = book("source", "plain").copy(title = "Beta", author = "Same Author")
        val original = listOf(plain, prefixed)
        val state = CatalogueSourceState(books = original)

        for (sort in listOf(CatalogueSort.TITLE, CatalogueSort.AUTHOR)) {
            val shown = state.shown(sort)
            assertEquals(listOf("prefixed", "plain"), shown.map { it.remoteId })
            assertSame(prefixed, shown.first())
            assertEquals("Same Author - Alpha", shown.first().title)
            assertEquals("Same Author", shown.first().author)
        }
        assertEquals(original, state.shown(CatalogueSort.LIBRARY))
        assertEquals(original, state.books)
    }

    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun model(fake: FakeCatalogue, handle: SavedStateHandle = SavedStateHandle()): CatalogueViewModel =
        CatalogueViewModel(fake, handle).also { vm -> stores += ViewModelStore().also { it.put("catalogue", vm) } }

    @Test fun noNetworkRequestsUntilBrowseIsDisplayed() = runTest(dispatcher) {
        val fake = FakeCatalogue()
        val vm = model(fake)
        runCurrent()
        assertTrue(fake.requests.isEmpty())
        vm.activate(); runCurrent()
        assertEquals(2, fake.requests.size)
        vm.activate(); runCurrent()
        assertEquals(2, fake.requests.size)
        fake.requests.forEach { it.result.complete(CataloguePage(emptyList(), null)) }; runCurrent()
    }

    @Test fun hiddenSelectionChangesMakeNoRequestsUntilBrowseReturns() = runTest(dispatcher) {
        val fake = FakeCatalogue(listOf(source("a"), source("b").copy(isSelected = false)))
        val vm = model(fake)
        vm.activate(); runCurrent()
        fake.requests.single().result.complete(CataloguePage(listOf(book("a", "1")), null)); runCurrent()
        vm.deactivate()
        vm.setLibrarySelected("a", false); runCurrent()
        vm.setLibrarySelected("b", true); runCurrent()
        assertEquals(1, fake.requests.size)
        vm.activate(); runCurrent()
        assertEquals(2, fake.requests.size)
        assertEquals("b", fake.requests.last().id)
        fake.requests.last().result.complete(CataloguePage(emptyList(), null)); runCurrent()
    }

    @Test fun returningResumesCancelledPageButRetainsCompletedSources() = runTest(dispatcher) {
        val fake = FakeCatalogue()
        val vm = model(fake)
        vm.activate(); runCurrent()
        fake.requests.first { it.id == "b" }.result.complete(CataloguePage(listOf(book("b", "complete")), null)); runCurrent()
        vm.deactivate()
        vm.activate(); runCurrent()
        assertEquals(3, fake.requests.size)
        assertEquals("a", fake.requests.last().id)
        fake.requests.last().result.complete(CataloguePage(listOf(book("a", "resumed")), 20)); runCurrent()
        fake.requests.first().result.complete(CataloguePage(listOf(book("a", "stale")), null)); runCurrent()
        assertEquals("resumed", vm.state.value.sources.getValue("a").books.single().remoteId)
        assertEquals("complete", vm.state.value.sources.getValue("b").books.single().remoteId)
        vm.loadMore("a"); runCurrent()
        vm.deactivate(); vm.activate(); runCurrent()
        assertEquals(20, fake.requests.last().offset)
        fake.requests.last().result.complete(CataloguePage(listOf(book("a", "page2")), null)); runCurrent()
        fake.requests[fake.requests.lastIndex - 1].result.complete(CataloguePage(listOf(book("a", "stale2")), null)); runCurrent()
        assertEquals(listOf("resumed", "page2"), vm.state.value.sources.getValue("a").books.map { it.remoteId })
    }

    @Test fun cancelledOldQueryCannotReplaceNewQueryEvenWhenProviderIgnoresCancellation() = runTest(dispatcher) {
        val fake = FakeCatalogue(listOf(source("a")))
        val vm = model(fake)
        vm.activate(); runCurrent()
        vm.setQuery("old"); vm.browse(); runCurrent()
        vm.setQuery("new"); vm.browse(); runCurrent()
        fake.requests.last().result.complete(CataloguePage(listOf(book("a", "new")), null)); runCurrent()
        fake.requests[1].result.complete(CataloguePage(listOf(book("a", "old")), null))
        fake.requests[0].result.complete(CataloguePage(listOf(book("a", "initial")), null)); runCurrent()
        assertEquals(listOf("new"), vm.state.value.sources.getValue("a").books.map { it.remoteId })
        assertEquals("new", vm.state.value.submittedQuery)
    }

    @Test fun deselectionCancelsSourceAndIgnoresItsLateResponseWithoutHidingOtherSource() = runTest(dispatcher) {
        val fake = FakeCatalogue()
        val vm = model(fake)
        vm.activate(); runCurrent()
        vm.setLibrarySelected("a", false); runCurrent()
        fake.requests.first { it.id == "a" }.result.complete(CataloguePage(listOf(book("a", "stale")), null))
        fake.requests.first { it.id == "b" }.result.complete(CataloguePage(listOf(book("b", "current")), null)); runCurrent()
        assertFalse("a" in vm.state.value.sources)
        assertEquals("current", vm.state.value.sources.getValue("b").books.single().remoteId)
        vm.setLibrarySelected("b", false); runCurrent()
        assertTrue(vm.state.value.browseLibraries.isEmpty())
        assertTrue(vm.state.value.sources.isEmpty())
    }

    @Test fun failedSourceAndRetryAreIndependent() = runTest(dispatcher) {
        val fake = FakeCatalogue()
        val vm = model(fake)
        vm.activate(); runCurrent()
        fake.requests.first { it.id == "a" }.result.completeExceptionally(OnlineBookException(OnlineBookError.NETWORK))
        fake.requests.first { it.id == "b" }.result.complete(CataloguePage(listOf(book("b", "1")), null)); runCurrent()
        assertEquals(OnlineBookError.NETWORK, vm.state.value.sources.getValue("a").error)
        assertEquals("1", vm.state.value.sources.getValue("b").books.single().remoteId)
        vm.retrySource("a"); runCurrent()
        assertEquals(3, fake.requests.size)
        assertEquals("a", fake.requests.last().id)
        fake.requests.last().result.complete(CataloguePage(listOf(book("a", "1")), null)); runCurrent()
        assertNull(vm.state.value.sources.getValue("a").error)
        assertEquals(1, vm.state.value.sources.getValue("b").books.size)
    }

    @Test fun paginationDeduplicatesIdentityAndRetainsRawCursorWhenLanguageHidesPage() = runTest(dispatcher) {
        val fake = FakeCatalogue(listOf(source("a")))
        val vm = model(fake)
        vm.activate(); runCurrent()
        fake.requests.single().result.complete(CataloguePage(listOf(book("a", "1", "English")), 20)); runCurrent()
        vm.selectLanguage("a", "French")
        assertTrue(vm.state.value.sources.getValue("a").shown(CatalogueSort.LIBRARY).isEmpty())
        vm.loadMore("a"); vm.loadMore("a"); runCurrent()
        assertEquals(2, fake.requests.size)
        assertEquals(20, fake.requests.last().offset)
        fake.requests.last().result.complete(CataloguePage(listOf(book("a", "1", "English"), book("a", "2", "French")), 40)); runCurrent()
        val result = vm.state.value.sources.getValue("a")
        assertEquals(2, result.books.size)
        assertEquals(40, result.nextOffset)
        assertEquals(listOf("2"), result.shown(CatalogueSort.TITLE).map { it.remoteId })
        vm.loadMore("a"); runCurrent()
        fake.requests.last().result.completeExceptionally(OnlineBookException(OnlineBookError.NETWORK)); runCurrent()
        vm.retrySource("a"); runCurrent()
        assertEquals(40, fake.requests.last().offset)
        fake.requests.last().result.complete(CataloguePage(emptyList(), null)); runCurrent()
    }

    @Test fun restoredDraftDoesNotSubmitUntilSearchAndSingleSourceDoesNotChangeSelection() = runTest(dispatcher) {
        val fake = FakeCatalogue(listOf(source("a"), source("b").copy(isSelected = false)))
        val handle = SavedStateHandle(mapOf("catalogue.draft" to "unfinished draft", "catalogue.submitted" to "submitted"))
        val vm = model(fake, handle)
        vm.activate("b"); runCurrent()
        assertEquals("b", fake.requests.single().id)
        assertEquals("submitted", fake.requests.single().query)
        assertEquals("unfinished draft", vm.state.value.query)
        assertTrue(fake.libraries.value.first().isSelected)
        assertFalse(fake.libraries.value.last().isSelected)
        fake.requests.single().result.complete(CataloguePage(emptyList(), null)); runCurrent()
        vm.setQuery("x".repeat(350))
        assertEquals(300, vm.state.value.query.length)
        assertEquals("submitted", handle.get<String>("catalogue.submitted"))
    }

    private class Request(val id: String, val query: String, val offset: Int) {
        val result = CompletableDeferred<CataloguePage>()
    }
    private class FakeCatalogue(initial: List<OnlineLibrary> = listOf(source("a"), source("b"))) : OnlineCatalogue {
        val libraries = MutableStateFlow(initial)
        val requests = mutableListOf<Request>()
        override fun observeLibraries() = libraries
        override suspend fun browse(libraryId: String, query: String, offset: Int): CataloguePage {
            val request = Request(libraryId, query, offset).also(requests::add)
            // Exercise providers that return a response after cancellation of the caller.
            return withContext(NonCancellable) { request.result.await() }
        }
        override suspend fun setLibrarySelected(id: String, selected: Boolean) { libraries.value = libraries.value.map { if (it.id == id) it.copy(isSelected = selected) else it } }
        override suspend fun addLibrary(input: String): OnlineLibrary = error("not used")
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition = error("not used")
        override suspend fun renameLibrary(id: String, name: String) = Unit
        override suspend fun removeLibrary(id: String) = Unit
    }
    companion object {
        private fun source(id: String) = OnlineLibrary(id, "https://$id.example/", id, LibraryProtocol.LIBRIVOX)
        private fun book(libraryId: String, id: String, language: String? = null) = CatalogueBook(libraryId, id, id, "author", "", null, null, language = language)
    }
}
