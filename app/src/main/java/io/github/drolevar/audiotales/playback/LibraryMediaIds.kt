package io.github.drolevar.audiotales.playback

/** Stable, opaque IDs shared by the phone and external media browsers. */
internal object LibraryMediaIds {
    const val ROOT = "audiotales:library"
    const val MY_BOOKS = "audiotales:my-books"
    private const val BOOK_PREFIX = "book:"
    private const val TRACK_PREFIX = "track:"

    fun book(bookId: String): String = "$BOOK_PREFIX$bookId"

    fun track(trackId: String): String = "$TRACK_PREFIX$trackId"

    fun bookId(mediaId: String): String? = mediaId.idAfter(BOOK_PREFIX)

    fun trackId(mediaId: String): String? = mediaId.idAfter(TRACK_PREFIX)

    private fun String.idAfter(prefix: String): String? =
        takeIf { startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf(String::isNotBlank)
}
