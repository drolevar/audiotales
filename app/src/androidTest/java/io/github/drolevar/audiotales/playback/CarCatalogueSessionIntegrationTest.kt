package io.github.drolevar.audiotales.playback

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionError
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.CataloguePage
import io.github.drolevar.audiotales.data.online.LibraryProtocol
import io.github.drolevar.audiotales.data.online.OnlineBookDetailsSource
import io.github.drolevar.audiotales.data.online.OnlineCatalogue
import io.github.drolevar.audiotales.data.online.OnlineLibrary
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * Exercises the production callback through a real Media3 browser/session transport. The isolated
 * test player is never prepared or played; no provider or media network request is made. These are
 * not Android Auto UI or separate-UID trust tests. Run only on the task-owned emulator.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CarCatalogueSessionIntegrationTest {
    @Test
    fun canonicalBookMetadataOverridesCallerAndEmbeddedTrackArtwork() = runBlocking {
        withFixture { fixture ->
            val book = LibraryBook("canonical-cover", "Book title", "Book author",
                coverUrl = "https://covers.invalid/book.jpg", coverReferer = "https://library.invalid/")
            fixture.library.seed(book, tracks(book.id))
            val supplied = MediaMetadata.Builder().setTitle("Caller title").setArtist("Caller artist")
                .setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
            onMain {
                fixture.browser.setMediaItem(MediaItem.Builder().setMediaId("track:${book.id}:0")
                    .setMediaMetadata(supplied).build())
            }
            fixture.await("Canonical request did not reach the player") {
                onMain { fixture.player.currentMediaItem?.mediaId == "track:${book.id}:0" }
            }
            onMain {
                val metadata = fixture.player.mediaMetadata
                assertEquals("Chapter 0", metadata.title.toString())
                assertEquals(book.displayTitle, metadata.albumTitle.toString())
                assertEquals("Book author", metadata.artist?.toString())
                assertEquals("Book author", metadata.albumArtist?.toString())
                assertTrue("Playback metadata must identify the repository book cover", metadata.artworkUri != null)
                assertTrue(metadata.artworkUri.toString().startsWith("audiotales-artwork://book/"))
                assertNull(metadata.artworkData)
                // ExoPlayer merges extracted track metadata first, then the authoritative item.
                val merged = supplied.buildUpon().populate(metadata).build()
                assertEquals(metadata.artworkUri, merged.artworkUri)
                assertNull("Book cover URI must clear unrelated embedded bytes", merged.artworkData)
                assertEquals(2, fixture.player.mediaItemCount)
                assertEquals(metadata.artworkUri, fixture.player.getMediaItemAt(1).mediaMetadata.artworkUri)
                assertFalse(fixture.player.playWhenReady)
            }
            val originalUri = onMain { fixture.player.mediaMetadata.artworkUri }
            onMain { fixture.browser.setMediaItem(MediaItem.Builder().setMediaId("book:${book.id}").build()) }
            fixture.await("Explicit queue retry reused the cached artwork attempt") {
                onMain { fixture.player.mediaMetadata.artworkUri != originalUri }
            }
        }
    }

    @Test
    fun onlineSearchDoesNotSaveAndExplicitSelectionResolvesOnlyChosenBook() = runBlocking {
        withFixture { fixture ->
            fixture.selectBaseline()
            val baseline = fixture.playerState()
            val items = fixture.search("  УШКО  ")
            assertEquals(listOf("УШКО"), fixture.online.requests.map { it.second })
            assertEquals(2, items.size)
            items.forEach { item ->
                assertEquals(false, item.mediaMetadata.isBrowsable)
                assertEquals(true, item.mediaMetadata.isPlayable)
                assertNull(item.localConfiguration)
                assertNull(item.requestMetadata.mediaUri)
            }
            assertEquals("Remote one", items[0].mediaMetadata.title.toString())
            assertEquals("Author", items[0].mediaMetadata.artist.toString())
            assertEquals(baseline, fixture.playerState())
            assertEquals(setOf("baseline"), fixture.library.books.value.map { it.id }.toSet())
            assertEquals(0, fixture.library.additions)
            assertEquals(0, fixture.details.inspections)

            onMain { fixture.browser.setMediaItem(MediaItem.Builder().setMediaId(items[0].mediaId).build()) }
            fixture.await("Selected online book did not become its repository-owned queue") {
                onMain { fixture.player.currentMediaItem?.mediaId == "track:online-source:1:0" }
            }
            assertEquals(1, fixture.details.inspections)
            assertEquals(1, fixture.library.additions)
            assertEquals(setOf("baseline", "online-source:1"), fixture.library.books.value.map { it.id }.toSet())
            onMain {
                assertEquals(listOf("track:online-source:1:0", "track:online-source:1:1"),
                    (0 until fixture.player.mediaItemCount).map { fixture.player.getMediaItemAt(it).mediaId })
                assertEquals(0L, fixture.player.currentPosition)
                assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
                assertFalse(fixture.player.playWhenReady)
                assertNull(fixture.player.playerError)
            }
        }
    }

    @Test
    fun selectedAlreadySavedResultUsesHistoricalIdentityAndExactResumeWithoutProviderInspection() = runBlocking {
        withFixture { fixture ->
            val saved = LibraryBook("historical-id", "Saved title", "Author", "online-source", "1")
            fixture.library.seed(saved, tracks(saved.id), ListeningProgress(saved.id, "${saved.id}:1", 7_250, 1))
            val result = fixture.search("online").first()
            onMain { fixture.browser.setMediaItem(MediaItem.Builder().setMediaId(result.mediaId).build()) }
            fixture.await("Saved provider identity was not resumed") {
                onMain { fixture.player.currentMediaItem?.mediaId == "track:historical-id:1" }
            }
            onMain {
                assertEquals(7_250L, fixture.player.currentPosition)
                assertEquals(listOf("track:historical-id:0", "track:historical-id:1"),
                    (0 until fixture.player.mediaItemCount).map { fixture.player.getMediaItemAt(it).mediaId })
                assertFalse(fixture.player.playWhenReady)
            }
            assertEquals(0, fixture.details.inspections)
            assertEquals(0, fixture.library.additions)
            assertEquals(setOf("baseline", "historical-id"), fixture.library.books.value.map { it.id }.toSet())
        }
    }

    @Test
    fun blankMissingAndFailedSearchCompleteTheirNotificationWithoutChangingPlayback() = runBlocking {
        withFixture { fixture ->
            fixture.selectBaseline()
            val baseline = fixture.playerState()
            assertTrue(fixture.search("   \t ").isEmpty())
            assertTrue(fixture.search("none").isEmpty())
            val failure = onMain { fixture.browser.search("fail", null) }.boundedAwait()
            assertEquals(SessionError.ERROR_IO, failure.resultCode)
            fixture.await("Failed online search did not notify the original query") {
                onMain { fixture.notifications.contains("fail" to 0) }
            }
            assertEquals(SessionError.ERROR_IO,
                onMain { fixture.browser.getSearchResult("fail", 0, 100, null) }.boundedAwait().resultCode)
            assertEquals(baseline, fixture.playerState())
            assertEquals(0, fixture.library.additions)
            assertEquals(0, fixture.details.inspections)
        }
    }

    @Test
    fun forgedLocationAndDeselectedSourceCannotReplaceQueueOrSaveBook() = runBlocking {
        withFixture { fixture ->
            fixture.selectBaseline()
            val baseline = fixture.playerState()
            val result = fixture.search("online").first()
            for (request in listOf(
                MediaItem.Builder().setMediaId("car-catalogue:forged-id").build(),
                MediaItem.Builder().setMediaId(result.mediaId).setUri("https://caller.invalid/audio.mp3").build(),
            )) {
                onMain { fixture.browser.setMediaItem(request) }
                fixture.await("Rejected request did not restore the controller's canonical queue") {
                    onMain { fixture.browser.currentMediaItem?.mediaId == baseline.currentId }
                }
                assertEquals(baseline, fixture.playerState())
            }
            fixture.online.sources.value = listOf(fixture.online.source.copy(isSelected = false))
            onMain { fixture.browser.setMediaItem(MediaItem.Builder().setMediaId(result.mediaId).build()) }
            fixture.await("Deselected source request did not restore the canonical queue") {
                onMain { fixture.browser.currentMediaItem?.mediaId == baseline.currentId }
            }
            assertEquals(baseline, fixture.playerState())
            assertEquals(0, fixture.library.additions)
            assertEquals(0, fixture.details.inspections)
        }
    }

    @Test
    fun sourceDisabledDuringQueueResolutionCannotReplaceExistingQueue() = runBlocking {
        withFixture { fixture ->
            fixture.selectBaseline()
            val baseline = fixture.playerState()
            val saved = LibraryBook("historical-id", "Saved title", "Author", "online-source", "1")
            fixture.library.seed(saved, tracks(saved.id))
            val result = fixture.search("online").first()
            fixture.library.suspendTracksFor = saved.id
            onMain { fixture.browser.setMediaItem(MediaItem.Builder().setMediaId(result.mediaId).build()) }
            withTimeout(10_000) { fixture.library.tracksEntered.await() }
            fixture.online.sources.value = listOf(fixture.online.source.copy(isSelected = false))
            fixture.library.resumeTracks.complete(Unit)
            fixture.await("Source change during resolution did not reject the request") {
                onMain { fixture.browser.currentMediaItem?.mediaId == baseline.currentId }
            }
            assertEquals(baseline, fixture.playerState())
            assertEquals(0, fixture.library.additions)
            assertEquals(0, fixture.details.inspections)
        }
    }

    @Test
    fun refreshedNormalizedQueryInvalidatesOlderSnapshotInsteadOfReturningStalePlayableIds() = runBlocking {
        withFixture { fixture ->
            fixture.selectBaseline()
            val baseline = fixture.playerState()
            val original = fixture.search("online")
            val refreshed = fixture.search(" online ")
            assertTrue(original.map { it.mediaId }.toSet().intersect(refreshed.map { it.mediaId }.toSet()).isEmpty())
            assertEquals(SessionError.ERROR_INVALID_STATE,
                onMain { fixture.browser.getSearchResult("online", 0, 100, null) }.boundedAwait().resultCode)
            assertEquals(baseline, fixture.playerState())
            assertEquals(0, fixture.library.additions)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic")) {
            "This suite is restricted to the task-owned emulator; do not run on a physical device"
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "car-session-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(base, directory)
        val fixture = onMain { Fixture(context) }
        try {
            fixture.connect()
            block(fixture)
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                } finally {
                    try {
                        withContext(Dispatchers.IO) { fixture.storage.cache.release() }
                    } finally {
                        context.closeDatabases()
                        directory.deleteRecursively()
                    }
                }
            }
        }
    }

    private data class PlayerState(val ids: List<String>, val currentId: String?, val position: Long,
        val state: Int, val playWhenReady: Boolean)

    private class Fixture(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val library = MemoryLibrary()
        val online = FakeCatalogue()
        val details = FakeDetails()
        val storage = MediaStorage(context)
        val player = ExoPlayer.Builder(context).build()
        val progress = PlaybackProgressRecorder(player, library) { }
        val sleep = SleepTimerController(player, onStateChanged = { })
        val session: MediaLibrarySession
        val notifications = mutableListOf<Pair<String, Int>>()
        lateinit var browser: MediaBrowser
        private var browserFuture: ListenableFuture<MediaBrowser>? = null

        init {
            val baseline = LibraryBook("baseline", "My saved book")
            library.seed(baseline, tracks(baseline.id), ListeningProgress(baseline.id, "baseline:1", 4_500, 1))
            progress.start(scope)
            sleep.start(scope)
            val callback = LibrarySessionCallback(library, scope, "AudioTales", "My books", storage,
                progress, sleep, context.packageName, Process.myUid(), BookRemovalGuard(), online, details)
            session = MediaLibrarySession.Builder(context, player, callback)
                .setId("car-search-test-${UUID.randomUUID()}").build()
        }

        suspend fun connect() {
            val future = onMain {
                MediaBrowser.Builder(InstrumentationRegistry.getInstrumentation().targetContext, session.token)
                    .setApplicationLooper(Looper.getMainLooper())
                    .setListener(object : MediaBrowser.Listener {
                        override fun onSearchResultChanged(browser: MediaBrowser, query: String,
                            itemCount: Int, params: LibraryParams?) {
                            notifications.add(query to itemCount)
                        }
                    }).buildAsync()
            }
            browserFuture = future
            browser = future.boundedAwait()
            onMain { assertTrue(browser.isConnected) }
        }

        suspend fun search(query: String): List<MediaItem> {
            assertEquals(LibraryResult.RESULT_SUCCESS, onMain { browser.search(query, null) }.boundedAwait().resultCode)
            await("Search notification did not preserve the original query") {
                onMain { notifications.any { it.first == query } }
            }
            val result = onMain { browser.getSearchResult(query, 0, 100, null) }.boundedAwait()
            assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
            val items = requireNotNull(result.value).toList()
            onMain { assertTrue(notifications.contains(query to items.size)) }
            return items
        }

        suspend fun selectBaseline() {
            onMain { browser.setMediaItem(MediaItem.Builder().setMediaId("book:baseline").build()) }
            await("Baseline queue did not reach the actual player") {
                onMain { player.currentMediaItem?.mediaId == "track:baseline:1" }
            }
            onMain { assertEquals(4_500L, player.currentPosition) }
        }

        suspend fun playerState(): PlayerState = onMain {
            PlayerState((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                player.currentMediaItem?.mediaId, player.currentPosition, player.playbackState, player.playWhenReady)
        }

        suspend fun await(message: String, predicate: suspend () -> Boolean) {
            withTimeout(10_000) {
                while (!predicate()) delay(10)
            }
            assertTrue(message, predicate())
        }

        suspend fun close() {
            val writer = onMain {
                browserFuture?.let { MediaController.releaseFuture(it) }
                sleep.stop()
                val writer = progress.stop()
                session.release()
                player.release()
                storage.downloadManager.release()
                writer
            }
            try {
                withTimeout(2_000) { writer.join() }
            } finally {
                scope.cancel()
            }
        }
    }

    private class MemoryLibrary : LibraryRepository {
        val books = MutableStateFlow<List<LibraryBook>>(emptyList())
        private val tracks = mutableMapOf<String, List<LibraryTrack>>()
        private val progress = mutableMapOf<String, ListeningProgress>()
        var additions = 0
        var suspendTracksFor: String? = null
        val tracksEntered = CompletableDeferred<Unit>()
        val resumeTracks = CompletableDeferred<Unit>()
        fun seed(book: LibraryBook, bookTracks: List<LibraryTrack>, position: ListeningProgress? = null) {
            books.value = books.value.filterNot { it.id == book.id } + book
            tracks[book.id] = bookTracks
            position?.let { progress[book.id] = it }
        }
        override fun observeBooks(): Flow<List<LibraryBook>> = books
        override fun observeCollection(): Flow<List<LibraryCollectionEntry>> = flowOf(emptyList())
        override suspend fun getBook(bookId: String): LibraryBook? = books.value.find { it.id == bookId }
        override suspend fun getTracks(bookId: String): List<LibraryTrack> {
            if (bookId == suspendTracksFor) {
                tracksEntered.complete(Unit)
                resumeTracks.await()
            }
            return tracks[bookId].orEmpty()
        }
        override suspend fun getTrack(trackId: String): LibraryTrack? = tracks.values.flatten().find { it.id == trackId }
        override suspend fun getProgress(bookId: String): ListeningProgress? = progress[bookId]
        override suspend fun saveProgress(bookId: String, trackId: String, positionMs: Long) {
            progress[bookId] = ListeningProgress(bookId, trackId, positionMs, 1)
        }
        override suspend fun addOnlineBook(book: ResolvedOnlineBook): LibraryAddition {
            additions++
            val saved = LibraryBook(book.id, book.title, book.author, book.providerId, book.providerBookId)
            seed(saved, book.tracks)
            return LibraryAddition(saved, true)
        }
        override suspend fun deleteBook(bookId: String) = error("This test never removes saved books")
    }

    private class FakeCatalogue : OnlineCatalogue {
        val source = OnlineLibrary("online-source", "https://library.invalid/", "Online library", LibraryProtocol.PARTNER)
        val sources = MutableStateFlow(listOf(source))
        val requests = mutableListOf<Pair<String, String>>()
        override fun observeLibraries(): Flow<List<OnlineLibrary>> = sources
        override suspend fun browse(libraryId: String, query: String, offset: Int): CataloguePage {
            requests.add(libraryId to query)
            if (query == "fail") throw IOException("Controlled provider failure")
            return CataloguePage(if (query == "none") emptyList() else listOf(
                CatalogueBook(source.id, "1", "Author - Remote one", "Author", coverUrl = null, sourceUrl = null),
                CatalogueBook(source.id, "2", "Remote two", null, coverUrl = null, sourceUrl = null),
            ), null)
        }
        override suspend fun addLibrary(input: String): OnlineLibrary = error("Not used")
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition = error("Search must not save")
        override suspend fun setLibrarySelected(id: String, selected: Boolean) = error("Not used")
        override suspend fun renameLibrary(id: String, name: String) = error("Not used")
        override suspend fun removeLibrary(id: String) = error("Not used")
    }

    private class FakeDetails : OnlineBookDetailsSource {
        var inspections = 0
        override suspend fun inspectBook(libraryId: String, remoteId: String): ResolvedOnlineBook {
            inspections++
            val id = "$libraryId:$remoteId"
            return ResolvedOnlineBook(id, libraryId, remoteId, "https://library.invalid/book/$remoteId",
                "Remote one", "Author", tracks(id))
        }
        override suspend fun inspectBookMetadata(libraryId: String, remoteId: String): LibraryBook = error("Not used")
        override suspend fun inspectBookAudio(book: LibraryBook): ResolvedOnlineBook = error("Not used")
        override suspend fun getLibrary(libraryId: String): OnlineLibrary = error("Not used")
        override suspend fun refreshBookMetadata(bookId: String): LibraryBook? = error("Not used")
        override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition = error("Callback must save resolved repository data")
        override suspend fun refreshBookSources(bookId: String) = error("Not used")
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = mutableListOf<SQLiteDatabase>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory).also(databases::add)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler).also(databases::add)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        fun closeDatabases() = databases.forEach { it.close() }
    }

    companion object {
        private fun tracks(bookId: String): List<LibraryTrack> = (0..1).map {
            LibraryTrack("$bookId:$it", bookId, "Chapter $it", "https://media.invalid/$bookId/$it.mp3", it, 60_000)
        }
        private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
        private suspend fun <T> ListenableFuture<T>.boundedAwait(): T = withTimeout(10_000) { await() }
    }
}
