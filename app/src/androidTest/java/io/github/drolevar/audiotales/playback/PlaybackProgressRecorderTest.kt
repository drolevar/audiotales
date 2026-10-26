package io.github.drolevar.audiotales.playback

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackProgressRecorderTest {
    @Test
    fun emptyQueueBarrierWaitsForInflightAndOutgoingDiscontinuityWrites() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val player = ExoPlayer.Builder(context).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val library = BlockingLibrary()
            val recorder = PlaybackProgressRecorder(player, library) {}
            try {
                val source = SilenceMediaSource.Factory().setDurationUs(1_000_000).createMediaSource()
                source.updateMediaItem(source.mediaItem.buildUpon().setMediaId("track:track")
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setExtras(Bundle().apply { putString("bookId", "book") }).build())
                    .build())
                player.setMediaSource(source)
                recorder.start(scope)
                recorder.recordCurrent()
                withTimeout(5_000) { library.writeStarted.await() }
                player.clearMediaItems()
                val drained = scope.async { recorder.drain() }
                delay(50)

                assertFalse("Barrier must wait for the blocked writer", drained.isCompleted)
                library.allowWrites.complete(Unit)
                withTimeout(5_000) { drained.await() }

                assertTrue("Outgoing discontinuity must be saved before the barrier", library.writes >= 2)
                val savedAfterDrain = library.writes
                delay(100)
                assertEquals("Cleared queue must not emit more old-book progress", savedAfterDrain, library.writes)
                withTimeout(5_000) { recorder.stop().join() }
            } finally {
                scope.cancel()
                player.release()
            }
        }
    }

    private class BlockingLibrary : LibraryRepository {
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrites = CompletableDeferred<Unit>()
        var writes = 0
        private val track = LibraryTrack("track", "book", "Track", "content://test/track", 0)
        override fun observeBooks(): Flow<List<LibraryBook>> = flowOf(emptyList())
        override fun observeCollection(): Flow<List<LibraryCollectionEntry>> = flowOf(emptyList())
        override suspend fun getBook(bookId: String): LibraryBook? = null
        override suspend fun getTracks(bookId: String): List<LibraryTrack> = listOf(track)
        override suspend fun getTrack(trackId: String): LibraryTrack? = track.takeIf { it.id == trackId }
        override suspend fun getProgress(bookId: String): ListeningProgress? = null
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) {
            writeStarted.complete(Unit)
            allowWrites.await()
            writes++
        }
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("Not used")
        override suspend fun deleteBook(bookId: String) = Unit
    }
}
