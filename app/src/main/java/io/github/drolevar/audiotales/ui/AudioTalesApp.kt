package io.github.drolevar.audiotales.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.drolevar.audiotales.data.preferences.ThemeMode
import io.github.drolevar.audiotales.ui.library.LibraryScreen
import io.github.drolevar.audiotales.ui.library.LibraryViewModel
import io.github.drolevar.audiotales.ui.settings.AboutScreen
import io.github.drolevar.audiotales.ui.settings.SettingsScreen
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import kotlinx.serialization.Serializable

@Serializable
private data object LibraryDestination : NavKey

@Serializable
private data object SettingsDestination : NavKey

@Serializable
private data object AboutDestination : NavKey

@Composable
fun AudioTalesApp(
    onDarkThemeChanged: (Boolean) -> Unit,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val preferenceSaveFailed by appViewModel.preferenceSaveFailed.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SideEffect { onDarkThemeChanged(darkTheme) }

    AudioTalesTheme(darkTheme = darkTheme) {
        val backStack = rememberNavBackStack(LibraryDestination)
        val navigate: (NavKey) -> Unit = { destination ->
            if (backStack.lastOrNull() != destination) backStack.add(destination)
        }
        val goBack: () -> Unit = {
            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                onBack = goBack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<LibraryDestination> {
                        val viewModel: LibraryViewModel = hiltViewModel()
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        LibraryScreen(
                            state = state,
                            onRetry = viewModel::retry,
                            onSettings = { navigate(SettingsDestination) },
                        )
                    }
                    entry<SettingsDestination> {
                        SettingsScreen(
                            themeMode = themeMode,
                            preferenceSaveFailed = preferenceSaveFailed,
                            onThemeModeChanged = appViewModel::setThemeMode,
                            onBack = goBack,
                            onAbout = { navigate(AboutDestination) },
                        )
                    }
                    entry<AboutDestination> { AboutScreen(onBack = goBack) }
                },
            )
        }
    }
}
