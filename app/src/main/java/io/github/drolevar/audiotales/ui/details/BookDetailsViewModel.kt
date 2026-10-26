package io.github.drolevar.audiotales.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.library.*
import io.github.drolevar.audiotales.data.online.OnlineBookDetailsSource
import io.github.drolevar.audiotales.playback.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only stable application/provider identities are retained by navigation. */
internal data class BookDetailsTarget(val bookId: String? = null, val libraryId: String? = null, val remoteId: String? = null)
internal enum class DetailsActionError { SAVE, PLAYBACK, DOWNLOAD }
internal data class BookDetailsUiState(
    val book: LibraryBook? = null,
    val tracks: List<LibraryTrack> = emptyList(),
    val progress: ListeningProgress? = null,
    val saved: Boolean = false,
    val audioLoading: Boolean = false,
    val audioFailed: Boolean = false,
    val sourceName: String? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val collectionFailed: Boolean = false,
    val downloadsReady: Boolean = false,
    val downloadsFailed: Boolean = false,
    val refreshing: Boolean = false,
    val metadataFailed: Boolean = false,
    val busy: Boolean = false,
    val actionError: DetailsActionError? = null,
    val downloads: Map<String, TrackDownloadState> = emptyMap(),
) {
    val audioReady get() = !audioLoading && !audioFailed && tracks.isNotEmpty()
    val download get() = summarizeBookDownloads(tracks.map { it.id }, downloads)
}

/** Small seam around the application download manager, so action/lifecycle tests need no Android service. */
internal interface DetailsDownloads {
    val state: StateFlow<Map<String, TrackDownloadState>>
    suspend fun initialize()
    suspend fun refresh()
    suspend fun download(bookId: String)
    suspend fun pause(bookId: String)
    suspend fun remove(bookId: String)
}
private class ManagedDetailsDownloads(private val delegate: BookDownloads) : DetailsDownloads {
    override val state = delegate.state
    override suspend fun initialize() = delegate.initialize()
    override suspend fun refresh() = delegate.refreshProgress()
    override suspend fun download(bookId: String) = delegate.downloadBook(bookId)
    override suspend fun pause(bookId: String) = delegate.pauseBook(bookId)
    override suspend fun remove(bookId: String) = delegate.removeBook(bookId)
}

