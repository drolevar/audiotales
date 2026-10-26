package io.github.drolevar.audiotales.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drolevar.audiotales.data.library.LibraryTrack
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One persistent cache shared by the single player and Media3's download service. */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class MediaStorage @Inject constructor(@ApplicationContext context: Context) {
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "downloaded-audio"), NoOpCacheEvictor(), database)
    val index = DefaultDownloadIndex(database)
    private val streamingHeaders = ConcurrentHashMap<String, PlaybackHeaders>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun upstream(download: Boolean): DataSource.Factory = ResolvingDataSource.Factory(
        DefaultHttpDataSource.Factory()
            .setUserAgent("AudioTales")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(false),
    ) { spec ->
        val key = spec.key
        val saved = key?.let { index.getDownload(it)?.request?.data }?.let(::readMetadata)
        val registered = key?.let(streamingHeaders::get)
        val referer = if (download) saved?.downloadReferer else {
            if (registered != null) registered.referer else saved?.streamReferer
        }
        if (referer.isNullOrBlank()) spec else spec.buildUpon()
            .setHttpRequestHeaders(spec.httpRequestHeaders + ("Referer" to referer)).build()
    }

    val playbackDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream(download = false))
        // Streaming reads downloads but does not create an unbounded temporary playback cache.
        .setCacheWriteDataSinkFactory(null)

    val downloadManager = DownloadManager(
        context,
        index,
        DefaultDownloaderFactory(
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream(download = true)),
            java.util.concurrent.Executor { task -> task.run() },
        ),
    ).apply {
        maxParallelDownloads = 2
        setRequirements(Requirements(Requirements.NETWORK))
    }

    fun registerPlayback(track: LibraryTrack) {
        streamingHeaders[track.downloadId] = PlaybackHeaders(track.streamReferer?.takeIf(String::isNotBlank))
    }

    /** Download the complete resource once, retaining all logical tracks in persistent metadata. */
    fun downloadRequest(tracks: List<LibraryTrack>): DownloadRequest {
        val track = tracks.first()
        require(tracks.all { it.bookId == track.bookId && it.downloadId == track.downloadId && it.uri == track.uri }) {
            "A download must contain one book's audio resource"
        }
        return DownloadRequest.Builder(track.downloadId, track.uri.toUri())
            .setMimeType("audio/mpeg")
            .setCustomCacheKey(track.downloadId)
            .setData(json.encodeToString(DownloadMetadata(track.bookId, track.streamReferer,
                track.downloadReferer, tracks.map { it.id }.distinct())).toByteArray())
            .build()
    }

    /** Old index entries have no member list and retain their original track-based identity. */
    fun states(download: Download, managerPaused: Boolean = false): Map<String, TrackDownloadState> {
        val trackIds = readMetadata(download.request.data)?.trackIds?.takeIf { it.isNotEmpty() }
            ?: listOf(download.request.id)
        val state = state(download, managerPaused)
        return trackIds.associateWith { state }
    }

    fun state(download: Download, managerPaused: Boolean = false): TrackDownloadState {
        val state = when (download.state) {
            Download.STATE_COMPLETED -> {
                // A stale index alone is not proof that the complete bytes still exist.
                if (download.contentLength > 0 && cache.isCached(download.request.customCacheKey ?: download.request.id, 0, download.contentLength)) {
                    DownloadStatus.COMPLETED
                } else DownloadStatus.FAILED
            }
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_REMOVING, Download.STATE_RESTARTING -> DownloadStatus.REMOVING
            Download.STATE_STOPPED -> DownloadStatus.PAUSED
            else -> if (managerPaused) DownloadStatus.PAUSED else DownloadStatus.QUEUED
        }
        return TrackDownloadState(state, download.bytesDownloaded, download.contentLength, download.request.id)
    }

    private fun readMetadata(bytes: ByteArray): DownloadMetadata? = try {
        json.decodeFromString<DownloadMetadata>(bytes.toString(Charsets.UTF_8))
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }

    private data class PlaybackHeaders(val referer: String?)

    @Serializable
    private data class DownloadMetadata(
        val bookId: String,
        val streamReferer: String?,
        val downloadReferer: String?,
        val trackIds: List<String> = emptyList(),
    )
}
