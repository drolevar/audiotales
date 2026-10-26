package io.github.drolevar.audiotales.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Service-owned player integration. No Activity connection owns or cancels this timer. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SleepTimerController(
    private val player: ExoPlayer,
    private val onStateChanged: (SleepTimerState) -> Unit,
    elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private val timer = SleepTimer(elapsedRealtimeMs)
    private var ticker: Job? = null
    private var scope: CoroutineScope? = null

    fun start(serviceScope: CoroutineScope) {
        check(scope == null)
        scope = serviceScope
        player.addListener(this)
    }

    fun setDuration(bookId: String, durationMs: Long): Boolean {
        if (!matchesCurrentBook(bookId) || !timer.setDuration(bookId, durationMs)) return false
        publish()
        ticker?.cancel()
        ticker = requireNotNull(scope).launch {
            while (isActive && timer.hasDeadline) {
                delay(minOf(1_000L, timer.state().remainingMs ?: 1_000L))
                if (timer.expireIfDue()) {
                    player.pause()
                }
                publish()
            }
        }
        return true
    }

    fun setEndOfTrack(bookId: String): Boolean {
        if (!matchesCurrentBook(bookId) || player.playbackState == Player.STATE_ENDED) return false
        val trackId = player.currentMediaItem?.mediaId?.let(LibraryMediaIds::trackId) ?: return false
        if (!timer.setEndOfTrack(bookId, trackId)) return false
        ticker?.cancel()
        ticker = null
        publish()
        return true
    }

    fun cancel(bookId: String): Boolean {
        if (!matchesCurrentBook(bookId)) return false
        timer.cancel()
        ticker?.cancel()
        ticker = null
        publish()
        return true
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (timer.onMediaChanged(mediaItem?.mediaMetadata?.extras?.getString("bookId"),
                mediaItem?.mediaId?.let(LibraryMediaIds::trackId))) {
            ticker?.cancel()
            ticker = null
            publish()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
            timer.onTrackEnded()
        ) publish()
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        timer.cancel()
        publish()
        player.removeListener(this)
        scope = null
    }

    private fun matchesCurrentBook(bookId: String): Boolean = bookId.isNotBlank() &&
        player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") == bookId &&
        player.currentMediaItem?.mediaId?.let(LibraryMediaIds::trackId) != null

    private fun publish() {
        val state = timer.state()
        player.pauseAtEndOfMediaItems = state.endOfTrack
        onStateChanged(state)
    }
}
