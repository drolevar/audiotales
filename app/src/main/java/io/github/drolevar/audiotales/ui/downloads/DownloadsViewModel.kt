package io.github.drolevar.audiotales.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.data.online.OnlineBookDetailsSource
import io.github.drolevar.audiotales.playback.BookDownloads
import io.github.drolevar.audiotales.playback.TrackDownloadState
import io.github.drolevar.audiotales.playback.summarizeBookDownloads
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class DownloadActionError { SOURCES, REQUEST }

internal data class DownloadsUiState(
    val books: List<DownloadBookItem> = emptyList(),
    val allBooks: List<LibraryBook> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busyBooks: Set<String> = emptySet(),
    val errors: Map<String, DownloadActionError> = emptyMap(),
)

/** Keeps lifecycle/action tests independent of the Android download service. */
internal interface DownloadsManager {
    val state: StateFlow<Map<String, TrackDownloadState>>
    suspend fun initialize()
    suspend fun refreshProgress()
    suspend fun downloadBook(bookId: String)
    suspend fun pauseBook(bookId: String)
    suspend fun removeBook(bookId: String)
}

private class ManagedDownloads(private val delegate: BookDownloads) : DownloadsManager {
    override val state = delegate.state
    override suspend fun initialize() = delegate.initialize()
    override suspend fun refreshProgress() = delegate.refreshProgress()
    override suspend fun downloadBook(bookId: String) = delegate.downloadBook(bookId)
    override suspend fun pauseBook(bookId: String) = delegate.pauseBook(bookId)
    override suspend fun removeBook(bookId: String) = delegate.removeBook(bookId)
}

@HiltViewModel
internal class DownloadsViewModel internal constructor(
    private val library: LibraryRepository,
    private val downloads: DownloadsManager,
    private val onlineLibraries: OnlineBookDetailsSource,
) : ViewModel() {
    @Inject constructor(library: LibraryRepository, downloads: BookDownloads, onlineLibraries: OnlineLibraries) :
        this(library, ManagedDownloads(downloads), onlineLibraries)

    private val _state = MutableStateFlow(DownloadsUiState())
    val state = _state.asStateFlow()
    private var visibleJob: Job? = null

    fun start() {
        if (visibleJob?.isActive == true) return
        _state.update { it.copy(loading = it.loading || it.failed, failed = false) }
        visibleJob = viewModelScope.launch {
            try {
                downloads.initialize()
                coroutineScope {
                    launch {
                        while (isActive) {
                            downloads.refreshProgress()
                            delay(1_000)
                        }
                    }
                    combine(library.observeCollection(), downloads.state) { collection, states ->
                        collection.map { it.book } to downloadOverview(collection, states)
                    }.collect { (allBooks, books) ->
                        _state.update { it.copy(books = books, allBooks = allBooks, loading = false, failed = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun stop() {
        visibleJob?.cancel()
        visibleJob = null
    }

    fun retry() {
        stop()
        start()
    }

    fun pause(bookId: String) = action(bookId) { downloads.pauseBook(bookId) }
    fun remove(bookId: String) = action(bookId) { downloads.removeBook(bookId) }
    fun resume(bookId: String) = action(bookId) {
        downloads.initialize()
        downloads.refreshProgress()
        val tracks = library.getTracks(bookId)
        // Completed offline audio must never need a network refresh.
        if (!summarizeBookDownloads(tracks.map { it.id }, downloads.state.value).complete) {
            try {
                onlineLibraries.refreshBookSources(bookId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errors = it.errors + (bookId to DownloadActionError.SOURCES)) }
                throw error
            }
        }
        downloads.downloadBook(bookId)
    }

    private fun action(bookId: String, block: suspend () -> Unit) {
        if (bookId in _state.value.busyBooks) return
        _state.update { it.copy(busyBooks = it.busyBooks + bookId, errors = it.errors - bookId) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { state ->
                    state.copy(errors = state.errors +
                        (bookId to (state.errors[bookId] ?: DownloadActionError.REQUEST)))
                }
            } finally {
                _state.update { it.copy(busyBooks = it.busyBooks - bookId) }
            }
        }
    }
}
