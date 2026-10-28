package io.github.drolevar.audiotales.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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

    @Test
    fun aRemovalCompletedDuringInspectionStillInvalidatesTheSelection() {
        val generation = guard.additionGeneration()
        guard.reserve("book", "remove")
        guard.release("book", "remove")
        assertRejected { guard.checkAdditionGeneration(generation) }
        guard.checkAdditionGeneration(guard.additionGeneration())
    }

    @Test
    fun invalidOrRepeatedReservationsDoNotAdvanceTheGeneration() {
        val original = guard.additionGeneration()
        assertFalse(guard.reserve("", "token"))
        guard.checkAdditionGeneration(original)
        guard.reserve("book", "owner")
        assertTrue(guard.reserve("book", "owner"))
        assertFalse(guard.reserve("book", "other"))
        guard.release("book", "owner")
        assertEquals(original + 1, guard.additionGeneration())
    }

    @Test
    fun removalWaitsForAnInsertionAlreadyInFlightAndItsPlaybackIsRejected() = runTest {
        val generation = guard.additionGeneration()
        val inserting = CompletableDeferred<Unit>()
        val completeInsert = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val addition = async {
            runCatching {
                guard.withAddition(generation) {
                    inserting.complete(Unit)
                    completeInsert.await()
                    order += "inserted"
                }
            }
        }
        inserting.await()
        guard.reserve("book", "remove")
        val removal = async { guard.awaitAdditions(); order += "remove" }
        runCurrent()
        assertFalse(removal.isCompleted)
        completeInsert.complete(Unit)
        assertTrue(addition.await().exceptionOrNull() is IllegalStateException)
        removal.await()
        assertEquals(listOf("inserted", "remove"), order)
        guard.release("book", "remove")
    }

    @Test
    fun cancellationReleasesTheAdditionBarrier() = runTest {
        val inserting = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val generation = guard.additionGeneration()
        val addition = async {
            guard.withAddition(generation) { inserting.complete(Unit); neverComplete.await() }
        }
        inserting.await()
        addition.cancelAndJoin()
        guard.awaitAdditions()
        assertEquals("next", guard.withAddition(generation) { "next" })
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Expected an overlapping removal to invalidate the selection", rejected)
    }
}
