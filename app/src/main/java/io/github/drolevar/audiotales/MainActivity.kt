package io.github.drolevar.audiotales

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.github.drolevar.audiotales.playback.PlaybackConnection
import io.github.drolevar.audiotales.ui.AudioTalesApp
import io.github.drolevar.audiotales.ui.links.IncomingBookLinkViewModel
import io.github.drolevar.audiotales.ui.links.IncomingLinkIntent
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var playbackConnection: PlaybackConnection
    private val incomingLinks: IncomingBookLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingLinks.restore()
        if (savedInstanceState == null) IncomingLinkIntent.text(intent)?.let(incomingLinks::accept)
        enableEdgeToEdge()
        setContent {
            AudioTalesApp(playbackConnection = playbackConnection, incomingLinks = incomingLinks, onDarkThemeChanged = { darkTheme ->
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                        darkTheme
                    },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                        darkTheme
                    },
                )
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingLinkIntent.text(intent)?.let(incomingLinks::accept)
    }

    override fun onStart() {
        super.onStart()
        playbackConnection.connect()
    }

    override fun onStop() {
        playbackConnection.disconnect()
        super.onStop()
    }
}
