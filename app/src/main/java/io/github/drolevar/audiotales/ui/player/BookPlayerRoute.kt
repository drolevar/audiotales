package io.github.drolevar.audiotales.ui.player

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.*
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.playback.*
import io.github.drolevar.audiotales.ui.BookCover
import java.net.URI
import kotlinx.coroutines.launch

/** Phone controls use the shared session; merely opening this destination never starts playback. */
@Composable
fun BookPlayerRoute(
    bookId: String,
    connection: PlaybackConnection,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: BookPlayerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadStates.collectAsStateWithLifecycle()
    val playback by connection.state.collectAsStateWithLifecycle()
    val controller by connection.connectedController.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val externalFailure = stringResource(R.string.player_external_failed)
    LifecycleStartEffect(viewModel, bookId) {
        viewModel.activate(bookId)
        onStopOrDispose { viewModel.deactivate() }
    }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    val openExternal: (Boolean) -> Unit = { share ->
        state.book?.let { book ->
            val url = safePlayerSourceUrl(book.sourceUrl)
            if (url != null) {
                try {
                    val intent = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, listOfNotNull(book.displayTitle, book.author?.takeIf(String::isNotBlank), url).joinToString("\n"))
                    }, null) else Intent(Intent.ACTION_VIEW, url.toUri())
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(externalFailure) }
                } catch (_: SecurityException) {
                    scope.launch { snackbar.showSnackbar(externalFailure) }
                }
            } else scope.launch { snackbar.showSnackbar(externalFailure) }
        }
    }
    BookPlayerContent(
        state = state, playback = playback,
        controller = if (playback.activeBookId == bookId) controller else null,
        downloads = downloads, onBack = onBack,
        onLoadRetry = { viewModel.load(bookId) }, onListen = { connection.playBook(bookId) },
        onConnectionRetry = connection::connect,
        onPlaybackRetry = { viewModel.retryPlayback(connection::retryBook) },
        onCancelAction = viewModel::cancelAction, onTrack = connection::playTrack,
        onDownload = viewModel::downloadBook, onPauseDownloads = viewModel::pauseDownloads,
        onRemoveDownloads = viewModel::removeDownloads,
        onRemoveBook = { viewModel.removeBook(connection::prepareBookRemoval, connection::finishBookRemoval) },
        onDownloadRetry = viewModel::retryDownloads, onRefreshMetadata = viewModel::refreshMetadata,
        onOpenSource = { openExternal(false) }, onShare = { openExternal(true) },
        onSleepTimer = { connection.setSleepTimer(bookId, it) },
        onEndOfTrackTimer = { connection.setEndOfTrackTimer(bookId) },
        onCancelSleepTimer = { connection.cancelSleepTimer(bookId) },
        onDismissCommandError = connection::clearCommandError,
        snackbarHost = { SnackbarHost(snackbar) }, modifier = modifier,
    )
}

