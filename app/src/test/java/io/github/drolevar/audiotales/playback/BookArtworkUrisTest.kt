package io.github.drolevar.audiotales.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookArtworkUrisTest {
    @Test
    fun canonicalUrisRoundTripOpaqueRepositoryIdsWithoutLocations() {
        for (id in listOf("book", "partner:123", "Книга / ?# %", "https://caller.invalid/path")) {
            val uri = BookArtworkUris.forBook(id)
            assertEquals(id, BookArtworkUris.bookId(uri))
        }
        assertNotEquals(BookArtworkUris.forBook("first"), BookArtworkUris.forBook("second"))
    }

    @Test
    fun locationsAndNoncanonicalShapesAreRejected() {
        val canonical = BookArtworkUris.forBook("book")
        for (uri in listOf("https://caller.invalid/image.jpg", "file:///cover.jpg", "content://book/cover",
            "audiotales-artwork://book/", "audiotales-artwork://other/Ym9vaw", canonical + "?x=1",
            canonical + "#cover", canonical + "/extra", canonical + "=", canonical.uppercase(),
            canonical.replace("://book/", "://user@book/"), "audiotales-artwork://book/IA")) {
            assertNull(uri, BookArtworkUris.bookId(uri))
        }
    }

    @Test
    fun attemptsAndSavedArtworkRevisionChangeIdentityWhilePreservingRepositoryBook() {
        val attempt = "12345678-1234-1234-1234-123456789abc"
        val original = BookArtworkUris.forBook("book", attempt = attempt)
        assertEquals(original, BookArtworkUris.refreshed(original, "book", null, null))
        val withCover = BookArtworkUris.refreshed(original, "book", "https://covers.invalid/book.png", null)
        assertNotEquals(original, withCover)
        assertEquals("book", BookArtworkUris.bookId(withCover))
        assertNotEquals(withCover,
            BookArtworkUris.refreshed(withCover, "book", "https://covers.invalid/book.png", "https://library.invalid/"))
        assertNotEquals(withCover, BookArtworkUris.forBook("book", "https://covers.invalid/book.png"))
        assertNull(BookArtworkUris.bookId(original.replace(attempt, "not-an-attempt")))
        val parts = original.split('/')
        assertNull(BookArtworkUris.bookId(original.replace(parts[parts.size - 2], "not-a-revision")))
    }
}
