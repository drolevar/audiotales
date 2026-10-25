package io.github.drolevar.audiotales

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.drolevar.audiotales.playback.PlaybackConnection
import io.github.drolevar.audiotales.ui.AudioTalesApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var playbackConnection: PlaybackConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioTalesApp(playbackConnection = playbackConnection, onDarkThemeChanged = { darkTheme ->
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

    override fun onStart() {
        super.onStart()
        playbackConnection.connect()
    }

    override fun onStop() {
        playbackConnection.disconnect()
        super.onStop()
    }
}
