package io.github.drolevar.audiotales.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryProtocolParserTest {
    private val base = LibraryProtocolParser.baseUrl("https://library.example")
    private val source = OnlineLibrary(LibraryProtocolParser.libraryId(base), base.toString(), "Library", LibraryProtocol.PARTNER)
    private val metadata = """{"id":42,"namebook":"A &amp; B","author":"Author","short_story":"<p>Description</p>"}"""

    @Test fun configurableInstanceNormalizesRootAndKeepsDifferentLibrariesDistinct() {
        assertEquals(base, LibraryProtocolParser.baseUrl(" https://LIBRARY.example/ "))
        assertNotEquals(source.id, LibraryProtocolParser.libraryId(LibraryProtocolParser.baseUrl("https://another.example")))
        listOf("http://library.example", "https://user:pass@library.example", "https://library.example?token=x", "https://library.example/%2e%2e/").forEach {
            assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.baseUrl(it) }
        }
    }

    @Test fun partnerSearchStringIdsAndCatalogueNumericIdsHaveSameIdentity() {
        val numeric = LibraryProtocolParser.catalogue(source, "[$metadata]").single()
        val string = LibraryProtocolParser.catalogue(source, "[${metadata.replace("\"id\":42", "\"id\":\"42\"")}]").single()
        assertEquals(numeric, string)
        assertEquals("A & B", numeric.title)
        assertEquals("Description", numeric.description)
        assertNull(numeric.sourceUrl)
    }

    @Test fun partnerHeadersAreResourceSpecificAndIdentitySurvivesTransportHostAndQueryChange() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        fun resolve(host: String, query: String) = LibraryProtocolParser.partnerResolution(source, book,
            """[{"title":"One","file":"https://$host/book/track.mp3?$query","start":0,"end":1403}]""")
        val original = resolve("one.example", "token=old").tracks.single()
        val refreshed = resolve("two.example", "token=new").tracks.single()
        assertEquals(original.id, refreshed.id)
        assertNotEquals(original.uri, refreshed.uri)
        assertEquals("https://library.example/mobapp", original.streamReferer)
        assertEquals("https://library.example/", original.downloadReferer)
        assertNull(original.durationMs)
    }

    @Test fun malformedOrUnsafePlaylistsCannotProducePartialBooks() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        listOf("[]", "{}", """[{"title":"One","file":"file:///private.mp3"}]""",
            """[{"title":"One","file":"https://cdn.example/a.mp3","start":10}]""",
            """[{"title":"One","file":"https://cdn.example/a.mp3"},{"title":"Two","file":"https://cdn.example/a.mp3"}]""").forEach {
            assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.partnerResolution(source, book, it) }
        }
    }

    @Test fun librivoxOrdersBySectionNumberNotIdsAndRequiresCompleteTrackSet() {
        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        val json = """{"books":[{"id":"11455","title":"Following the Color Line","url_librivox":"https://librivox.org/following/","num_sections":"2","sections":[
            {"id":"101","section_number":"1","title":"Next","playtime":"45","listen_url":"https://archive.org/download/book/next.mp3"},
            {"id":"201","section_number":"0","title":"Preface","playtime":"216","listen_url":"https://archive.org/download/book/preface.mp3"}]}]}"""
        val result = LibraryProtocolParser.librivoxResolution(org, "11455", json)
        assertEquals(listOf("201", "101"), result.tracks.map { it.providerTrackRef })
        assertEquals(216_000L, result.tracks.first().durationMs)
        assertThrows(OnlineBookException::class.java) {
            LibraryProtocolParser.librivoxResolution(org, "11455", json.replace("\"num_sections\":\"2\"", "\"num_sections\":\"3\""))
        }
    }

    @Test fun documentedLibrivoxEmptyResponseIsAnEmptyPage() {
        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        assertEquals(emptyList<CatalogueBook>(), LibraryProtocolParser.catalogue(org,
            """{"error":"Audiobooks could not be found"}"""))
    }

    @Test fun librivoxCatalogueMayLackPublishedPageButSavingRequiresIt() {
        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        val json = """{"books":[{"id":"23028","title":"Under the Storm","url_librivox":"","num_sections":"1","sections":[
            {"id":"1","section_number":"0","title":"Chapter","listen_url":"https://archive.org/book.mp3"}]}]}"""
        val listed = LibraryProtocolParser.catalogue(org, json).single()
        assertEquals("23028", listed.remoteId)
        assertNull(listed.sourceUrl)
        assertThrows(OnlineBookException::class.java) {
            LibraryProtocolParser.librivoxResolution(org, "23028", json)
        }
        assertThrows(OnlineBookException::class.java) {
            LibraryProtocolParser.catalogue(org, json.replace("\"url_librivox\":\"\"", "\"url_librivox\":\"https://other.example/book\""))
        }
    }

    @Test fun partnerArtworkIsResolvedAndCarriedIntoTheSavedBook() {
        val json = metadata.dropLast(1) + ",\"cover\":\"cover.jpg\",\"img_dir\":\"/uploads/books\"}"
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(json))
        val result = LibraryProtocolParser.partnerResolution(source, book,
            """[{"title":"One","file":"https://cdn.example/track.mp3"}]""")
        assertEquals("https://library.example/uploads/books/cover.jpg", result.coverUrl)
        assertEquals("https://library.example/", result.coverReferer)
        val blank = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(
            metadata.dropLast(1) + ",\"cover\":\"\",\"img_dir\":\"/uploads/books\"}"))
        assertNull(blank.coverUrl)
    }

    @Test fun librivoxMetadataPrefersFullCoverAndFallsBackFromUnsafeOrMissingArtwork() {
        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        // Field names and full-size/thumbnail paths verified in the retained API book 11455 fixture.
        val json = """{"books":[{"id":"11455","title":"Following the Color Line",
            "coverart_jpg":"https://archive.org/download/book/full.jpg",
            "coverart_thumbnail":"https://archive.org/download/thumbs12/thumb.jpg"}]}"""
        assertEquals("https://archive.org/download/book/full.jpg",
            LibraryProtocolParser.librivoxMetadata(org, "11455", json).coverUrl)
        assertEquals("https://archive.org/download/thumbs12/thumb.jpg",
            LibraryProtocolParser.librivoxMetadata(org, "11455", json.replace(
                "https://archive.org/download/book/full.jpg", "file:///private.jpg")).coverUrl)
        assertNull(LibraryProtocolParser.librivoxMetadata(org, "11455",
            """{"books":[{"id":"11455","title":"Book","coverart_jpg":null,"coverart_thumbnail":""}]}""").coverUrl)
        assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.librivoxMetadata(org, "42", json) }
        assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.librivoxMetadata(org, "11455", """{"books":[]}""") }
    }

    @Test fun languageComesOnlyFromLibrivoxMetadataAndBlankLanguageIsAbsent() {
        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        val json = """{"books":[{"id":"42","title":"Book","language":"  English  "}]}"""
        assertEquals("English", LibraryProtocolParser.catalogue(org, json).single().language)
        assertEquals("English", LibraryProtocolParser.librivoxMetadata(org, "42", json).language)
        assertNull(LibraryProtocolParser.catalogue(org, json.replace("  English  ", "   ")).single().language)
        assertNull(LibraryProtocolParser.catalogue(org, """{"books":[{"id":"42","title":"Book"}]}""").single().language)
        val partner = metadata.dropLast(1) + ",\"language\":\"English\"}"
        assertNull(LibraryProtocolParser.catalogue(source, "[$partner]").single().language)
    }

    @Test fun htmlSuccessAndDuplicateRemoteIdsAreRejected() {
        listOf("Hacking attempt!", "[$metadata,$metadata]", "{}").forEach {
            assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.catalogue(source, it) }
        }
    }
}
