package io.github.drolevar.audiotales.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private var nowMs = 10_000L
    private val timer = SleepTimer { nowMs }
    private val fifteenMinutes = 15 * 60_000L

    @Test
    fun elapsedDeadlineExpiresOnceEvenAfterLongBackgroundGap() {
        assertTrue(timer.setDuration("book", fifteenMinutes))
        nowMs += fifteenMinutes - 1
        assertEquals(1L, timer.state().remainingMs)
        assertFalse(timer.expireIfDue())

        nowMs += 60_000

        assertTrue(timer.expireIfDue())
        assertEquals(SleepTimerState(), timer.state())
        assertFalse(timer.expireIfDue())
    }

    @Test
    fun replacingDurationStartsANewDeadline() {
        timer.setDuration("book", fifteenMinutes)
        nowMs += 60_000

        timer.setDuration("book", 30 * 60_000L)
        nowMs += fifteenMinutes

        assertFalse(timer.expireIfDue())
        assertEquals(fifteenMinutes, timer.state().remainingMs)
    }

    @Test
    fun cancelPreventsAnOldDeadlineFromPausing() {
        timer.setDuration("book", fifteenMinutes)
        timer.cancel()
        nowMs += fifteenMinutes

        assertFalse(timer.expireIfDue())
        assertEquals(SleepTimerState(), timer.state())
    }

    @Test
    fun durationContinuesAcrossTracksInTheSameBook() {
        timer.setDuration("book", fifteenMinutes)
        nowMs += 1234

        assertFalse(timer.onMediaChanged("book", "next-track"))
        assertEquals(SleepTimerState("book", fifteenMinutes - 1234), timer.state())
    }

    @Test
    fun changingBookOrClearingQueueCancelsDuration() {
        timer.setDuration("book", fifteenMinutes)
        assertTrue(timer.onMediaChanged("other-book", "other-track"))
        assertEquals(SleepTimerState(), timer.state())

        timer.setDuration("book", fifteenMinutes)
        assertTrue(timer.onMediaChanged(null, null))
        assertEquals(SleepTimerState(), timer.state())
    }

    @Test
    fun endOfCurrentTrackConsumesOnceWithoutUsingElapsedTime() {
        timer.setEndOfTrack("book", "track")
        nowMs += 60 * 60_000L

        assertEquals(SleepTimerState("book", endOfTrack = true), timer.state())
        assertFalse(timer.expireIfDue())
        assertTrue(timer.onTrackEnded())
        assertFalse(timer.onTrackEnded())
        assertEquals(SleepTimerState(), timer.state())
    }

    @Test
    fun selectingAnotherTrackCancelsEndOfCurrentTrack() {
        timer.setEndOfTrack("book", "track")

        assertTrue(timer.onMediaChanged("book", "next-track"))
        assertEquals(SleepTimerState(), timer.state())
        assertFalse(timer.onTrackEnded())
    }

    @Test
    fun replacingModesRemovesPreviousDeadlineOrTrackTarget() {
        timer.setDuration("book", fifteenMinutes)
        timer.setEndOfTrack("book", "track")
        assertFalse(timer.hasDeadline)

        timer.setDuration("book", fifteenMinutes)
        assertFalse(timer.onTrackEnded())
        assertTrue(timer.hasDeadline)
        assertFalse(timer.state().endOfTrack)
    }

    @Test
    fun onlyBoundedPresetsAndValidIdentitiesAreAccepted() {
        for (duration in listOf(0L, -1L, 1L, 61 * 60_000L, Long.MAX_VALUE)) {
            assertFalse(timer.setDuration("book", duration))
        }
        assertFalse(timer.setDuration("", fifteenMinutes))
        assertFalse(timer.setEndOfTrack("book", ""))
        assertEquals(SleepTimerState(), timer.state())

        for (duration in listOf(15L, 30L, 45L, 60L).map { it * 60_000 }) {
            assertTrue(timer.setDuration("book", duration))
            assertEquals(duration, timer.state().remainingMs)
        }
    }
}
