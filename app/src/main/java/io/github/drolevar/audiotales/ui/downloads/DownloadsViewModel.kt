package io.github.drolevar.audiotales.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.playback.BookDownloads
import io.github.drolevar.audiotales.playback.summarizeBookDownloads
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

@HiltViewModel
internal class DownloadsViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: BookDownloads,
    private val onlineLibraries: OnlineLibraries,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsUiState())
    val state = _state.asStateFlow()
    private var visibleJob: Job? = null

    fun start() {
        if (visibleJob?.isActive == true) return
        visibleJob = viewModelScope.launch {
            _state.update { it.copy(failed = false) }
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
