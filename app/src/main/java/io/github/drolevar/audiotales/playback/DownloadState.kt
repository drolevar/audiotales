package io.github.drolevar.audiotales.playback

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, REMOVING }

data class TrackDownloadState(
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    /** Shared physical download identity; null keeps legacy per-track callers compatible. */
    val resourceId: String? = null,
)

data class BookDownloadSummary(
    val totalTracks: Int,
    val completedTracks: Int,
    val hasDownloads: Boolean,
    val active: Boolean,
    val hasFailures: Boolean,
    val removing: Boolean,
    val queuedTracks: Int,
    val downloadingTracks: Int,
    val pausedTracks: Int,
) {
    val complete: Boolean get() = totalTracks > 0 && completedTracks == totalTracks
}

fun summarizeBookDownloads(
    requiredTrackIds: List<String>,
    downloads: Map<String, TrackDownloadState>,
): BookDownloadSummary {
    val relevant = requiredTrackIds.mapNotNull(downloads::get)
    return BookDownloadSummary(
        totalTracks = requiredTrackIds.size,
        completedTracks = relevant.count { it.status == DownloadStatus.COMPLETED },
        hasDownloads = relevant.isNotEmpty(),
        active = relevant.any { it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) },
        hasFailures = relevant.any { it.status == DownloadStatus.FAILED },
        removing = relevant.any { it.status == DownloadStatus.REMOVING },
        queuedTracks = relevant.count { it.status == DownloadStatus.QUEUED },
        downloadingTracks = relevant.count { it.status == DownloadStatus.DOWNLOADING },
        pausedTracks = relevant.count { it.status == DownloadStatus.PAUSED },
    )
}
