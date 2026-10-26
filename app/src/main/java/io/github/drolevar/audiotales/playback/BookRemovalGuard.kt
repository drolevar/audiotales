package io.github.drolevar.audiotales.playback

import androidx.annotation.MainThread
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local reservation owned by the whole removal operation, including Activity gaps. */
@Singleton
class BookRemovalGuard @Inject constructor() {
    private val tokens = mutableMapOf<String, String>()

    @MainThread
    fun reserve(bookId: String, token: String): Boolean {
        if (bookId.isBlank() || token.isBlank()) return false
        val owner = tokens[bookId]
        if (owner != null && owner != token) return false
        tokens[bookId] = token
        return true
    }

    @MainThread
    fun owns(bookId: String, token: String?): Boolean = token != null && tokens[bookId] == token

    @MainThread
    fun isReserved(bookId: String?): Boolean = bookId != null && bookId in tokens

    @MainThread
    fun release(bookId: String, token: String): Boolean = tokens.remove(bookId, token)
}
