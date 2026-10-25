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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private var initialized = false
    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            _state.update { it + (download.request.id to storage.state(download, manager.downloadsPaused)) }
        }
        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
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

    suspend fun downloadBook(bookId: String) {
        initialize()
        val tracks = library.getTracks(bookId)
        require(tracks.isNotEmpty()) { "Book has no tracks" }
        withContext(Dispatchers.Main.immediate) {
            tracks.forEach { track ->
                if (_state.value[track.id]?.status != DownloadStatus.COMPLETED) {
                    DownloadService.sendAddDownload(context, AudioDownloadService::class.java, storage.downloadRequest(track), true)
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

    suspend fun pauseBook(bookId: String) {
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

    suspend fun removeBook(bookId: String) {
        initialize()
        val tracks = library.getTracks(bookId)
        withContext(Dispatchers.Main.immediate) {
            tracks.filter { it.id in _state.value }.forEach {
                DownloadService.sendRemoveDownload(context, AudioDownloadService::class.java, it.id, true)
            }
        }
    }
}
