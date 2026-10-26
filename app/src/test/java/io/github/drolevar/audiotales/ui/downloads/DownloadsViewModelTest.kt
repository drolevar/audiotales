package io.github.drolevar.audiotales.ui.downloads

import androidx.lifecycle.ViewModelStore
import io.github.drolevar.audiotales.data.library.*
import io.github.drolevar.audiotales.data.online.*
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.TrackDownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val library = FakeLibrary()
    private val downloads = FakeDownloads()
    private val online = FakeOnline()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() {
        stores.forEach { it.clear() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model() = DownloadsViewModel(library, downloads, online).also { vm ->
        stores += ViewModelStore().also { it.put("downloads", vm) }
    }

    @Test fun failedInitializationRetryStaysLoadingUntilActualDownloadsArrive() = runTest(dispatcher) {
        val first = CompletableDeferred<Unit>()
        downloads.initializeBlock = { first.await() }
        library.entries.value = listOf(entry)
        downloads.state.value = mapOf(track.id to TrackDownloadState(DownloadStatus.PAUSED))
        val vm = model()
        vm.start(); runCurrent()
        assertTrue(vm.state.value.loading)
        first.completeExceptionally(IllegalStateException("index unavailable")); runCurrent()
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.loading)

        val retry = CompletableDeferred<Unit>()
        downloads.initializeBlock = { retry.await() }
        vm.retry()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.failed)
        runCurrent()
        assertEquals(2, downloads.initializations)
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.books.isEmpty())

        retry.complete(Unit); runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.failed)
        assertEquals(listOf(book), vm.state.value.allBooks)
        assertEquals(book.id, vm.state.value.books.single().entry.book.id)
        assertEquals(DownloadBookStatus.PAUSED, vm.state.value.books.single().status)
        vm.stop()
    }

    @Test fun failedInitializationRetryCanFinishWithAnActualEmptyDownloadList() = runTest(dispatcher) {
        downloads.initializeBlock = { error("index unavailable") }
        library.entries.value = listOf(entry)
        val vm = model()
        vm.start(); runCurrent()
        assertTrue(vm.state.value.failed)

        val retry = CompletableDeferred<Unit>()
        downloads.initializeBlock = { retry.await() }
        vm.retry(); runCurrent()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.failed)

        retry.complete(Unit); runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.failed)
        assertTrue(vm.state.value.books.isEmpty())
        assertEquals(listOf(book), vm.state.value.allBooks)
        vm.stop()
    }

    @Test fun lifecycleReturnKeepsLoadedContentAndRestartsPollingOnlyWhenVisible() = runTest(dispatcher) {
        library.entries.value = listOf(entry)
        downloads.state.value = mapOf(track.id to TrackDownloadState(DownloadStatus.DOWNLOADING))
        val vm = model()
        vm.start(); runCurrent()
        val loaded = vm.state.value
        val initialRefreshes = downloads.refreshes
        vm.start(); runCurrent()
        assertEquals(1, downloads.initializations)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(downloads.refreshes > initialRefreshes)

        vm.stop(); runCurrent()
        val stoppedRefreshes = downloads.refreshes
        advanceTimeBy(4_000); runCurrent()
        assertEquals(stoppedRefreshes, downloads.refreshes)

        val restart = CompletableDeferred<Unit>()
        downloads.initializeBlock = { restart.await() }
        vm.start(); runCurrent()
        assertEquals(loaded, vm.state.value)
        assertFalse(vm.state.value.loading)
        restart.complete(Unit); runCurrent()
        assertTrue(downloads.refreshes > stoppedRefreshes)
        assertEquals(loaded.books, vm.state.value.books)
        vm.stop()
    }

    @Test fun sourceRefreshFailureStaysPerBookAndResumeClearsItWithoutDuplicateRequests() = runTest(dispatcher) {
        library.entries.value = listOf(entry)
        online.refreshSources = { error("source unavailable") }
        val vm = model()
        vm.resume(book.id); runCurrent()
        assertEquals(mapOf(book.id to DownloadActionError.SOURCES), vm.state.value.errors)
        assertTrue(vm.state.value.busyBooks.isEmpty())
        assertTrue(downloads.requests.isEmpty())

        val refresh = CompletableDeferred<Unit>()
        online.refreshSources = { refresh.await() }
        vm.resume(book.id)
        assertFalse(book.id in vm.state.value.errors)
        assertEquals(setOf(book.id), vm.state.value.busyBooks)
        vm.resume(book.id); runCurrent()
        assertEquals(listOf(book.id, book.id), online.refreshedBooks)
        assertTrue(downloads.requests.isEmpty())
        refresh.complete(Unit); runCurrent()
        assertEquals(listOf("download:${book.id}"), downloads.requests)
        assertTrue(vm.state.value.errors.isEmpty())
        assertTrue(vm.state.value.busyBooks.isEmpty())
    }

    @Test fun downloadRequestFailureUsesRequestCategoryAndCompletedAudioSkipsSourceRefresh() = runTest(dispatcher) {
        library.entries.value = listOf(entry)
        downloads.state.value = mapOf(track.id to TrackDownloadState(DownloadStatus.COMPLETED))
        downloads.downloadBlock = { error("request unavailable") }
        val vm = model()
        vm.resume(book.id); runCurrent()
        assertEquals(mapOf(book.id to DownloadActionError.REQUEST), vm.state.value.errors)
        assertTrue(vm.state.value.busyBooks.isEmpty())
        assertTrue(online.refreshedBooks.isEmpty())
        assertEquals(listOf("download:${book.id}"), downloads.requests)
    }

    private class FakeDownloads : DownloadsManager {
        override val state = MutableStateFlow<Map<String, TrackDownloadState>>(emptyMap())
        var initializeBlock: suspend () -> Unit = {}
        var downloadBlock: suspend () -> Unit = {}
        var initializations = 0
        var refreshes = 0
        val requests = mutableListOf<String>()
        override suspend fun initialize() { initializations++; initializeBlock() }
        override suspend fun refreshProgress() { refreshes++ }
        override suspend fun downloadBook(bookId: String) { requests += "download:$bookId"; downloadBlock() }
        override suspend fun pauseBook(bookId: String) { requests += "pause:$bookId" }
        override suspend fun removeBook(bookId: String) { requests += "remove:$bookId" }
    }

    private class FakeOnline : OnlineBookDetailsSource {
        var refreshSources: suspend () -> Unit = {}
        val refreshedBooks = mutableListOf<String>()
        override suspend fun refreshBookSources(bookId: String) { refreshedBooks += bookId; refreshSources() }
        override suspend fun inspectBook(libraryId: String, remoteId: String): ResolvedOnlineBook = error("unused")
        override suspend fun getLibrary(libraryId: String): OnlineLibrary = error("unused")
        override suspend fun refreshBookMetadata(bookId: String): LibraryBook? = error("unused")
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition = error("unused")
    }

    private class FakeLibrary : LibraryRepository {
        val entries = MutableStateFlow<List<LibraryCollectionEntry>>(emptyList())
        override fun observeCollection() = entries
        override fun observeBooks() = entries.map { collection -> collection.map { it.book } }
        override suspend fun getBook(bookId: String) = entries.value.firstOrNull { it.book.id == bookId }?.book
        override suspend fun getTracks(bookId: String) = entries.value.firstOrNull { it.book.id == bookId }?.tracks.orEmpty()
        override suspend fun getProgress(bookId: String) = entries.value.firstOrNull { it.book.id == bookId }?.progress
        override suspend fun getTrack(trackId: String) = entries.value.flatMap { it.tracks }.firstOrNull { it.id == trackId }
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) = Unit
        override suspend fun deleteBook(bookId: String): Unit = error("Unexpected book deletion")
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("unused")
    }

    companion object {
        private val book = LibraryBook("canonical", "Saved book")
        private val track = LibraryTrack("canonical-track", book.id, "Track", "https://example.org/audio", 0)
        private val entry = LibraryCollectionEntry(book, listOf(track), null)
    }
}
