package io.github.drolevar.audiotales.ui.catalogue

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.online.*
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

internal enum class CatalogueSort { LIBRARY, TITLE, AUTHOR }
internal data class CatalogueSourceState(
    val books: List<CatalogueBook> = emptyList(),
    val loading: Boolean = false,
    val error: OnlineBookError? = null,
    val nextOffset: Int? = null,
    val language: String? = null,
) {
    val languages: List<String> get() = books.mapNotNull { it.language?.takeIf(String::isNotBlank) }.distinct().sorted()
    fun shown(sort: CatalogueSort): List<CatalogueBook> {
        val filtered = books.filter { language == null || it.language == language }
        return when (sort) {
            CatalogueSort.LIBRARY -> filtered
            CatalogueSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            CatalogueSort.AUTHOR -> filtered.sortedWith(compareBy<CatalogueBook> { it.author?.lowercase().orEmpty() }.thenBy { it.title.lowercase() })
        }
    }
}
internal data class CatalogueUiState(
    val libraries: List<OnlineLibrary> = emptyList(),
    val librariesLoading: Boolean = true,
    val librariesError: Boolean = false,
    val singleLibraryId: String? = null,
    val query: String = "",
    val submittedQuery: String = "",
    val sources: Map<String, CatalogueSourceState> = emptyMap(),
    val sort: CatalogueSort = CatalogueSort.LIBRARY,
    val addLibrary: AddLibraryUiState? = null,
    val details: CatalogueBook? = null,
    val saving: Boolean = false,
    val saved: LibraryAddition? = null,
    val saveError: OnlineBookError? = null,
    val editingLibrary: OnlineLibrary? = null,
    val libraryName: String = "",
    val removingLibrary: OnlineLibrary? = null,
    val changingLibrary: Boolean = false,
    val libraryActionError: OnlineBookError? = null,
) {
    val browseLibraries: List<OnlineLibrary> get() = libraries.filter { if (singleLibraryId != null) it.id == singleLibraryId else it.isSelected }
}
internal data class AddLibraryUiState(val url: String = "", val adding: Boolean = false, val error: OnlineBookError? = null)

