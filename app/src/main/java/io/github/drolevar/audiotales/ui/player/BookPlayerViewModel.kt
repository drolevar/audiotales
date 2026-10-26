package io.github.drolevar.audiotales.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.playback.BookDownloads
import io.github.drolevar.audiotales.playback.summarizeBookDownloads
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.data.online.OnlineBookDetailsSource
import io.github.drolevar.audiotales.playback.TrackDownloadState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PlayerActionError { SOURCES, PLAYBACK, DOWNLOAD, REMOVE_BOOK }

internal data class BookPlayerUiState(
    val book: LibraryBook? = null,
    val tracks: List<LibraryTrack> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val downloadsReady: Boolean = false,
    val actionBusy: Boolean = false,
    val refreshingSources: Boolean = false,
    val actionError: PlayerActionError? = null,
    val progress: ListeningProgress? = null,
    val downloadsFailed: Boolean = false,
    val metadataRefreshing: Boolean = false,
    val metadataFailed: Boolean = false,
    val removingBook: Boolean = false,
    val removed: Boolean = false,
)

/** Android download services stay behind this boundary in state/lifecycle tests. */
internal interface PlayerDownloads {
    val state: StateFlow<Map<String, TrackDownloadState>>
    suspend fun initialize()
    suspend fun refreshProgress()
    suspend fun downloadBook(bookId: String)
    suspend fun pauseBook(bookId: String)
    suspend fun removeBook(bookId: String)
    suspend fun removeBookAndAwait(bookId: String, afterRemoval: suspend () -> Unit)
}

private class ManagedPlayerDownloads(private val delegate: BookDownloads) : PlayerDownloads {
    override val state = delegate.state
    override suspend fun initialize() = delegate.initialize()
    override suspend fun refreshProgress() = delegate.refreshProgress()
    override suspend fun downloadBook(bookId: String) = delegate.downloadBook(bookId)
    override suspend fun pauseBook(bookId: String) = delegate.pauseBook(bookId)
    override suspend fun removeBook(bookId: String) = delegate.removeBook(bookId)
    override suspend fun removeBookAndAwait(bookId: String, afterRemoval: suspend () -> Unit) =
        delegate.removeBookAndAwait(bookId, afterRemoval)
}

