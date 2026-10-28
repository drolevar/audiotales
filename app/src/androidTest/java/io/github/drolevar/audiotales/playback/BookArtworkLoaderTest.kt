package io.github.drolevar.audiotales.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.httpHeaders
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import io.github.drolevar.audiotales.artwork.bookCoverRequestBuilder
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Coil decoding/cache with an in-memory fetcher; never opens a provider connection. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BookArtworkLoaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun phoneRequestAndSessionShareHeadersCacheAndFittedSoftwareArtwork() = runBlocking {
        val book = LibraryBook("first", "First", coverUrl = "https://covers.invalid/first.png",
            coverReferer = "https://library.invalid/")
        val library = ArtworkLibrary(book)
        var fetches = 0
        val png = png(Color.RED)
        val images = ImageLoader.Builder(context).diskCache(null).components {
            add(Fetcher.Factory<CoilUri> { _, options, _ ->
                object : Fetcher {
                    override suspend fun fetch(): SourceFetchResult {
                        fetches++
                        assertEquals("AudioTales", options.httpHeaders["User-Agent"])
                        assertEquals(book.coverReferer, options.httpHeaders["Referer"])
                        return SourceFetchResult(ImageSource(Buffer().write(png), FileSystem.SYSTEM),
                            "image/png", DataSource.NETWORK)
                    }
                }
            })
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val loader = BookArtworkLoader(context, library, scope, images)
        try {
            // Warm the same cache through the request builder used by phone BookCover.
            val warm = images.execute(bookCoverRequestBuilder(context, book.coverUrl, book.coverReferer)
                .size(512).scale(Scale.FIT).precision(Precision.EXACT).allowHardware(false).build())
            assertTrue(warm is SuccessResult)
            val bitmap = withTimeout(5_000) { loader.loadBitmap(artwork(book.id)).await() }
            assertEquals(256, bitmap.width)
            assertEquals(512, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(128, 256))
            assertTrue(bitmap.config != Bitmap.Config.HARDWARE)
            assertEquals("Session should reuse the phone's cached image", 1, fetches)
            assertEquals(listOf(book.id), library.reads)
        } finally {
            loader.close()
            scope.cancel()
            images.shutdown()
        }
    }

    @Test
    fun failureMissingAndDeletedBooksNeverReuseThePreviousBooksCoverAndCanRetry() = runBlocking {
        val first = LibraryBook("first", "First", coverUrl = "https://covers.invalid/first.png")
        val second = LibraryBook("second", "Second")
        val library = ArtworkLibrary(first, second)
        val images = ImageLoader.Builder(context).diskCache(null).components {
            add(Fetcher.Factory<CoilUri> { data, _, _ ->
                object : Fetcher {
                    override suspend fun fetch(): SourceFetchResult {
                        if (data.toString().endsWith("broken.png")) throw IOException("Controlled cover failure")
                        val color = if (data.toString().endsWith("recovered.png")) Color.GREEN else Color.BLUE
                        return SourceFetchResult(ImageSource(Buffer().write(png(color)), FileSystem.SYSTEM),
                            "image/png", DataSource.NETWORK)
                    }
                }
            })
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val loader = BookArtworkLoader(context, library, scope, images)
        try {
            assertEquals(Color.BLUE, loader.loadBitmap(artwork(first.id)).await().getPixel(10, 10))
            expectFailure { loader.loadBitmap(artwork(second.id)).await() }
            library.books[second.id] = second.copy(coverUrl = "https://covers.invalid/broken.png")
            expectFailure { loader.loadBitmap(artwork(second.id)).await() }
            library.books[second.id] = second.copy(coverUrl = "https://covers.invalid/recovered.png")
            assertEquals(Color.GREEN, loader.loadBitmap(artwork(second.id)).await().getPixel(10, 10))
            library.books.remove(first.id)
            expectFailure { loader.loadBitmap(artwork(first.id)).await() }
        } finally {
            loader.close()
            scope.cancel()
            images.shutdown()
        }
    }

    @Test
    fun untrustedLocationsEmbeddedBytesAndStoredNonHttpsCoversAreRejectedBeforeFetching() = runBlocking {
        val library = ArtworkLibrary(LibraryBook("unsafe", "Unsafe", coverUrl = "file:///private/cover.png"))
        var fetches = 0
        val images = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<CoilUri> { _, _, _ -> fetches++; error("No fetch is permitted") })
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val loader = BookArtworkLoader(context, library, scope, images)
        try {
            for (uri in listOf("https://caller.invalid/cover.jpg", "content://private/cover",
                "file:///private/cover", artwork("unsafe").toString() + "?url=https://caller.invalid/")) {
                expectFailure { loader.loadBitmap(Uri.parse(uri)).await() }
            }
            expectFailure { loader.decodeBitmap(byteArrayOf(1, 2, 3)).await() }
            assertFalse(loader.supportsMimeType("image/jpeg"))
            assertTrue(library.reads.isEmpty())
            for (cover in listOf("file:///private/cover.png", "http://covers.invalid/cover.png",
                "https://covers.invalid:444/cover.png", "https://covers.invalid/cover.png#fragment",
                "https://user:password@covers.invalid/cover.png")) {
                library.books["unsafe"] = library.books.getValue("unsafe").copy(coverUrl = cover)
                expectFailure { loader.loadBitmap(artwork("unsafe")).await() }
            }
            assertEquals(List(5) { "unsafe" }, library.reads)
            assertEquals(0, fetches)
        } finally {
            loader.close()
            scope.cancel()
            images.shutdown()
        }
    }

    @Test
    fun serviceScopeAndExplicitCloseCancelImageWorkAndRejectSubsequentLoads() = runBlocking {
        for (cancelParent in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val book = LibraryBook("pending", "Pending", coverUrl = "https://covers.invalid/pending.png")
            val images = ImageLoader.Builder(context).components {
                add(Fetcher.Factory<CoilUri> { _, _, _ ->
                    object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            started.complete(Unit)
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                    }
                })
            }.build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val loader = BookArtworkLoader(context, ArtworkLibrary(book), scope, images)
            try {
                val future = loader.loadBitmap(artwork(book.id))
                withTimeout(5_000) { started.await() }
                if (cancelParent) scope.cancel() else loader.close()
                withTimeout(5_000) { cancelled.await() }
                expectFailure { future.await() }
                assertTrue(future.isCancelled)
                val afterStop = loader.loadBitmap(artwork(book.id))
                expectFailure { afterStop.await() }
                assertTrue(afterStop.isCancelled)
            } finally {
                loader.close()
                scope.cancel()
                images.shutdown()
            }
        }
    }

    private fun artwork(id: String): Uri = Uri.parse(BookArtworkUris.forBook(id))

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            withTimeout(5_000) { block() }
        } catch (timeout: TimeoutCancellationException) { throw timeout
        } catch (_: IOException) { return
        } catch (_: IllegalArgumentException) { return
        } catch (_: CancellationException) { return
        } catch (_: ExecutionException) { return }
        throw AssertionError("Expected artwork request to fail without a fallback")
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1_000, 2_000, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private class ArtworkLibrary(vararg initial: LibraryBook) : LibraryRepository {
        val books = initial.associateBy(LibraryBook::id).toMutableMap()
        val reads = mutableListOf<String>()
        override fun observeBooks() = flowOf(books.values.toList())
        override fun observeCollection() = flowOf(emptyList<LibraryCollectionEntry>())
        override suspend fun getBook(bookId: String) = books[bookId].also { reads.add(bookId) }
        override suspend fun getTracks(bookId: String) = emptyList<LibraryTrack>()
        override suspend fun getTrack(trackId: String): LibraryTrack? = null
        override suspend fun getProgress(bookId: String): ListeningProgress? = null
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) = Unit
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("Not used")
        override suspend fun deleteBook(bookId: String) = error("Not used")
    }
}
