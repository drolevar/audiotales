package io.github.drolevar.audiotales.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.drolevar.audiotales.data.preferences.ThemeMode
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme

@Preview(name = "Settings", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun SettingsPreview() {
    AudioTalesTheme(darkTheme = false, dynamicColor = false) {
        SettingsScreen(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true,
            dynamicColorAvailable = true,
            preferenceSaveFailed = false,
            onThemeModeChanged = {},
            onDynamicColorChanged = {},
            onBack = {},
            onAbout = {},
        )
    }
}

@Preview(
    name = "Settings · large text · static colors",
    widthDp = 393,
    heightDp = 852,
    showSystemUi = true,
    fontScale = 2f,
)
@Composable
fun SettingsLargeTextPreview() {
    AudioTalesTheme(darkTheme = false, dynamicColor = false) {
        SettingsScreen(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = false,
            dynamicColorAvailable = false,
            preferenceSaveFailed = false,
            onThemeModeChanged = {},
            onDynamicColorChanged = {},
            onBack = {},
            onAbout = {},
        )
    }
}
