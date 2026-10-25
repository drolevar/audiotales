package io.github.drolevar.audiotales.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSummaryTest {
    @Test
    fun offlineRequiresEveryRequiredTrackAndANonemptyBook() {
        val complete = TrackDownloadState(DownloadStatus.COMPLETED)
        assertFalse(summarizeBookDownloads(emptyList(), emptyMap()).complete)
        assertFalse(summarizeBookDownloads(listOf("a", "b"), mapOf("a" to complete)).complete)
        assertTrue(summarizeBookDownloads(listOf("a", "b"), mapOf("a" to complete, "b" to complete)).complete)
    }

    @Test
    fun queuedAndRunningCountsIncludeOnlyTheRequiredBook() {
        val summary = summarizeBookDownloads(listOf("a", "b", "c"), mapOf(
            "a" to TrackDownloadState(DownloadStatus.QUEUED),
            "b" to TrackDownloadState(DownloadStatus.DOWNLOADING),
            "c" to TrackDownloadState(DownloadStatus.DOWNLOADING),
            "other" to TrackDownloadState(DownloadStatus.QUEUED),
        ))
        assertEquals(1, summary.queuedTracks)
        assertEquals(2, summary.downloadingTracks)
        assertEquals(0, summary.pausedTracks)
        assertTrue(summary.active)
    }

    @Test
    fun missingFailedPausedAndRemovingTracksRemainHonestPartialStates() {
        val summary = summarizeBookDownloads(listOf("a", "b", "c", "d"), mapOf(
            "a" to TrackDownloadState(DownloadStatus.COMPLETED),
            "b" to TrackDownloadState(DownloadStatus.FAILED),
            "c" to TrackDownloadState(DownloadStatus.PAUSED),
            "d" to TrackDownloadState(DownloadStatus.REMOVING),
            "unrelated" to TrackDownloadState(DownloadStatus.COMPLETED),
        ))
        assertFalse(summary.complete)
        assertEquals(1, summary.completedTracks)
        assertEquals(4, summary.totalTracks)
        assertTrue(summary.hasDownloads)
        assertTrue(summary.hasFailures)
        assertTrue(summary.removing)
        assertEquals(1, summary.pausedTracks)
        assertEquals(0, summary.queuedTracks)
        assertEquals(0, summary.downloadingTracks)
    }
}
