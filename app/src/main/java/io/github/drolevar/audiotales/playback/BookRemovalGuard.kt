package io.github.drolevar.audiotales.playback

import androidx.annotation.MainThread
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local reservation owned by the whole removal operation, including Activity gaps. */
@Singleton
class BookRemovalGuard @Inject constructor() {
    private val tokens = mutableMapOf<String, String>()
    private val additions = Mutex()
    private var removalGeneration = 0L

    @MainThread
    fun reserve(bookId: String, token: String): Boolean {
        if (bookId.isBlank() || token.isBlank()) return false
        val owner = tokens[bookId]
        if (owner != null && owner != token) return false
        if (owner == null) removalGeneration++
        tokens[bookId] = token
        return true
    }

    @MainThread
    fun owns(bookId: String, token: String?): Boolean = token != null && tokens[bookId] == token

    @MainThread
    fun isReserved(bookId: String?): Boolean = bookId != null && bookId in tokens

    @MainThread
    fun release(bookId: String, token: String): Boolean = tokens.remove(bookId, token)

    /** Conservatively invalidate an online Listen request if any removal overlaps it. */
    @MainThread
    fun additionGeneration(): Long = removalGeneration.also(::checkAdditionGeneration)

    @MainThread
    fun checkAdditionGeneration(generation: Long) {
        check(generation == removalGeneration && tokens.isEmpty()) { "Book removal interrupted this request" }
    }

    /** Provider inspection happens before this short, local database commit. */
    @MainThread
    suspend fun <T> withAddition(generation: Long, block: suspend () -> T): T = additions.withLock {
        checkAdditionGeneration(generation)
        val result = block()
        checkAdditionGeneration(generation)
        result
    }

    /** A reserved removal must see any insertion already in flight before deleting the book. */
    @MainThread
    suspend fun awaitAdditions() = additions.withLock { }
}
