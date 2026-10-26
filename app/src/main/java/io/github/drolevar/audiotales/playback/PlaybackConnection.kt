package io.github.drolevar.audiotales.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

enum class PlaybackConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class PlaybackState(
    val connection: PlaybackConnectionStatus = PlaybackConnectionStatus.DISCONNECTED,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val activeBookId: String? = null,
    val activeTrackId: String? = null,
    val chapterTitle: String? = null,
    val error: String? = null,
    val commandError: String? = null,
    val progressSaveFailed: Boolean = false,
    val sleepTimer: SleepTimerState = SleepTimerState(),
)

/** Each Activity owns its controller connection; the shared service still owns one player. */
@ActivityScoped
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRemovalGuard: BookRemovalGuard,
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var commandError: String? = null
    private val bookRemovalTokens = mutableMapOf<String, String>()
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
        commandError = null
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
            activeTrackId = player.currentMediaItem?.mediaId?.let(LibraryMediaIds::trackId),
            chapterTitle = player.mediaMetadata.title?.toString(),
            error = player.playerError?.message,
            commandError = commandError,
            progressSaveFailed = controller?.sessionExtras?.getBoolean("progressSaveFailed") == true,
            sleepTimer = controller?.sessionExtras?.let(SleepTimerCommands::readState) ?: SleepTimerState(),
        )
    }

    /** True means the connected service supports the command and it was dispatched. */
    @MainThread
    fun setSleepTimer(bookId: String, durationMs: Long): Boolean = dispatchSleepTimer(
        SleepTimerCommands.SET_DURATION,
        bookId,
        Bundle().apply { putLong(SleepTimerCommands.DURATION_MS, durationMs) },
    )

    @MainThread
    fun setEndOfTrackTimer(bookId: String): Boolean =
        dispatchSleepTimer(SleepTimerCommands.END_OF_TRACK, bookId)

    @MainThread
    fun cancelSleepTimer(bookId: String): Boolean = dispatchSleepTimer(SleepTimerCommands.CANCEL, bookId)

    private fun dispatchSleepTimer(command: SessionCommand, bookId: String, args: Bundle = Bundle()): Boolean {
        val connected = controller
        if (connected == null || !connected.isConnected || bookId.isBlank() ||
            !connected.availableSessionCommands.contains(command)
        ) {
            reportCommandError("Sleep timer is unavailable.")
            return false
        }
        args.putString(SleepTimerCommands.BOOK_ID, bookId)
        val result = connected.sendCustomCommand(command, args)
        result.addListener(
            {
                if (controller === connected) {
                    try {
                        commandError = if (result.get().resultCode == SessionResult.RESULT_SUCCESS) null
                        else "Sleep timer request was rejected."
                        updateState(connected)
                    } catch (_: ExecutionException) {
                        reportCommandError("Sleep timer request failed.")
                    } catch (_: CancellationException) {
                        reportCommandError("Sleep timer request was canceled.")
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        reportCommandError("Sleep timer request was interrupted.")
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        return true
    }

    /** Reserves this book against new playback, clears its active queue and drains progress. */
    @MainThread
    suspend fun prepareBookRemoval(bookId: String): Boolean {
        val token = bookRemovalTokens[bookId] ?: UUID.randomUUID().toString()
        if (!bookRemovalGuard.reserve(bookId, token)) {
            reportCommandError("Book removal is already in progress.")
            return false
        }
        bookRemovalTokens[bookId] = token
        return sendRemovalCommand(bookId, token)
    }

    /** Release in the caller's finally block, including after a canceled/failed deletion. */
    @MainThread
    suspend fun finishBookRemoval(bookId: String): Boolean {
        val token = bookRemovalTokens.remove(bookId) ?: return true
        bookRemovalGuard.release(bookId, token)
        return true
    }

    private suspend fun sendRemovalCommand(bookId: String, token: String): Boolean {
        val connected = controller ?: return false
        if (!connected.isConnected || !connected.availableSessionCommands.contains(prepareBookRemovalCommand)) return false
        return try {
            val result = connected.sendCustomCommand(prepareBookRemovalCommand, Bundle().apply {
                putString(SleepTimerCommands.BOOK_ID, bookId)
                putString(BOOK_REMOVAL_TOKEN, token)
            }).await()
            val accepted = controller === connected && connected.isConnected && result.resultCode == SessionResult.RESULT_SUCCESS
            if (accepted) clearCommandError()
            else reportCommandError("Book removal could not stop playback.")
            accepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportCommandError("Book removal could not stop playback.")
            false
        }
    }

    private fun reportCommandError(message: String) {
        commandError = message
        mutableState.value = mutableState.value.copy(commandError = message)
    }

    @MainThread
    fun clearCommandError() {
        commandError = null
        mutableState.value = mutableState.value.copy(commandError = null)
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
