package io.github.drolevar.audiotales.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF28675F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFAAEDE0),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF755843),
    background = Color(0xFFFCF9F3),
    surface = Color(0xFFFCF9F3),
    surfaceContainer = Color(0xFFF0EEE8),
    onBackground = Color(0xFF1B1C19),
    onSurface = Color(0xFF1B1C19),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED1C5),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0D5047),
    onPrimaryContainer = Color(0xFFAAEDE0),
    secondary = Color(0xFFE5BFA3),
    background = Color(0xFF121512),
    surface = Color(0xFF121512),
    surfaceContainer = Color(0xFF1E211E),
    onBackground = Color(0xFFE3E3DC),
    onSurface = Color(0xFFE3E3DC),
)

@Composable
fun AudioTalesTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
