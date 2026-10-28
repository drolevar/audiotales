package io.github.drolevar.audiotales.playback

import java.util.Base64
import java.security.MessageDigest
import java.util.UUID

/** Opaque repository identities, never a caller-supplied image location. */
internal object BookArtworkUris {
    private const val PREFIX = "audiotales-artwork://book/"

    fun forBook(
        bookId: String,
        coverUrl: String? = null,
        coverReferer: String? = null,
        attempt: String = UUID.randomUUID().toString(),
    ): String {
        require(bookId.isNotBlank())
        require(validAttempt(attempt))
        val revision = MessageDigest.getInstance("SHA-256").digest(listOf(coverUrl, coverReferer)
            .joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$PREFIX${encode(bookId)}/$revision/$attempt"
    }

    fun bookId(uri: String): String? = parts(uri)?.first

    /** A saved-cover change invalidates Media3's last-result cache without changing the audio. */
    fun refreshed(uri: String, bookId: String, coverUrl: String?, coverReferer: String?): String =
        forBook(bookId, coverUrl, coverReferer, parts(uri)?.second ?: UUID.randomUUID().toString())

    private fun parts(uri: String): Pair<String, String>? {
        if (!uri.startsWith(PREFIX)) return null
        val parts = uri.removePrefix(PREFIX).split('/')
        if (parts.size != 3 || !parts[1].matches(Regex("[0-9a-f]{64}")) || !validAttempt(parts[2])) return null
        val id = try {
            String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Reject alternate encodings, extra paths, queries, fragments and invalid UTF-8.
        return if (id.isNotBlank() && encode(id) == parts[0]) id to parts[2] else null
    }

    private fun encode(id: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(id.toByteArray(Charsets.UTF_8))

    private fun validAttempt(value: String): Boolean =
        value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
}
