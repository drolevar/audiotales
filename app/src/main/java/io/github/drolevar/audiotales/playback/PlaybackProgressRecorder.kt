package io.github.drolevar.audiotales.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.drolevar.audiotales.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Service lifecycle owns the writer, including a final bounded queue drain on release. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackProgressRecorder(
    private val player: Player,
    private val library: LibraryRepository,
    private val onSaveFailed: (Boolean) -> Unit,
) : Player.Listener {
    private data class Position(val trackId: String, val positionMs: Long, val completion: CompletableDeferred<Unit>? = null)
    private val positions = Channel<Position>(64)
    private var ticker: Job? = null
    private lateinit var writer: Job

    fun start(scope: CoroutineScope) {
        writer = scope.launch {
            for (position in positions) {
                persist(position)
                position.completion?.complete(Unit)
            }
        }
        player.addListener(this)
        ticker = scope.launch {
            while (isActive) {
                delay(2_000)
                if (player.isPlaying) recordCurrent()
            }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                Player.EVENT_PLAYER_ERROR,
            )) recordCurrent()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        enqueue(oldPosition.mediaItem, oldPosition.positionMs)
        recordCurrent()
    }

    fun recordCurrent() = enqueue(player.currentMediaItem, player.currentPosition)

    /** Called before replacing a queue so the outgoing book has its latest durable position. */
    suspend fun flushCurrent() {
        val id = player.currentMediaItem?.mediaId?.let(LibraryMediaIds::trackId) ?: return
        val completion = CompletableDeferred<Unit>()
        positions.send(Position(id, player.currentPosition.coerceAtLeast(0), completion))
        completion.await()
    }

    fun stop(): Job {
        recordCurrent()
        ticker?.cancel()
        player.removeListener(this)
        positions.close()
        return writer
    }

    private fun enqueue(item: MediaItem?, positionMs: Long) {
        val id = item?.mediaId?.let(LibraryMediaIds::trackId) ?: return
        if (!positions.trySend(Position(id, positionMs.coerceAtLeast(0))).isSuccess) onSaveFailed(true)
    }

    private suspend fun persist(position: Position) {
        try {
            val track = library.getTrack(position.trackId) ?: return
            library.saveProgress(track.bookId, track.id, position.positionMs)
            onSaveFailed(false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onSaveFailed(true)
        }
    }
}
