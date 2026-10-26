package io.github.drolevar.audiotales.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ReaderCredit
import io.github.drolevar.audiotales.data.library.ReaderCreditsCodec
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import io.github.drolevar.audiotales.data.library.RoomLibraryRepository
import io.github.drolevar.audiotales.data.online.OnlineBookError
import io.github.drolevar.audiotales.data.online.OnlineBookException
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.data.online.ProviderHttpClient
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderCreditStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val oldCredits = listOf(ReaderCredit("7", "Saved Reader"))
    private val newCredits = listOf(ReaderCredit("8", "New Reader"), ReaderCredit("9", "Another Reader"))

    @Test fun populatedVersionSevenMigrationPreservesRecordingAndAllListeningData() = runTest {
        val name = "reader-migration-v7.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/7.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                old.execSQL("INSERT INTO books VALUES ('saved', 'Saved book', 'Author', 'org', '42', 'https://librivox.org/book/', 'https://archive.org/cover.jpg', NULL, 'Description', 'English')")
                old.execSQL("INSERT INTO tracks VALUES ('one', 'saved', 'One', 'https://archive.org/audio.mp3?old=1', 0, 2000, '1', 'https://librivox.org/stream', 'https://librivox.org/download', 1000, 3000, 'resource')")
                old.execSQL("INSERT INTO tracks VALUES ('two', 'saved', 'Two', 'https://archive.org/two.mp3', 1, 5000, '2', NULL, NULL, 0, NULL, NULL)")
                old.execSQL("INSERT INTO playback_progress VALUES ('saved', 'one', 1500, 10000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark', 'saved', 'one', 500, 'Keep', 9000)")
                old.execSQL("INSERT INTO online_libraries VALUES ('org', 'https://librivox.org/', 'LibriVox', 'LIBRIVOX', 0, 0)")
                old.version = 7
            }
            withDatabase(name) { database ->
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                val tracks = repository.getTracks("saved")
                assertEquals(listOf("one", "two"), tracks.map { it.id })
                assertEquals(listOf("resource", "two"), tracks.map { it.downloadId })
                assertEquals(listOf(null, null), tracks.map { it.readerCredits })
                assertEquals(1000L, tracks.first().sourceStartMs)
                assertEquals(3000L, tracks.first().sourceEndMs)
                assertEquals("https://archive.org/audio.mp3?old=1", tracks.first().uri)
                assertEquals("https://librivox.org/stream", tracks.first().streamReferer)
                assertEquals("https://librivox.org/download", tracks.first().downloadReferer)
                assertEquals("Description", repository.getBook("saved")?.description)
                assertEquals("Author", repository.getBook("saved")?.author)
                assertEquals(PlaybackProgressEntity("saved", "one", 1500, 10000), dao.getProgress("saved"))
                assertEquals(BookmarkEntity("mark", "saved", "one", 500, "Keep", 9000), dao.observeBookmarks("saved").first().single())
                assertEquals(false, dao.getOnlineLibrary("org")?.isRegistered)
                assertEquals(false, dao.getOnlineLibrary("org")?.isSelected)
            }
            withDatabase(name) { database ->
                assertEquals(listOf("resource", "two"), RoomLibraryRepository(database.libraryDao()).getTracks("saved").map { it.downloadId })
                assertEquals(1500L, database.libraryDao().getProgress("saved")?.positionMs)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun saveAndReopenRoundTripsKnownUnknownAndEmptyCreditsAndTransportRenewalPreservesThem() = runTest {
        val name = "reader-roundtrip.db"
        context.deleteDatabase(name)
        val original = book().copy(tracks = book().tracks + book().tracks.first().copy(
            id = "three", sequenceIndex = 2, providerTrackRef = "3", readerCredits = emptyList()))
        try {
            withDatabase(name) { database ->
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                repository.addOnlineBook(original.copy(tracks = original.tracks.map {
                    if (it.id == "two") it.copy(readerCredits = null) else it
                }))
                val stored = repository.getTracks("saved")
                dao.refreshTrackSources("saved", dao.getTracks("saved").map {
                    it.copy(uri = "https://renewed.example/${it.id}.mp3", readerCreditsJson = "[]")
                })
                assertEquals(stored.map { it.readerCredits }, repository.getTracks("saved").map { it.readerCredits })
            }
            withDatabase(name) { database ->
                val repository = RoomLibraryRepository(database.libraryDao())
                assertEquals(listOf(oldCredits, null, emptyList<ReaderCredit>()), repository.getTracks("saved").map { it.readerCredits })
                assertEquals(listOf("one", "two", "three"), repository.getTracks("saved").map { it.downloadId })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun metadataRefreshUsesOneExtendedResponseWithoutValidAudioAndPreservesOmittedMismatchAndOfflineCredits() = runTest {
        val name = "reader-refresh.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { database ->
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                repository.addOnlineBook(book())
                dao.insertOnlineLibrary(OnlineLibraryEntity("org", "https://librivox.org/", "LibriVox", "LIBRIVOX", false, false))
                dao.saveProgress(PlaybackProgressEntity("saved", "one", 1200, 4000))
                dao.insertBookmark(BookmarkEntity("mark", "saved", "one", 500, "Keep", 3000))
                val original = repository.getTracks("saved")
                var response = metadata("\"readers\":[{\"reader_id\":\"8\",\"display_name\":\"New Reader\"},{\"reader_id\":\"9\",\"display_name\":\"Another Reader\"}]")
                var offline = false
                val requests = mutableListOf<HttpUrl>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    requests += chain.request().url
                    if (offline) throw IOException("Controlled offline")
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(response.toResponseBody()).build()
                }.build()
                val source = OnlineLibraries(dao, repository, ProviderHttpClient(client))
                source.refreshBookMetadata("saved")
                assertEquals("1", requests.single().queryParameter("extended"))
                assertEquals(listOf(original.first().copy(readerCredits = newCredits), original.last()), repository.getTracks("saved"))
                response = metadata("\"readers\":[]", "\"readers\":{}")
                source.refreshBookMetadata("saved")
                val cleared = listOf(original.first().copy(readerCredits = emptyList()), original.last())
                assertEquals(cleared, repository.getTracks("saved"))
                response = metadata("\"readers\":[]", secondId = "3").replace("Refreshed", "Changed set")
                source.refreshBookMetadata("saved")
                assertEquals("Changed set", repository.getBook("saved")?.description)
                assertEquals(cleared, repository.getTracks("saved"))
                response = metadata("\"readers\":[]").replace("\"num_sections\":\"2\"", "\"num_sections\":\"3\"")
                source.refreshBookMetadata("saved")
                assertEquals(cleared, repository.getTracks("saved"))
                offline = true
                try { source.refreshBookMetadata("saved"); fail("Expected offline refresh failure") }
                catch (error: OnlineBookException) { assertEquals(OnlineBookError.NETWORK, error.reason) }
                assertEquals(cleared, repository.getTracks("saved"))
                assertEquals(PlaybackProgressEntity("saved", "one", 1200, 4000), dao.getProgress("saved"))
                assertEquals("Keep", dao.observeBookmarks("saved").first().single().label)
                assertEquals("Saved author", repository.getBook("saved")?.author)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun readerTransactionRejectsWrongIdentitiesAndRollsBackAllCreditsOnUpdateFailure() = runTest {
        val name = "reader-atomic.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { database ->
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                repository.addOnlineBook(book())
                val original = repository.getTracks("saved")
                val json = ReaderCreditsCodec.encode(newCredits)
                assertFalse(dao.refreshTrackReaderCredits("saved", "wrong", "42", mapOf("1" to json, "2" to json)))
                assertFalse(dao.refreshTrackReaderCredits("saved", "org", "42", mapOf("1" to json)))
                assertFalse(dao.refreshTrackReaderCredits("saved", "org", "42", mapOf("1" to json, "3" to json)))
                assertEquals(original, repository.getTracks("saved"))
                SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
                    sql.execSQL("CREATE TRIGGER fail_second_credit BEFORE UPDATE OF readerCreditsJson ON tracks WHEN OLD.id = 'two' BEGIN SELECT RAISE(ABORT, 'Controlled credit failure'); END")
                }
                try {
                    dao.refreshTrackReaderCredits("saved", "org", "42", mapOf("1" to json, "2" to json))
                    fail("The second update must fail")
                } catch (_: android.database.SQLException) { /* Expected rollback. */ }
                assertEquals(original, repository.getTracks("saved"))
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun metadata(first: String, second: String = "", secondId: String = "2") =
        """{"books":[{"id":"42","title":"Remote title","description":"Refreshed","num_sections":"2","sections":[
            {"id":"1","listen_url":"file:///unplayable.mp3",$first},
            {"id":"$secondId"${if (second.isEmpty()) "" else ",$second"}}]}]}"""

    private fun book() = ResolvedOnlineBook("saved", "org", "42", "https://librivox.org/book/", "Saved", "Saved author",
        listOf(LibraryTrack("one", "saved", "One", "https://archive.org/one.mp3", 0, providerTrackRef = "1", readerCredits = oldCredits),
            LibraryTrack("two", "saved", "Two", "https://archive.org/two.mp3", 1, providerTrackRef = "2", readerCredits = oldCredits)))

    private fun openDatabase(name: String): AudioTalesDatabase = Room.databaseBuilder<AudioTalesDatabase>(context, name)
        .setDriver(AndroidSQLiteDriver()).addMigrations(LibraryMigrations.MIGRATION_7_8).build()

    private suspend fun withDatabase(name: String, block: suspend (AudioTalesDatabase) -> Unit) {
        val database = openDatabase(name)
        try { block(database) } finally { database.close() }
    }
}
