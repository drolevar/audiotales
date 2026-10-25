package io.github.drolevar.audiotales.ui

import android.os.Build
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.data.preferences.ThemeMode
import io.github.drolevar.audiotales.playback.PlaybackConnection
import io.github.drolevar.audiotales.ui.catalogue.AddLibraryDialog
import io.github.drolevar.audiotales.ui.catalogue.CatalogueDetailsDialog
import io.github.drolevar.audiotales.ui.catalogue.CatalogueScreen
import io.github.drolevar.audiotales.ui.catalogue.CatalogueViewModel
import io.github.drolevar.audiotales.ui.catalogue.CatalogueUiState
import io.github.drolevar.audiotales.ui.catalogue.ManageLibrariesScreen
import io.github.drolevar.audiotales.ui.catalogue.LibraryManagementDialogs
import io.github.drolevar.audiotales.ui.downloads.DownloadsRoute
import io.github.drolevar.audiotales.ui.library.LibraryScreen
import io.github.drolevar.audiotales.ui.library.LibraryUiState
import io.github.drolevar.audiotales.ui.library.LibraryViewModel
import io.github.drolevar.audiotales.ui.player.BookPlayerRoute
import io.github.drolevar.audiotales.ui.player.MiniPlayerBar
import io.github.drolevar.audiotales.ui.settings.AboutScreen
import io.github.drolevar.audiotales.ui.settings.SettingsScreen
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import kotlinx.serialization.Serializable

@Serializable
private data object LibraryDestination : NavKey

@Serializable
private data class PlayerDestination(val bookId: String) : NavKey

@Serializable
private data object DownloadsDestination : NavKey

@Serializable
private data object ManageLibrariesDestination : NavKey

@Serializable
private data class BrowseLibraryDestination(val libraryId: String) : NavKey

@Serializable
private data object AboutDestination : NavKey

