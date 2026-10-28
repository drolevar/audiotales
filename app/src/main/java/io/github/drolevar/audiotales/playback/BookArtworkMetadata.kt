package io.github.drolevar.audiotales.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.drolevar.audiotales.data.library.LibraryBook

/** Metadata-only replacements let ExoPlayer retain its source, clip, position and play state. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun refreshQueuedBookArtwork(player: Player, books: List<LibraryBook>) {
    val byId = books.associateBy(LibraryBook::id)
    for (index in 0 until player.mediaItemCount) {
        val item = player.getMediaItemAt(index)
        val bookId = item.mediaMetadata.extras?.getString("bookId") ?: continue
        val book = byId[bookId] ?: continue
        val uri = Uri.parse(BookArtworkUris.refreshed(item.mediaMetadata.artworkUri.toString(),
            bookId, book.coverUrl, book.coverReferer))
        if (uri == item.mediaMetadata.artworkUri) continue
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setArtworkUri(uri).build()).build())
    }
}
