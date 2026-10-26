package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.library.ReaderCredit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryProtocolParserTest {
    private val base = LibraryProtocolParser.baseUrl("https://library.example")
    private val source = OnlineLibrary(LibraryProtocolParser.libraryId(base), base.toString(), "Library", LibraryProtocol.PARTNER)
    private val metadata = """{"id":42,"namebook":"A &amp; B","author":"Author","short_story":"<p>Description</p>"}"""

    @Test fun partnerSectionsSharingOneRecordingResolveAsCompleteOrderedTracks() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        val tracks = LibraryProtocolParser.partnerResolution(source, book, """[
            {"title":"One","file":"https://cdn.example/book.mp3","start":0,"end":290},
            {"title":"Two","file":"https://cdn.example/book.mp3","start":290,"end":487},
            {"title":"Three","file":"https://cdn.example/book.mp3","start":487,"end":735},
            {"title":"Four","file":"https://cdn.example/book.mp3","start":735,"end":863}
        ]""").tracks
        assertEquals(listOf("One", "Two", "Three", "Four"), tracks.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), tracks.map { it.sequenceIndex })
        assertEquals(listOf(290_000L, 197_000L, 248_000L, 128_000L), tracks.map { it.durationMs })
        assertEquals(4, tracks.map { it.id }.distinct().size)
        assertEquals(4, tracks.map { it.providerTrackRef }.distinct().size)
        assertEquals(listOf(0L, 290_000L, 487_000L, 735_000L), tracks.map { it.sourceStartMs })
        assertEquals(listOf(290_000L, 487_000L, 735_000L, 863_000L), tracks.map { it.sourceEndMs })
        assertEquals(1, tracks.map { it.downloadId }.distinct().size)
        assertNotEquals(tracks.first().id, tracks.first().downloadId)
        assertEquals(listOf("/book.mp3#t=0-290000", "/book.mp3#t=290000-487000",
            "/book.mp3#t=487000-735000", "/book.mp3#t=735000-863000"), tracks.map { it.providerTrackRef })
    }

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
        assertEquals(1_403_000L, original.durationMs)
    }

    @Test fun malformedOrUnsafePlaylistsCannotProducePartialBooks() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        listOf("[]", "{}", """[{"title":"One","file":"file:///private.mp3"}]""",
            """[{"title":"One","file":"https://cdn.example/a.mp3","start":10}]""",
            """[{"title":"One","file":"https://cdn.example/a.mp3"},{"title":"Two","file":"https://cdn.example/a.mp3"}]""").forEach {
            assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.partnerResolution(source, book, it) }
        }
    }

    @Test fun malformedAmbiguousOrOverlappingRangesCannotProducePartialBooks() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        val invalidFields = listOf(
            "\"start\":-1,\"end\":290", "\"start\":0,\"end\":-1",
            "\"start\":\"NaN\",\"end\":290", "\"start\":0,\"end\":\"Infinity\"",
            "\"start\":\"wrong\",\"end\":290", "\"start\":null,\"end\":290",
            "\"start\":false,\"end\":290", "\"start\":{},\"end\":290",
            "\"start\":0,\"end\":null", "\"start\":1,\"end\":1",
            "\"start\":2,\"end\":1", "\"start\":0.0001,\"end\":290",
            "\"start\":0,\"end\":9223372036854775807", "\"start\":1",
            "\"start\":0,\"end\":\"1e100000000\"", "\"start\":\"1e-100000000\",\"end\":290",
        )
        invalidFields.forEach { fields ->
            assertThrows(fields, OnlineBookException::class.java) {
                LibraryProtocolParser.partnerResolution(source, book,
                    """[{"title":"One","file":"https://cdn.example/book.mp3",$fields}]""")
            }
        }
        val first = """{"title":"One","file":"https://cdn.example/book.mp3","start":0,"end":290}"""
        listOf(
            "\"start\":0,\"end\":290", "\"start\":289,\"end\":487", "\"start\":290",
        ).forEach { fields ->
            assertThrows(OnlineBookException::class.java) {
                LibraryProtocolParser.partnerResolution(source, book,
                    """[$first,{"title":"Two","file":"https://cdn.example/book.mp3",$fields}]""")
            }
        }
    }

    @Test fun partnerMillisecondsAreExactAndUniqueZeroStartKeepsLegacyIdentity() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        fun resolve(fields: String) = LibraryProtocolParser.partnerResolution(source, book,
            """[{"title":"One","file":"https://cdn.example/book.mp3"$fields}]""").tracks.single()
        val legacy = resolve("")
        val bounded = resolve(",\"start\":\"0.000\",\"end\":\"290.125\"")
        assertEquals(legacy.id, bounded.id)
        assertEquals(legacy.providerTrackRef, bounded.providerTrackRef)
        assertEquals(legacy.downloadId, bounded.downloadId)
        assertEquals(290_125L, bounded.durationMs)
        assertEquals(290_125L, bounded.sourceEndMs)
        assertNull(legacy.durationMs)
    }

    @Test fun sectionAndResourceIdentitySurviveTransportHostAndQueryChanges() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        fun resolve(host: String, query: String) = LibraryProtocolParser.partnerResolution(source, book, """[
            {"title":"One","file":"https://$host/book.mp3?$query","start":0,"end":290},
            {"title":"Two","file":"https://$host/book.mp3?$query","start":290,"end":487}
        ]""").tracks
        val original = resolve("old.example", "token=old")
        val renewed = resolve("new.example", "token=new")
        assertEquals(original.map { it.id }, renewed.map { it.id })
        assertEquals(original.map { it.providerTrackRef }, renewed.map { it.providerTrackRef })
        assertEquals(original.map { it.downloadId }, renewed.map { it.downloadId })
        assertNotEquals(original.map { it.uri }, renewed.map { it.uri })
        val legacy = LibraryProtocolParser.partnerResolution(source, book,
            """[{"title":"Whole file","file":"https://old.example/book.mp3"}]""").tracks.single()
        assertEquals(legacy.id, original.first().downloadId)
    }

    @Test fun samePathSectionsCannotShareAResourceAcrossDifferentPlaylistUrls() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        listOf("https://different.example/book.mp3?token=one", "https://cdn.example/book.mp3?token=two")
            .forEach { secondUrl ->
                assertThrows(OnlineBookException::class.java) {
                    LibraryProtocolParser.partnerResolution(source, book, """[
                        {"title":"One","file":"https://cdn.example/book.mp3?token=one","start":0,"end":290},
                        {"title":"Two","file":"$secondUrl","start":290,"end":487}
                    ]""")
                }
            }
    }

    @Test fun nonzeroSingleRangeHasStableSectionAndWholeResourceIdentity() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        val track = LibraryProtocolParser.partnerResolution(source, book,
            """[{"title":"Excerpt","file":"https://cdn.example/book.mp3","start":"290.125","end":"487.250"}]""")
            .tracks.single()
        assertEquals(290_125L, track.sourceStartMs)
        assertEquals(487_250L, track.sourceEndMs)
        assertEquals(197_125L, track.durationMs)
        assertEquals("/book.mp3#t=290125-487250", track.providerTrackRef)
        assertNotEquals(track.id, track.downloadId)
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

    @Test fun resolvedDetailsUseCleanProviderTextAndOnlyVerifiedLanguage() {
        val partner = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(
            metadata.dropLast(1) + ",\"language\":\"Invented language\"}"))
        val resolvedPartner = LibraryProtocolParser.partnerResolution(source, partner,
            """[{"title":"One","file":"https://cdn.example/track.mp3"}]""")
        assertEquals("Description", resolvedPartner.description)
        assertNull(resolvedPartner.language)
        assertNull(resolvedPartner.tracks.single().durationMs)

        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        val json = """{"books":[{"id":"42","title":"Book","description":"<p>A &amp; B</p>","language":" English ",
            "url_librivox":"https://librivox.org/book/","num_sections":"1","sections":[
            {"id":"1","section_number":"0","title":"Chapter","listen_url":"https://archive.org/book.mp3"}]}]}"""
        val resolved = LibraryProtocolParser.librivoxResolution(org, "42", json)
        assertEquals("A & B", resolved.description)
        assertEquals("English", resolved.language)
    }

    @Test fun librivoxReadersAreOptionalPerSectionAndNeverInferredFromAuthors() {
        val fields = listOf("", ",\"readers\":null", ",\"readers\":[]",
            ",\"readers\":[{\"reader_id\":\"7\",\"display_name\":\"Reader\"}]")
        val result = readerBook(fields)
        assertEquals(listOf(null, null, emptyList<ReaderCredit>(), listOf(ReaderCredit("7", "Reader"))),
            result.tracks.map { it.readerCredits })
        assertEquals("An Author", result.author)
    }

    @Test fun librivoxMultipleReadersArePlainTextAndExactlyDeduplicated() {
        val result = readerBook(listOf(",\"readers\":[{\"reader_id\":\"7\",\"display_name\":\" <b>A &amp; B</b> \"}," +
            "{\"reader_id\":\"8\",\"display_name\":\"Second\"},{\"reader_id\":\"7\",\"display_name\":\"A &amp; B\"}]"))
        assertEquals(listOf(ReaderCredit("7", "A & B"), ReaderCredit("8", "Second")), result.tracks.single().readerCredits)
    }

    @Test fun malformedOptionalReadersCannotHideOtherwiseValidAudio() {
        val invalid = listOf("{}", "[null]", "[{}]", "[false]", "[\"Reader\"]",
            "[{\"reader_id\":7,\"display_name\":\"Reader\"}]",
            "[{\"reader_id\":\"7\",\"display_name\":false}]",
            "[{\"reader_id\":\"7\",\"display_name\":\"<b></b>\"}]",
            "[{\"reader_id\":\"7\",\"display_name\":\"${"x".repeat(201)}\"}]",
            "[" + List(33) { "{\"reader_id\":\"7\",\"display_name\":\"Reader\"}" }.joinToString(",") + "]",
            "[{\"reader_id\":\"7\",\"display_name\":\"Valid\"},{}]")
        invalid.forEach { credits ->
            assertNull(credits, readerBook(listOf(",\"readers\":$credits")).tracks.single().readerCredits)
        }
    }

    @Test fun readerAssignmentsRequireCompleteStableSectionSetButDoNotValidateAudio() {
        val text = readerJson(listOf(",\"readers\":[{\"reader_id\":\"7\",\"display_name\":\"Reader\"}]", ",\"readers\":[]"))
            .replace("https://archive.org/1.mp3", "file:///unavailable.mp3")
        val assignments = LibraryProtocolParser.librivoxReaderAssignments("42", text)
        assertEquals(mapOf("1" to listOf(ReaderCredit("7", "Reader")), "2" to emptyList<ReaderCredit>()), assignments)
        assertThrows(OnlineBookException::class.java) { LibraryProtocolParser.librivoxResolution(org, "42", text) }
        assertNull(LibraryProtocolParser.librivoxReaderAssignments("42", text.replace("\"num_sections\":\"2\"", "\"num_sections\":\"3\"")))
        assertNull(LibraryProtocolParser.librivoxReaderAssignments("42", text.replace("\"id\":\"2\"", "\"id\":\"1\"")))
        assertNull(LibraryProtocolParser.librivoxReaderAssignments("42", """{"books":[{"id":"42","title":"Book"}]}"""))
    }

    @Test fun partnerMetadataAndPlaylistsDoNotInventReadersFromUnverifiedFields() {
        val book = LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(metadata))
        val result = LibraryProtocolParser.partnerResolution(source, book,
            """[{"title":"One","file":"https://cdn.example/track.mp3","author":"Someone","readers":[{"reader_id":"7","display_name":"Unverified"}]}]""")
        assertNull(result.tracks.single().readerCredits)
    }

    private val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
    private fun readerBook(fields: List<String>) = LibraryProtocolParser.librivoxResolution(org, "42", readerJson(fields))
    private fun readerJson(fields: List<String>): String {
        val sections = fields.mapIndexed { index, field ->
            """{"id":"${index + 1}","section_number":"$index","title":"Section $index","listen_url":"https://archive.org/${index + 1}.mp3"$field}"""
        }.joinToString(",")
        return """{"books":[{"id":"42","title":"Book","url_librivox":"https://librivox.org/book/","authors":[{"first_name":"An","last_name":"Author"}],"num_sections":"${fields.size}","sections":[$sections]}]}"""
    }

    @Test fun descriptionDistinguishesMissingNullAndExplicitlyEmptyFields() {
        val org = OnlineLibrary("org", "https://librivox.org/", "LibriVox", LibraryProtocol.LIBRIVOX)
        for (field in listOf("", ",\"description\":null")) {
            assertNull(LibraryProtocolParser.librivoxMetadata(org, "42",
                """{"books":[{"id":"42","title":"Book"$field}]}""").description)
        }
        assertEquals("", LibraryProtocolParser.librivoxMetadata(org, "42",
            """{"books":[{"id":"42","title":"Book","description":" <p></p> "}]}""").description)
        assertNull(LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(
            """{"id":"42","namebook":"Book"}""")).description)
        assertEquals("", LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(
            """{"id":"42","namebook":"Book","short_story":""}""")).description)
    }
}
