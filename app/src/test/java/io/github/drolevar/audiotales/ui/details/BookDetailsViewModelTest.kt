package io.github.drolevar.audiotales.ui.details

import androidx.lifecycle.ViewModelStore
import io.github.drolevar.audiotales.data.library.*
import io.github.drolevar.audiotales.data.online.*
import io.github.drolevar.audiotales.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val library = FakeLibrary()
    private val online = FakeOnline(library)
    private val downloads = FakeDownloads()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun model() = BookDetailsViewModel(library, online, downloads).also { vm ->
        stores += ViewModelStore().also { it.put("details", vm) }
    }

    @Test fun inspectDoesNotSaveOrPlayAndRepeatedActivationDoesNotReload() = runTest(dispatcher) {
        val vm = model()
        vm.activate(remote); runCurrent()
        assertEquals(1, online.inspections)
        assertEquals(0, online.saves)
        assertFalse(vm.state.value.saved)
        assertEquals("inspected-track", vm.state.value.tracks.single().id)
        vm.activate(remote); runCurrent()
        assertEquals(1, online.inspections)
        vm.deactivate()
    }

    @Test fun retryAfterFailedInspectionShowsPendingStateUntilSuccessWithoutSavingOrPlaying() = runTest(dispatcher) {
        online.inspect = { error("offline") }
        val vm = model(); vm.activate(remote); runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.book)

        val result = CompletableDeferred<ResolvedOnlineBook>()
        online.inspect = { result.await() }
        vm.retry()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.loadFailed)
        runCurrent()
        assertEquals(2, online.inspections)
        assertTrue(vm.state.value.loading)
        vm.activate(remote); runCurrent()
        assertEquals(2, online.inspections)
        var callbacks = 0
        vm.listen(play = { _, _ -> callbacks++; true }, onOpened = { callbacks++ }); runCurrent()
        assertEquals(0, callbacks)

        result.complete(resolved); runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loadFailed)
        assertEquals("inspected", vm.state.value.book?.title)
        assertFalse(vm.state.value.saved)
        assertEquals(0, online.saves)
        assertEquals(0, callbacks)
        vm.deactivate()
    }

    @Test fun providerIdentityUsesExistingCanonicalBookOfflineWithoutInspection() = runTest(dispatcher) {
        library.entries.value = listOf(entry)
        online.inspect = { error("offline") }
        online.metadata = { error("offline") }
        val vm = model()
        vm.activate(remote); runCurrent()
        assertEquals("canonical", vm.state.value.book?.id)
        assertEquals("canonical-track", vm.state.value.tracks.single().id)
        assertEquals(progress, vm.state.value.progress)
        assertTrue(vm.state.value.saved)
        assertEquals(0, online.inspections)
        assertTrue(vm.state.value.metadataFailed)
        var played: String? = null
        vm.listen(play = { id, _ -> played = id; true }, onOpened = {}); runCurrent()
        assertEquals("canonical", played)
        vm.deactivate()
    }

    @Test fun explicitSaveDoesNotStartPlaybackAndDuplicateSaveUsesStoredTracks() = runTest(dispatcher) {
        val vm = model(); vm.activate(remote); runCurrent()
        vm.save(); runCurrent()
        assertTrue(vm.state.value.saved)
        assertEquals("canonical", vm.state.value.book?.id)
        assertEquals("canonical-track", vm.state.value.tracks.single().id)
        assertEquals(1, online.saves)
        vm.deactivate()
    }

    @Test fun trackActionAfterSaveUsesCanonicalTrackAndOnlyThenOpensPlayer() = runTest(dispatcher) {
        val vm = model(); vm.activate(remote); runCurrent()
        val events = mutableListOf<String>()
        vm.listen(0, { book, track -> events += "$book/$track"; true }, { events += "open:$it" }); runCurrent()
        assertEquals(listOf("canonical/canonical-track", "open:canonical"), events)
        assertEquals(1, online.saves)
        vm.deactivate()
    }

    @Test fun failedPlaybackDoesNotNavigateAndCanBeRetriedWithoutSavingAgain() = runTest(dispatcher) {
        val vm = model(); vm.activate(remote); runCurrent()
        var opens = 0
        vm.listen(play = { _, _ -> false }, onOpened = { opens++ }); runCurrent()
        assertEquals(0, opens)
        assertEquals(DetailsActionError.PLAYBACK, vm.state.value.actionError)
        assertFalse(vm.state.value.busy)
        vm.listen(play = { _, _ -> true }, onOpened = { opens++ }); runCurrent()
        assertEquals(1, opens)
        assertEquals(1, online.saves)
        assertNull(vm.state.value.actionError)
        vm.deactivate()
    }

    @Test fun failedSaveNeverInvokesPlaybackOrNavigation() = runTest(dispatcher) {
        online.save = { throw IllegalStateException("unavailable") }
        val vm = model(); vm.activate(remote); runCurrent()
        var callbacks = 0
        vm.listen(play = { _, _ -> callbacks++; true }, onOpened = { callbacks++ }); runCurrent()
        assertEquals(0, callbacks)
        assertFalse(vm.state.value.saved)
        assertFalse(vm.state.value.busy)
        assertEquals(DetailsActionError.PLAYBACK, vm.state.value.actionError)
        vm.deactivate()
    }

    @Test fun leavingDuringUncooperativeSaveCannotLaterPlayOrNavigate() = runTest(dispatcher) {
        val result = CompletableDeferred<LibraryAddition>()
        online.save = { withContext(NonCancellable) { result.await() } }
        val vm = model(); vm.activate(remote); runCurrent()
        var callbacks = 0
        vm.listen(play = { _, _ -> callbacks++; true }, onOpened = { callbacks++ }); runCurrent()
        assertTrue(vm.state.value.busy)
        vm.deactivate()
        result.complete(LibraryAddition(book, false)); runCurrent()
        assertEquals(0, callbacks)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.saved)
    }

    @Test fun lateInspectionCannotReplaceNewTargetAndReturningRestartsIncompleteLoad() = runTest(dispatcher) {
        val first = CompletableDeferred<ResolvedOnlineBook>()
        online.inspect = { id -> if (id == "remote") withContext(NonCancellable) { first.await() } else resolved.copy(title = "new") }
        val vm = model(); vm.activate(remote); runCurrent()
        vm.deactivate(); vm.activate(remote.copy(remoteId = "new")); runCurrent()
        assertEquals("new", vm.state.value.book?.title)
        first.complete(resolved.copy(title = "stale")); runCurrent()
        assertEquals("new", vm.state.value.book?.title)
        vm.deactivate()
        val second = CompletableDeferred<ResolvedOnlineBook>()
        online.inspect = { second.await() }
        vm.activate(remote); runCurrent(); vm.deactivate()
        online.inspect = { resolved }
        vm.activate(remote); runCurrent()
        assertEquals("inspected", vm.state.value.book?.title)
        assertFalse(vm.state.value.loading)
        vm.deactivate()
    }

    @Test fun stopCancelsPollingAndReturnResumesItWithoutInspectingAgain() = runTest(dispatcher) {
        val vm = model(); vm.activate(remote); runCurrent()
        val initial = downloads.refreshes
        advanceTimeBy(1_000); runCurrent()
        assertTrue(downloads.refreshes > initial)
        vm.deactivate()
        val stopped = downloads.refreshes
        advanceTimeBy(4_000); runCurrent()
        assertEquals(stopped, downloads.refreshes)
        vm.activate(remote)
        assertEquals("inspected", vm.state.value.book?.title)
        assertFalse(vm.state.value.loading)
        runCurrent()
        assertTrue(downloads.refreshes > stopped)
        assertEquals(1, online.inspections)
        vm.deactivate()
    }

    @Test fun completedDownloadOpensDownloadsWithoutSourceRefreshAndPauseUsesManager() = runTest(dispatcher) {
        library.entries.value = listOf(entry.copy(book = book.copy(description = "cached")))
        downloads.state.value = mapOf("canonical-track" to TrackDownloadState(DownloadStatus.COMPLETED))
        val vm = model(); vm.activate(remote); runCurrent()
        var opens = 0
        vm.downloadAction { opens++ }; runCurrent()
        assertEquals(1, opens)
        assertEquals(0, online.sourceRefreshes)
        assertTrue(downloads.requests.isEmpty())
        downloads.state.value = mapOf("canonical-track" to TrackDownloadState(DownloadStatus.DOWNLOADING)); runCurrent()
        vm.downloadAction { opens++ }; runCurrent()
        assertEquals(listOf("pause:canonical"), downloads.requests)
        assertEquals(0, online.sourceRefreshes)
        vm.deactivate()
    }

    @Test fun missingBytesRefreshFailureDoesNotReportDownloadSuccessAndRetryWorks() = runTest(dispatcher) {
        val vm = model(); vm.activate(remote); runCurrent()
        online.sourcesFail = true
        vm.downloadAction {}; runCurrent()
        assertEquals(DetailsActionError.DOWNLOAD, vm.state.value.actionError)
        assertTrue(downloads.requests.isEmpty())
        online.sourcesFail = false
        vm.downloadAction {}; runCurrent()
        assertEquals(listOf("download:canonical"), downloads.requests)
        assertNull(vm.state.value.actionError)
        assertEquals(1, online.saves)
        vm.deactivate()
    }

    @Test fun selectedProviderReferenceMapsToCanonicalTrackAfterPlaylistReordering() = runTest(dispatcher) {
        val other = track.copy(id = "other", sequenceIndex = 0, providerTrackRef = "other-reference")
        online.save = {
            library.entries.value = listOf(entry.copy(tracks = listOf(other, track.copy(sequenceIndex = 1))))
            LibraryAddition(book, false)
        }
        val vm = model(); vm.activate(remote); runCurrent()
        var selected: String? = null
        vm.listen(0, { _, id -> selected = id; true }, {}); runCurrent()
        assertEquals("canonical-track", selected)
        vm.deactivate()
    }

    @Test fun absentSelectedProviderReferenceFailsWithoutPlayingDifferentTrack() = runTest(dispatcher) {
        online.save = {
            library.entries.value = listOf(entry.copy(tracks = listOf(track.copy(id = "different", providerTrackRef = "different"))))
            LibraryAddition(book, false)
        }
        val vm = model(); vm.activate(remote); runCurrent()
        var callbacks = 0
        vm.listen(0, { _, _ -> callbacks++; true }, { callbacks++ }); runCurrent()
        assertEquals(0, callbacks)
        assertEquals(DetailsActionError.PLAYBACK, vm.state.value.actionError)
        vm.deactivate()
    }

    @Test fun failedDownloadInitializationKeepsSavedPlaybackAvailableAndCanRetry() = runTest(dispatcher) {
        downloads.initializationFails = true
        library.entries.value = listOf(entry.copy(book = book.copy(description = "cached")))
        val vm = model(); vm.activate(remote); runCurrent()
        assertFalse(vm.state.value.downloadsReady)
        assertTrue(vm.state.value.downloadsFailed)
        assertEquals("canonical", vm.state.value.book?.id)
        var played = false
        vm.listen(play = { _, _ -> played = true; true }, onOpened = {}); runCurrent()
        assertTrue(played)
        downloads.initializationFails = false
        vm.downloadAction {}; runCurrent()
        assertTrue(vm.state.value.downloadsReady)
        assertFalse(vm.state.value.downloadsFailed)
        downloads.state.value = mapOf("canonical-track" to TrackDownloadState(DownloadStatus.COMPLETED)); runCurrent()
        assertTrue(vm.state.value.download.complete)
        assertEquals(listOf("download:canonical"), downloads.requests)
        vm.deactivate()
    }

    private class FakeDownloads : DetailsDownloads {
        override val state = MutableStateFlow<Map<String, TrackDownloadState>>(emptyMap())
        val requests = mutableListOf<String>()
        var refreshes = 0
        var initializationFails = false
        override suspend fun initialize() { if (initializationFails) error("index unavailable") }
        override suspend fun refresh() { refreshes++ }
        override suspend fun download(bookId: String) { requests += "download:$bookId" }
        override suspend fun pause(bookId: String) { requests += "pause:$bookId" }
        override suspend fun remove(bookId: String) { requests += "remove:$bookId" }
    }
    private class FakeOnline(private val library: FakeLibrary) : OnlineBookDetailsSource {
        var inspections = 0; var saves = 0; var sourceRefreshes = 0; var sourcesFail = false
        var inspect: suspend (String) -> ResolvedOnlineBook = { resolved }
        var save: suspend () -> LibraryAddition = { library.entries.value = listOf(entry); LibraryAddition(book, false) }
        var metadata: suspend () -> LibraryBook? = { book }
        override suspend fun inspectBook(libraryId: String, remoteId: String): ResolvedOnlineBook { inspections++; return inspect(remoteId) }
        override suspend fun getLibrary(libraryId: String) = OnlineLibrary("source", "https://example.org/", "Example", LibraryProtocol.LIBRIVOX)
        override suspend fun refreshBookMetadata(bookId: String) = metadata()
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition { saves++; return save() }
        override suspend fun refreshBookSources(bookId: String) { sourceRefreshes++; if (sourcesFail) error("offline") }
    }
    private class FakeLibrary : LibraryRepository {
        val entries = MutableStateFlow<List<LibraryCollectionEntry>>(emptyList())
        override fun observeCollection() = entries
        override fun observeBooks() = MutableStateFlow(entries.value.map { it.book })
        override suspend fun getBook(bookId: String) = entries.value.firstOrNull { it.book.id == bookId }?.book
        override suspend fun getTracks(bookId: String) = entries.value.firstOrNull { it.book.id == bookId }?.tracks.orEmpty()
        override suspend fun getProgress(bookId: String) = entries.value.firstOrNull { it.book.id == bookId }?.progress
        override suspend fun getTrack(trackId: String) = entries.value.flatMap { it.tracks }.firstOrNull { it.id == trackId }
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) = Unit
        override suspend fun deleteBook(bookId: String): Unit = error("Unexpected book deletion")
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("unused")
    }
    companion object {
        private val remote = BookDetailsTarget(libraryId = "source", remoteId = "remote")
        private val book = LibraryBook("canonical", "saved", providerId = "source", providerBookId = "remote", sourceUrl = "https://example.org/")
        private val track = LibraryTrack("canonical-track", "canonical", "Track", "https://example.org/audio", 0, providerTrackRef = "provider-track")
        private val progress = ListeningProgress("canonical", track.id, 15_000, 1)
        private val entry = LibraryCollectionEntry(book, listOf(track), progress)
        private val resolved = ResolvedOnlineBook("inspected-id", "source", "remote", "https://example.org/", "inspected", null, listOf(track.copy(id = "inspected-track", bookId = "inspected-id")))
    }
}