@HiltViewModel
internal class BookDetailsViewModel internal constructor(
    private val library: LibraryRepository,
    private val online: OnlineBookDetailsSource,
    private val downloads: DetailsDownloads,
) : ViewModel() {
    @Inject constructor(library: LibraryRepository, online: OnlineBookDetailsSource, downloads: BookDownloads) :
        this(library, online, ManagedDetailsDownloads(downloads))

    private val _state = MutableStateFlow(BookDetailsUiState())
    val state = _state.asStateFlow()
    private var target: BookDetailsTarget? = null
    private var generation = 0L
    private var visible: Job? = null
    private var metadata: Job? = null
    private var audio: Job? = null
    private var downloadObservation: Job? = null
    private var action: Job? = null
    private var active = false

    fun activate(input: BookDetailsTarget) {
        if (active && target == input) return
        val changed = target != input
        deactivate()
        target = input
        if (changed) _state.value = BookDetailsUiState()
        if (_state.value.book == null) _state.update { it.copy(loading = true, loadFailed = false) }
        active = true
        val token = generation
        visible = viewModelScope.launch {
            try {
                // Render saved state before any optional network or download initialization.
                val collection = library.observeCollection().first()
                ensureCurrent(token)
                reconcileCollection(collection)
                if (_state.value.book == null) startMetadata(initial = true)
                else if (!_state.value.audioReady && !_state.value.audioFailed) retryAudio()
                supervisorScope {
                    launch {
                        try {
                            library.observeCollection().collect { entries ->
                                ensureCurrent(token)
                                reconcileCollection(entries)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { ensureCurrent(token); _state.update { it.copy(collectionFailed = true) } }
                    }
                    startDownloadObservation(token)
                    launch {
                        val id = _state.value.book?.providerId ?: input.libraryId
                        if (id != null) try {
                            val source = online.getLibrary(id)
                            ensureCurrent(token)
                            _state.update { it.copy(sourceName = source.displayName) }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { /* Saved playback does not depend on discovery configuration. */ }
                    }
                    if (_state.value.saved && _state.value.book?.description == null) refreshDetails()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                ensureCurrent(token)
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun deactivate() {
        active = false
        generation++
        visible?.cancel(); visible = null
        metadata?.cancel(); metadata = null
        audio?.cancel(); audio = null
        downloadObservation?.cancel(); downloadObservation = null
        action?.cancel(); action = null
        _state.update { it.copy(busy = false, refreshing = false, audioLoading = false) }
    }

    fun retry() { val input = target ?: return; deactivate(); activate(input) }

    fun retryAudio() {
        val book = _state.value.book ?: return
        if (!active || _state.value.saved || audio?.isActive == true || providerTarget() == null) return
        val token = generation
        _state.update { it.copy(audioLoading = true, audioFailed = false) }
        audio = viewModelScope.launch {
            try {
                val resolved = online.inspectBookAudio(book)
                ensureCurrent(token)
                val canonical = reconcileCollection(library.observeCollection().first())
                if (canonical != null) return@launch
                ensureCurrent(token)
                check(resolved.tracks.isNotEmpty())
                // Audio resolution owns only tracks; a newer metadata response must remain visible.
                _state.update { it.copy(tracks = resolved.tracks.sortedBy { track -> track.sequenceIndex }, audioLoading = false, audioFailed = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { ensureCurrent(token); _state.update { it.copy(audioLoading = false, audioFailed = true) } }
        }
    }

    fun refreshDetails() = startMetadata(initial = _state.value.book == null)

    private fun startMetadata(initial: Boolean) {
        if (!active || metadata?.isActive == true) return
        val token = generation
        _state.update { if (initial) it.copy(loading = true, loadFailed = false) else it.copy(refreshing = true, metadataFailed = false) }
        metadata = viewModelScope.launch {
            try {
                val input = _state.value
                val book = input.book
                if (input.saved && book != null) {
                    val refreshed = online.refreshBookMetadata(book.id)
                    ensureCurrent(token)
                    val canonical = reconcileCollection(library.observeCollection().first())
                    ensureCurrent(token)
                    if (canonical?.book?.id == book.id && refreshed?.id == book.id) _state.update { it.copy(book = refreshed) }
                } else {
                    val request = requireNotNull(providerTarget())
                    val refreshed = online.inspectBookMetadata(requireNotNull(request.libraryId), requireNotNull(request.remoteId))
                    ensureCurrent(token)
                    val canonical = reconcileCollection(library.observeCollection().first())
                    ensureCurrent(token)
                    if (canonical == null) {
                        _state.update { it.copy(book = refreshed, loading = false, loadFailed = false) }
                        if (initial) retryAudio()
                    }
                }
                _state.update { it.copy(refreshing = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                ensureCurrent(token)
                _state.update { if (initial && it.book == null) it.copy(loading = false, loadFailed = true)
                    else it.copy(refreshing = false, metadataFailed = true) }
            }
        }
    }

    fun save() = perform(DetailsActionError.SAVE) { ensureSaved() }

    /** Playback callback runs only while this explicit action and destination remain active. */
    fun listen(trackIndex: Int? = null, play: (String, String?) -> Boolean, onOpened: (String) -> Unit) {
        val selected = trackIndex?.let { _state.value.tracks.getOrNull(it) ?: return }
        perform(DetailsActionError.PLAYBACK) {
            val entry = ensureSaved()
            currentCoroutineContext().ensureActive()
            val track = selected?.let { original ->
                entry.tracks.firstOrNull { it.id == original.id } ?: original.providerTrackRef?.let { reference ->
                    entry.tracks.singleOrNull { it.providerTrackRef == reference }
                } ?: error("Selected track unavailable")
            }
            check(play(entry.book.id, track?.id))
            onOpened(entry.book.id)
        }
    }

    fun downloadAction(onDownloads: () -> Unit) = perform(DetailsActionError.DOWNLOAD) {
        val entry = ensureSaved()
        downloads.initialize()
        downloads.refresh()
        currentCoroutineContext().ensureActive()
        _state.update { it.copy(downloads = downloads.state.value, downloadsReady = true, downloadsFailed = false) }
        startDownloadObservation(generation)
        val summary = summarizeBookDownloads(entry.tracks.map { it.id }, downloads.state.value)
        when {
            summary.complete -> onDownloads()
            summary.active -> downloads.pause(entry.book.id)
            summary.removing -> Unit
            else -> {
                if (entry.book.providerId != null) online.refreshBookSources(entry.book.id)
                currentCoroutineContext().ensureActive()
                downloads.download(entry.book.id)
            }
        }
    }
    fun removeDownloads() = perform(DetailsActionError.DOWNLOAD) {
        val book = _state.value.book?.takeIf { _state.value.saved } ?: return@perform
        downloads.remove(book.id)
    }
    fun dismissError() { _state.update { it.copy(actionError = null) } }

    private fun startDownloadObservation(token: Long) {
        if (!active || downloadObservation?.isActive == true) return
        downloadObservation = viewModelScope.launch {
            try {
                downloads.initialize()
                ensureCurrent(token)
                _state.update { it.copy(downloads = downloads.state.value, downloadsReady = true, downloadsFailed = false) }
                coroutineScope {
                    launch { downloads.state.collect { states -> ensureCurrent(token); _state.update { it.copy(downloads = states) } } }
                    while (isActive) { downloads.refresh(); delay(1_000) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { ensureCurrent(token); _state.update { it.copy(actionError = DetailsActionError.DOWNLOAD, downloadsFailed = true) } }
        }
    }

    private fun perform(error: DetailsActionError, block: suspend () -> Unit) {
        if (!active || _state.value.busy || _state.value.book == null || !_state.value.audioReady) return
        val token = generation
        _state.update { it.copy(busy = true, actionError = null) }
        action = viewModelScope.launch {
            try { block(); ensureCurrent(token); _state.update { it.copy(busy = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { ensureCurrent(token); _state.update { it.copy(busy = false, actionError = error) } }
        }
    }

    private suspend fun ensureSaved(): LibraryCollectionEntry {
        val token = generation
        val collection = library.observeCollection().first()
        ensureCurrent(token)
        val existing = reconcileCollection(collection)
        ensureCurrent(token)
        if (existing != null) return existing
        val input = requireNotNull(providerTarget())
        online.saveBook(requireNotNull(input.libraryId), requireNotNull(input.remoteId))
        ensureCurrent(token)
        // The save response may predate removal. Only current collection membership is authoritative.
        val savedCollection = library.observeCollection().first()
        ensureCurrent(token)
        val entry = requireNotNull(reconcileCollection(savedCollection))
        ensureCurrent(token)
        return entry
    }
    private fun providerTarget(): BookDetailsTarget? {
        val input = target ?: return null
        val book = _state.value.book
        return BookDetailsTarget(
            libraryId = input.libraryId ?: book?.providerId ?: return null,
            remoteId = input.remoteId ?: book?.providerBookId ?: return null,
        )
    }
    private fun reconcileCollection(entries: List<LibraryCollectionEntry>): LibraryCollectionEntry? {
        val saved = findSaved(entries)
        if (saved != null) publish(saved)
        else if (_state.value.saved) {
            // Full removal invalidates listening history; provider metadata may remain for inspection.
            val canResolve = providerTarget() != null
            metadata?.cancel(); metadata = null
            audio?.cancel(); audio = null
            action?.cancel(); action = null
            _state.update { it.copy(
                book = it.book.takeIf { canResolve }, tracks = if (canResolve) it.tracks else emptyList(),
                progress = null, saved = false, loading = false, loadFailed = !canResolve, busy = false,
                collectionFailed = false, refreshing = false, metadataFailed = false, audioLoading = false, audioFailed = false,
            ) }
        }
        return saved
    }
    private fun findSaved(entries: List<LibraryCollectionEntry>): LibraryCollectionEntry? {
        val input = target ?: return null
        val book = _state.value.book
        return entries.firstOrNull { entry ->
            entry.book.id == (if (_state.value.saved) book?.id else input.bookId) ||
                (entry.book.providerId != null && entry.book.providerId == (input.libraryId ?: book?.providerId) &&
                    entry.book.providerBookId == (input.remoteId ?: book?.providerBookId))
        }
    }
    private fun publish(entry: LibraryCollectionEntry) {
        audio?.cancel(); audio = null
        _state.update { it.copy(book = entry.book, tracks = entry.tracks.sortedBy { track -> track.sequenceIndex }, progress = entry.progress,
            saved = true, loading = false, loadFailed = false, collectionFailed = false, audioLoading = false, audioFailed = false) }
    }
    private suspend fun ensureCurrent(token: Long) { currentCoroutineContext().ensureActive(); check(active && generation == token) }
}
