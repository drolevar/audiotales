package io.github.drolevar.audiotales.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var library: LibraryRepository

    private val libraryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        librarySession = MediaLibrarySession.Builder(
            this,
            player,
            LibrarySessionCallback(library, libraryScope, getString(R.string.media_library_title)),
        )
            .setSessionActivity(openApp)
            .build()
    }

    // Legacy browsers first bind with a placeholder identity. The session callback validates
    // the actual controller before granting access to the library or playback commands.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        libraryScope.cancel()
        librarySession?.let { session ->
            val player = session.player
            session.release()
            player.release()
        }
        librarySession = null
        super.onDestroy()
    }
}
