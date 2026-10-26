package io.github.drolevar.audiotales.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderCreditTest {
    private val credits = listOf(ReaderCredit("1", "First Reader"), ReaderCredit("2", "Second Reader"))

    @Test fun codecRoundTripsUnknownEmptyAndMultipleCredits() {
        for (value in listOf(null, emptyList(), credits)) {
            assertEquals(value, ReaderCreditsCodec.decode(ReaderCreditsCodec.encode(value)))
        }
        assertEquals("[]", ReaderCreditsCodec.encode(emptyList()))
        assertNull(ReaderCreditsCodec.encode(null))
    }

    @Test fun codecRejectsMalformedOrUnboundedStorageWithoutMakingTrackUnreadable() {
        for (value in listOf("", "broken", "null", "{}", "[null]", "[{}]",
            "[{\"providerReaderId\":1,\"displayName\":\"Reader\"}]", " ".repeat(65_537))) {
            assertNull(value.take(80), ReaderCreditsCodec.decode(value))
        }
        assertNull(ReaderCreditsCodec.encode(List(33) { credits.first() }))
        assertNull(ReaderCreditsCodec.encode(listOf(ReaderCredit("1", "x".repeat(201)))))
        assertNull(ReaderCreditsCodec.encode(listOf(ReaderCredit("", "Reader"))))
    }

    @Test fun codecNormalizesPlainTextWhitespaceControlsAndExactDuplicates() {
        assertEquals(listOf(ReaderCredit("1", "First Reader")), ReaderCreditsCodec.decode(
            ReaderCreditsCodec.encode(listOf(ReaderCredit(" 1 ", " First\nReader\u0000 "), credits.first()))))
    }

    @Test fun storageDoesNotInterpretAlreadyPlainReaderNamesAsHtmlAgain() {
        val credits = listOf(ReaderCredit("7", "Reader <One> & Two"))
        assertEquals(credits, ReaderCreditsCodec.decode(ReaderCreditsCodec.encode(credits)))
    }

    @Test fun bookWideCreditRequiresEveryTrackToHaveTheSameCompleteNonemptyAttribution() {
        val track = LibraryTrack("a", "book", "One", "https://archive.org/a.mp3", 0, readerCredits = credits)
        assertEquals(credits, commonReaderCredits(listOf(track, track.copy(id = "b"))))
        assertNull(commonReaderCredits(emptyList()))
        for (other in listOf(null, emptyList(), credits.reversed(), credits.take(1))) {
            assertNull(commonReaderCredits(listOf(track, track.copy(id = "b", readerCredits = other))))
        }
        assertEquals("First Reader, Second Reader", readerCreditNames(credits))
        assertNull(readerCreditNames(null))
        assertNull(readerCreditNames(emptyList()))
    }
}
