package io.github.drolevar.audiotales.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.drolevar.audiotales.artwork.bookCoverRequestBuilder
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.validArtworkUrl
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.roundToInt

/** Artwork is independent of audio resolution and lives only as long as its owning service. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BookArtworkLoader(
    private val context: Context,
    private val library: LibraryRepository,
    parentScope: CoroutineScope,
    private val images: ImageLoader,
) : BitmapLoader, Player.Listener {
    private val scope = CoroutineScope(parentScope.coroutineContext +
        SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)

    // Track-embedded artwork is deliberately not an alternative to the chosen book cover.
    override fun supportsMimeType(mimeType: String): Boolean = false

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.immediateFailedFuture(IllegalArgumentException("Book artwork requires a repository identity"))

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> =
        metadata.artworkUri?.let(::loadBitmap)
            // Media3 1.11.1's legacy bridge leaves its old pending callback active when this
            // returns null. A failed future invalidates it on queue clear, including a success
            // already posted to the player looper. Native controls keep their own placeholder.
            ?: Futures.immediateFailedFuture(IOException("Book artwork is unavailable"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        withTimeout(10_000) {
            val bookId = requireNotNull(BookArtworkUris.bookId(uri.toString())) { "Unknown artwork identity" }
            val book = library.getBook(bookId) ?: throw IOException("Book artwork is unavailable")
            val coverUrl = validArtworkUrl(book.coverUrl) ?: throw IOException("Book artwork is unavailable")
            val request = bookCoverRequestBuilder(context, coverUrl, book.coverReferer)
                .size(MAX_SIZE)
                .scale(Scale.FIT)
                .precision(Precision.EXACT)
                .allowHardware(false)
                .build()
            val result = images.execute(request)
            if (result !is SuccessResult) {
                throw IOException("Book artwork could not be loaded", (result as? ErrorResult)?.throwable)
            }
            val bitmap = result.image.toBitmap().let {
                if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
            }
            // Also bound images returned from cache or a non-bitmap Coil component.
            val largestSide = max(bitmap.width, bitmap.height)
            if (largestSide <= MAX_SIZE) bitmap else {
                val scale = MAX_SIZE.toDouble() / largestSide
                Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()),
                    max(1, (bitmap.height * scale).roundToInt()), true)
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // The legacy session does not invalidate its pending bitmap callback for empty metadata.
        if (mediaItem == null) cancelPending()
    }

    fun cancelPending() = scope.coroutineContext.cancelChildren()

    fun close() = scope.cancel()

    private companion object {
        const val MAX_SIZE = 512
    }
}
