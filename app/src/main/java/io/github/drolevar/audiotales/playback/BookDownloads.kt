package io.github.drolevar.audiotales.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drolevar.audiotales.data.library.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class BookDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val storage: MediaStorage,
) {
    private val _state = MutableStateFlow<Map<String, TrackDownloadState>>(emptyMap())
    val state = _state.asStateFlow()
    private val initialization = Mutex()
    private val mutations = Mutex()
    // Accessed on Main, including DownloadManager callbacks. A service intent may not yet have
    // reached the persistent index when full-book removal begins.
    private val pendingAdditions = mutableSetOf<String>()
    private var initialized = false
    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            pendingAdditions.remove(download.request.id)
            _state.update { it + (download.request.id to storage.state(download, manager.downloadsPaused)) }
        }
        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
            pendingAdditions.remove(download.request.id)
            _state.update { it - download.request.id }
        }
    }

    /** Explicitly loaded by the UI owner; completed records survive process restarts. */
    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        val managerPaused = withContext(Dispatchers.Main.immediate) {
            storage.downloadManager.addListener(listener)
            storage.downloadManager.downloadsPaused
        }
        val saved = withContext(Dispatchers.IO) {
            buildMap {
                storage.index.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) put(cursor.download.request.id, storage.state(cursor.download, managerPaused))
                }
            }
        }
        _state.update { saved + it }
        initialized = true
    }

    suspend fun downloadBook(bookId: String) = mutations.withLock {
        initialize()
        val tracks = library.getTracks(bookId)
        require(tracks.isNotEmpty()) { "Book has no tracks" }
        withContext(Dispatchers.Main.immediate) {
            tracks.forEach { track ->
                if (_state.value[track.id]?.status != DownloadStatus.COMPLETED) {
                    pendingAdditions.add(track.id)
                    try {
                        DownloadService.sendAddDownload(context, AudioDownloadService::class.java, storage.downloadRequest(track), true)
                    } catch (error: RuntimeException) {
                        pendingAdditions.remove(track.id)
                        throw error
                    }
                }
            }
        }
    }

    suspend fun retryBook(bookId: String) = downloadBook(bookId)

    suspend fun refreshProgress() = withContext(Dispatchers.Main.immediate) {
        storage.downloadManager.currentDownloads.forEach { download ->
            _state.update { it + (download.request.id to storage.state(download, storage.downloadManager.downloadsPaused)) }
        }
    }

    suspend fun pauseBook(bookId: String) = mutations.withLock {
        initialize()
        val tracks = library.getTracks(bookId)
        withContext(Dispatchers.Main.immediate) {
            tracks.filter {
                _state.value[it.id]?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
            }.forEach {
                DownloadService.sendSetStopReason(context, AudioDownloadService::class.java, it.id, 1, true)
            }
        }
    }

    suspend fun removeBook(bookId: String) = mutations.withLock {
        initialize()
        val tracks = library.getTracks(bookId)
        withContext(Dispatchers.Main.immediate) {
            tracks.filter { it.id in _state.value }.forEach {
                DownloadService.sendRemoveDownload(context, AudioDownloadService::class.java, it.id, true)
            }
        }
    }

    /**
     * Full-book deletion only: call after stopping playback, passing [LibraryRepository.deleteBook]
     * as [afterRemoval] so queued download actions cannot read tracks between cleanup and deletion.
     * Unlike download-only [removeBook], success confirms persistent records and cached bytes are
     * gone. Failure or cancellation leaves the saved book and track identities available for retry;
     * any removal already handed to Media3 continues independently of this caller.
     */
    suspend fun removeBookAndAwait(bookId: String, afterRemoval: suspend () -> Unit = {}) = mutations.withLock {
        val removed = withTimeoutOrNull(REMOVAL_TIMEOUT_MS) {
            initialize()
            val trackIds = library.getTracks(bookId).map { it.id }.toSet()
            if (trackIds.isEmpty()) return@withTimeoutOrNull true

            // Let prior add intents reach Media3 before deciding which tracks have downloads.
            while (!withContext(Dispatchers.Main.immediate) {
                storage.downloadManager.isInitialized && pendingAdditions.none { it in trackIds }
            }) delay(REMOVAL_POLL_MS)

            val indexedRequests = withContext(Dispatchers.IO) {
                trackIds.mapNotNull { storage.index.getDownload(it)?.request }
            }
            val requests = withContext(Dispatchers.Main.immediate) {
                (indexedRequests + storage.downloadManager.currentDownloads
                    .filter { it.request.id in trackIds }.map { it.request }).distinctBy { it.id }
            }
            withContext(Dispatchers.Main.immediate) {
                requests.forEach { request ->
                    DownloadService.sendRemoveDownload(context, AudioDownloadService::class.java, request.id, true)
                }
            }
            while (true) {
                val recordsGone = withContext(Dispatchers.IO) {
                    trackIds.all { storage.index.getDownload(it) == null }
                }
                val tasksGone = withContext(Dispatchers.Main.immediate) {
                    storage.downloadManager.currentDownloads.none { it.request.id in trackIds }
                }
                if (recordsGone && tasksGone) break
                delay(REMOVAL_POLL_MS)
            }
            withContext(Dispatchers.IO) {
                // Retry also cleans orphan spans if Media3 removed a record but cache cleanup failed.
                val cacheKeys = trackIds + requests.map { it.customCacheKey ?: it.id }
                cacheKeys.forEach { storage.cache.removeResource(it) }
                check(cacheKeys.all { storage.cache.getCachedSpans(it).isEmpty() }) {
                    "Could not remove all downloaded audio; try removing the book again"
                }
            }
            _state.update { it - trackIds }
            true
        }
        check(removed == true) { "Download removal timed out; try removing the book again" }
        afterRemoval()
    }

    private companion object {
        const val REMOVAL_TIMEOUT_MS = 30_000L
        const val REMOVAL_POLL_MS = 100L
    }
}
