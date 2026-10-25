package io.github.drolevar.audiotales.playback

import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.LibraryTrack

internal data class PlaybackRequest(
    val mediaId: String,
    val hasExternalLocation: Boolean,
)

/** Resolve only library identities; callers never choose the file or network location to open. */
internal class PlaybackItemResolver(private val library: LibraryRepository) {
    suspend fun resolve(requests: List<PlaybackRequest>): List<LibraryTrack> {
        require(requests.none(PlaybackRequest::hasExternalLocation)) {
            "Playback requests must contain library IDs without media locations."
        }
        val trackIds = requests.map { request ->
            requireNotNull(LibraryMediaIds.trackId(request.mediaId)) {
                "Playback requires a track from this library."
            }
        }
        return trackIds.map { trackId ->
            requireNotNull(library.getTrack(trackId)) { "The requested track is not in the library." }
        }
    }

    suspend fun resolveBook(bookId: String): BookPlaybackQueue {
        requireNotNull(library.getBook(bookId)) { "Book is not in the library." }
        val tracks = library.getTracks(bookId)
        require(tracks.isNotEmpty()) { "Book has no playable tracks." }
        val progress = library.getProgress(bookId)
        val index = tracks.indexOfFirst { it.id == progress?.trackId }.coerceAtLeast(0)
        val position = if (tracks[index].id == progress?.trackId) progress.positionMs.coerceAtLeast(0) else 0
        return BookPlaybackQueue(tracks, index, position)
    }
}

internal data class BookPlaybackQueue(val tracks: List<LibraryTrack>, val startIndex: Int, val startPositionMs: Long)
