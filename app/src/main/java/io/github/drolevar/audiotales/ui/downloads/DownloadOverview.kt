package io.github.drolevar.audiotales.ui.downloads

import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.playback.BookDownloadSummary
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.TrackDownloadState
import io.github.drolevar.audiotales.playback.summarizeBookDownloads

internal enum class DownloadBookStatus { COMPLETE, DOWNLOADING, QUEUED, PAUSED, FAILED, REMOVING, PARTIAL }

internal data class DownloadBookItem(
    val entry: LibraryCollectionEntry,
    val summary: BookDownloadSummary,
    val status: DownloadBookStatus,
    val tracks: Map<String, TrackDownloadState>,
    val byteProgress: Float?,
    val failedTracks: Int,
)

/** Missing records and unknown lengths never become an invented download percentage. */
internal fun downloadOverview(
    collection: List<LibraryCollectionEntry>,
    downloads: Map<String, TrackDownloadState>,
): List<DownloadBookItem> = collection.mapNotNull { entry ->
    val ids = entry.tracks.map { it.id }
    val states = ids.mapNotNull { id -> downloads[id]?.let { id to it } }.toMap()
    val summary = summarizeBookDownloads(ids, states)
    if (!summary.hasDownloads) return@mapNotNull null
    val status = when {
        summary.removing -> DownloadBookStatus.REMOVING
        summary.complete -> DownloadBookStatus.COMPLETE
        summary.downloadingTracks > 0 -> DownloadBookStatus.DOWNLOADING
        summary.queuedTracks > 0 -> DownloadBookStatus.QUEUED
        summary.hasFailures -> DownloadBookStatus.FAILED
        summary.pausedTracks > 0 -> DownloadBookStatus.PAUSED
        else -> DownloadBookStatus.PARTIAL
    }
    DownloadBookItem(entry, summary, status, states, knownByteProgress(ids, states),
        states.values.count { it.status == DownloadStatus.FAILED })
}

internal fun knownByteProgress(ids: List<String>, states: Map<String, TrackDownloadState>): Float? {
    if (ids.isEmpty()) return null
    var total = 0L
    var downloaded = 0L
    val resources = mutableSetOf<String>()
    for (id in ids) {
        val state = states[id] ?: return null
        if (state.totalBytes <= 0) return null
        if (!resources.add(state.resourceId ?: id)) continue
        if (Long.MAX_VALUE - total < state.totalBytes) return null
        total += state.totalBytes
        downloaded += state.bytesDownloaded.coerceIn(0, state.totalBytes)
    }
    return (downloaded.toDouble() / total).toFloat()
}
