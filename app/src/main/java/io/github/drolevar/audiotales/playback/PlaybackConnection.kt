package io.github.drolevar.audiotales.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
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
        val future = controllerFuture
        controllerFuture = null
        if (future != null) MediaController.releaseFuture(future)
        mutableState.value = PlaybackState()
    }

    private fun connectionFailed(future: ListenableFuture<MediaController>) {
        controllerFuture = null
        controller = null
        MediaController.releaseFuture(future)
        mutableState.value = PlaybackState(connection = PlaybackConnectionStatus.FAILED)
    }

    private fun updateState(player: Player) {
        mutableState.value = PlaybackState(
            connection = PlaybackConnectionStatus.CONNECTED,
            isPlaying = player.isPlaying,
            title = player.mediaMetadata.title?.toString(),
        )
    }
}
