package io.github.drolevar.audiotales.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class PlaybackState(
    val connection: PlaybackConnectionStatus = PlaybackConnectionStatus.DISCONNECTED,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val activeBookId: String? = null,
    val chapterTitle: String? = null,
    val error: String? = null,
    val progressSaveFailed: Boolean = false,
)

/** Each Activity owns its controller connection; the shared service still owns one player. */
@ActivityScoped
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val mutableController = MutableStateFlow<MediaController?>(null)
    val connectedController: StateFlow<MediaController?> = mutableController.asStateFlow()
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === controller) updateState(player)
        }
    }

    @MainThread
    fun connect() {
        if (controllerFuture != null) return
        mutableState.value = PlaybackState(connection = PlaybackConnectionStatus.CONNECTING)
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlaybackConnection.controller !== controller) return
                    disconnect()
                    mutableState.value = PlaybackState(connection = PlaybackConnectionStatus.FAILED)
                }
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    if (this@PlaybackConnection.controller === controller) updateState(controller)
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                // A stopped/recreated Activity may already have released this connection.
                if (controllerFuture === future) {
                    try {
                        val connected = future.get()
                        if (connected.isConnected) {
                            controller = connected
                            mutableController.value = connected
                            connected.addListener(playerListener)
                            updateState(connected)
                        } else {
                            connectionFailed(future)
                        }
                    } catch (_: ExecutionException) {
                        connectionFailed(future)
                    } catch (_: CancellationException) {
                        connectionFailed(future)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        connectionFailed(future)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    @MainThread
    fun disconnect() {
        controller?.removeListener(playerListener)
        controller = null
        mutableController.value = null
        val future = controllerFuture
        controllerFuture = null
        if (future != null) MediaController.releaseFuture(future)
        mutableState.value = PlaybackState()
    }

    private fun connectionFailed(future: ListenableFuture<MediaController>) {
        controllerFuture = null
        controller = null
        mutableController.value = null
        MediaController.releaseFuture(future)
        mutableState.value = PlaybackState(connection = PlaybackConnectionStatus.FAILED)
    }

    private fun updateState(player: Player) {
        mutableState.value = PlaybackState(
            connection = PlaybackConnectionStatus.CONNECTED,
            isPlaying = player.isPlaying,
            title = player.mediaMetadata.albumTitle?.toString() ?: player.mediaMetadata.title?.toString(),
            activeBookId = player.mediaMetadata.extras?.getString("bookId"),
            chapterTitle = player.mediaMetadata.title?.toString(),
            error = player.playerError?.message,
            progressSaveFailed = controller?.sessionExtras?.getBoolean("progressSaveFailed") == true,
        )
    }

    @MainThread
    fun playBook(bookId: String) {
        val player = controller ?: return
        if (mutableState.value.activeBookId != bookId) {
            player.setMediaItem(MediaItem.Builder().setMediaId(LibraryMediaIds.book(bookId)).build())
        } else if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
        }
        player.prepare()
        player.play()
    }

    @MainThread
    fun retryBook(bookId: String): Boolean {
        val player = controller ?: return false
        // Always resolve the stable book again. prepare() alone retains expired transport URLs.
        // The service flushes outgoing progress before it restores the refreshed queue.
        player.setMediaItem(MediaItem.Builder().setMediaId(LibraryMediaIds.book(bookId)).build())
        player.prepare()
        player.play()
        return true
    }

    @MainThread
    fun playTrack(trackId: String) {
        val player = controller ?: return
        val mediaId = LibraryMediaIds.track(trackId)
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
        if (index != null) player.seekTo(index, 0)
        else player.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        player.prepare()
        player.play()
    }
}
