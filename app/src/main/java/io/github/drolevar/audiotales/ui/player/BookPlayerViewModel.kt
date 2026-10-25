package io.github.drolevar.audiotales.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.playback.BookDownloads
import io.github.drolevar.audiotales.playback.summarizeBookDownloads
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class PlayerActionError { SOURCES, PLAYBACK, DOWNLOAD }

internal data class BookPlayerUiState(
    val book: LibraryBook? = null,
    val tracks: List<LibraryTrack> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val downloadsReady: Boolean = false,
    val actionBusy: Boolean = false,
    val refreshingSources: Boolean = false,
    val actionError: PlayerActionError? = null,
)

@HiltViewModel
internal class BookPlayerViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: BookDownloads,
    private val onlineLibraries: OnlineLibraries,
) : ViewModel() {
    private val _state = MutableStateFlow(BookPlayerUiState())
    val state = _state.asStateFlow()
    val downloadStates = downloads.state
    private var loadJob: Job? = null
    private var artworkJob: Job? = null
    private var actionJob: Job? = null
    private var currentBookId: String? = null

    fun load(bookId: String) {
        if (currentBookId == bookId && !_state.value.failed) return
        actionJob?.cancel()
        artworkJob?.cancel()
        currentBookId = bookId
        loadJob?.cancel()
        _state.value = BookPlayerUiState()
        loadJob = viewModelScope.launch {
            try {
                val book = requireNotNull(library.getBook(bookId))
                val tracks = library.getTracks(bookId)
                require(tracks.isNotEmpty())
                _state.value = BookPlayerUiState(book, tracks, loading = false)
                if (book.coverUrl.isNullOrBlank()) refreshArtwork(bookId)
                downloads.initialize()
                _state.update { it.copy(downloadsReady = true) }
                while (isActive) {
                    downloads.refreshProgress()
                    delay(1_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun refreshArtwork(bookId: String) {
        artworkJob = viewModelScope.launch {
            try {
                val book = onlineLibraries.refreshBookArtwork(bookId) ?: return@launch
                currentCoroutineContext().ensureActive()
                if (currentBookId == bookId) {
                    _state.update { state ->
                        if (state.book?.id == bookId) state.copy(book = book) else state
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Missing artwork must never delay or fail playback and downloads.
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
        if (actionJob?.isActive == true) return
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
