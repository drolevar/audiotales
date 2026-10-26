package io.github.drolevar.audiotales.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookRemovalGuardTest {
    private val guard = BookRemovalGuard()

    @Test
    fun reservationRemainsUntilItsOperationOwnerReleasesIt() {
        assertTrue(guard.reserve("book", "operation"))
        assertTrue(guard.isReserved("book"))
        assertTrue(guard.owns("book", "operation"))
        assertTrue(guard.reserve("book", "operation"))

        assertTrue(guard.release("book", "operation"))

        assertFalse(guard.isReserved("book"))
        assertFalse(guard.owns("book", "operation"))
    }

    @Test
    fun anotherOwnerCannotReplaceOrReleaseAnActiveReservation() {
        guard.reserve("book", "first")

        assertFalse(guard.reserve("book", "second"))
        assertFalse(guard.release("book", "second"))
        assertTrue(guard.owns("book", "first"))
        assertFalse(guard.owns("book", null))
    }

    @Test
    fun lateCleanupCannotReleaseANewerOperationForTheSameBook() {
        guard.reserve("book", "old")
        guard.release("book", "old")
        guard.reserve("book", "new")

        assertFalse(guard.release("book", "old"))
        assertTrue(guard.owns("book", "new"))
    }

    @Test
    fun differentBooksHaveIndependentOwners() {
        guard.reserve("first-book", "first-operation")
        guard.reserve("second-book", "second-operation")
        guard.release("first-book", "first-operation")

        assertFalse(guard.isReserved("first-book"))
        assertTrue(guard.owns("second-book", "second-operation"))
    }

    @Test
    fun missingIdentitiesDoNotCreateReservations() {
        assertFalse(guard.reserve("", "token"))
        assertFalse(guard.reserve("book", ""))
        assertFalse(guard.isReserved("book"))
        assertFalse(guard.isReserved(null))
    }
}
