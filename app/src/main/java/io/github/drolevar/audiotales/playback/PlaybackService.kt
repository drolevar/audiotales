package io.github.drolevar.audiotales.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import io.github.drolevar.audiotales.MainActivity
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.data.library.LibraryRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var storage: MediaStorage
    @Inject lateinit var bookRemovalGuard: BookRemovalGuard

    private val libraryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var librarySession: MediaLibrarySession? = null
    private lateinit var progress: PlaybackProgressRecorder
    private lateinit var sleepTimer: SleepTimerController
    private var libraryObserver: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(storage.playbackDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000L)
            .setSeekForwardIncrementMs(30_000L)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        progress = PlaybackProgressRecorder(player, library) { failed ->
            updateSessionExtras { putBoolean("progressSaveFailed", failed) }
        }
        progress.start(libraryScope)
        sleepTimer = SleepTimerController(player, onStateChanged = { state ->
            updateSessionExtras { SleepTimerCommands.writeState(this, state) }
        })
        sleepTimer.start(libraryScope)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        librarySession = MediaLibrarySession.Builder(
            this,
            player,
            LibrarySessionCallback(
                library, libraryScope, getString(R.string.media_library_title), storage, progress,
                sleepTimer, packageName, Process.myUid(), bookRemovalGuard,
            ),
        )
            .setSessionActivity(openApp)
            .build()
        libraryObserver = libraryScope.launch {
            library.observeBooks().distinctUntilChanged().catch { /* Browsing reports its own read error. */ }
                .collect { books -> librarySession?.notifyChildrenChanged(LibraryMediaIds.ROOT, books.size, null) }
        }
    }

    // Legacy browsers first bind with a placeholder identity. The session callback validates
    // the actual controller before granting access to the library or playback commands.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        libraryObserver?.cancel()
        sleepTimer.stop()
        val writer = progress.stop()
        librarySession?.let { session ->
            val player = session.player
            session.release()
            player.release()
        }
        librarySession = null
        libraryScope.launch {
            withTimeoutOrNull(2_000) { writer.join() }
            libraryScope.cancel()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        progress.recordCurrent()
        super.onTaskRemoved(rootIntent)
    }

    private fun updateSessionExtras(update: Bundle.() -> Unit) {
        val session = librarySession ?: return
        session.setSessionExtras(Bundle(session.sessionExtras).apply(update))
    }
}
