package io.github.drolevar.audiotales.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.playback.BookDownloads
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Content(
        /** All saved books remain available to the shared mini-player. */
        val books: List<LibraryBook>,
        val rows: List<CollectionRow> = emptyList(),
        val filter: CollectionFilter = CollectionFilter.ALL,
        val sort: CollectionSort = CollectionSort.RECENT,
        val busyBookIds: Set<String> = emptySet(),
        val actionFailed: Boolean = false,
    ) : LibraryUiState
    data object Error : LibraryUiState
}

private data class CollectionSelection(
    val filter: CollectionFilter = CollectionFilter.ALL,
    val sort: CollectionSort = CollectionSort.RECENT,
)
private data class CollectionOperations(val busy: Set<String> = emptySet(), val failed: Boolean = false)

@HiltViewModel
internal class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val onlineLibraries: OnlineLibraries,
    private val downloads: BookDownloads,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val selection = MutableStateFlow(CollectionSelection())
    private val operations = MutableStateFlow(CollectionOperations())
    private var artworkJob: Job? = null
    private var downloadRefreshJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = refresh.flatMapLatest {
        combine(repository.observeCollection(), downloads.state, selection, operations) { entries, downloadState, selected, operation ->
            if (entries.isEmpty()) LibraryUiState.Empty else LibraryUiState.Content(
                books = entries.map { it.book },
                rows = collectionRows(entries, downloadState, selected.filter, selected.sort),
                filter = selected.filter,
                sort = selected.sort,
                busyBookIds = operation.busy,
                actionFailed = operation.failed,
            )
        }.onStart { emit(LibraryUiState.Loading) }
            .catch { emit(LibraryUiState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.Loading)

    fun retry() { refresh.update { it + 1 } }
    fun selectFilter(filter: CollectionFilter) { selection.update { it.copy(filter = filter) } }
    fun selectSort(sort: CollectionSort) { selection.update { it.copy(sort = sort) } }
    fun dismissActionError() { operations.update { it.copy(failed = false) } }

    fun downloadAction(bookId: String) = runBookAction(bookId) {
        downloads.initialize()
        val tracks = repository.getTracks(bookId)
        val summary = io.github.drolevar.audiotales.playback.summarizeBookDownloads(tracks.map { it.id }, downloads.state.value)
        when {
            summary.complete || summary.removing -> Unit
            summary.active -> downloads.pauseBook(bookId)
            else -> {
                // Refresh expiring online sources only when audio still needs downloading.
                if (repository.getBook(bookId)?.providerId != null) onlineLibraries.refreshBookSources(bookId)
                downloads.downloadBook(bookId)
            }
        }
    }

    fun removeDownloads(bookId: String) = runBookAction(bookId) { downloads.removeBook(bookId) }

    private fun runBookAction(bookId: String, action: suspend () -> Unit) {
        if (bookId in operations.value.busy) return
        operations.update { it.copy(busy = it.busy + bookId, failed = false) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operations.update { it.copy(failed = true) }
            } finally {
                operations.update { it.copy(busy = it.busy - bookId) }
            }
        }
    }

    /** Called by the foreground lifecycle owner, alongside artwork refresh. */
    fun startDownloadRefresh() {
        if (downloadRefreshJob?.isActive == true) return
        downloadRefreshJob = viewModelScope.launch {
            try {
                downloads.initialize()
                while (isActive) {
                    downloads.refreshProgress()
                    delay(1_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operations.update { it.copy(failed = true) }
            }
        }
    }

    fun stopDownloadRefresh() { downloadRefreshJob?.cancel() }

    fun refreshArtwork() {
        if (artworkJob?.isActive == true) return
        artworkJob = viewModelScope.launch {
            try {
                onlineLibraries.refreshMissingArtwork()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Artwork is optional; the saved library remains usable while offline.
            }
        }
    }

    fun stopArtworkRefresh() { artworkJob?.cancel() }
}
