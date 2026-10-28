package io.github.drolevar.audiotales.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPaginationTest {
    @Test
    fun preservesItemsAndHandlesUnpagedAndOutOfRangeRequests() {
        val items = listOf("one", "two", "three")
        assertEquals(listOf("one"), items.libraryPage(0, 1))
        assertEquals(listOf("two"), items.libraryPage(1, 1))
        assertEquals(listOf("three"), items.libraryPage(1, 2))
        assertEquals(items, items.libraryPage(0, Int.MAX_VALUE))
        assertTrue(items.libraryPage(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(emptyList<String>().libraryPage(0, 1).isEmpty())
    }

    @Test
    fun rejectsInvalidBounds() {
        for ((page, size) in listOf(-1 to 1, 0 to 0, 0 to -1)) {
            var rejected = false
            try { listOf("one").libraryPage(page, size) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Invalid bounds must be rejected: $page/$size", rejected)
        }
    }
}
