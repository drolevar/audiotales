package io.github.drolevar.audiotales.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import android.os.Bundle
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.online.OnlineCatalogue
import io.github.drolevar.audiotales.data.online.OnlineBookDetailsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import java.util.UUID

internal val prepareBookRemovalCommand = SessionCommand("io.github.drolevar.audiotales.PREPARE_BOOK_REMOVAL", Bundle.EMPTY)
internal const val BOOK_REMOVAL_TOKEN = "bookRemovalToken"
// The platform browse-search key has no public MediaConstants alias in Media3 1.11.1.
private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

@androidx.annotation.OptIn(UnstableApi::class)
internal class LibrarySessionCallback(
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    rootTitle: String,
    myBooksTitle: String,
    private val storage: MediaStorage,
    private val progress: PlaybackProgressRecorder,
    private val sleepTimer: SleepTimerController,
    private val appPackageName: String,
    private val appUid: Int,
    private val bookRemovalGuard: BookRemovalGuard,
    onlineCatalogue: OnlineCatalogue,
    private val onlineDetails: OnlineBookDetailsSource,
) : MediaLibrarySession.Callback {
    private val resolver = PlaybackItemResolver(library)
    private val catalogue = CarCatalogueBrowser(onlineCatalogue)
    private data class SearchKey(val browser: MediaSession.ControllerInfo, val query: String)
    private class SearchSnapshot(var items: List<CarCatalogueItem>? = null, var error: Boolean = false)
    private val searches = linkedMapOf<SearchKey, SearchSnapshot>()
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
    private val myBooks = MediaItem.Builder()
        .setMediaId(LibraryMediaIds.MY_BOOKS)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(myBooksTitle)
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
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).apply {
                if (isAppController(controller)) {
                    setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                            .add(SleepTimerCommands.SET_DURATION)
                            .add(SleepTimerCommands.END_OF_TRACK)
                            .add(SleepTimerCommands.CANCEL)
                            .add(prepareBookRemovalCommand)
                            .build(),
                    )
                }
            }.build()
        } else {
            MediaSession.ConnectionResult.reject()
        },
    )

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (!isAppController(controller)) return commandResult(SessionError.ERROR_PERMISSION_DENIED)
        val bookId = args.getString(SleepTimerCommands.BOOK_ID)?.takeIf(String::isNotBlank)
            ?: return commandResult(SessionError.ERROR_BAD_VALUE)
        if (customCommand == prepareBookRemovalCommand) return scope.future {
            val token = args.getString(BOOK_REMOVAL_TOKEN)
            if (!bookRemovalGuard.owns(bookId, token)) return@future SessionResult(SessionError.ERROR_INVALID_STATE)
            try {
                bookRemovalGuard.awaitAdditions()
                if (!bookRemovalGuard.owns(bookId, token)) return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                if (session.player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") == bookId) {
                    session.player.pause()
                    progress.flushCurrent()
                }
                if (!bookRemovalGuard.owns(bookId, token)) return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                // Also remove this book from a mixed queue without stopping another active book.
                val queuedIndices = (0 until session.player.mediaItemCount).filter {
                    session.player.getMediaItemAt(it).mediaMetadata.extras?.getString("bookId") == bookId
                }
                if (queuedIndices.size == session.player.mediaItemCount) session.player.clearMediaItems()
                else queuedIndices.asReversed().forEach(session.player::removeMediaItem)
                progress.drain()
                if (!bookRemovalGuard.owns(bookId, token)) return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SessionResult(SessionError.ERROR_IO)
            }
        }
        if (bookRemovalGuard.isReserved(bookId)) return commandResult(SessionError.ERROR_INVALID_STATE)
        val accepted = when (customCommand) {
            SleepTimerCommands.SET_DURATION -> sleepTimer.setDuration(bookId, args.getLong(SleepTimerCommands.DURATION_MS))
            SleepTimerCommands.END_OF_TRACK -> sleepTimer.setEndOfTrack(bookId)
            SleepTimerCommands.CANCEL -> sleepTimer.cancel(bookId)
            else -> return commandResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        return commandResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
    }

    private fun isAppController(controller: MediaSession.ControllerInfo): Boolean =
        controller.isTrusted && controller.uid == appUid && controller.packageName == appPackageName

    private fun commandResult(code: Int): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(code))

    // Root discovery must be immediate for platform/legacy browsers, including Android Auto.
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder()
            .setOffline(params?.isOffline ?: false)
            .setRecent(params?.isRecent ?: false)
            .setSuggested(params?.isSuggested ?: false)
            .setExtras(Bundle(params?.extras ?: Bundle.EMPTY).apply { putBoolean(SEARCH_SUPPORTED, true) })
            .build()))

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
            parentId == LibraryMediaIds.ROOT -> listOf(myBooks)
            parentId == LibraryMediaIds.MY_BOOKS -> library.observeBooks().first().map { it.toMediaItem() }
            catalogue.item(parentId) is CarCatalogueItem.Folder ->
                requireNotNull(catalogue.children(parentId)).map { it.toMediaItem() }
            LibraryMediaIds.bookId(parentId) != null -> {
                val bookId = requireNotNull(LibraryMediaIds.bookId(parentId))
                if (library.getBook(bookId) == null) {
                    return@readLibrary LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
                library.getTracks(bookId).toMediaItems(includeLocation = false)
            }
            else -> return@readLibrary LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        // Auto requests an unpaged list. Other browsers may request individual pages.
        LibraryResult.ofItemList(children.libraryPage(page, pageSize), params)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val key = SearchKey(browser, query)
        val snapshot = SearchSnapshot()
        searches.remove(key)
        searches[key] = snapshot
        while (searches.size > 16) searches.remove(searches.keys.first())
        return readLibrary(params, onReadFailure = {
            snapshot.error = true
            // Legacy search ignores the future; it needs this notification even on error.
            if (searches[key] == null || searches[key] === snapshot) {
                session.notifySearchResultChanged(browser, query, 0, params)
            }
        }) {
            val items = catalogue.search(query)
            if (searches[key] === snapshot) {
                snapshot.items = items
                session.notifySearchResultChanged(browser, query, items.size, params)
            } else if (searches[key] == null) {
                session.notifySearchResultChanged(browser, query, 0, params)
            }
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = readLibrary(params) {
        if (page < 0 || pageSize < 1) {
            return@readLibrary LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        val snapshot = searches[SearchKey(browser, query)]
            ?: return@readLibrary LibraryResult.ofError(SessionError.ERROR_INVALID_STATE)
        if (snapshot.error) return@readLibrary LibraryResult.ofError(SessionError.ERROR_IO)
        val matches = snapshot.items ?: return@readLibrary LibraryResult.ofError(SessionError.ERROR_INVALID_STATE)
        if (matches.any { catalogue.item(it.mediaId) == null }) {
            return@readLibrary LibraryResult.ofError(SessionError.ERROR_INVALID_STATE)
        }
        LibraryResult.ofItemList(matches.libraryPage(page, pageSize).map { it.toMediaItem() }, params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = readLibrary {
        val item = when {
            mediaId == LibraryMediaIds.ROOT -> root
            mediaId == LibraryMediaIds.MY_BOOKS -> myBooks
            catalogue.item(mediaId) != null -> catalogue.item(mediaId)?.toMediaItem()
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
        require(mediaItems.none { it.localConfiguration != null || it.requestMetadata.mediaUri != null })
        if (mediaItems.size == 1 && catalogue.item(mediaItems.single().mediaId) is CarCatalogueItem.Book) {
            val generation = bookRemovalGuard.additionGeneration()
            val bookId = saveSearchSelection(mediaItems.single().mediaId, generation)
            val items = resolver.resolveBook(bookId).tracks.toMediaItems(includeLocation = true)
            rejectPendingRemovalItems(items)
            catalogue.selectedBook(mediaItems.single().mediaId)
            bookRemovalGuard.checkAdditionGeneration(generation)
            return@future items
        }
        rejectPendingRemovalRequests(mediaItems)
        val requests = mediaItems.map { item ->
            PlaybackRequest(
                mediaId = item.mediaId,
                hasExternalLocation = item.localConfiguration != null || item.requestMetadata.mediaUri != null,
            )
        }
        val resolved = resolver.resolve(requests).toMediaItems(includeLocation = true)
        rejectPendingRemovalItems(resolved)
        resolved
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        require(mediaItems.none { it.localConfiguration != null || it.requestMetadata.mediaUri != null })
        rejectPendingRemovalRequests(mediaItems)
        val onlineId = mediaItems.singleOrNull()?.mediaId?.takeIf { catalogue.item(it) is CarCatalogueItem.Book }
        val generation = onlineId?.let { bookRemovalGuard.additionGeneration() }
        val selectedBookId = onlineId?.let { saveSearchSelection(it, requireNotNull(generation)) }
        progress.flushCurrent()
        val bookId = selectedBookId ?: mediaItems.singleOrNull()?.mediaId?.let(LibraryMediaIds::bookId)
        val result = if (bookId != null) {
            val queue = resolver.resolveBook(bookId)
            MediaSession.MediaItemsWithStartPosition(
                queue.tracks.toMediaItems(includeLocation = true),
                queue.startIndex,
                queue.startPositionMs,
            )
        } else {
            val tracks = resolver.resolve(mediaItems.map { PlaybackRequest(it.mediaId, false) })
            if (tracks.size == 1) {
                val queue = resolver.resolveBook(tracks.single().bookId)
                MediaSession.MediaItemsWithStartPosition(
                    queue.tracks.toMediaItems(includeLocation = true),
                    queue.tracks.indexOfFirst { it.id == tracks.single().id },
                    startPositionMs.takeUnless { it == C.TIME_UNSET } ?: 0,
                )
            } else {
                MediaSession.MediaItemsWithStartPosition(
                    tracks.toMediaItems(includeLocation = true), startIndex, startPositionMs,
                )
            }
        }
        rejectPendingRemovalItems(result.mediaItems)
        if (generation != null) {
            catalogue.selectedBook(requireNotNull(onlineId))
            bookRemovalGuard.checkAdditionGeneration(generation)
        }
        result
    }

    private suspend fun saveSearchSelection(mediaId: String, generation: Long): String {
        val selected = catalogue.selectedBook(mediaId)
        val saved = library.observeBooks().first().firstOrNull {
            it.providerId == selected.libraryId && it.providerBookId == selected.remoteId
        }
        if (saved != null) {
            bookRemovalGuard.checkAdditionGeneration(generation)
            require(!bookRemovalGuard.isReserved(saved.id))
            return saved.id
        }
        val resolved = onlineDetails.inspectBook(selected.libraryId, selected.remoteId)
        require(resolved.providerId == selected.libraryId && resolved.providerBookId == selected.remoteId)
        // Selection may change while the provider resolves its playlist; never save a stale source.
        catalogue.selectedBook(mediaId)
        return bookRemovalGuard.withAddition(generation) { library.addOnlineBook(resolved).book.id }
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        searches.keys.removeAll { it.browser == controller }
    }

    private suspend fun rejectPendingRemovalRequests(items: List<MediaItem>) {
        items.forEach { item ->
            val bookId = LibraryMediaIds.bookId(item.mediaId)
                ?: LibraryMediaIds.trackId(item.mediaId)?.let { library.getTrack(it)?.bookId }
            require(!bookRemovalGuard.isReserved(bookId)) { "Book removal is in progress" }
        }
    }

    private fun rejectPendingRemovalItems(items: List<MediaItem>) {
        require(items.none { bookRemovalGuard.isReserved(it.mediaMetadata.extras?.getString("bookId")) }) {
            "Book removal is in progress"
        }
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = readLibrary(params) {
        if (parentId == LibraryMediaIds.ROOT || parentId == LibraryMediaIds.MY_BOOKS ||
            catalogue.item(parentId) is CarCatalogueItem.Folder ||
            LibraryMediaIds.bookId(parentId)?.let { library.getBook(it) } != null) {
            LibraryResult.ofVoid(params)
        } else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    private fun <T : Any> readLibrary(
        params: LibraryParams? = null,
        onReadFailure: () -> Unit = {},
        block: suspend () -> LibraryResult<T>,
    ): ListenableFuture<LibraryResult<T>> = scope.future {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onReadFailure()
            // A failed read must remain distinguishable from a successfully empty library.
            LibraryResult.ofError<T>(SessionError.ERROR_IO, params)
        }
    }

    private fun LibraryBook.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(LibraryMediaIds.book(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(author)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    private fun CarCatalogueItem.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().apply {
            when (this@toMediaItem) {
                is CarCatalogueItem.Book -> {
                    setTitle(book.displayTitle)
                    setArtist(book.author)
                    setIsBrowsable(false)
                    setIsPlayable(true)
                    setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                }
                is CarCatalogueItem.Folder -> {
                    setTitle(title)
                    setIsBrowsable(true)
                    setIsPlayable(false)
                    setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                }
            }
        }.build())
        .build()

    private suspend fun List<LibraryTrack>.toMediaItems(includeLocation: Boolean): List<MediaItem> {
        // Both session and notification wrap the loader in a last-result cache, including errors.
        // An explicit queue selection/retry gets one fresh attempt shared by all its chapters.
        val attempt = UUID.randomUUID().toString()
        val books = map(LibraryTrack::bookId).distinct().associateWith { library.getBook(it) }
        return map { it.toPlaybackMediaItem(books[it.bookId], storage, includeLocation, attempt) }
    }

    private suspend fun LibraryTrack.toMediaItem(
        includeLocation: Boolean,
        artworkAttempt: String = UUID.randomUUID().toString(),
    ): MediaItem = toPlaybackMediaItem(library.getBook(bookId), storage, includeLocation, artworkAttempt)
}

/** Build only from repository-owned transport and bounds; player positions stay clip-relative. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun LibraryTrack.toPlaybackMediaItem(
    book: LibraryBook?,
    storage: MediaStorage,
    includeLocation: Boolean,
    artworkAttempt: String = UUID.randomUUID().toString(),
): MediaItem = MediaItem.Builder()
    .setMediaId(LibraryMediaIds.track(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumTitle(book?.displayTitle)
            .setArtist(book?.author)
            .setAlbumArtist(book?.author)
            .apply {
                // Playing queues always identify the book, even without a cover, clearing
                // extracted artworkData. Browse-only thumbnails need separate host support.
                if (includeLocation) setArtworkUri(Uri.parse(BookArtworkUris.forBook(
                    bookId, book?.coverUrl, book?.coverReferer, artworkAttempt)))
            }
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setExtras(Bundle().apply { putString("bookId", bookId) })
            .build(),
    )
    .apply {
        if (includeLocation) {
            require(sourceStartMs >= 0 && (sourceEndMs == null || sourceEndMs > sourceStartMs)) {
                "Invalid audio range"
            }
            storage.registerPlayback(this@toPlaybackMediaItem)
            setUri(uri)
            setCustomCacheKey(downloadId)
            setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(sourceStartMs)
                .setEndPositionMs(sourceEndMs ?: C.TIME_END_OF_SOURCE)
                .build())
        }
    }
    .build()
