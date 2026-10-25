package io.github.drolevar.audiotales.ui.library

import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.playback.BookDownloadSummary
import io.github.drolevar.audiotales.playback.TrackDownloadState
import io.github.drolevar.audiotales.playback.summarizeBookDownloads
import java.util.Locale

enum class CollectionFilter { ALL, IN_PROGRESS, DOWNLOADED }
enum class CollectionSort { RECENT, TITLE, AUTHOR }
enum class CollectionDownloadAction { DOWNLOAD, PAUSE, RESUME, RETRY, VIEW }

data class CollectionListeningStatus(
    val started: Boolean = false,
    val complete: Boolean = false,
    val percentage: Int? = null,
    val trackNumber: Int = 0,
    val trackCount: Int = 0,
    val positionMs: Long = 0,
)

data class CollectionRow(
    val entry: LibraryCollectionEntry,
    val listening: CollectionListeningStatus,
    val download: BookDownloadSummary,
) {
    val downloadAction: CollectionDownloadAction get() = when {
        download.complete -> CollectionDownloadAction.VIEW
        download.active -> CollectionDownloadAction.PAUSE
        download.hasFailures -> CollectionDownloadAction.RETRY
        download.hasDownloads -> CollectionDownloadAction.RESUME
        else -> CollectionDownloadAction.DOWNLOAD
    }
}

fun collectionListeningStatus(entry: LibraryCollectionEntry): CollectionListeningStatus {
    val tracks = entry.tracks.sortedWith(compareBy({ it.sequenceIndex }, { it.id }))
    val progress = entry.progress ?: return CollectionListeningStatus(trackCount = tracks.size)
    val index = tracks.indexOfFirst { it.id == progress.trackId }
    if (index < 0) return CollectionListeningStatus(trackCount = tracks.size)
    val position = progress.positionMs.coerceAtLeast(0)
    val duration = tracks[index].durationMs
    val completed = index == tracks.lastIndex && duration != null && duration > 0 && position >= duration
    // A percentage is meaningful only with a known, positive total duration.
    val durationsKnown = tracks.all { it.durationMs != null && it.durationMs >= 0 }
    val total = if (durationsKnown) tracks.sumOf { it.durationMs!!.toDouble() } else 0.0
    val played = if (durationsKnown) tracks.take(index).sumOf { it.durationMs!!.toDouble() } +
        position.coerceAtMost(duration ?: 0).toDouble() else 0.0
    return CollectionListeningStatus(
        started = index > 0 || position > 0,
        complete = completed,
        percentage = if (total > 0) ((played / total) * 100).toInt().coerceIn(0, 100) else null,
        trackNumber = index + 1,
        trackCount = tracks.size,
        positionMs = position,
    )
}

fun collectionRows(
    entries: List<LibraryCollectionEntry>,
    downloads: Map<String, TrackDownloadState>,
    filter: CollectionFilter,
    sort: CollectionSort,
): List<CollectionRow> {
    val rows = entries.map { entry ->
        CollectionRow(entry, collectionListeningStatus(entry), summarizeBookDownloads(entry.tracks.map { it.id }, downloads))
    }.filter { row ->
        when (filter) {
            CollectionFilter.ALL -> true
            CollectionFilter.IN_PROGRESS -> row.listening.started && !row.listening.complete
            CollectionFilter.DOWNLOADED -> row.download.complete
        }
    }
    val titleOrder = compareBy<CollectionRow> { it.entry.book.title.lowercase(Locale.ROOT) }
        .thenBy { it.entry.book.title }.thenBy { it.entry.book.id }
    val order = when (sort) {
        CollectionSort.RECENT -> compareByDescending<CollectionRow> { it.entry.progress?.updatedAtEpochMs ?: Long.MIN_VALUE }
            .then(titleOrder)
        CollectionSort.TITLE -> titleOrder
        CollectionSort.AUTHOR -> compareBy<CollectionRow> { it.entry.book.author.isNullOrBlank() }
            .thenBy { it.entry.book.author.orEmpty().lowercase(Locale.ROOT) }.then(titleOrder)
    }
    return rows.sortedWith(order)
}
