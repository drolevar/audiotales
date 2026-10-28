package io.github.drolevar.audiotales.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled PCM media exercises the production item mapper and Media3's real clipped timeline. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SharedAudioRangesTest {
    @Test
    fun pendingSharedAdditionIsSentOnceAndBlocksFullRemovalUntilMedia3ReceivesIt() = runBlocking {
        withStorage { context, storage ->
            val library = RangeLibrary(tracks(File(context.filesDir, "shared.wav")))
            val downloads = BookDownloads(context, library, storage)
            withContext(Dispatchers.Main.immediate) { await { storage.downloadManager.isInitialized } }
            downloads.downloadBook("book")
            downloads.downloadBook("book")
            assertEquals("Four ranges and a repeated pending action must enqueue one resource", 1, context.serviceIntents.size)
            var deleted = false
            coroutineScope {
                val removal = async { downloads.removeBookAndAwait("book") { deleted = true } }
                try {
                    delay(150)
                    assertFalse("A pending resource intent must prevent premature deletion", removal.isCompleted)
                    assertFalse(deleted)
                } finally {
                    removal.cancelAndJoin()
                }
            }
            downloads.removeBook("book")
            assertEquals("Download-only removal must include the pending shared resource", 2, context.serviceIntents.size)
        }
    }

    @Test
    fun oneCachedFilePlaysClipRelativePositionsTransitionsAndProgress() = runBlocking {
        withStorage { context, storage ->
            val source = File(context.filesDir, "shared.wav").apply { writeBytes(wav()) }
            val tracks = tracks(source)
            val request = storage.downloadRequest(tracks)
            assertEquals("book-audio", request.id)
            assertEquals("book-audio", request.customCacheKey)
            val length = source.length()
            withContext(Dispatchers.IO) {
                ProgressiveDownloader(request.toMediaItem(), CacheDataSource.Factory()
                    .setCache(storage.cache).setUpstreamDataSourceFactory(FileDataSource.Factory()))
                    .download(null)
            }
            assertTrue(source.delete())
            assertEquals(setOf("book-audio"), storage.cache.keys)
            assertEquals(length, storage.cache.cacheSpace)

            val library = RangeLibrary(tracks)
            withContext(Dispatchers.Main.immediate) {
                val player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(storage.playbackDataSourceFactory)).build()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val recorder = PlaybackProgressRecorder(player, library) {}
                recorder.start(scope)
                try {
                    val items = tracks.map { it.toPlaybackMediaItem(LibraryBook("book", "Book"), storage, includeLocation = true) }
                    assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), items.map { it.clippingConfiguration.startPositionMs })
                    assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), items.map { it.clippingConfiguration.endPositionMs })
                    assertEquals(setOf("book-audio"), items.map { it.localConfiguration!!.customCacheKey }.toSet())
                    assertEquals(4, items.map { it.mediaId }.toSet().size)
                    player.setMediaItems(items)
                    player.prepare()
                    await { player.playbackState == Player.STATE_READY }
                    assertEquals(1_000L, player.duration)

                    player.seekTo(3, 250)
                    await { player.currentMediaItemIndex == 3 && player.playbackState == Player.STATE_READY }
                    assertEquals(1_000L, player.duration)
                    assertEquals(250L, player.currentPosition)
                    val oldArtwork = player.mediaMetadata.artworkUri
                    refreshQueuedBookArtwork(player, listOf(LibraryBook("book", "Book",
                        coverUrl = "https://covers.invalid/book.png")))
                    assertEquals(3, player.currentMediaItemIndex)
                    assertEquals(250L, player.currentPosition)
                    assertEquals(Player.STATE_READY, player.playbackState)
                    assertFalse(player.playWhenReady)
                    assertEquals(1_000L, player.duration)
                    assertTrue(oldArtwork != player.mediaMetadata.artworkUri)
                    items.forEachIndexed { index, original ->
                        val refreshed = player.getMediaItemAt(index)
                        assertEquals(original.mediaId, refreshed.mediaId)
                        assertEquals(original.localConfiguration, refreshed.localConfiguration)
                        assertEquals(original.clippingConfiguration, refreshed.clippingConfiguration)
                    }
                    recorder.flushCurrent()
                    assertEquals("clip-3", library.progress!!.trackId)
                    assertEquals(250L, library.progress!!.positionMs)

                    player.seekTo(0, 900)
                    player.play()
                    await { player.currentMediaItemIndex == 1 }
                    player.pause()
                    assertEquals("track:clip-1", player.currentMediaItem!!.mediaId)
                    assertTrue(player.currentPosition in 0L..999L)
                    assertEquals(1_000L, player.duration)
                    recorder.flushCurrent()
                    assertEquals("clip-1", library.progress!!.trackId)
                    assertTrue(library.progress!!.positionMs in 0L..999L)
                    assertNull(player.playerError)
                } finally {
                    recorder.stop().join()
                    scope.cancel()
                    player.release()
                }
            }
            assertEquals("All clips must reuse the same full-file cache", length, storage.cache.cacheSpace)
        }
    }

    @Test
    fun sharedDownloadMetadataRestoresEveryTrackAndRemovalCleansOrphanResource() = runBlocking {
        withStorage { context, storage ->
            val source = File(context.filesDir, "shared.wav").apply { writeBytes(wav()) }
            val tracks = tracks(source)
            val request = storage.downloadRequest(tracks)
            val length = source.length()
            withContext(Dispatchers.IO) {
                ProgressiveDownloader(request.toMediaItem(), CacheDataSource.Factory()
                    .setCache(storage.cache).setUpstreamDataSourceFactory(FileDataSource.Factory()))
                    .download(null)
                storage.index.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, length,
                    Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
                    DownloadProgress().apply { bytesDownloaded = length; percentDownloaded = 100f }))
            }
            val library = RangeLibrary(tracks)
            val initial = BookDownloads(context, library, storage).apply { initialize() }
            val restored = BookDownloads(context, library, storage).apply { initialize() }
            assertEquals(tracks.map { it.id }.toSet(), restored.state.value.keys)
            assertEquals(initial.state.value, restored.state.value)
            assertTrue(restored.state.value.values.all { it.status == DownloadStatus.COMPLETED })
            assertEquals(setOf("book-audio"), restored.state.value.values.map { it.resourceId }.toSet())
            assertEquals(4, summarizeBookDownloads(tracks.map { it.id }, restored.state.value).completedTracks)

            // Simulate an interrupted prior removal after its index record was deleted. Retrying
            // full-book removal must still clean the shared cache key and every logical state.
            withContext(Dispatchers.IO) { storage.index.removeDownload(request.id) }
            var deleted = false
            restored.removeBookAndAwait("book") { deleted = true }
            assertTrue(deleted)
            assertTrue(restored.state.value.isEmpty())
            assertTrue(storage.cache.keys.isEmpty())
            assertNull(storage.index.getDownload(request.id))
        }
    }

    @Test
    fun legacyDownloadMetadataKeepsItsTrackIdentity() = runBlocking {
        withStorage { _, storage ->
            val request = DownloadRequest.Builder("legacy-track", "https://example.test/file.mp3".toUri())
                .setCustomCacheKey("legacy-track")
                .setData("""{"bookId":"book","streamReferer":null,"downloadReferer":null}""".toByteArray()).build()
            val download = Download(request, Download.STATE_QUEUED, 0, 0, -1,
                Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
            val states = storage.states(download)
            assertEquals(setOf("legacy-track"), states.keys)
            assertEquals("legacy-track", states.getValue("legacy-track").resourceId)
            assertFalse(states.getValue("legacy-track").status == DownloadStatus.COMPLETED)
        }
    }

    private fun tracks(source: File) = (0..3).map { index ->
        LibraryTrack("clip-$index", "book", "Chapter $index", source.toUri().toString(), index,
            durationMs = 1_000, sourceStartMs = index * 1_000L, sourceEndMs = (index + 1) * 1_000L,
            audioResourceId = "book-audio")
    }

    private suspend fun withStorage(block: suspend (IsolatedContext, MediaStorage) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "ranges-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(base, directory)
        val storage = withContext(Dispatchers.Main.immediate) { MediaStorage(context) }
        try {
            block(context, storage)
        } finally {
            withContext(Dispatchers.Main.immediate) { storage.downloadManager.release() }
            withContext(Dispatchers.IO) { storage.cache.release() }
            context.closeDatabases()
            directory.deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    private fun wav(): ByteArray {
        val dataSize = 8_000 * 4 * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
            repeat(dataSize / 2) { putShort(0) }
        }.array()
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = mutableListOf<SQLiteDatabase>()
        val serviceIntents = mutableListOf<Intent>()
        override fun startForegroundService(service: Intent): ComponentName? {
            serviceIntents.add(service)
            return service.component
        }
        override fun startService(service: Intent): ComponentName? = startForegroundService(service)
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory).also { databases.add(it) }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler).also { databases.add(it) }
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        fun closeDatabases() = databases.forEach { it.close() }
    }

    private class RangeLibrary(private val tracks: List<LibraryTrack>) : LibraryRepository {
        var progress: ListeningProgress? = null
        override fun observeBooks(): Flow<List<LibraryBook>> = flowOf(listOf(LibraryBook("book", "Book")))
        override fun observeCollection(): Flow<List<LibraryCollectionEntry>> = flowOf(emptyList())
        override suspend fun getBook(bookId: String): LibraryBook? = LibraryBook("book", "Book").takeIf { it.id == bookId }
        override suspend fun getTracks(bookId: String): List<LibraryTrack> = tracks.filter { it.bookId == bookId }
        override suspend fun getTrack(trackId: String): LibraryTrack? = tracks.find { it.id == trackId }
        override suspend fun getProgress(bookId: String): ListeningProgress? = progress
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) {
            progress = ListeningProgress(bookId, trackId, positionMs, 1)
        }
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition = error("Not used")
        override suspend fun deleteBook(bookId: String) = Unit
    }
}
