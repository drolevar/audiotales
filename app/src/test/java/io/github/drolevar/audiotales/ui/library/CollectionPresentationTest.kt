package io.github.drolevar.audiotales.ui.library

import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.TrackDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPresentationTest {
    @Test
    fun recentUsesLastListenedTimestampThenDeterministicTitleFallback() {
        val entries = listOf(entry("z", "Zulu"), entry("b", "Beta", updated = 10), entry("a", "Alpha"), entry("c", "Charlie", updated = 20))
        assertEquals(listOf("c", "b", "a", "z"), rows(entries).map { it.entry.book.id })
    }

    @Test
    fun titleAndAuthorSortUseDeterministicTiesAndPutUnknownAuthorsLast() {
        val entries = listOf(entry("z", "Zulu", author = "Anderson"), entry("b", "beta", author = "smith"), entry("a", "Alpha", author = "Smith"), entry("c", "Charlie"))
        assertEquals(listOf("a", "b", "c", "z"), rows(entries, sort = CollectionSort.TITLE).map { it.entry.book.id })
        assertEquals(listOf("z", "a", "b", "c"), rows(entries, sort = CollectionSort.AUTHOR).map { it.entry.book.id })
    }

    @Test
    fun percentageAccountsForPriorTrackDurationsAndClampsCurrentPosition() {
        val original = entry("a", "Alpha", durations = listOf(10_000, 30_000), position = 10_000, trackIndex = 1)
        val status = collectionListeningStatus(original)
        assertEquals(50, status.percentage)
        assertEquals(2, status.trackNumber)
        assertTrue(status.started)
        assertFalse(status.complete)
        assertEquals(100, collectionListeningStatus(original.copy(progress = original.progress!!.copy(positionMs = 99_000))).percentage)
    }

    @Test
    fun unknownDurationKeepsTrackAndPositionWithoutInventingPercentageOrCompletion() {
        val status = collectionListeningStatus(entry("a", "Alpha", durations = listOf(10_000, null), position = 99_000, trackIndex = 1))
        assertNull(status.percentage)
        assertTrue(status.started)
        assertFalse(status.complete)
        assertEquals(2, status.trackNumber)
        assertEquals(2, status.trackCount)
        assertEquals(99_000, status.positionMs)
    }

    @Test
    fun inProgressExcludesUnplayedAndKnownCompletedFinalTrack() {
        val entries = listOf(
            entry("unplayed", "Unplayed"),
            entry("started", "Started", durations = listOf(100L), position = 1),
            entry("complete", "Complete", durations = listOf(100L), position = 100),
            entry("unknown", "Unknown", durations = listOf(null), position = 100),
            entry("previous", "Previous", durations = listOf(100L, 100L), position = 100),
        )
        assertEquals(setOf("started", "unknown", "previous"), rows(entries, filter = CollectionFilter.IN_PROGRESS).map { it.entry.book.id }.toSet())
    }

    @Test
    fun downloadedRequiresEveryTrackAndNoTracksIsNeverOffline() {
        val complete = entry("complete", "Complete", durations = listOf(100L, 100L))
        val partial = entry("partial", "Partial", durations = listOf(100L, 100L))
        val empty = entry("empty", "Empty", durations = emptyList())
        val downloads = mapOf(
            "complete-0" to TrackDownloadState(DownloadStatus.COMPLETED),
            "complete-1" to TrackDownloadState(DownloadStatus.COMPLETED),
            "partial-0" to TrackDownloadState(DownloadStatus.COMPLETED),
            "partial-1" to TrackDownloadState(DownloadStatus.PAUSED),
        )
        assertEquals(listOf("complete"), collectionRows(listOf(complete, partial, empty), downloads, CollectionFilter.DOWNLOADED, CollectionSort.TITLE).map { it.entry.book.id })
    }

    @Test
    fun downloadActionUsesLiveStatusAndIgnoresUnrelatedDownloads() {
        val book = entry("a", "Alpha", durations = listOf(100L))
        fun action(status: DownloadStatus?) = collectionRows(
            listOf(book),
            buildMap {
                put("other", TrackDownloadState(DownloadStatus.DOWNLOADING))
                status?.let { put("a-0", TrackDownloadState(it)) }
            }, CollectionFilter.ALL, CollectionSort.RECENT,
        ).single().downloadAction
        assertEquals(CollectionDownloadAction.DOWNLOAD, action(null))
        assertEquals(CollectionDownloadAction.PAUSE, action(DownloadStatus.QUEUED))
        assertEquals(CollectionDownloadAction.PAUSE, action(DownloadStatus.DOWNLOADING))
        assertEquals(CollectionDownloadAction.RESUME, action(DownloadStatus.PAUSED))
        assertEquals(CollectionDownloadAction.RETRY, action(DownloadStatus.FAILED))
        assertEquals(CollectionDownloadAction.VIEW, action(DownloadStatus.COMPLETED))
    }

    @Test
    fun invalidSavedTrackDoesNotShowInventedListeningProgress() {
        val original = entry("a", "Alpha", position = 10)
        val status = collectionListeningStatus(original.copy(progress = original.progress!!.copy(trackId = "missing")))
        assertFalse(status.started)
        assertFalse(status.complete)
        assertNull(status.percentage)
    }

    @Test
    fun zeroTotalDurationDoesNotCreatePercentageOrCompletion() {
        val status = collectionListeningStatus(entry("a", "Alpha", durations = listOf(0L), position = 1))
        assertNull(status.percentage)
        assertFalse(status.complete)
    }

    private fun rows(entries: List<LibraryCollectionEntry>, filter: CollectionFilter = CollectionFilter.ALL, sort: CollectionSort = CollectionSort.RECENT) =
        collectionRows(entries, emptyMap(), filter, sort)

    private fun entry(
        id: String,
        title: String,
        author: String? = null,
        updated: Long? = null,
        durations: List<Long?> = listOf(null),
        position: Long? = null,
        trackIndex: Int = 0,
    ): LibraryCollectionEntry = LibraryCollectionEntry(
        book = LibraryBook(id, title, author),
        tracks = durations.mapIndexed { index, duration -> LibraryTrack("$id-$index", id, "Track ${index + 1}", "https://example.org/$id/$index", index, duration) },
        progress = if (updated == null && position == null) null else ListeningProgress(id, "$id-$trackIndex", position ?: 0, updated ?: 0),
    )
}
