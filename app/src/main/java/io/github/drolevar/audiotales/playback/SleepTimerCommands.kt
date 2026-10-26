package io.github.drolevar.audiotales.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal object SleepTimerCommands {
    val SET_DURATION = SessionCommand("io.github.drolevar.audiotales.sleep.SET_DURATION", Bundle.EMPTY)
    val END_OF_TRACK = SessionCommand("io.github.drolevar.audiotales.sleep.END_OF_TRACK", Bundle.EMPTY)
    val CANCEL = SessionCommand("io.github.drolevar.audiotales.sleep.CANCEL", Bundle.EMPTY)
    const val BOOK_ID = "bookId"
    const val DURATION_MS = "durationMs"
    private const val TIMER_BOOK_ID = "sleepTimer.bookId"
    private const val REMAINING_MS = "sleepTimer.remainingMs"
    private const val END_TRACK = "sleepTimer.endOfTrack"

    fun writeState(extras: Bundle, state: SleepTimerState) {
        extras.remove(TIMER_BOOK_ID)
        extras.remove(REMAINING_MS)
        extras.remove(END_TRACK)
        state.bookId?.let { extras.putString(TIMER_BOOK_ID, it) }
        state.remainingMs?.let { extras.putLong(REMAINING_MS, it) }
        if (state.endOfTrack) extras.putBoolean(END_TRACK, true)
    }

    fun readState(extras: Bundle): SleepTimerState {
        val bookId = extras.getString(TIMER_BOOK_ID)?.takeIf(String::isNotBlank) ?: return SleepTimerState()
        return SleepTimerState(
            bookId,
            extras.getLong(REMAINING_MS).takeIf { extras.containsKey(REMAINING_MS) && it >= 0 },
            extras.getBoolean(END_TRACK),
        )
    }
}