/** Durable state and events cross this boundary; menu, sheet and dialog visibility stay local. */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun BookPlayerContent(
    state: BookPlayerUiState,
    playback: PlaybackState,
    controller: Player?,
    downloads: Map<String, TrackDownloadState>,
    onBack: () -> Unit,
    onLoadRetry: () -> Unit,
    onListen: () -> Unit,
    onConnectionRetry: () -> Unit,
    onPlaybackRetry: () -> Unit,
    onCancelAction: () -> Unit,
    onTrack: (String) -> Unit,
    onDownload: () -> Unit,
    onPauseDownloads: () -> Unit,
    onRemoveDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    onRemoveBook: () -> Unit = {},
    onDownloadRetry: () -> Unit = {},
    onRefreshMetadata: () -> Unit = {},
    onOpenSource: () -> Unit = {},
    onShare: () -> Unit = {},
    onSleepTimer: (Long) -> Unit = {},
    onEndOfTrackTimer: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onDismissCommandError: () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
) {
    val book = state.book
    var menu by rememberSaveable(book?.id) { mutableStateOf(false) }
    var sheet by rememberSaveable(book?.id) { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable(book?.id) { mutableStateOf<String?>(null) }
    val summary = summarizeBookDownloads(state.tracks.map { it.id }, downloads)
    val connected = playback.connection == PlaybackConnectionStatus.CONNECTED
    val activeController = controller?.takeIf { playback.activeBookId == book?.id && !state.removingBook }
    val activeTrackId = if (playback.activeBookId == book?.id) playback.activeTrackId else state.progress?.trackId
    val hasSource = safePlayerSourceUrl(book?.sourceUrl) != null
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.player_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.removingBook) {
                        Icon(painterResource(R.drawable.ic_chevron_down), stringResource(R.string.player_close))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = book != null && !state.loading && !state.removingBook) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.player_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.player_information)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_player_info), null) }, onClick = {
                                menu = false; sheet = "information"
                                if (book?.description == null && !state.metadataRefreshing) onRefreshMetadata()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.player_manage_downloads)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) }, onClick = { menu = false; sheet = "downloads" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.player_share)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_player_share), null) }, enabled = hasSource, onClick = { menu = false; onShare() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.player_remove_book), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(painterResource(R.drawable.ic_player_delete), null, tint = MaterialTheme.colorScheme.error) }, enabled = !state.actionBusy && !summary.removing, onClick = { menu = false; confirmation = "book" })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.player_loading))
                }
                state.failed || book == null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PlayerError(stringResource(R.string.player_book_unavailable))
                    Button(onClick = onLoadRetry) { Text(stringResource(R.string.try_again)) }
                }
                else -> BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                    val availableHeight = maxHeight
                    val coverHeight = (availableHeight * .47f).coerceIn(160.dp, 352.dp)
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = availableHeight).padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BookCover(book.coverUrl, Modifier.widthIn(max = 345.dp).fillMaxWidth().height(coverHeight), book.coverReferer,
                                containerColor = MaterialTheme.colorScheme.surface, placeholderSize = 64.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(book.displayTitle, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
                            book.author?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(if (state.downloadsReady && summary.complete) R.drawable.ic_download_done else R.drawable.ic_book), null, Modifier.size(16.dp))
                                Text(stringResource(when {
                                    state.downloadsFailed -> R.string.player_download_check_failed
                                    !state.downloadsReady -> R.string.player_download_checking
                                    summary.complete -> R.string.player_download_ready
                                    else -> R.string.player_streaming
                                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val currentIndex = state.tracks.indexOfFirst { it.id == activeTrackId }
                            Text(
                                if (currentIndex >= 0) stringResource(R.string.player_current_track, currentIndex + 1, state.tracks.size)
                                else pluralStringResource(R.plurals.player_chapter_count, state.tracks.size, state.tracks.size),
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth(),
                            )
                            ProgressSlider(activeController, Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (activeController != null) {
                                    PositionText(activeController, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    DurationText(activeController, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(playerDurationLabel(state.progress?.positionMs ?: 0), style = MaterialTheme.typography.bodyMedium)
                                    Text(state.tracks.firstOrNull { it.id == activeTrackId }?.durationMs?.let(::playerDurationLabel) ?: stringResource(R.string.player_unknown_duration), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                PreviousButton(activeController, Modifier.size(48.dp))
                                SeekBackButton(activeController, Modifier.size(48.dp), contentDescription = {
                                    val seconds = (seekBackAmountMs / 1_000).toInt()
                                    pluralStringResource(androidx.media3.ui.compose.material3.R.plurals.seek_back_by_amount_button, seconds, seconds)
                                })
                                if (activeController != null) PlayPauseButton(activeController, Modifier.size(80.dp), iconSize = 40.dp, colors = IconButtonDefaults.filledIconButtonColors())
                                else FilledIconButton(onClick = onListen, enabled = connected && !state.actionBusy, modifier = Modifier.size(80.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                                    Icon(painterResource(R.drawable.ic_play), stringResource(R.string.player_listen), Modifier.size(40.dp))
                                }
                                SeekForwardButton(activeController, Modifier.size(48.dp), contentDescription = {
                                    val seconds = (seekForwardAmountMs / 1_000).toInt()
                                    pluralStringResource(androidx.media3.ui.compose.material3.R.plurals.seek_forward_by_amount_button, seconds, seconds)
                                })
                                NextButton(activeController, Modifier.size(48.dp))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                val speedLabel = stringResource(R.string.player_speed)
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PlaybackSpeedToggleButton(activeController, Modifier.heightIn(min = 48.dp).semantics { contentDescription = speedLabel })
                                    Text(speedLabel, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics {})
                                }
                                PlayerTool(R.drawable.ic_player_sleep, stringResource(R.string.player_sleep_timer), Modifier.weight(1f), enabled = activeController != null, onClick = { sheet = "sleep" })
                                PlayerTool(R.drawable.ic_tracks, stringResource(R.string.player_tracks), Modifier.weight(1f), enabled = state.tracks.isNotEmpty(), onClick = { sheet = "tracks" })
                                PlayerTool(if (state.downloadsReady && summary.complete) R.drawable.ic_download_done else R.drawable.ic_download,
                                    stringResource(if (state.downloadsReady && summary.complete) R.string.player_downloaded else R.string.player_downloads), Modifier.weight(1f), onClick = { sheet = "downloads" })
                            }
                            playback.sleepTimer.takeIf { it.bookId == book.id }?.let { timer ->
                                if (timer.endOfTrack || timer.remainingMs != null) Text(
                                    if (timer.endOfTrack) stringResource(R.string.player_timer_end_active)
                                    else stringResource(R.string.player_timer_remaining, playerDurationLabel(timer.remainingMs ?: 0)),
                                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            PlayerConnectionStatus(playback, onConnectionRetry)
                            if (playback.commandError != null) {
                                PlayerError(stringResource(R.string.player_command_failed))
                                TextButton(onClick = onDismissCommandError) { Text(stringResource(R.string.player_dismiss_error)) }
                            }
                            if (playback.activeBookId == book.id && playback.error != null) {
                                PlayerError(stringResource(R.string.player_playback_error))
                                TextButton(onClick = onPlaybackRetry, enabled = !state.actionBusy) { Text(stringResource(R.string.try_again)) }
                            }
                            if (playback.progressSaveFailed && playback.activeBookId == book.id) PlayerError(stringResource(R.string.player_position_error))
                            if (state.actionBusy) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                                Text(stringResource(when { state.removingBook -> R.string.player_removing_book; state.refreshingSources -> R.string.player_source_refreshing; else -> R.string.player_action_working }))
                                if (!state.removingBook) TextButton(onClick = onCancelAction) { Text(stringResource(R.string.cancel)) }
                            }
                            state.actionError?.let { PlayerError(stringResource(playerActionErrorString(it))) }
                        }
                    }
                }
            }
        }
    }
    if (book != null) {
        when (sheet) {
            "information" -> PlayerInformationSheet(state, hasSource, onOpenSource, onRefreshMetadata, onDismiss = { sheet = null })
            "downloads" -> PlayerDownloadsSheet(state, summary, onDownload, onPauseDownloads,
                onRemove = { confirmation = "downloads" }, onRetry = onDownloadRetry, onDismiss = { sheet = null })
            "tracks" -> PlayerTracksSheet(state, downloads, activeTrackId, connected && !state.actionBusy,
                onTrack = { sheet = null; onTrack(it) }, onDismiss = { sheet = null })
            "sleep" -> PlayerSleepSheet(playback.sleepTimer.takeIf { it.bookId == book.id }, activeController != null,
                onSleepTimer = { onSleepTimer(it); sheet = null }, onEndOfTrack = { onEndOfTrackTimer(); sheet = null },
                onCancel = { onCancelSleepTimer(); sheet = null }, onDismiss = { sheet = null })
        }
        if (confirmation != null) PlayerRemovalDialog(book.displayTitle, removeBook = confirmation == "book", enabled = !state.actionBusy,
            onDismiss = { confirmation = null }, onConfirm = {
                val removeBook = confirmation == "book"
                confirmation = null
                if (removeBook) { sheet = null; onRemoveBook() } else onRemoveDownloads()
            })
    }
}

@Composable
private fun PlayerTool(icon: Int, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(painterResource(icon), label) }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun PlayerConnectionStatus(playback: PlaybackState, onRetry: () -> Unit) {
    when (playback.connection) {
        PlaybackConnectionStatus.FAILED -> {
            PlayerError(stringResource(R.string.player_connection_failed))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.player_reconnect)) }
        }
        PlaybackConnectionStatus.DISCONNECTED, PlaybackConnectionStatus.CONNECTING -> Text(stringResource(R.string.player_connecting), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        PlaybackConnectionStatus.CONNECTED -> Unit
    }
}

@Composable
internal fun PlayerError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

internal fun playerActionErrorString(error: PlayerActionError): Int = when (error) {
    PlayerActionError.SOURCES -> R.string.player_source_refresh_failed
    PlayerActionError.PLAYBACK -> R.string.player_retry_failed
    PlayerActionError.DOWNLOAD -> R.string.player_download_action_failed
    PlayerActionError.REMOVE_BOOK -> R.string.player_remove_book_failed
}

/** Source-page actions accept only a stored, ordinary web URL, never arbitrary Android schemes. */
internal fun safePlayerSourceUrl(value: String?): String? = value?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }?.toASCIIString()
}
