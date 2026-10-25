package io.github.drolevar.audiotales.ui.catalogue

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.OnlineBookError
import io.github.drolevar.audiotales.data.online.OnlineBookException
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.data.online.OnlineLibrary
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CatalogueUiState(
    val libraries: List<OnlineLibrary> = emptyList(),
    val librariesLoading: Boolean = true,
    val librariesError: Boolean = false,
    val selectedLibraryId: String? = null,
    val query: String = "",
    val submittedQuery: String = "",
    val books: List<CatalogueBook> = emptyList(),
    val loading: Boolean = false,
    val error: OnlineBookError? = null,
    val nextOffset: Int? = null,
    val addLibrary: AddLibraryUiState? = null,
    val details: CatalogueBook? = null,
    val saving: Boolean = false,
    val saved: LibraryAddition? = null,
    val saveError: OnlineBookError? = null,
)

internal data class AddLibraryUiState(
    val url: String = "",
    val adding: Boolean = false,
    val error: OnlineBookError? = null,
)

@HiltViewModel
internal class CatalogueViewModel @Inject constructor(
    private val onlineLibraries: OnlineLibraries,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogueUiState(
        selectedLibraryId = savedState["catalogue.library"],
        query = savedState["catalogue.query"] ?: "",
        submittedQuery = savedState["catalogue.query"] ?: "",
    ))
    val state = _state.asStateFlow()
    private var browseJob: Job? = null
    private var libraryJob: Job? = null
    private var addJob: Job? = null
    private var saveJob: Job? = null

    init { observeLibraries() }

    fun observeLibraries() {
        libraryJob?.cancel()
        _state.update { it.copy(librariesLoading = true, librariesError = false) }
        libraryJob = viewModelScope.launch {
            onlineLibraries.observeLibraries()
                .catch { _state.update { it.copy(librariesLoading = false, librariesError = true) } }
                .collect { libraries ->
                    val previous = _state.value.selectedLibraryId
                    val selected = previous?.takeIf { id -> libraries.any { it.id == id } }
                        ?: libraries.firstOrNull()?.id
                    _state.update { it.copy(libraries = libraries, librariesLoading = false, selectedLibraryId = selected) }
                    savedState["catalogue.library"] = selected
                    if (selected != null && (selected != previous || browseJob == null)) browse()
                }
        }
    }

    fun selectLibrary(id: String) {
        if (id == _state.value.selectedLibraryId) return
        savedState["catalogue.library"] = id
        _state.update { it.copy(selectedLibraryId = id, details = null) }
        browse()
    }

    fun setQuery(query: String) {
        savedState["catalogue.query"] = query
        _state.update { it.copy(query = query) }
    }

    fun browse() = loadPage(append = false)
    fun loadMore() = loadPage(append = true)

    private fun loadPage(append: Boolean) {
        val input = _state.value
        val libraryId = input.selectedLibraryId ?: return
        if (append && (input.loading || input.nextOffset == null)) return
        val query = if (append) input.submittedQuery else input.query.trim()
        val offset = if (append) input.nextOffset ?: return else 0
        browseJob?.cancel()
        _state.update { it.copy(
            loading = true, error = null, submittedQuery = query,
            books = if (append) it.books else emptyList(),
            nextOffset = if (append) it.nextOffset else null,
        ) }
        browseJob = viewModelScope.launch {
            try {
                val page = onlineLibraries.browse(libraryId, query, offset)
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(
                    books = (if (append) it.books + page.books else page.books).distinctBy { book -> book.remoteId },
                    nextOffset = page.nextOffset?.takeIf { next -> next > offset },
                    loading = false,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(loading = false, error = error.onlineReason()) }
            }
        }
    }

    fun openAddLibrary() { _state.update { it.copy(addLibrary = AddLibraryUiState()) } }
    fun setLibraryUrl(url: String) {
        _state.update { state -> state.copy(addLibrary = state.addLibrary?.takeUnless { it.adding }?.copy(url = url)) }
    }
    fun dismissAddLibrary() {
        addJob?.cancel()
        _state.update { it.copy(addLibrary = null) }
    }
    fun addLibrary() {
        val input = _state.value.addLibrary?.takeUnless { it.adding || it.url.isBlank() } ?: return
        _state.update { it.copy(addLibrary = input.copy(adding = true, error = null)) }
        addJob = viewModelScope.launch {
            try {
                val library = onlineLibraries.addLibrary(input.url)
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(addLibrary = null) }
                selectLibrary(library.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(addLibrary = input.copy(error = error.onlineReason())) }
            }
        }
    }

    fun inspect(book: CatalogueBook) {
        saveJob?.cancel()
        _state.update { it.copy(details = book, saving = false, saved = null, saveError = null) }
    }
    fun dismissDetails() {
        saveJob?.cancel()
        _state.update { it.copy(details = null, saving = false, saved = null, saveError = null) }
    }
    fun saveBook() {
        val input = _state.value
        val book = input.details ?: return
        if (input.saving || input.saved != null) return
        _state.update { it.copy(saving = true, saveError = null) }
        saveJob = viewModelScope.launch {
            try {
                val result = onlineLibraries.saveBook(book.libraryId, book.remoteId)
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(saving = false, saved = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(saving = false, saveError = error.onlineReason()) }
            }
        }
    }
}

private fun Exception.onlineReason(): OnlineBookError =
    (this as? OnlineBookException)?.reason ?: OnlineBookError.NETWORK
