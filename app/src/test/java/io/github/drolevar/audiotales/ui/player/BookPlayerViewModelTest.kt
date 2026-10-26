package io.github.drolevar.audiotales.ui.player

import androidx.lifecycle.ViewModelStore
import io.github.drolevar.audiotales.data.library.*
import io.github.drolevar.audiotales.data.online.*
import io.github.drolevar.audiotales.playback.*
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
class BookPlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val events = mutableListOf<String>()
    private val library = FakeLibrary(events)
    private val downloads = FakeDownloads(events)
    private val online = FakeOnline()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() {
        stores.forEach { it.clear() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model() = BookPlayerViewModel(library, downloads, online).also { vm ->
        stores += ViewModelStore().also { it.put("player", vm) }
        vm.activate(book.id)
        dispatcher.scheduler.runCurrent()
    }

    private fun playerTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() }
        finally {
            // Close ViewModel-owned polling before runTest waits for its scheduler to idle.
            stores.forEach { it.clear() }
            runCurrent()
        }
    }

    @Test fun unavailableDownloadIndexKeepsBookAndProgressAndCanRetryWithoutDownloading() = playerTest {
        downloads.initializeBlock = { error("index unavailable") }
        val vm = model()
        assertEquals(book, vm.state.value.book)
        assertEquals(progress, vm.state.value.progress)
        assertFalse(vm.state.value.failed)
        assertTrue(vm.state.value.downloadsFailed)
        assertFalse(vm.state.value.downloadsReady)
        downloads.initializeBlock = {}
        vm.retryDownloads(); runCurrent()
        assertTrue(vm.state.value.downloadsReady)
        assertFalse(vm.state.value.downloadsFailed)
        assertTrue(events.isEmpty())
    }

    @Test fun downloadRemovalKeepsSavedBookAndHistory() = playerTest {
        val vm = model()
        vm.removeDownloads(); runCurrent()
        assertEquals(listOf("remove-downloads"), events)
        assertEquals(book, library.getBook(book.id))
        assertEquals(progress, library.getProgress(book.id))
        assertFalse(vm.state.value.removed)
    }

    @Test fun bookDeletionWaitsForPlaybackAndDownloadsBeforeDeletingHistory() = playerTest {
        val finishedAudio = CompletableDeferred<Unit>()
        downloads.strictRemoval = { finishedAudio.await() }
        val vm = model()
        vm.removeBook({ events += "prepare"; true }, { events += "finish"; true })
        runCurrent()
        assertTrue(vm.state.value.removingBook)
        assertEquals(listOf("prepare", "remove-audio-and-wait"), events)
        assertNotNull(library.getBook(book.id))
        vm.removeBook({ error("duplicate") }, { error("duplicate") })
        finishedAudio.complete(Unit); runCurrent()
        assertEquals(listOf("prepare", "remove-audio-and-wait", "delete-history", "finish"), events)
        assertNull(library.getBook(book.id))
        assertNull(library.getProgress(book.id))
        assertTrue(vm.state.value.removed)
        assertFalse(vm.state.value.actionBusy)
    }

    @Test fun failedAudioRemovalRetainsBookAndHistoryAndReleasesPlaybackReservation() = playerTest {
        downloads.strictRemoval = { error("removal timed out") }
        val vm = model()
        vm.removeBook({ events += "prepare"; true }, { events += "finish"; true }); runCurrent()
        assertEquals(listOf("prepare", "remove-audio-and-wait", "finish"), events)
        assertEquals(book, library.getBook(book.id))
        assertEquals(progress, library.getProgress(book.id))
        assertEquals(PlayerActionError.REMOVE_BOOK, vm.state.value.actionError)
        assertFalse(vm.state.value.removed)
        assertFalse(vm.state.value.removingBook)
    }

    @Test fun disconnectedPlaybackDoesNotDeleteAnyData() = playerTest {
        val vm = model()
        vm.removeBook({ false }, { events += "finish"; true }); runCurrent()
        assertEquals(listOf("finish"), events)
        assertEquals(PlayerActionError.REMOVE_BOOK, vm.state.value.actionError)
        assertNotNull(library.getBook(book.id))
    }

    @Test fun clearingViewModelDuringRemovalReleasesReservationWithoutDeletingHistory() = playerTest {
        downloads.strictRemoval = { CompletableDeferred<Unit>().await() }
        val vm = model()
        vm.removeBook({ true }, { events += "finish"; true }); runCurrent()
        stores.single().clear(); runCurrent()
        assertEquals(listOf("remove-audio-and-wait", "finish"), events)
        assertNotNull(library.getProgress(book.id))
    }

    @Test fun leavingScreenCancelsSourceRetryBeforeItCanStartPlayback() = playerTest {
        val source = CompletableDeferred<Unit>()
        online.sourceRefresh = { source.await() }
        val vm = model()
        var started = false
        vm.retryPlayback { started = true; true }; runCurrent()
        assertTrue(vm.state.value.refreshingSources)
        vm.deactivate(); runCurrent()
        source.complete(Unit); runCurrent()
        assertFalse(started)
        assertFalse(vm.state.value.actionBusy)
        val refreshes = downloads.refreshes
        advanceTimeBy(3_000); runCurrent()
        assertEquals(refreshes, downloads.refreshes)
        vm.activate(book.id); runCurrent()
        assertTrue(downloads.refreshes > refreshes)
    }

    @Test fun offlinePlaybackRetryDoesNotContactProvider() = playerTest {
        downloads.state.value = mapOf(track.id to TrackDownloadState(DownloadStatus.COMPLETED))
        online.sourceRefresh = { error("must stay offline") }
        val vm = model()
        var started = false
        vm.retryPlayback { started = true; true }; runCurrent()
        assertTrue(started)
        assertNull(vm.state.value.actionError)
        assertEquals(0, online.sourceCalls)
    }

    @Test fun metadataFailureRetainsSavedDescriptionAndPlaybackState() = playerTest {
        val vm = model()
        online.metadataRefresh = { error("offline") }
        vm.refreshMetadata(); runCurrent()
        assertTrue(vm.state.value.metadataFailed)
        assertFalse(vm.state.value.failed)
        assertEquals(book.description, vm.state.value.book?.description)
        assertEquals(listOf(track), vm.state.value.tracks)
    }

    @Test fun metadataRefreshPublishesSavedBookAndReaderCreditsTogetherWithoutChangingProgress() = playerTest {
        val vm = model()
        val credited = track.copy(readerCredits = listOf(ReaderCredit("7", "Reader")))
        val updated = book.copy(description = "Updated description")
        online.metadataRefresh = {
            library.tracks = listOf(credited)
            library.updateBook(updated)
            updated
        }
        vm.refreshMetadata(); runCurrent()
        assertEquals(updated, vm.state.value.book)
        assertEquals(listOf(credited), vm.state.value.tracks)
        assertEquals(progress, vm.state.value.progress)
        assertFalse(vm.state.value.metadataFailed)
        assertFalse(vm.state.value.metadataRefreshing)
    }

    @Test fun failedTrackReloadKeepsKnownCreditsAndDescriptionTogether() = playerTest {
        val credited = track.copy(readerCredits = listOf(ReaderCredit("7", "Known reader")))
        library.tracks = listOf(credited)
        val vm = model()
        library.beforeTracks = { error("read failed") }
        online.metadataRefresh = { book.copy(description = "New remote description") }
        vm.refreshMetadata(); runCurrent()
        assertEquals(book, vm.state.value.book)
        assertEquals(listOf(credited), vm.state.value.tracks)
        assertTrue(vm.state.value.metadataFailed)
        assertFalse(vm.state.value.metadataRefreshing)
    }

    @Test fun leavingDuringTrackReloadPreservesKnownDataAndClearsMetadataLoading() = playerTest {
        val vm = model()
        val gate = CompletableDeferred<Unit>()
        library.beforeTracks = { gate.await() }
        online.metadataRefresh = { book.copy(description = "Stale") }
        vm.refreshMetadata(); runCurrent()
        assertTrue(vm.state.value.metadataRefreshing)
        vm.deactivate(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(book, vm.state.value.book)
        assertEquals(listOf(track), vm.state.value.tracks)
        assertFalse(vm.state.value.metadataRefreshing)
        assertFalse(vm.state.value.metadataFailed)
    }

    @Test fun removalDuringMetadataRefreshCannotPublishDeletedBookOrTracks() = playerTest {
        val vm = model()
        online.metadataRefresh = {
            library.deleteBook(book.id)
            book.copy(description = "Stale result")
        }
        vm.refreshMetadata(); runCurrent()
        assertNull(library.getBook(book.id))
        assertEquals(book, vm.state.value.book)
        assertEquals(listOf(track), vm.state.value.tracks)
        assertFalse(vm.state.value.metadataRefreshing)
    }

    @Test fun loadingSameBookAgainPreventsOldMetadataGenerationFromPublishing() = playerTest {
        val vm = model()
        val gate = CompletableDeferred<Unit>()
        online.metadataRefresh = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            book.copy(description = "Stale result")
        }
        vm.refreshMetadata(); runCurrent()
        vm.load(book.id); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(book, vm.state.value.book)
        assertEquals(listOf(track), vm.state.value.tracks)
        assertFalse(vm.state.value.metadataRefreshing)
        assertFalse(vm.state.value.metadataFailed)
    }

    private class FakeDownloads(private val events: MutableList<String>) : PlayerDownloads {
        override val state = MutableStateFlow<Map<String, TrackDownloadState>>(emptyMap())
        var initializeBlock: suspend () -> Unit = {}
        var strictRemoval: suspend () -> Unit = {}
        var refreshes = 0
        override suspend fun initialize() = initializeBlock()
        override suspend fun refreshProgress() { refreshes++ }
        override suspend fun downloadBook(bookId: String) { events += "download" }
        override suspend fun pauseBook(bookId: String) { events += "pause" }
        override suspend fun removeBook(bookId: String) { events += "remove-downloads" }
        override suspend fun removeBookAndAwait(bookId: String, afterRemoval: suspend () -> Unit) {
            events += "remove-audio-and-wait"; strictRemoval(); afterRemoval()
        }
    }

    private class FakeOnline : OnlineBookDetailsSource {
        override suspend fun inspectBookMetadata(libraryId: String, remoteId: String): LibraryBook = error("unused")
        override suspend fun inspectBookAudio(book: LibraryBook): ResolvedOnlineBook = error("unused")
        var sourceCalls = 0
        var sourceRefresh: suspend () -> Unit = {}
        var metadataRefresh: suspend () -> LibraryBook? = { book }
        override suspend fun refreshBookSources(bookId: String) { sourceCalls++; sourceRefresh() }
        override suspend fun refreshBookMetadata(bookId: String) = metadataRefresh()
        override suspend fun inspectBook(libraryId: String, remoteId: String): ResolvedOnlineBook = error("unused")
        override suspend fun getLibrary(libraryId: String): OnlineLibrary = error("unused")
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition = error("unused")
    }

    private class FakeLibrary(private val events: MutableList<String>) : LibraryRepository {
        private val books = MutableStateFlow(listOf(book))
        var tracks = listOf(track)
        var beforeTracks: suspend () -> Unit = {}
        fun updateBook(value: LibraryBook) { books.value = books.value.map { if (it.id == value.id) value else it } }
        override fun observeBooks() = books
        override fun observeCollection() = books.map { values -> values.map { LibraryCollectionEntry(it, tracks, progress) } }
        override suspend fun getBook(bookId: String) = books.value.firstOrNull { it.id == bookId }
        override suspend fun getTracks(bookId: String): List<LibraryTrack> {
            beforeTracks()
            return if (getBook(bookId) != null) tracks else emptyList()
        }
        override suspend fun getTrack(trackId: String) = getTracks(book.id).firstOrNull { it.id == trackId }
        override suspend fun getProgress(bookId: String) = if (getBook(bookId) != null) progress else null
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) = Unit
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("unused")
        override suspend fun deleteBook(bookId: String) { events += "delete-history"; books.value = books.value.filterNot { it.id == bookId } }
    }

    companion object {
        private val book = LibraryBook("book", "Saved book", coverUrl = "https://example.org/cover.jpg", description = "Saved description")
        private val track = LibraryTrack("track", book.id, "First track", "https://example.org/audio", 0)
        private val progress = ListeningProgress(book.id, track.id, 14_000, 100)
    }
}
