package io.github.drolevar.audiotales.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

@androidx.annotation.OptIn(UnstableApi::class)
internal class LibrarySessionCallback(
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    rootTitle: String,
) : MediaLibrarySession.Callback {
    private val resolver = PlaybackItemResolver(library)
    private val root = MediaItem.Builder()
        .setMediaId(LibraryMediaIds.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(rootTitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build(),
        )
        .build()

    // The default accepts untrusted read-only browsers, which would disclose library titles.
    // Use Android's media-control trust rather than package-name or connection-hint allowlists.
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
        if (controller.isTrusted) {
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
        } else {
            MediaSession.ConnectionResult.reject()
        },
    )

    // Root discovery must be immediate for platform/legacy browsers, including Android Auto.
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(root, params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = readLibrary(params) {
        if (page < 0 || pageSize < 1) {
            return@readLibrary LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        val children = when {
            parentId == LibraryMediaIds.ROOT -> library.observeBooks().first().map { it.toMediaItem() }
            LibraryMediaIds.bookId(parentId) != null -> {
                val bookId = requireNotNull(LibraryMediaIds.bookId(parentId))
                if (library.getBook(bookId) == null) {
                    return@readLibrary LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
                library.getTracks(bookId).map { it.toMediaItem(includeLocation = false) }
            }
            else -> return@readLibrary LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        // Auto requests an unpaged list. Other browsers may request individual pages.
        val fromIndex = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
        val toIndex = (fromIndex.toLong() + pageSize).coerceAtMost(children.size.toLong()).toInt()
        LibraryResult.ofItemList(children.subList(fromIndex, toIndex), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = readLibrary {
        val item = when {
            mediaId == LibraryMediaIds.ROOT -> root
            LibraryMediaIds.bookId(mediaId) != null -> {
                library.getBook(requireNotNull(LibraryMediaIds.bookId(mediaId)))?.toMediaItem()
            }
            LibraryMediaIds.trackId(mediaId) != null -> {
                library.getTrack(requireNotNull(LibraryMediaIds.trackId(mediaId)))
                    ?.toMediaItem(includeLocation = false)
            }
            else -> null
        }
        if (item == null) {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } else {
            LibraryResult.ofItem(item, null)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future {
        val requests = mediaItems.map { item ->
            PlaybackRequest(
                mediaId = item.mediaId,
                hasExternalLocation = item.localConfiguration != null || item.requestMetadata.mediaUri != null,
            )
        }
        resolver.resolve(requests).map { it.toMediaItem(includeLocation = true) }
    }

    private fun <T : Any> readLibrary(
        params: LibraryParams? = null,
        block: suspend () -> LibraryResult<T>,
    ): ListenableFuture<LibraryResult<T>> = scope.future {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed read must remain distinguishable from a successfully empty library.
            LibraryResult.ofError<T>(SessionError.ERROR_IO, params)
        }
    }

    private fun LibraryBook.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(LibraryMediaIds.book(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(author)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    private fun LibraryTrack.toMediaItem(includeLocation: Boolean): MediaItem = MediaItem.Builder()
        .setMediaId(LibraryMediaIds.track(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .build(),
        )
        .apply { if (includeLocation) setUri(uri) }
        .build()
}