@Composable
fun AudioTalesApp(
    onDarkThemeChanged: (Boolean) -> Unit,
    playbackConnection: PlaybackConnection,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by appViewModel.dynamicColor.collectAsStateWithLifecycle()
    val preferenceSaveFailed by appViewModel.preferenceSaveFailed.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SideEffect { onDarkThemeChanged(darkTheme) }

    AudioTalesTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        val backStack = rememberNavBackStack(LibraryDestination)
        var selectedTab by rememberSaveable { mutableStateOf(0) }
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
                        val catalogue: CatalogueViewModel = hiltViewModel()
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val catalogueState by catalogue.state.collectAsStateWithLifecycle()
                        LifecycleStartEffect(viewModel, selectedTab) {
                            if (selectedTab == 0) {
                                viewModel.refreshArtwork()
                                viewModel.startDownloadRefresh()
                            }
                            onStopOrDispose {
                                viewModel.stopArtworkRefresh()
                                viewModel.stopDownloadRefresh()
                            }
                        }
                        val tabState = rememberSaveableStateHolder()
                        val openBook: (String) -> Unit = { navigate(PlayerDestination(it)) }
                        NavigationSuiteScaffold(
                            navigationSuiteItems = {
                                item(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(painterResource(R.drawable.ic_book), null) },
                                    label = { Text(stringResource(R.string.my_books)) },
                                )
                                item(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(painterResource(R.drawable.ic_catalogue_search), null) },
                                    label = { Text(stringResource(R.string.catalogue_browse)) },
                                )
                                item(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(painterResource(R.drawable.ic_settings), null) },
                                    label = { Text(stringResource(R.string.settings)) },
                                )
                            },
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) {
                                    tabState.SaveableStateProvider(selectedTab) {
                                        when (selectedTab) {
                                            0 -> LibraryScreen(
                                                state = state,
                                                onRetry = viewModel::retry,
                                                onSettings = { selectedTab = 2 },
                                                onOpenBook = openBook,
                                                onOpenDownloads = { navigate(DownloadsDestination) },
                                                onFilterChange = viewModel::selectFilter,
                                                onSortChange = viewModel::selectSort,
                                                onDownloadAction = viewModel::downloadAction,
                                                onRemoveDownloads = viewModel::removeDownloads,
                                                onDismissActionError = viewModel::dismissActionError,
                                            )
                                            1 -> {
                                                LifecycleStartEffect(catalogue) {
                                                    catalogue.activate()
                                                    onStopOrDispose { catalogue.deactivate() }
                                                }
                                                CatalogueBrowseContent(
                                                    catalogue, catalogueState,
                                                    onManage = { navigate(ManageLibrariesDestination) },
                                                )
                                            }
                                            else -> SettingsScreen(
                                                themeMode = themeMode,
                                                dynamicColor = dynamicColor,
                                                dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                                preferenceSaveFailed = preferenceSaveFailed,
                                                onThemeModeChanged = appViewModel::setThemeMode,
                                                onDynamicColorChanged = appViewModel::setDynamicColor,
                                                onBack = { selectedTab = 0 },
                                                onAbout = { navigate(AboutDestination) },
                                                onDownloads = { navigate(DownloadsDestination) },
                                            )
                                        }
                                    }
                                }
                                MiniPlayerBar(
                                    connection = playbackConnection,
                                    onOpenBook = openBook,
                                    books = (state as? LibraryUiState.Content)?.books.orEmpty(),
                                )
                            }
                        }
                        catalogueState.addLibrary?.let { input ->
                            AddLibraryDialog(
                                state = input,
                                onUrlChanged = catalogue::setLibraryUrl,
                                onSubmit = catalogue::addLibrary,
                                onDismiss = catalogue::dismissAddLibrary,
                            )
                        }
                        if (catalogueState.details != null) {
                            CatalogueDetailsDialog(
                                state = catalogueState,
                                onSave = catalogue::saveBook,
                                onOpenBook = openBook,
                                onDismiss = catalogue::dismissDetails,
                            )
                        }
                    }
                    entry<ManageLibrariesDestination> {
                        val catalogue: CatalogueViewModel = hiltViewModel()
                        val state by catalogue.state.collectAsStateWithLifecycle()
                        val libraryViewModel: LibraryViewModel = hiltViewModel()
                        val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                ManageLibrariesScreen(
                                    state = state, onBack = goBack, onAdd = catalogue::openAddLibrary,
                                    onSelected = catalogue::setLibrarySelected,
                                    onBrowse = { navigate(BrowseLibraryDestination(it)) },
                                    onRename = catalogue::rename, onRemove = catalogue::remove,
                                    onShare = { library ->
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, library.baseUrl)
                                        }, null))
                                    },
                                    onRetry = catalogue::observeLibraries,
                                )
                            }
                            MiniPlayerBar(
                                connection = playbackConnection,
                                onOpenBook = { navigate(PlayerDestination(it)) },
                                books = (libraryState as? LibraryUiState.Content)?.books.orEmpty(),
                            )
                        }
                        CatalogueDialogs(catalogue, state, { navigate(PlayerDestination(it)) })
                        LibraryManagementDialogs(state, catalogue::setLibraryName, catalogue::confirmRename, catalogue::confirmRemove, catalogue::dismissLibraryAction)
                    }
                    entry<BrowseLibraryDestination> { destination ->
                        val catalogue: CatalogueViewModel = hiltViewModel()
                        val state by catalogue.state.collectAsStateWithLifecycle()
                        val libraryViewModel: LibraryViewModel = hiltViewModel()
                        val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
                        LifecycleStartEffect(catalogue, destination.libraryId) {
                            catalogue.activate(destination.libraryId)
                            onStopOrDispose { catalogue.deactivate() }
                        }
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                CatalogueBrowseContent(catalogue, state, onManage = goBack, onBack = goBack)
                            }
                            MiniPlayerBar(
                                connection = playbackConnection,
                                onOpenBook = { navigate(PlayerDestination(it)) },
                                books = (libraryState as? LibraryUiState.Content)?.books.orEmpty(),
                            )
                        }
                        CatalogueDialogs(catalogue, state, { navigate(PlayerDestination(it)) })
                    }
                    entry<PlayerDestination> { destination ->
                        BookPlayerRoute(
                            bookId = destination.bookId,
                            connection = playbackConnection,
                            onBack = goBack,
                        )
                    }
                    entry<DownloadsDestination> {
                        DownloadsRoute(
                            connection = playbackConnection,
                            onBack = goBack,
                            onOpenBook = { navigate(PlayerDestination(it)) },
                            onOpenMyBooks = {
                                selectedTab = 0
                                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                        )
                    }
                    entry<AboutDestination> { AboutScreen(onBack = goBack) }
                },
            )
        }
    }
}

@Composable
private fun CatalogueBrowseContent(
    catalogue: CatalogueViewModel,
    state: CatalogueUiState,
    onManage: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    CatalogueScreen(
        state = state,
        onQueryChanged = catalogue::setQuery,
        onSearch = catalogue::browse,
        onLibrarySelected = catalogue::setLibrarySelected,
        onManageLibraries = onManage,
        onRetryLibraries = catalogue::observeLibraries,
        onRetrySource = catalogue::retrySource,
        onLoadMore = catalogue::loadMore,
        onSort = catalogue::selectSort,
        onLanguage = catalogue::selectLanguage,
        onInspect = catalogue::inspect,
        onBack = onBack,
    )
}

@Composable
private fun CatalogueDialogs(catalogue: CatalogueViewModel, state: CatalogueUiState, onOpenBook: (String) -> Unit) {
    state.addLibrary?.let { input ->
        AddLibraryDialog(input, catalogue::setLibraryUrl, catalogue::addLibrary, catalogue::dismissAddLibrary)
    }
    if (state.details != null) CatalogueDetailsDialog(state, catalogue::saveBook, onOpenBook, catalogue::dismissDetails)
}
