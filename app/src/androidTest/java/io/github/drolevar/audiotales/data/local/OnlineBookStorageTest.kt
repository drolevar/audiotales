package io.github.drolevar.audiotales.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ReaderCredit
import io.github.drolevar.audiotales.data.library.ReaderCreditsCodec
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import io.github.drolevar.audiotales.data.library.RoomLibraryRepository
import io.github.drolevar.audiotales.data.online.LibraryProtocolParser
import io.github.drolevar.audiotales.data.online.OnlineBookException
import io.github.drolevar.audiotales.data.online.OnlineLibraries
import io.github.drolevar.audiotales.data.online.ProviderHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(AndroidJUnit4::class)
class OnlineBookStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun versionSixMigrationPreservesWholeFileHistoryAndRoundTripsRangedTracks() = runTest {
        val name = "migration-ranges-v6-test.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/6.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(text: String) = text.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                old.execSQL("INSERT INTO books VALUES ('saved', 'Saved book', 'Author', 'library', '42', 'https://library.example/', NULL, NULL, 'Description', 'English')")
                old.execSQL("INSERT INTO tracks VALUES ('track', 'saved', 'Chapter', 'https://cdn.example/book.mp3', 0, 100000, '/book.mp3', 'https://library.example/mobapp', 'https://library.example/')")
                old.execSQL("INSERT INTO playback_progress VALUES ('saved', 'track', 45000, 10000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark', 'saved', 'track', 15000, 'Keep this', 9000)")
                old.version = 6
            }
            val original = book()
            val resource = original.tracks.single().id
            val first = original.tracks.single().copy(id = "section-one", durationMs = 290_000,
                providerTrackRef = "/book.mp3#t=0-290000", sourceEndMs = 290_000, audioResourceId = resource)
            val second = first.copy(id = "section-two", sequenceIndex = 1, durationMs = 197_000,
                providerTrackRef = "/book.mp3#t=290000-487000", sourceStartMs = 290_000, sourceEndMs = 487_000)
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                val legacy = requireNotNull(repository.getTrack("track"))
                assertEquals(0L, legacy.sourceStartMs)
                assertNull(legacy.sourceEndMs)
                assertNull(legacy.audioResourceId)
                assertEquals("track", legacy.downloadId)
                assertEquals(PlaybackProgressEntity("saved", "track", 45000, 10000), dao.getProgress("saved"))
                assertEquals(BookmarkEntity("mark", "saved", "track", 15000, "Keep this", 9000),
                    dao.observeBookmarks("saved").first().single())
                assertEquals("Description", repository.getBook("saved")?.description)
                assertTrue(repository.addOnlineBook(original.copy(tracks = listOf(first, second))).added)
                assertEquals(listOf(first, second), repository.getTracks(original.id))
            } finally { database.close() }
            val reopened = openDatabase(name)
            try {
                val repository = RoomLibraryRepository(reopened.libraryDao())
                assertEquals(listOf(first, second), repository.getTracks(original.id))
                assertEquals(listOf(resource, resource), repository.getTracks(original.id).map { it.downloadId })
                assertEquals(45000L, repository.getProgress("saved")?.positionMs)
                assertEquals("Keep this", reopened.libraryDao().observeBookmarks("saved").first().single().label)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun rangedSourceRenewalPreservesClipsResourcesAndHistoryAndRejectsChangedRanges() = runTest {
        val name = "ranged-source-refresh-test.db"
        context.deleteDatabase(name)
        val database = openDatabase(name)
        try {
            val dao = database.libraryDao()
            val original = book()
            val first = original.tracks.single().copy(id = "section-one", durationMs = 290_000,
                providerTrackRef = "/book.mp3#t=0-290000", sourceEndMs = 290_000, audioResourceId = "shared")
            val second = first.copy(id = "section-two", sequenceIndex = 1, durationMs = 197_000,
                providerTrackRef = "/book.mp3#t=290000-487000", sourceStartMs = 290_000, sourceEndMs = 487_000)
            val repository = RoomLibraryRepository(dao)
            repository.addOnlineBook(original.copy(tracks = listOf(first, second)))
            dao.saveProgress(PlaybackProgressEntity(original.id, second.id, 45_000, 10_000))
            dao.insertBookmark(BookmarkEntity("range-mark", original.id, second.id, 15_000, "Keep this", 9_000))
            val replacements = dao.getTracks(original.id).map {
                it.copy(uri = "https://renewed.example/book.mp3?token=new")
            }
            dao.refreshTrackSources(original.id, replacements.reversed())
            val renewed = repository.getTracks(original.id)
            assertEquals(listOf(first, second).map { it.copy(uri = "https://renewed.example/book.mp3?token=new") }, renewed)
            for (changed in listOf(
                replacements.last().copy(sourceStartMs = 290_001),
                replacements.last().copy(sourceEndMs = 487_001),
                replacements.last().copy(audioResourceId = "different"),
            )) {
                try {
                    dao.refreshTrackSources(original.id, listOf(replacements.first().copy(uri = "https://wrong.example/book.mp3"), changed))
                    fail("Changed clip or recording must not renew saved transport")
                } catch (_: IllegalArgumentException) { /* Expected, including transaction rollback. */ }
                assertEquals(renewed, repository.getTracks(original.id))
            }
            assertEquals(PlaybackProgressEntity(original.id, second.id, 45_000, 10_000), dao.getProgress(original.id))
            assertEquals("Keep this", dao.observeBookmarks(original.id).first().single().label)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionOneHistorySurvivesMigrationAndNewOnlineBooksCanBeSaved() = runTest {
        val name = "migration-online-test.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/1.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indexes = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indexes.length()) {
                        old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                }
                old.execSQL("INSERT INTO books VALUES ('old-book', 'Existing book', 'Existing author')")
                old.execSQL("INSERT INTO tracks VALUES ('old-track', 'old-book', 'Existing chapter', 'content://existing', 0, 20000)")
                old.execSQL("INSERT INTO playback_progress VALUES ('old-book', 'old-track', 1200, 4000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('old-mark', 'old-book', 'old-track', 500, 'Remember', 3000)")
                old.version = 1
            }
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                assertEquals("Existing book", dao.getBook("old-book")?.title)
                assertNull(dao.getBook("old-book")?.providerId)
                assertNull(dao.getBook("old-book")?.coverUrl)
                assertNull(dao.getBook("old-book")?.coverReferer)
                assertEquals(1200L, dao.getProgress("old-book")?.positionMs)
                assertEquals("Remember", dao.observeBookmarks("old-book").first().single().label)
                val repository = RoomLibraryRepository(dao)
                assertTrue(repository.addOnlineBook(book()).added)
                assertEquals(book().sourceUrl, dao.getBook(book().id)?.sourceUrl)
                assertEquals(book().coverUrl, repository.getBook(book().id)?.coverUrl)
                assertEquals(book().coverReferer, repository.getBook(book().id)?.coverReferer)
                assertEquals(book().description, repository.getBook(book().id)?.description)
                assertEquals(book().language, repository.getBook(book().id)?.language)
                assertEquals("/s04/999/track-01.mp3", dao.getTracks(book().id).single().providerTrackRef)
                assertEquals(2, dao.observeBooks().first().size)
                assertEquals("https://library.example/mobapp", dao.getTracks(book().id).single().streamReferer)
                dao.insertOnlineLibrary(OnlineLibraryEntity("library", "https://library.example/", "Example", "PARTNER"))
                assertEquals("https://library.example/", dao.getOnlineLibrary("library")?.baseUrl)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionTwoProviderIdentityAndHistorySurviveMigration() = runTest {
        val name = "migration-provider-v2-test.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/2.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(text: String) = text.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                old.execSQL("INSERT INTO books VALUES ('saved', 'Saved book', 'Author', 'audioknigi.com.ua', '42', 'https://audioknigi.com.ua/audio-42-book')")
                old.execSQL("INSERT INTO tracks VALUES ('saved-track', 'saved', 'Chapter', 'https://cdn.example/book.mp3', 0, 100000, '/book.mp3')")
                old.execSQL("INSERT INTO playback_progress VALUES ('saved', 'saved-track', 45000, 10000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('saved-mark', 'saved', 'saved-track', 15000, 'Keep this', 9000)")
                old.version = 2
            }
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                val libraryId = LibraryProtocolParser.libraryId("https://audioknigi.com.ua/".toHttpUrl())
                assertEquals("42", dao.getBook("saved")?.providerBookId)
                assertEquals(libraryId, dao.getBook("saved")?.providerId)
                assertEquals("https://audioknigi.com.ua/", dao.getOnlineLibrary(libraryId)?.baseUrl)
                assertEquals("PARTNER", dao.getOnlineLibrary(libraryId)?.protocol)
                assertEquals("https://audioknigi.com.ua/audio-42-book", dao.getBook("saved")?.sourceUrl)
                assertEquals("/book.mp3", dao.getTrack("saved-track")?.providerTrackRef)
                assertEquals("https://audioknigi.com.ua/mobapp", dao.getTrack("saved-track")?.streamReferer)
                assertEquals("https://audioknigi.com.ua/", dao.getTrack("saved-track")?.downloadReferer)
                val duplicate = book().copy(id = "$libraryId:42", providerId = libraryId, providerBookId = "42",
                    tracks = book().tracks.map { it.copy(bookId = "$libraryId:42") })
                val result = RoomLibraryRepository(dao).addOnlineBook(duplicate)
                assertFalse(result.added)
                assertEquals("saved", result.book.id)
                assertEquals(duplicate.coverUrl, result.book.coverUrl)
                assertEquals(duplicate.coverReferer, result.book.coverReferer)
                assertEquals("saved-track", dao.getTracks("saved").single().id)
                assertEquals(45000L, dao.getProgress("saved")?.positionMs)
                assertEquals("Keep this", dao.observeBookmarks("saved").first().single().label)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionThreeMigrationAddsNullableArtworkAndPreservesAllListeningData() = runTest {
        val name = "migration-artwork-v3-test.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/3.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(text: String) = text.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                old.execSQL("INSERT INTO books VALUES ('saved', 'Saved book', 'Author', 'library', '42', 'https://library.example/')")
                old.execSQL("INSERT INTO tracks VALUES ('track', 'saved', 'Chapter', 'https://cdn.example/book.mp3', 0, 100000, '/book.mp3', 'https://library.example/mobapp', 'https://library.example/')")
                old.execSQL("INSERT INTO playback_progress VALUES ('saved', 'track', 45000, 10000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark', 'saved', 'track', 15000, 'Keep this', 9000)")
                old.execSQL("INSERT INTO online_libraries VALUES ('library', 'https://library.example/', 'Example', 'PARTNER')")
                old.version = 3
            }
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                assertNull(dao.getBook("saved")?.coverUrl)
                assertNull(dao.getBook("saved")?.coverReferer)
                val before = requireNotNull(dao.getBook("saved"))
                val tracks = dao.getTracks("saved")
                assertEquals(1, dao.updateBookArtwork("saved", "library", "42", "https://covers.example/42.jpg", "https://library.example/"))
                assertEquals(before.copy(coverUrl = "https://covers.example/42.jpg", coverReferer = "https://library.example/"), dao.getBook("saved"))
                assertEquals(0, dao.updateBookArtwork("saved", "other-library", "42", "https://covers.example/wrong.jpg", null))
                assertEquals(tracks, dao.getTracks("saved"))
                assertEquals(45000L, dao.getProgress("saved")?.positionMs)
                assertEquals("Keep this", dao.observeBookmarks("saved").first().single().label)
                assertEquals("https://library.example/", dao.getOnlineLibrary("library")?.baseUrl)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionFourMigrationRegistersAndSelectsLibrariesWithoutChangingSavedData() = runTest {
        val name = "migration-registration-v4-test.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/4.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(text: String) = text.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                old.execSQL("INSERT INTO online_libraries VALUES ('library', 'https://library.example/', 'My library', 'PARTNER')")
                old.execSQL("INSERT INTO books VALUES ('saved', 'Saved book', 'Author', 'library', '42', 'https://library.example/', 'https://covers.example/book.jpg', 'https://library.example/')")
                old.execSQL("INSERT INTO tracks VALUES ('track', 'saved', 'Chapter', 'https://cdn.example/book.mp3', 0, 100000, '/book.mp3', 'https://library.example/mobapp', 'https://library.example/')")
                old.execSQL("INSERT INTO playback_progress VALUES ('saved', 'track', 45000, 10000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark', 'saved', 'track', 15000, 'Keep this', 9000)")
                old.version = 4
            }
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                assertEquals(OnlineLibraryEntity("library", "https://library.example/", "My library", "PARTNER"),
                    dao.observeOnlineLibraries().first().single())
                assertEquals(BookEntity("saved", "Saved book", "Author", "library", "42", "https://library.example/",
                    "https://covers.example/book.jpg", "https://library.example/"), dao.getBook("saved"))
                assertEquals(TrackEntity("track", "saved", "Chapter", "https://cdn.example/book.mp3", 0, 100000,
                    "/book.mp3", "https://library.example/mobapp", "https://library.example/"), dao.getTrack("track"))
                assertEquals(PlaybackProgressEntity("saved", "track", 45000, 10000), dao.getProgress("saved"))
                assertEquals(BookmarkEntity("mark", "saved", "track", 15000, "Keep this", 9000),
                    dao.observeBookmarks("saved").first().single())
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun selectionRenameRemovalAndReadditionPreserveSavedSourceAndListeningData() = runTest {
        val name = "library-management-test.db"
        context.deleteDatabase(name)
        try {
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                // Re-registration must preserve even a historical ID that differs from the URL hash.
                val source = OnlineLibraryEntity("historical-library", "https://librivox.org/", "LibriVox", "LIBRIVOX")
                dao.insertOnlineLibrary(source)
                val saved = book().copy(providerId = source.id, tracks = book().tracks.map { it.copy(providerTrackRef = "201") })
                repository.addOnlineBook(saved)
                val track = saved.tracks.single()
                dao.saveProgress(PlaybackProgressEntity(saved.id, track.id, 45000, 10000))
                dao.insertBookmark(BookmarkEntity("mark", saved.id, track.id, 15000, "Keep this", 9000))
                var requests = 0
                val online = onlineLibraries(dao) {
                    requests++
                    """{"books":[{"id":"123","title":"Remote book","url_librivox":"https://librivox.org/book/","num_sections":"1","sections":[
                        {"id":"201","section_number":"0","title":"Chapter","listen_url":"https://archive.org/renewed.mp3"}]}]}"""
                }
                online.setLibrarySelected(source.id, false)
                assertFalse(online.observeLibraries().first().single().isSelected)
                online.renameLibrary(source.id, "  My classics  ")
                assertEquals("My classics", online.observeLibraries().first().single().displayName)
                for (invalid in listOf("   ", "x".repeat(101))) {
                    try {
                        online.renameLibrary(source.id, invalid)
                        fail("Invalid names must be rejected")
                    } catch (_: IllegalArgumentException) { /* Expected. */ }
                }
                val original = requireNotNull(dao.getBook(saved.id))
                val originalTrack = requireNotNull(dao.getTrack(track.id))
                online.removeLibrary(source.id)
                online.removeLibrary(source.id) // Removal is idempotent.
                assertTrue(online.observeLibraries().first().isEmpty())
                assertEquals(source.copy(displayName = "My classics", isRegistered = false, isSelected = false), dao.getOnlineLibrary(source.id))
                assertEquals(original, dao.getBook(saved.id))
                assertEquals(originalTrack, dao.getTrack(track.id))
                try {
                    online.setLibrarySelected(source.id, true)
                    fail("A removed source must not be selected implicitly")
                } catch (_: OnlineBookException) { /* Registration is required. */ }
                try {
                    online.renameLibrary(source.id, "Hidden name")
                    fail("A removed source must not be renamed")
                } catch (_: OnlineBookException) { /* Registration is required. */ }
                // Saved-source lookup must still find the hidden library for explicit renewal.
                online.refreshBookSources(saved.id)
                assertEquals(1, requests)
                assertEquals(originalTrack.copy(uri = "https://archive.org/renewed.mp3", streamReferer = null, downloadReferer = null), dao.getTrack(track.id))
                val restored = online.addLibrary(" https://LIBRIVOX.org ")
                assertEquals(source.id, restored.id)
                assertEquals("My classics", restored.displayName)
                assertTrue(restored.isSelected)
                assertEquals(2, requests) // Restoring a hidden source validates its catalogue again.
                online.setLibrarySelected(source.id, false)
                assertTrue(online.addLibrary(source.baseUrl).isSelected)
                assertEquals(3, requests) // Re-enabling an unselected source also validates before persistence.
                assertTrue(online.addLibrary(source.baseUrl).isSelected)
                assertEquals(3, requests) // An already registered and selected source needs no further probe.
                assertEquals(1, online.observeLibraries().first().size)
                assertEquals(original, dao.getBook(saved.id))
                assertEquals(PlaybackProgressEntity(saved.id, track.id, 45000, 10000), dao.getProgress(saved.id))
                assertEquals(BookmarkEntity("mark", saved.id, track.id, 15000, "Keep this", 9000), dao.observeBookmarks(saved.id).first().single())
                online.setLibrarySelected(source.id, false)
            } finally { database.close() }
            val reopened = openDatabase(name)
            try {
                val restored = reopened.libraryDao().observeOnlineLibraries().first().single()
                assertEquals("historical-library", restored.id)
                assertEquals("My classics", restored.displayName)
                assertFalse(restored.isSelected)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun newLibraryStillRequiresValidCatalogueBeforeRegistration() = runTest {
        val name = "library-validation-test.db"
        context.deleteDatabase(name)
        val database = openDatabase(name)
        try {
            val dao = database.libraryDao()
            var response = "<html>Success is not a catalogue</html>"
            var requests = 0
            val online = onlineLibraries(dao) { requests++; response }
            try {
                online.addLibrary("https://librivox.org/")
                fail("HTTP success without catalogue shape must not register a source")
            } catch (_: OnlineBookException) { /* Expected. */ }
            assertTrue(dao.observeOnlineLibraries().first().isEmpty())
            response = """{"books":[]}"""
            val added = online.addLibrary("https://librivox.org/")
            assertTrue(added.isSelected)
            assertEquals(1, dao.observeOnlineLibraries().first().size)
            assertEquals(2, requests)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionFiveMigrationAddsNullableDetailsAndRetainsRemovedSourcesAndListeningData() = runTest {
        val name = "migration-details-v5-test.db"
        context.deleteDatabase(name)
        try {
            val schema = JSONObject(instrumentation.context.assets.open(
                "io.github.drolevar.audiotales.data.local.AudioTalesDatabase/5.json",
            ).bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(text: String) = text.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                old.execSQL("INSERT INTO online_libraries VALUES ('library', 'https://library.example/', 'My library', 'PARTNER', 0, 0)")
                old.execSQL("INSERT INTO books VALUES ('saved', 'Saved book', 'Author', 'library', '42', 'https://library.example/', 'https://covers.example/book.jpg', 'https://library.example/')")
                old.execSQL("INSERT INTO tracks VALUES ('track', 'saved', 'Chapter', 'https://cdn.example/book.mp3', 0, 100000, '/book.mp3', 'https://library.example/mobapp', 'https://library.example/')")
                old.execSQL("INSERT INTO playback_progress VALUES ('saved', 'track', 45000, 10000)")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark', 'saved', 'track', 15000, 'Keep this', 9000)")
                old.version = 5
            }
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                assertEquals(BookEntity("saved", "Saved book", "Author", "library", "42", "https://library.example/",
                    "https://covers.example/book.jpg", "https://library.example/"), dao.getBook("saved"))
                assertNull(dao.getBook("saved")?.description)
                assertNull(dao.getBook("saved")?.language)
                assertEquals(TrackEntity("track", "saved", "Chapter", "https://cdn.example/book.mp3", 0, 100000,
                    "/book.mp3", "https://library.example/mobapp", "https://library.example/"), dao.getTrack("track"))
                assertEquals(PlaybackProgressEntity("saved", "track", 45000, 10000), dao.getProgress("saved"))
                assertEquals(BookmarkEntity("mark", "saved", "track", 15000, "Keep this", 9000),
                    dao.observeBookmarks("saved").first().single())
                assertEquals(OnlineLibraryEntity("library", "https://library.example/", "My library", "PARTNER", false, false),
                    dao.getOnlineLibrary("library"))
                assertTrue(dao.observeOnlineLibraries().first().isEmpty())
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun metadataRefreshUpdatesOnlyOptionalDetailsAndArtworkAndPreservesAllListeningState() = runTest {
        val name = "details-refresh-test.db"
        context.deleteDatabase(name)
        val database = openDatabase(name)
        try {
            val dao = database.libraryDao()
            dao.insertOnlineLibrary(OnlineLibraryEntity("org", "https://librivox.org/", "My classics", "LIBRIVOX", false, false))
            val repository = RoomLibraryRepository(dao)
            val original = book().copy(providerId = "org", description = null, language = null)
            repository.addOnlineBook(original)
            val track = original.tracks.single()
            dao.saveProgress(PlaybackProgressEntity(original.id, track.id, 45000, 10000))
            dao.insertBookmark(BookmarkEntity("mark", original.id, track.id, 15000, "Keep this", 9000))
            val before = requireNotNull(dao.getBook(original.id))
            val tracks = dao.getTracks(original.id)
            val progress = dao.getProgress(original.id)
            val marks = dao.observeBookmarks(original.id).first()
            var requests = 0
            var response = """{"books":[{"id":"123","title":"Changed title","description":"<p>Real &amp; clean</p>",
                "language":"English","coverart_jpg":"https://archive.org/new.jpg"}]}"""
            val online = onlineLibraries(dao) { requests++; response }
            val updated = online.refreshBookMetadata(original.id)
            assertEquals(1, requests)
            assertEquals(before.copy(description = "Real & clean", language = "English",
                coverUrl = "https://archive.org/new.jpg", coverReferer = null), dao.getBook(original.id))
            assertEquals("Real & clean", updated?.description)
            assertEquals("English", repository.observeBooks().first().single().language)
            assertEquals("Real & clean", repository.observeCollection().first().single().book.description)
            response = """{"books":[{"id":"123","title":"Changed title","coverart_jpg":"file:///bad.jpg"}]}"""
            assertEquals(updated, online.refreshBookMetadata(original.id))
            response = """{"books":[{"id":"123","title":"Changed title","description":""}]}"""
            assertEquals("", online.refreshBookMetadata(original.id)?.description)
            assertEquals(0, dao.updateBookMetadata(original.id, "other", "123", "Wrong", "Wrong", null, null))
            assertEquals(tracks, dao.getTracks(original.id))
            assertEquals(progress, dao.getProgress(original.id))
            assertEquals(marks, dao.observeBookmarks(original.id).first())
            assertEquals(3, requests)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sourceRefreshPreservesIdentityAndHistoryAndRejectsChangedTrackSets() = runTest {
        val name = "refresh-source-test.db"
        context.deleteDatabase(name)
        val database = openDatabase(name)
        try {
            val dao = database.libraryDao()
            val original = book()
            val first = original.tracks.single()
            val second = first.copy(id = "second", sequenceIndex = 1, providerTrackRef = "/chapter-2.mp3")
            RoomLibraryRepository(dao).addOnlineBook(original.copy(tracks = listOf(first, second)))
            dao.saveProgress(PlaybackProgressEntity(original.id, second.id, 45000, 10000))
            dao.insertBookmark(BookmarkEntity("refresh-mark", original.id, second.id, 15000, "Keep this", 9000))
            val updates = dao.getTracks(original.id).map { track ->
                track.copy(id = "new-${track.id}", uri = "https://renewed.example${track.providerTrackRef}?token=new",
                    streamReferer = "https://library.example/mobapp", downloadReferer = "https://library.example/",
                    sourceEndMs = 100_000)
            }
            dao.refreshTrackSources(original.id, updates.reversed())
            val renewed = dao.getTracks(original.id)
            assertEquals(listOf(first.id, second.id), renewed.map { it.id })
            assertTrue(renewed.all { it.uri.startsWith("https://renewed.example") })
            assertEquals("https://library.example/mobapp", renewed.first().streamReferer)
            assertTrue(renewed.all { it.sourceStartMs == 0L && it.sourceEndMs == null && it.audioResourceId == null })
            assertEquals(45000L, dao.getProgress(original.id)?.positionMs)
            assertEquals("Keep this", dao.observeBookmarks(original.id).first().single().label)
            try {
                dao.refreshTrackSources(original.id, listOf(updates.first().copy(uri = "https://wrong.example/a.mp3"),
                    updates.last().copy(providerTrackRef = "/different-recording.mp3")))
                fail("Changed recording identity must not overwrite saved transport")
            } catch (_: IllegalArgumentException) { /* Expected; transaction must retain both prior sources. */ }
            assertEquals(renewed, dao.getTracks(original.id))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentDuplicateAddsPreserveOriginalMetadataTracksProgressAndBookmarks() = runTest {
        val name = "dedup-online-test.db"
        context.deleteDatabase(name)
        try {
            val database = openDatabase(name)
            try {
                val dao = database.libraryDao()
                val repository = RoomLibraryRepository(dao)
                val original = book().copy(coverUrl = null, coverReferer = null, description = null, language = null)
                val outcomes = List(8) {
                    async(Dispatchers.IO) { repository.addOnlineBook(original) }
                }.awaitAll()
                assertEquals(1, outcomes.count { it.added })
                assertEquals(1, dao.observeBooks().first().size)
                val track = original.tracks.single()
                dao.saveProgress(PlaybackProgressEntity(original.id, track.id, 1200, 4000))
                dao.insertBookmark(BookmarkEntity("mark", original.id, track.id, 500, "Keep", 3000))
                val duplicate = original.copy(
                    title = "A changed title",
                    coverUrl = "https://covers.example/new.jpg",
                    coverReferer = "https://library.example/",
                    description = "Verified description",
                    language = "English",
                    sourceUrl = "https://audioknigi.com.ua/audio-123-new-slug",
                    tracks = listOf(track.copy(uri = "https://changed.redirectto.cc/changed.mp3")),
                )
                val result = repository.addOnlineBook(duplicate)
                assertFalse(result.added)
                assertEquals(original.title, result.book.title)
                assertEquals(duplicate.coverUrl, result.book.coverUrl)
                assertEquals(duplicate.coverReferer, result.book.coverReferer)
                assertEquals(duplicate.description, result.book.description)
                assertEquals(duplicate.language, result.book.language)
                val withoutDetails = repository.addOnlineBook(duplicate.copy(description = null, language = null)).book
                assertEquals(duplicate.description, withoutDetails.description)
                assertEquals(duplicate.language, withoutDetails.language)
                // Neither absent nor invalid optional artwork can clear a known cover.
                assertEquals(duplicate.coverUrl, repository.addOnlineBook(duplicate.copy(coverUrl = null, coverReferer = null)).book.coverUrl)
                assertEquals(duplicate.coverUrl, repository.addOnlineBook(duplicate.copy(coverUrl = "file:///bad.jpg")).book.coverUrl)
                assertEquals(original.sourceUrl, dao.getBook(original.id)?.sourceUrl)
                assertEquals(track.uri, dao.getTrack(track.id)?.uri)
                assertEquals(1200L, dao.getProgress(original.id)?.positionMs)
                assertEquals("Keep", dao.observeBookmarks(original.id).first().single().label)
            } finally {
                database.close()
            }
            val reopened = openDatabase(name)
            try {
                assertEquals("Книга", reopened.libraryDao().getBook(book().id)?.title)
                assertEquals("https://covers.example/new.jpg", reopened.libraryDao().getBook(book().id)?.coverUrl)
                assertEquals("https://library.example/", reopened.libraryDao().getBook(book().id)?.coverReferer)
                assertEquals("Verified description", reopened.libraryDao().getBook(book().id)?.description)
                assertEquals("English", reopened.libraryDao().getBook(book().id)?.language)
                assertEquals(1200L, reopened.libraryDao().getProgress(book().id)?.positionMs)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun collectionObservesProgressAndTrackChangesWithOrderedTracks() = runTest {
        // Use the IO dispatcher for real Room invalidations and a real timeout.
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                val name = "collection-projection-test.db"
                context.deleteDatabase(name)
                val database = openDatabase(name)
                try {
                    val dao = database.libraryDao()
                    val repository = RoomLibraryRepository(dao)
                    val original = book()
                    val first = original.tracks.single()
                    val second = first.copy(id = "second", sequenceIndex = 1, providerTrackRef = "/chapter-2.mp3")
                    repository.addOnlineBook(original.copy(tracks = listOf(second, first)))
                    val emissions = Channel<List<LibraryCollectionEntry>>(Channel.UNLIMITED)
                    val collection = launch { repository.observeCollection().collect { emissions.send(it) } }
                    try {
                        val initial = emissions.receive().single()
                        assertEquals(listOf(first.id, second.id), initial.tracks.map { it.id })
                        assertNull(initial.progress)

                        dao.saveProgress(PlaybackProgressEntity(original.id, second.id, 45_000, 10_000))
                        val progressed = emissions.receiveAsFlow().first { it.single().progress?.positionMs == 45_000L }.single()
                        assertEquals(second.id, progressed.progress?.trackId)
                        assertEquals(10_000L, progressed.progress?.updatedAtEpochMs)
                        assertEquals(listOf(first.id, second.id), progressed.tracks.map { it.id })

                        dao.updateTrackTransport(second.id, original.id, "https://renewed.example/second.mp3", second.streamReferer, second.downloadReferer)
                        val renewed = emissions.receiveAsFlow().first {
                            it.single().tracks.last().uri == "https://renewed.example/second.mp3"
                        }.single()
                        assertEquals(listOf(first.id, second.id), renewed.tracks.map { it.id })
                        assertEquals(progressed.progress, renewed.progress)

                        dao.updateBookArtwork(original.id, original.providerId, original.providerBookId, "https://covers.example/refreshed.jpg", original.coverReferer)
                        val artwork = emissions.receiveAsFlow().first {
                            it.single().book.coverUrl == "https://covers.example/refreshed.jpg"
                        }.single()
                        assertEquals(renewed.tracks, artwork.tracks)
                        assertEquals(progressed.progress, artwork.progress)

                        val credits = listOf(ReaderCredit("7", "Reader"))
                        dao.refreshTrackReaderCredits(original.id, original.providerId, original.providerBookId,
                            mapOf(requireNotNull(first.providerTrackRef) to null,
                                requireNotNull(second.providerTrackRef) to ReaderCreditsCodec.encode(credits)))
                        val credited = emissions.receiveAsFlow().first {
                            it.single().tracks.last().readerCredits == credits
                        }.single()
                        assertEquals(artwork.tracks.first(), credited.tracks.first())
                        assertEquals(artwork.tracks.last().copy(readerCredits = credits), credited.tracks.last())
                        assertEquals(progressed.progress, credited.progress)

                    } finally {
                        collection.cancelAndJoin()
                        emissions.close()
                    }
                } finally {
                    database.close()
                    context.deleteDatabase(name)
                }
            }
        }
    }

    private fun onlineLibraries(dao: LibraryDao, response: () -> String): OnlineLibraries {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toResponseBody()).build()
        }.build()
        return OnlineLibraries(dao, RoomLibraryRepository(dao), ProviderHttpClient(client))
    }

    private fun openDatabase(name: String): AudioTalesDatabase =
        Room.databaseBuilder<AudioTalesDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(LibraryMigrations.MIGRATION_1_2, LibraryMigrations.MIGRATION_2_3, LibraryMigrations.MIGRATION_3_4, LibraryMigrations.MIGRATION_4_5, LibraryMigrations.MIGRATION_5_6, LibraryMigrations.MIGRATION_6_7, LibraryMigrations.MIGRATION_7_8)
            .build()

    private fun book() = ResolvedOnlineBook(
        id = "audioknigi-123",
        providerId = "audioknigi.com.ua",
        providerBookId = "123",
        sourceUrl = "https://audioknigi.com.ua/audio-123-book",
        title = "Книга",
        author = "Автор",
        coverUrl = "https://covers.example/original.jpg",
        coverReferer = "https://library.example/",
        description = "Saved provider description",
        language = "English",
        tracks = listOf(LibraryTrack(
            id = "stable-track-id",
            bookId = "audioknigi-123",
            title = "Розділ",
            uri = "https://one.redirectto.cc/s04/999/track-01.mp3",
            sequenceIndex = 0,
            providerTrackRef = "/s04/999/track-01.mp3",
            streamReferer = "https://library.example/mobapp",
            downloadReferer = "https://library.example/",
        )),
    )
}
