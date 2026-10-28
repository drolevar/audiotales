package io.github.drolevar.audiotales.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata as PlatformMetadata
import android.media.session.MediaController as PlatformController
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.CacheBitmapLoader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import java.io.IOException
import java.util.UUID
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers Media3's real session cache and native metadata bridge, with no audio/network opened. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BookArtworkSessionTest {
    @Test
    fun freshQueueAttemptRetriesFailureThroughActualSessionBitmapLoader() = runBlocking {
        val book = LibraryBook("book", "Book", coverUrl = "https://covers.invalid/book.png")
        var fail = true
        var fetches = 0
        withFixture(book, fetch = {
            fetches++
            if (fail) throw IOException("Controlled failure")
            image(Color.GREEN)
        }) { fixture ->
            val original = item(book)
            onMain { fixture.player.setMediaItem(original) }
            expectImageFailure { onMain { fixture.session.bitmapLoader.loadBitmap(original.mediaMetadata.artworkUri!!) }.await() }
            assertEquals(1, fetches)
            fail = false
            // The wrapper deliberately keeps the failed future for this exact URI.
            expectImageFailure { onMain { fixture.session.bitmapLoader.loadBitmap(original.mediaMetadata.artworkUri!!) }.await() }
            assertEquals(1, fetches)
            val retried = item(book)
            assertNotEquals(original.mediaMetadata.artworkUri, retried.mediaMetadata.artworkUri)
            onMain { fixture.player.setMediaItem(retried) }
            val bitmap = onMain { fixture.session.bitmapLoader.loadBitmap(retried.mediaMetadata.artworkUri!!) }.await()
            assertEquals(Color.GREEN, bitmap.getPixel(10, 10))
            assertEquals(2, fetches)
        }
    }

    @Test
    fun savedCoverRefreshReplacesOnlyQueueMetadataAndUpdatesNativeArtwork() = runBlocking {
        val missing = LibraryBook("book", "Book", "Author")
        withFixture(missing, fetch = { image(Color.BLUE) }) { fixture ->
            val attempt = UUID.randomUUID().toString()
            val items = listOf(item(missing, 0, attempt), item(missing, 1, attempt))
            onMain {
                fixture.player.setMediaItems(items, 1, 7_250)
                fixture.player.playWhenReady = true
            }
            val oldUri = items[1].mediaMetadata.artworkUri!!
            expectImageFailure { onMain { fixture.session.bitmapLoader.loadBitmap(oldUri) }.await() }
            val state = onMain { fixture.player.playbackState }
            val updated = missing.copy(coverUrl = "https://covers.invalid/updated.png", coverReferer = "https://library.invalid/")
            fixture.library.book = updated
            onMain { refreshQueuedBookArtwork(fixture.player, listOf(updated)) }
            val uri = onMain {
                assertEquals(2, fixture.player.mediaItemCount)
                assertEquals(1, fixture.player.currentMediaItemIndex)
                assertEquals(7_250L, fixture.player.currentPosition)
                assertEquals(state, fixture.player.playbackState)
                assertTrue(fixture.player.playWhenReady)
                for (index in items.indices) {
                    val actual = fixture.player.getMediaItemAt(index)
                    assertEquals(items[index].mediaId, actual.mediaId)
                    assertEquals(items[index].localConfiguration, actual.localConfiguration)
                    assertEquals(items[index].clippingConfiguration, actual.clippingConfiguration)
                    assertEquals(items[index].mediaMetadata.extras?.getString("bookId"),
                        actual.mediaMetadata.extras?.getString("bookId"))
                    assertEquals(items[index].mediaMetadata.title, actual.mediaMetadata.title)
                    assertNull(actual.mediaMetadata.artworkData)
                }
                fixture.player.currentMediaItem!!.mediaMetadata.artworkUri!!
            }
            assertNotEquals(oldUri, uri)
            assertEquals(Color.BLUE, onMain { fixture.session.bitmapLoader.loadBitmap(uri) }.await().getPixel(10, 10))
            await {
                onMain { fixture.platform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART) != null }
            }
            onMain {
                val metadata = requireNotNull(fixture.platform.metadata)
                assertEquals("Chapter 1", metadata.getString(PlatformMetadata.METADATA_KEY_TITLE))
                assertEquals(Color.BLUE, requireNotNull(metadata.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART)).getPixel(10, 10))
            }
        }
    }

    @Test
    fun clearingQueueCancelsDelayedArtworkWithoutRestoringOldNativeMetadata() = runBlocking {
        val book = LibraryBook("book", "Book", coverUrl = "https://covers.invalid/pending.png")
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var delayImage = true
        withFixture(book, fetch = {
            if (delayImage) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            image(Color.GREEN)
        }) { fixture ->
            onMain { fixture.player.setMediaItem(item(book)) }
            withTimeout(5_000) { started.await() }
            onMain { fixture.player.clearMediaItems() }
            withTimeout(5_000) { cancelled.await() }
            // Let the framework bridge process cancellation and the empty queue.
            delay(150)
            onMain {
                assertEquals(0, fixture.player.mediaItemCount)
                assertTrue(fixture.platform.metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE).isNullOrEmpty())
                assertNull(fixture.platform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART))
            }
            delayImage = false
            onMain { fixture.player.setMediaItem(item(book, 1)) }
            await { onMain { fixture.platform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART) != null } }
            onMain {
                val metadata = requireNotNull(fixture.platform.metadata)
                assertEquals("Chapter 1", metadata.getString(PlatformMetadata.METADATA_KEY_TITLE))
                assertEquals(Color.GREEN, requireNotNull(metadata.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART)).getPixel(10, 10))
                assertFalse(fixture.player.playWhenReady)
            }
        }
    }

    @Test
    fun alreadyCompletedArtworkCannotRepublishClearedBookThroughTwoSessionCacheWrappers() = runBlocking {
        val book = LibraryBook("book", "Book", coverUrl = "https://covers.invalid/book.png")
        for (productionEmptyPolicy in listOf(false, true)) {
            val completed = SettableFuture.create<Bitmap>()
            withFixture(book, fetch = { error("Controlled future supplies this image") }, wrap = { loader ->
                // The notification provider adds another cache around the session's own cache.
                // The control retains upstream's null result for empty metadata.
                CacheBitmapLoader(object : BitmapLoader {
                    override fun supportsMimeType(mimeType: String) = loader.supportsMimeType(mimeType)
                    override fun decodeBitmap(data: ByteArray) = loader.decodeBitmap(data)
                    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = completed
                    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
                        if (metadata.artworkUri != null) loadBitmap(metadata.artworkUri!!)
                        else if (productionEmptyPolicy) loader.loadBitmapFromMetadata(metadata) else null
                })
            }) { fixture ->
                onMain { fixture.player.setMediaItem(item(book)) }
                await { onMain { fixture.platform.metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE) == "Chapter 0" } }
                // Complete and clear in one looper turn. Media3 has posted its old success
                // callback before clear can invalidate it. The control must reproduce the bug.
                onMain {
                    completed.set(Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
                    fixture.player.clearMediaItems()
                }
                if (!productionEmptyPolicy) {
                    await { onMain { fixture.platform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART) != null } }
                }
                delay(150)
                onMain {
                    assertTrue(completed.isDone)
                    assertFalse(completed.isCancelled)
                    assertEquals(0, fixture.player.mediaItemCount)
                    val metadata = fixture.platform.metadata
                    if (productionEmptyPolicy) {
                        assertTrue(metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE).isNullOrEmpty())
                        assertNull(metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART))
                    } else {
                        assertEquals("Chapter 0", metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE))
                        assertEquals(Color.RED, requireNotNull(metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART)).getPixel(10, 10))
                    }
                }
            }
        }
    }

    private fun item(book: LibraryBook, index: Int = 0, attempt: String = UUID.randomUUID().toString()): MediaItem =
        MediaItem.Builder().setMediaId("track:${book.id}:$index").setUri("https://audio.invalid/$index.mp3")
            .setCustomCacheKey("cache:$index")
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(1_000)
                .setEndPositionMs(60_000).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Chapter $index").setAlbumTitle(book.title)
                .setArtist(book.author).setIsPlayable(true).setIsBrowsable(false)
                .setExtras(Bundle().apply { putString("bookId", book.id) })
                .setArtworkUri(Uri.parse(BookArtworkUris.forBook(book.id, book.coverUrl, book.coverReferer, attempt))).build())
            .build()

    private suspend fun withFixture(book: LibraryBook, fetch: suspend () -> ImageFetchResult,
        wrap: (BookArtworkLoader) -> BitmapLoader = { it },
        block: suspend (Fixture) -> Unit) {
        val fixture = onMain { Fixture(book, fetch, wrap) }
        try { withTimeout(15_000) { block(fixture) } } finally { onMain { fixture.close() } }
    }

    private class Fixture(book: LibraryBook, fetchImage: suspend () -> ImageFetchResult,
        wrap: (BookArtworkLoader) -> BitmapLoader) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val library = ArtworkLibrary(book)
        val images = ImageLoader.Builder(context).diskCache(null).components {
            add(Fetcher.Factory<CoilUri> { _, _, _ -> object : Fetcher {
                override suspend fun fetch() = fetchImage()
            } })
        }.build()
        val loader = BookArtworkLoader(context, library, scope, images)
        val player = ExoPlayer.Builder(context).build().apply { addListener(loader) }
        val session = MediaSession.Builder(context, player).setId("artwork-${UUID.randomUUID()}")
            .setBitmapLoader(wrap(loader)).build()
        val platform = PlatformController(context, session.platformToken)
        fun close() {
            loader.close()
            session.release()
            player.release()
            scope.cancel()
            images.shutdown()
        }
    }

    private class ArtworkLibrary(var book: LibraryBook) : LibraryRepository {
        override fun observeBooks() = flowOf(listOf(book))
        override fun observeCollection() = flowOf(emptyList<LibraryCollectionEntry>())
        override suspend fun getBook(bookId: String) = book.takeIf { it.id == bookId }
        override suspend fun getTracks(bookId: String) = emptyList<LibraryTrack>()
        override suspend fun getTrack(trackId: String): LibraryTrack? = null
        override suspend fun getProgress(bookId: String): ListeningProgress? = null
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) = Unit
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("Not used")
        override suspend fun deleteBook(bookId: String) = error("Not used")
    }

    companion object {
        private fun image(color: Int) = ImageFetchResult(Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }.asImage(), false, DataSource.NETWORK)
        private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
        private suspend fun await(predicate: suspend () -> Boolean) {
            withTimeout(5_000) { while (!predicate()) delay(10) }
        }
        private suspend fun expectImageFailure(block: suspend () -> Unit) {
            try { withTimeout(5_000) { block() } } catch (_: IOException) { return }
            throw AssertionError("Expected artwork failure")
        }
    }
}
