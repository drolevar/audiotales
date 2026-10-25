package io.github.drolevar.audiotales.ui.downloads

import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.TrackDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOverviewTest {
    private val book = LibraryCollectionEntry(
        LibraryBook("book", "A book"),
        listOf(
            LibraryTrack("a", "book", "One", "https://example.test/1.mp3", 0),
            LibraryTrack("b", "book", "Two", "https://example.test/2.mp3", 1),
        ),
        null,
    )

    @Test
    fun onlyBooksWithTheirOwnManagedRecordsAppear() {
        assertTrue(downloadOverview(listOf(book), emptyMap()).isEmpty())
        assertTrue(downloadOverview(listOf(book), mapOf("other" to state(DownloadStatus.COMPLETED))).isEmpty())
        val item = downloadOverview(listOf(book), mapOf("a" to state(DownloadStatus.COMPLETED))).single()
        assertEquals(DownloadBookStatus.PARTIAL, item.status)
        assertFalse(item.summary.complete)
        assertEquals(1, item.summary.completedTracks)
        assertNull(item.byteProgress)
    }

    @Test
    fun completeRequiresAllTracksAndRemovingTakesPrecedence() {
        val complete = mapOf("a" to state(DownloadStatus.COMPLETED), "b" to state(DownloadStatus.COMPLETED))
        assertEquals(DownloadBookStatus.COMPLETE, downloadOverview(listOf(book), complete).single().status)
        val removing = complete + ("b" to state(DownloadStatus.REMOVING))
        val item = downloadOverview(listOf(book), removing).single()
        assertEquals(DownloadBookStatus.REMOVING, item.status)
        assertFalse(item.summary.complete)
    }

    @Test
    fun mixedActiveFailedStateRetainsFailureAndRealTrackCounts() {
        val item = downloadOverview(listOf(book), mapOf(
            "a" to state(DownloadStatus.DOWNLOADING, 25, 100),
            "b" to state(DownloadStatus.FAILED, 50, 100),
        )).single()
        assertEquals(DownloadBookStatus.DOWNLOADING, item.status)
        assertEquals(1, item.failedTracks)
        assertEquals(0, item.summary.completedTracks)
        assertEquals(2, item.summary.totalTracks)
        assertEquals(0.375f, item.byteProgress!!, 0.0001f)
    }

    @Test
    fun queuePauseAndFailureAreDistinct() {
        listOf(
            DownloadStatus.QUEUED to DownloadBookStatus.QUEUED,
            DownloadStatus.PAUSED to DownloadBookStatus.PAUSED,
            DownloadStatus.FAILED to DownloadBookStatus.FAILED,
        ).forEach { (track, expected) ->
            assertEquals(expected, downloadOverview(listOf(book), mapOf("a" to state(track))).single().status)
        }
    }

    @Test
    fun unknownMissingAndZeroByteTotalsNeverGenerateAPercentage() {
        assertNull(knownByteProgress(listOf("a", "b"), mapOf("a" to state(DownloadStatus.DOWNLOADING, 50, 100))))
        for (unknown in listOf(-1L, 0L)) {
            assertNull(knownByteProgress(listOf("a", "b"), mapOf(
                "a" to state(DownloadStatus.COMPLETED, 100, 100),
                "b" to state(DownloadStatus.DOWNLOADING, 50, unknown),
            )))
        }
        assertNull(knownByteProgress(emptyList(), emptyMap()))
    }

    @Test
    fun bytesUseWeightedTotalsAndIgnoreUnrelatedDownloads() {
        val progress = knownByteProgress(listOf("a", "b"), mapOf(
            "a" to state(DownloadStatus.COMPLETED, 100, 100),
            "b" to state(DownloadStatus.DOWNLOADING, 50, 300),
            "other" to state(DownloadStatus.DOWNLOADING, 900, 1_000),
        ))
        assertEquals(0.375f, progress!!, 0.0001f)
    }

    @Test
    fun invalidByteCountersAreBoundedAndOverflowIsUnknown() {
        assertEquals(1f, knownByteProgress(listOf("a"), mapOf("a" to state(DownloadStatus.DOWNLOADING, 200, 100)))!!, 0f)
        assertEquals(0f, knownByteProgress(listOf("a"), mapOf("a" to state(DownloadStatus.DOWNLOADING, -1, 100)))!!, 0f)
        assertNull(knownByteProgress(listOf("a", "b"), mapOf(
            "a" to state(DownloadStatus.DOWNLOADING, 0, Long.MAX_VALUE),
            "b" to state(DownloadStatus.DOWNLOADING, 0, 1),
        )))
    }

    private fun state(status: DownloadStatus, bytes: Long = 0, total: Long = -1) = TrackDownloadState(status, bytes, total)
}
