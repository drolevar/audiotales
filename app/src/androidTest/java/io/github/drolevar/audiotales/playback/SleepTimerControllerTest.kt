package io.github.drolevar.audiotales.playback

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses bounded silent media to verify the real player's boundary, without provider access. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SleepTimerControllerTest {
    @Test
    fun endOfTrackPausesBeforeNextTrackAndRestoresNormalTransition() = runBlocking {
        withPlayer { player, scope ->
            var state = SleepTimerState()
            val timer = SleepTimerController(player, onStateChanged = { state = it })
            timer.start(scope)
            try {
                player.setMediaSources(listOf(silence("book", "first"), silence("book", "second")))
                player.prepare()
                assertTrue(timer.setEndOfTrack("book"))
                player.play()

                await { !player.playWhenReady && state.bookId == null }

                assertEquals(0, player.currentMediaItemIndex)
                assertEquals("track:first", player.currentMediaItem?.mediaId)
                assertFalse(player.pauseAtEndOfMediaItems)
                player.play()
                await { player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_ENDED }
                assertEquals(SleepTimerState(), state)
            } finally {
                timer.stop()
            }
        }
    }

    @Test
    fun elapsedExpiryPausesOnceWithoutAnActivityAndKeepsOtherExtras() = runBlocking {
        withPlayer { player, scope ->
            var nowMs = 1_000L
            val extras = Bundle().apply { putBoolean("progressSaveFailed", true) }
            val timer = SleepTimerController(
                player,
                onStateChanged = { SleepTimerCommands.writeState(extras, it) },
                elapsedRealtimeMs = { nowMs },
            )
            timer.start(scope)
            try {
                player.setMediaSources(listOf(silence("book", "first"), silence("book", "second")))
                player.play()
                assertTrue(timer.setDuration("book", 15 * 60_000L))
                assertEquals(15 * 60_000L, SleepTimerCommands.readState(extras).remainingMs)
                player.seekTo(1, 0)
                assertEquals("book", SleepTimerCommands.readState(extras).bookId)
                nowMs += 15 * 60_000L

                await { SleepTimerCommands.readState(extras).bookId == null }

                assertFalse(player.playWhenReady)
                assertTrue(extras.getBoolean("progressSaveFailed"))
                player.play()
                delay(1_100)
                assertTrue("Consumed timer must not pause a later play request", player.playWhenReady)
            } finally {
                timer.stop()
            }
        }
    }

    @Test
    fun cancellationAndBookReplacementRestorePlayerTransitionPolicy() = runBlocking {
        withPlayer { player, scope ->
            var state = SleepTimerState()
            val timer = SleepTimerController(player, onStateChanged = { state = it })
            timer.start(scope)
            try {
                player.setMediaSource(silence("book", "first"))
                assertFalse(timer.setDuration("different", 15 * 60_000L))
                assertTrue(timer.setEndOfTrack("book"))
                assertTrue(player.pauseAtEndOfMediaItems)
                assertTrue(timer.cancel("book"))
                assertFalse(player.pauseAtEndOfMediaItems)
                assertEquals(SleepTimerState(), state)

                assertTrue(timer.setEndOfTrack("book"))
                player.setMediaSource(silence("other", "other-track"))
                assertFalse(player.pauseAtEndOfMediaItems)
                assertEquals(SleepTimerState(), state)

                assertTrue(timer.setDuration("other", 30 * 60_000L))
                player.clearMediaItems()
                assertEquals(SleepTimerState(), state)
            } finally {
                timer.stop()
            }
        }
    }

    private suspend fun withPlayer(block: suspend (ExoPlayer, CoroutineScope) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val player = ExoPlayer.Builder(context).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                block(player, scope)
            } finally {
                scope.cancel()
                player.release()
            }
        }

    private fun silence(bookId: String, trackId: String): SilenceMediaSource =
        SilenceMediaSource.Factory().setDurationUs(300_000).createMediaSource().apply {
            updateMediaItem(mediaItem.buildUpon()
                .setMediaId(LibraryMediaIds.track(trackId))
                .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString("bookId", bookId) }).build())
                .build())
        }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }
}