@HiltViewModel
internal class BookPlayerViewModel internal constructor(
    private val library: LibraryRepository,
    private val downloads: PlayerDownloads,
    private val onlineLibraries: OnlineBookDetailsSource,
) : ViewModel() {
    @Inject constructor(library: LibraryRepository, downloads: BookDownloads, onlineLibraries: OnlineLibraries) :
        this(library, ManagedPlayerDownloads(downloads), onlineLibraries)
    private val _state = MutableStateFlow(BookPlayerUiState())
    val state = _state.asStateFlow()
    val downloadStates = downloads.state
    private var loadJob: Job? = null
    private var metadataJob: Job? = null
    private var downloadsJob: Job? = null
    private var actionJob: Job? = null
    private var currentBookId: String? = null
    private var visible = false

    fun activate(bookId: String) {
        visible = true
        if (currentBookId != bookId || _state.value.loading || _state.value.failed) load(bookId)
        else if (!_state.value.removed && !_state.value.removingBook) startDownloads()
    }

    fun deactivate() {
        visible = false
        loadJob?.cancel()
        downloadsJob?.cancel()
        metadataJob?.cancel()
        if (!_state.value.removingBook) actionJob?.cancel()
    }

    fun load(bookId: String) {
        if (_state.value.removingBook) return
        actionJob?.cancel()
        metadataJob?.cancel()
        downloadsJob?.cancel()
        currentBookId = bookId
        loadJob?.cancel()
        _state.value = BookPlayerUiState()
        loadJob = viewModelScope.launch {
            try {
                val book = requireNotNull(library.getBook(bookId))
                val tracks = library.getTracks(bookId)
                require(tracks.isNotEmpty())
                val progress = library.getProgress(bookId)
                _state.value = BookPlayerUiState(book, tracks, loading = false, progress = progress)
                if (book.coverUrl.isNullOrBlank() || book.description == null) refreshMetadata()
                startDownloads()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun refreshMetadata() {
        val bookId = _state.value.book?.id ?: return
        if (metadataJob?.isActive == true || _state.value.removingBook || _state.value.removed) return
        _state.update { it.copy(metadataRefreshing = true, metadataFailed = false) }
        metadataJob = viewModelScope.launch {
            try {
                val book = onlineLibraries.refreshBookMetadata(bookId) ?: return@launch
                currentCoroutineContext().ensureActive()
                if (currentBookId == bookId) {
                    _state.update { state ->
                        if (state.book?.id == bookId) state.copy(book = book) else state
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentBookId == bookId) _state.update { it.copy(metadataFailed = true) }
            } finally {
                if (currentBookId == bookId) _state.update { it.copy(metadataRefreshing = false) }
            }
        }
    }

    fun retryDownloads() {
        downloadsJob?.cancel()
        startDownloads()
    }

    private fun startDownloads() {
        if (!visible || downloadsJob?.isActive == true || _state.value.removingBook || _state.value.removed) return
        _state.update { it.copy(downloadsFailed = false) }
        downloadsJob = viewModelScope.launch {
            try {
                downloads.initialize()
                _state.update { it.copy(downloadsReady = true, downloadsFailed = false) }
                while (isActive) {
                    downloads.refreshProgress()
                    delay(1_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unavailable download index must not hide book information or playback.
                _state.update { it.copy(downloadsReady = false, downloadsFailed = true) }
            }
        }
    }

    fun retryPlayback(onSourcesReady: (String) -> Boolean) = action(PlayerActionError.PLAYBACK) { bookId ->
        refreshSourcesUnlessComplete(bookId)
        check(onSourcesReady(bookId)) { "Player is disconnected" }
    }

    fun downloadBook() = action { bookId ->
        refreshSourcesUnlessComplete(bookId)
        // Read fresh repository locations only after a valid atomic source refresh.
        downloads.downloadBook(bookId)
    }
    fun pauseDownloads() = action { downloads.pauseBook(it) }
    fun removeDownloads() = action { downloads.removeBook(it) }
    fun cancelAction() { actionJob?.cancel() }

    fun removeBook(
        preparePlayback: suspend (String) -> Boolean,
        finishPlayback: suspend (String) -> Boolean,
    ) {
        val bookId = _state.value.book?.id ?: return
        if (actionJob?.isActive == true || _state.value.removed) return
        loadJob?.cancel()
        metadataJob?.cancel()
        downloadsJob?.cancel()
        _state.update { it.copy(removingBook = true, actionBusy = true, actionError = null) }
        actionJob = viewModelScope.launch {
            try {
                check(preparePlayback(bookId)) { "Playback could not be stopped" }
                downloads.removeBookAndAwait(bookId) {
                    currentCoroutineContext().ensureActive()
                    // Commit under the download mutation lock so a queued download request
                    // cannot re-add these tracks between audio removal and book deletion.
                    withContext(NonCancellable) { library.deleteBook(bookId) }
                }
                _state.update { it.copy(removed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(actionError = PlayerActionError.REMOVE_BOOK) }
            } finally {
                // Release the application-owned guard even after the controller disconnects.
                withContext(NonCancellable) {
                    try { withTimeoutOrNull(5_000) { finishPlayback(bookId) } }
                    catch (_: Exception) { /* The connection's local release has no IPC dependency. */ }
                }
                _state.update { it.copy(removingBook = false, actionBusy = false) }
                if (!_state.value.removed) startDownloads()
            }
        }
    }

    private suspend fun refreshSourcesUnlessComplete(bookId: String) {
        downloads.initialize()
        downloads.refreshProgress()
        val tracks = library.getTracks(bookId)
        if (summarizeBookDownloads(tracks.map { it.id }, downloads.state.value).complete) return
        _state.update { it.copy(refreshingSources = true) }
        try {
            onlineLibraries.refreshBookSources(bookId)
            val refreshedTracks = library.getTracks(bookId)
            _state.update { it.copy(tracks = refreshedTracks) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(actionError = PlayerActionError.SOURCES) }
            throw error
        } finally {
            if (currentBookId == bookId) _state.update { it.copy(refreshingSources = false) }
        }
    }

    private fun action(errorKind: PlayerActionError = PlayerActionError.DOWNLOAD, block: suspend (String) -> Unit) {
        val bookId = _state.value.book?.id ?: return
        if (actionJob?.isActive == true || _state.value.removed) return
        _state.update { it.copy(actionBusy = true, actionError = null) }
        actionJob = viewModelScope.launch {
            try {
                block(bookId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentBookId == bookId) _state.update { it.copy(actionError = it.actionError ?: errorKind) }
            } finally {
                if (currentBookId == bookId) _state.update { it.copy(actionBusy = false, refreshingSources = false) }
            }
        }
    }
}
