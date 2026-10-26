package io.github.drolevar.audiotales.playback

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import io.github.drolevar.audiotales.data.local.AudioTalesDatabase
import io.github.drolevar.audiotales.data.local.BookEntity
import io.github.drolevar.audiotales.data.local.PlaybackProgressEntity
import io.github.drolevar.audiotales.data.local.TrackEntity
import io.github.drolevar.audiotales.di.StorageModule
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only checks against the application's real service, Hilt graph and on-disk database.
 * Instrumentation uses the target UID: these tests do not qualify external-controller trust.
 * Inert HTTPS media is never prepared or played. Decoding, audio focus, last-controller background
 * survival, process death and Android Auto require separate runtime qualification.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MediaLibraryServiceIntegrationTest {
    @Test
    fun realServiceBrowsesStableBookAndTrackIdsAndRejectsUnknownItems() = runBlocking {
        withFixture { fixture ->
            val browser = fixture.connect()
            val root = value(onMain { browser.getLibraryRoot(null) }.boundedAwait())
            assertEquals("audiotales:library", root.mediaId)
            assertEquals(true, root.mediaMetadata.isBrowsable)
            assertEquals(false, root.mediaMetadata.isPlayable)

            val books = value(onMain {
                browser.getChildren(root.mediaId, 0, Int.MAX_VALUE, null)
            }.boundedAwait())
            val book = books.single { it.mediaId == fixture.bookMediaId }
            assertEquals(fixture.book.title, book.mediaMetadata.title.toString())
            assertEquals(fixture.book.author, book.mediaMetadata.artist.toString())
            assertEquals(true, book.mediaMetadata.isBrowsable)
            assertEquals(false, book.mediaMetadata.isPlayable)
            assertNull(book.localConfiguration)

            val tracks = value(onMain {
                browser.getChildren(book.mediaId, 0, 10, null)
            }.boundedAwait())
            assertEquals(fixture.trackMediaIds, tracks.map { it.mediaId })
            tracks.forEachIndexed { index, item ->
                assertEquals(fixture.tracks[index].title, item.mediaMetadata.title.toString())
                assertEquals(false, item.mediaMetadata.isBrowsable)
                assertEquals(true, item.mediaMetadata.isPlayable)
                assertNull("Browsing must not disclose a transport location", item.localConfiguration)
                val fetched = value(onMain { browser.getItem(item.mediaId) }.boundedAwait())
                assertEquals(item.mediaId, fetched.mediaId)
                assertEquals(item.mediaMetadata, fetched.mediaMetadata)
                assertNull(fetched.localConfiguration)
            }
            val secondPage = value(onMain {
                browser.getChildren(book.mediaId, 1, 1, null)
            }.boundedAwait())
            assertEquals(listOf(fixture.trackMediaIds[1]), secondPage.map { it.mediaId })
            assertEquals(book.mediaId, value(onMain { browser.getItem(book.mediaId) }.boundedAwait()).mediaId)

            for (missing in listOf("book:${fixture.id}-absent", "track:${fixture.id}-absent", "invalid:${fixture.id}")) {
                val result = onMain { browser.getItem(missing) }.boundedAwait()
                assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
                assertNull(result.value)
            }
            val missingChildren = onMain {
                browser.getChildren("book:${fixture.id}-absent", 0, 10, null)
            }.boundedAwait()
            assertEquals(SessionError.ERROR_BAD_VALUE, missingChildren.resultCode)
            assertNull(missingChildren.value)
        }
    }

    @Test
    fun realServiceRejectsUnknownIdsAndCallerLocationsWithoutReplacingCanonicalQueue() = runBlocking {
        withFixture { fixture ->
            val requester = fixture.connect()
            val observer = fixture.connect()
            fixture.selectBook(requester, observer)

            val invalidItems = listOf(
                MediaItem.Builder().setMediaId("track:${fixture.id}-absent").build(),
                MediaItem.Builder().setMediaId("book:${fixture.id}-absent").build(),
                MediaItem.Builder().setMediaId(fixture.trackMediaIds[0])
                    .setUri("https://caller.invalid/replacement.wav").build(),
                MediaItem.Builder().setMediaId(fixture.trackMediaIds[0])
                    .setUri("file:///caller-owned.wav").build(),
                MediaItem.Builder().setMediaId(fixture.trackMediaIds[0])
                    .setUri("content://caller.invalid/replacement").build(),
                MediaItem.Builder().setMediaId(fixture.trackMediaIds[0])
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                        .setMediaUri(Uri.parse("https://caller.invalid/request.wav")).build())
                    .build(),
            )
            for (invalid in invalidItems) {
                onMain { requester.setMediaItem(invalid) }
                // Wait for the requesting controller's optimistic timeline to roll back. Checking
                // only the observer immediately after dispatch could pass before resolution ran.
                awaitCondition("Rejected set request did not restore the canonical queue: ${invalid.mediaId}") {
                    onMain { fixture.isCanonical(requester) }
                }
                onMain { fixture.assertCanonical(observer) }
            }

            val injected = MediaItem.Builder().setMediaId(fixture.trackMediaIds[0])
                .setUri("https://caller.invalid/added.wav")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Caller title").build()).build()
            onMain { requester.addMediaItem(injected) }
            awaitCondition("Rejected add request did not restore the canonical queue") {
                onMain { fixture.isCanonical(requester) }
            }
            val freshObserver = fixture.connect()
            onMain { fixture.assertCanonical(freshObserver) }
        }
    }

    @Test
    fun realServicePersistsIdleSeekAndSharesQueueAcrossControllerReconnect() = runBlocking {
        withFixture { fixture ->
            val requester = fixture.connect()
            // This independent browser anchors the service; this is not a last-client test.
            val observer = fixture.connect()
            fixture.selectBook(requester, observer)
            onMain {
                assertTrue("Indexed seek must be available: ${fixture.controllerState(requester)}",
                    requester.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
                assertTrue("Pause must be available: ${fixture.controllerState(requester)}",
                    requester.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                assertTrue("Speed must be available: ${fixture.controllerState(requester)}",
                    requester.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
                requester.pause()
                // Before preparation the placeholder window is not seekable, so seekTo(position)
                // requires an unavailable current-item command. Indexed seek has its own command
                // and sets the selected queue item's pending position without fetching media.
                requester.seekTo(1, 7_250L)
                requester.setPlaybackSpeed(1.25f)
            }
            awaitCondition("The service did not persist the exact seek position",
                diagnostics = { fixture.seekDiagnostics(requester, observer) }) {
                fixture.database.libraryDao().getProgress(fixture.id)?.let {
                    it.trackId == fixture.tracks[1].id && it.positionMs == 7_250L
                } == true
            }
            awaitCondition("The second controller did not receive the service seek and speed",
                diagnostics = { fixture.seekDiagnostics(requester, observer) }) {
                onMain { observer.currentPosition == 7_250L && observer.playbackParameters.speed == 1.25f }
            }
            onMain { requester.release() }
            val reconnected = fixture.connect()
            onMain {
                fixture.assertCanonical(reconnected)
                assertEquals(7_250L, reconnected.currentPosition)
                assertEquals(1.25f, reconnected.playbackParameters.speed, 0f)
                assertEquals(Player.STATE_IDLE, reconnected.playbackState)
                assertFalse(reconnected.playWhenReady)
                assertFalse(observer.playWhenReady)
                assertNull(reconnected.playerError)
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic")) {
            "This suite is restricted to the task-owned emulator; do not run on a physical device"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = withContext(Dispatchers.IO) { StorageModule.provideDatabase(context) }
        val fixture = Fixture(context, database)
        val dao = database.libraryDao()
        try {
            val originalCollection = dao.observeCollection().first()
            val originalBookmarks = originalCollection.associate { entry ->
                entry.book.id to dao.observeBookmarks(entry.book.id).first()
            }
            dao.insertBookWithTracks(fixture.book, fixture.tracks)
            dao.saveProgress(PlaybackProgressEntity(fixture.id, fixture.tracks[1].id, 4_500L, 1L))
            try {
                block(fixture)
            } finally {
                withContext(NonCancellable) { fixture.close() }
            }
            assertEquals("Existing library and progress must remain untouched", originalCollection,
                dao.observeCollection().first())
            originalBookmarks.forEach { (bookId, bookmarks) ->
                assertEquals("Existing bookmarks must remain untouched", bookmarks, dao.observeBookmarks(bookId).first())
            }
        } finally {
            withContext(NonCancellable) {
                // Also handles setup failure; every mutation is scoped to this generated identity.
                dao.deleteBook(fixture.id)
                database.close()
            }
        }
    }

    private class Fixture(val context: Context, val database: AudioTalesDatabase) {
        val id = "service-qualification-${UUID.randomUUID()}"
        val book = BookEntity(id, "Service qualification $id", "Qualification author")
        val tracks = (0..1).map { index ->
            TrackEntity("$id-track-$index", id, "Qualification track $index",
                "https://media.invalid/$id/$index.wav", index, durationMs = 60_000L)
        }
        val bookMediaId = "book:$id"
        val trackMediaIds = tracks.map { "track:${it.id}" }
        private val component = ComponentName(context, PlaybackService::class.java)
        private val serviceWasRunning = isServiceRunning()
        private val futures = mutableListOf<ListenableFuture<MediaBrowser>>()
        private val browsers = mutableListOf<MediaBrowser>()
        private var ownsQueue = false

        suspend fun connect(): MediaBrowser {
            val future = onMain {
                MediaBrowser.Builder(context, SessionToken(context, component))
                    .setApplicationLooper(Looper.getMainLooper()).buildAsync()
            }
            futures.add(future)
            val browser = future.boundedAwait()
            browsers.add(browser)
            onMain {
                assertTrue(browser.isConnected)
                assertTrue(browser.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                assertTrue(browser.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                if (!ownsQueue) {
                    assertEquals("Use an idle emulator fixture; never replace another queue", 0, browser.mediaItemCount)
                    assertFalse("Use an idle emulator fixture", browser.playWhenReady)
                }
            }
            return browser
        }

        suspend fun selectBook(requester: MediaBrowser, observer: MediaBrowser) {
            ownsQueue = true
            onMain { requester.setMediaItem(MediaItem.Builder().setMediaId(bookMediaId).build()) }
            awaitCondition("The service did not resolve the book into the repository queue and saved track") {
                onMain { isCanonical(requester) && isCanonical(observer) }
            }
            onMain {
                assertCanonical(observer)
                assertEquals(4_500L, observer.currentPosition)
                assertFalse("Selecting a book must not start audio", observer.playWhenReady)
                assertEquals(Player.STATE_IDLE, observer.playbackState)
            }
        }

        fun isCanonical(browser: MediaBrowser): Boolean = browser.isConnected &&
            (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } == trackMediaIds &&
            browser.currentMediaItem?.mediaId == trackMediaIds[1] &&
            browser.currentMediaItem?.mediaMetadata?.title.toString() == tracks[1].title

        fun assertCanonical(browser: MediaBrowser) {
            assertTrue("Expected canonical repository queue and selected track", isCanonical(browser))
            assertEquals(book.title, browser.currentMediaItem?.mediaMetadata?.albumTitle.toString())
            assertEquals(id, browser.currentMediaItem?.mediaMetadata?.extras?.getString("bookId"))
        }

        suspend fun seekDiagnostics(requester: MediaBrowser, observer: MediaBrowser): String {
            val saved = database.libraryDao().getProgress(id)
            return "Expected track=${tracks[1].id}, position=7250; Room=$saved; " + onMain {
                "requester=${controllerState(requester)}; observer=${controllerState(observer)}"
            }
        }

        // Caller must use the browser's application looper, including when building failure text.
        fun controllerState(browser: MediaBrowser): String = if (!browser.isConnected) {
            "disconnected"
        } else {
            "track=${browser.currentMediaItem?.mediaId}, index=${browser.currentMediaItemIndex}, " +
                "count=${browser.mediaItemCount}, position=${browser.currentPosition}, " +
                "speed=${browser.playbackParameters.speed}, state=${browser.playbackState}, " +
                "playWhenReady=${browser.playWhenReady}, seekable=${browser.isCurrentMediaItemSeekable}, " +
                "indexedSeek=${browser.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)}, " +
                "currentSeek=${browser.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}, " +
                "progressSaveFailed=${browser.sessionExtras.getBoolean("progressSaveFailed")}, " +
                "playerError=${browser.playerError}"
        }

        suspend fun close() {
            try {
                val connected = onMain { browsers.filter { it.isConnected } }
                if (ownsQueue && connected.isNotEmpty()) {
                    onMain { connected.first().pause(); connected.first().clearMediaItems() }
                    // A second controller observes the service state, avoiding optimistic masking.
                    val observer = connected.last()
                    awaitCondition("The service did not clear the owned queue during cleanup") {
                        onMain { observer.mediaItemCount == 0 }
                    }
                }
            } finally {
                onMain { futures.forEach { MediaController.releaseFuture(it) } }
                if (!serviceWasRunning) {
                    onMain { context.stopService(Intent(context, PlaybackService::class.java)) }
                    awaitCondition("The test-started service did not stop during cleanup") { !isServiceRunning() }
                }
                database.libraryDao().deleteBook(id)
            }
        }

        @Suppress("DEPRECATION")
        private fun isServiceRunning(): Boolean =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getRunningServices(Int.MAX_VALUE).any { it.service == component }
    }

    private companion object {
        suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

        suspend fun <T> ListenableFuture<T>.boundedAwait(): T = withTimeout(10_000) { await() }

        fun <T> value(result: LibraryResult<T>): T {
            assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
            return requireNotNull(result.value)
        }

        suspend fun awaitCondition(
            message: String,
            diagnostics: (suspend () -> String)? = null,
            condition: suspend () -> Boolean,
        ) {
            val completed = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (!condition()) delay(25)
                true
            }
            assertTrue(if (completed == true) message else "$message; ${diagnostics?.invoke().orEmpty()}",
                completed == true)
        }
    }
}
