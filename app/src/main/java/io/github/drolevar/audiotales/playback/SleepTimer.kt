package io.github.drolevar.audiotales.playback

/** Current service-confirmed timer. A null book identifies an inactive timer. */
data class SleepTimerState(
    val bookId: String? = null,
    val remainingMs: Long? = null,
    val endOfTrack: Boolean = false,
)

/** Main-thread timer state machine; elapsed time is independent of playback speed and wall time. */
internal class SleepTimer(private val elapsedRealtimeMs: () -> Long) {
    private var bookId: String? = null
    private var deadlineMs: Long? = null
    private var endTrackId: String? = null

    val hasDeadline: Boolean get() = deadlineMs != null

    fun state(): SleepTimerState = SleepTimerState(
        bookId = bookId,
        remainingMs = deadlineMs?.let { (it - elapsedRealtimeMs()).coerceAtLeast(0) },
        endOfTrack = endTrackId != null,
    )

    fun setDuration(bookId: String, durationMs: Long): Boolean {
        if (bookId.isBlank() || durationMs !in DURATION_PRESETS_MS) return false
        this.bookId = bookId
        deadlineMs = elapsedRealtimeMs() + durationMs
        endTrackId = null
        return true
    }

    fun setEndOfTrack(bookId: String, trackId: String): Boolean {
        if (bookId.isBlank() || trackId.isBlank()) return false
        this.bookId = bookId
        deadlineMs = null
        endTrackId = trackId
        return true
    }

    fun cancel() {
        bookId = null
        deadlineMs = null
        endTrackId = null
    }

    /** Selecting a different track cancels a timer targeting the previous track's end. */
    fun onMediaChanged(currentBookId: String?, currentTrackId: String?): Boolean {
        if (bookId == null) return false
        if (bookId != currentBookId || currentTrackId == null ||
            (endTrackId != null && endTrackId != currentTrackId)
        ) {
            cancel()
            return true
        }
        return false
    }

    /** Consumes the expiry before the caller pauses, so listener re-entry cannot pause twice. */
    fun expireIfDue(): Boolean {
        val deadline = deadlineMs ?: return false
        if (elapsedRealtimeMs() < deadline) return false
        cancel()
        return true
    }

    /** ExoPlayer already paused before advancing; only consume the one-shot timer here. */
    fun onTrackEnded(): Boolean {
        if (endTrackId == null) return false
        cancel()
        return true
    }

    companion object {
        val DURATION_PRESETS_MS: Set<Long> = setOf(15L, 30L, 45L, 60L).map { it * 60_000 }.toSet()
    }
}