/** Each navigation entry owns its draft, submitted search and independently cancellable sources. */
@HiltViewModel
internal class CatalogueViewModel @Inject constructor(
    private val onlineLibraries: OnlineCatalogue,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogueUiState(
        singleLibraryId = savedState["catalogue.single"],
        query = savedState["catalogue.draft"] ?: "",
        submittedQuery = savedState["catalogue.submitted"] ?: "",
        sort = CatalogueSort.entries.firstOrNull { it.name == savedState.get<String>("catalogue.sort") } ?: CatalogueSort.LIBRARY,
    ))
    val state = _state.asStateFlow()
    private val browseJobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private var libraryJob: Job? = null
    private var addJob: Job? = null
    private var saveJob: Job? = null
    private var active = false
    private val pendingLoads = mutableMapOf<String, Boolean>()

    init { observeLibraries() }

    /** Called only when a browse destination is actually displayed; management never browses. */
    fun activate(libraryId: String? = null) {
        if (active) return
        active = true
        savedState["catalogue.single"] = libraryId
        _state.update { it.copy(singleLibraryId = libraryId) }
        reconcileSources()
        val unfinished = pendingLoads.toMap()
        pendingLoads.clear()
        unfinished.forEach { (id, append) ->
            if (_state.value.sources[id]?.loading == false) loadPage(id, append)
        }
    }

    fun deactivate() {
        active = false
        val unfinished = _state.value.sources.filterValues { it.loading }
        unfinished.forEach { (id, source) ->
            pendingLoads[id] = source.nextOffset != null
            generations[id] = (generations[id] ?: 0) + 1
            browseJobs.remove(id)?.cancel()
        }
        _state.update { state -> state.copy(sources = state.sources.mapValues { (id, source) ->
            if (id in unfinished) source.copy(loading = false) else source
        }) }
    }

    fun observeLibraries() {
        libraryJob?.cancel()
        _state.update { it.copy(librariesLoading = true, librariesError = false) }
        libraryJob = viewModelScope.launch {
            onlineLibraries.observeLibraries()
                .catch { _state.update { it.copy(librariesLoading = false, librariesError = true) } }
                .collect { libraries ->
                    _state.update { it.copy(libraries = libraries, librariesLoading = false, librariesError = false) }
                    if (active) reconcileSources()
                }
        }
    }

    private fun reconcileSources() {
        val ids = _state.value.browseLibraries.map { it.id }.toSet()
        (_state.value.sources.keys - ids).forEach { id ->
            browseJobs.remove(id)?.cancel()
            generations[id] = (generations[id] ?: 0) + 1
        }
        _state.update { it.copy(sources = it.sources.filterKeys { id -> id in ids }) }
        ids.filterNot { it in _state.value.sources }.forEach { loadPage(it, false) }
    }

    fun setQuery(query: String) {
        val bounded = query.take(300)
        savedState["catalogue.draft"] = bounded
        _state.update { it.copy(query = bounded) }
    }
    fun browse() {
        val query = _state.value.query.trim()
        savedState["catalogue.submitted"] = query
        _state.update { it.copy(submittedQuery = query) }
        _state.value.browseLibraries.forEach { loadPage(it.id, false) }
    }
    fun retrySource(id: String) = loadPage(id, _state.value.sources[id]?.books?.isNotEmpty() == true)
    fun loadMore(id: String) = loadPage(id, true)
    private fun loadPage(id: String, append: Boolean) {
        if (!active) return
        val input = _state.value
        if (input.browseLibraries.none { it.id == id }) return
        val source = input.sources[id] ?: CatalogueSourceState(language = savedState["catalogue.language.$id"])
        if (append && (source.loading || source.nextOffset == null)) return
        val offset = if (append) source.nextOffset ?: return else 0
        val generation = (generations[id] ?: 0) + 1
        generations[id] = generation
        browseJobs[id]?.cancel()
        _state.update { it.copy(sources = it.sources + (id to source.copy(
            books = if (append) source.books else emptyList(), loading = true, error = null,
            nextOffset = if (append) source.nextOffset else null,
        ))) }
        browseJobs[id] = viewModelScope.launch {
            try {
                val page = onlineLibraries.browse(id, input.submittedQuery, offset)
                currentCoroutineContext().ensureActive()
                if (generations[id] != generation) return@launch
                _state.update { state ->
                    val current = state.sources[id] ?: return@update state
                    state.copy(sources = state.sources + (id to current.copy(
                        books = ((if (append) current.books else emptyList()) + page.books)
                            .distinctBy { it.libraryId to it.remoteId },
                        nextOffset = page.nextOffset?.takeIf { it > offset }, loading = false,
                    )))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generations[id] == generation) _state.update { state ->
                    val current = state.sources[id] ?: return@update state
                    state.copy(sources = state.sources + (id to current.copy(loading = false, error = error.onlineReason())))
                }
            }
        }
    }
    fun selectSort(sort: CatalogueSort) {
        savedState["catalogue.sort"] = sort.name
        _state.update { it.copy(sort = sort) }
    }
    fun selectLanguage(id: String, language: String?) {
        savedState["catalogue.language.$id"] = language
        _state.update { state -> state.copy(sources = state.sources.mapValues { (key, value) -> if (key == id) value.copy(language = language) else value }) }
    }
    fun setLibrarySelected(id: String, selected: Boolean) = libraryAction { onlineLibraries.setLibrarySelected(id, selected) }
    fun rename(library: OnlineLibrary) { _state.update { it.copy(editingLibrary = library, libraryName = library.displayName, libraryActionError = null) } }
    fun setLibraryName(name: String) { if (!_state.value.changingLibrary) _state.update { it.copy(libraryName = name.take(100)) } }
    fun confirmRename() {
        val input = _state.value
        val library = input.editingLibrary ?: return
        if (input.libraryName.isBlank()) return
        libraryAction { onlineLibraries.renameLibrary(library.id, input.libraryName.trim()); _state.update { it.copy(editingLibrary = null) } }
    }
    fun remove(library: OnlineLibrary) { _state.update { it.copy(removingLibrary = library, libraryActionError = null) } }
    fun confirmRemove() {
        val library = _state.value.removingLibrary ?: return
        libraryAction { onlineLibraries.removeLibrary(library.id); _state.update { it.copy(removingLibrary = null) } }
    }
    fun dismissLibraryAction() { if (!_state.value.changingLibrary) _state.update { it.copy(editingLibrary = null, removingLibrary = null, libraryActionError = null) } }
    private fun libraryAction(action: suspend () -> Unit) {
        if (_state.value.changingLibrary) return
        _state.update { it.copy(changingLibrary = true, libraryActionError = null) }
        viewModelScope.launch {
            try { action(); _state.update { it.copy(changingLibrary = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.update { it.copy(changingLibrary = false, libraryActionError = error.onlineReason()) } }
        }
    }
    fun openAddLibrary() { _state.update { it.copy(addLibrary = AddLibraryUiState()) } }
    fun setLibraryUrl(url: String) { _state.update { state -> state.copy(addLibrary = state.addLibrary?.takeUnless { it.adding }?.copy(url = url)) } }
    fun dismissAddLibrary() { addJob?.cancel(); _state.update { it.copy(addLibrary = null) } }
    fun addLibrary() {
        val input = _state.value.addLibrary?.takeUnless { it.adding || it.url.isBlank() } ?: return
        _state.update { it.copy(addLibrary = input.copy(adding = true, error = null)) }
        addJob = viewModelScope.launch {
            try {
                onlineLibraries.addLibrary(input.url)
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(addLibrary = null) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(addLibrary = input.copy(error = error.onlineReason())) }
            }
        }
    }
    fun inspect(book: CatalogueBook) { saveJob?.cancel(); _state.update { it.copy(details = book, saving = false, saved = null, saveError = null) } }
    fun dismissDetails() { saveJob?.cancel(); _state.update { it.copy(details = null, saving = false, saved = null, saveError = null) } }
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
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(saving = false, saveError = error.onlineReason()) }
            }
        }
    }
}
private fun Exception.onlineReason(): OnlineBookError = (this as? OnlineBookException)?.reason ?: OnlineBookError.NETWORK
