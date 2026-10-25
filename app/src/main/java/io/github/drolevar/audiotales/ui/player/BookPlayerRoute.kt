package io.github.drolevar.audiotales.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedToggleButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.ui.BookCover
import io.github.drolevar.audiotales.playback.BookDownloadSummary
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.PlaybackConnection
import io.github.drolevar.audiotales.playback.PlaybackConnectionStatus
import io.github.drolevar.audiotales.playback.PlaybackState
import io.github.drolevar.audiotales.playback.TrackDownloadState
import io.github.drolevar.audiotales.playback.summarizeBookDownloads

/** Phone UI only controls the MediaController; PlaybackService remains the sole player owner. */
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
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    BookPlayerContent(
        state = state,
        playback = playback,
        controller = if (playback.activeBookId == bookId) controller else null,
        downloads = downloads,
        onBack = onBack,
        onLoadRetry = { viewModel.load(bookId) },
        onListen = { connection.playBook(bookId) },
        onConnectionRetry = connection::connect,
        onPlaybackRetry = { viewModel.retryPlayback(connection::retryBook) },
        onCancelAction = viewModel::cancelAction,
        onTrack = connection::playTrack,
        onDownload = viewModel::downloadBook,
        onPauseDownloads = viewModel::pauseDownloads,
        onRemoveDownloads = viewModel::removeDownloads,
        modifier = modifier,
    )
}

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
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.player_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.failed || state.book == null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.player_book_unavailable))
                    Button(onClick = onLoadRetry) { Text(stringResource(R.string.try_again)) }
                }
                else -> {
                    val book = state.book
                    val summary = summarizeBookDownloads(state.tracks.map { it.id }, downloads)
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                BookCover(
                                    url = book.coverUrl,
                                    referer = book.coverReferer,
                                    modifier = Modifier.widthIn(max = 345.dp).fillMaxWidth().aspectRatio(345f / 336f),
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    placeholderSize = 64.dp,
                                )
                                Text(
                                    book.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.semantics { heading() },
                                )
                                book.author?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                                }
                                Text(
                                    stringResource(if (summary.complete) R.string.player_download_ready else R.string.player_streaming),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                when (playback.connection) {
                                    PlaybackConnectionStatus.FAILED -> {
                                        Text(stringResource(R.string.player_connection_failed))
                                        Button(onClick = onConnectionRetry) { Text(stringResource(R.string.try_again)) }
                                    }
                                    PlaybackConnectionStatus.DISCONNECTED, PlaybackConnectionStatus.CONNECTING -> {
                                        Text(stringResource(R.string.player_connecting))
                                    }
                                    PlaybackConnectionStatus.CONNECTED -> {
                                        if (controller == null) {
                                            Button(onClick = onListen, enabled = !state.actionBusy) { Text(stringResource(R.string.player_listen)) }
                                        } else {
                                            Text(playback.chapterTitle.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                            ProgressSlider(controller, modifier = Modifier.fillMaxWidth())
                                            PositionAndDurationText(controller)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceEvenly,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                PreviousButton(controller)
                                                SeekBackButton(
                                                    controller,
                                                    contentDescription = {
                                                        val seconds = (seekBackAmountMs / 1_000).toInt()
                                                        pluralStringResource(
                                                            androidx.media3.ui.compose.material3.R.plurals.seek_back_by_amount_button,
                                                            seconds,
                                                            seconds,
                                                        )
                                                    },
                                                )
                                                PlayPauseButton(
                                                    controller,
                                                    modifier = Modifier.size(72.dp),
                                                    iconSize = 40.dp,
                                                    colors = IconButtonDefaults.filledIconButtonColors(),
                                                )
                                                SeekForwardButton(
                                                    controller,
                                                    contentDescription = {
                                                        val seconds = (seekForwardAmountMs / 1_000).toInt()
                                                        pluralStringResource(
                                                            androidx.media3.ui.compose.material3.R.plurals.seek_forward_by_amount_button,
                                                            seconds,
                                                            seconds,
                                                        )
                                                    },
                                                )
                                                NextButton(controller)
                                            }
                                            PlaybackSpeedToggleButton(controller)
                                            if (playback.error != null) {
                                                PlayerError(stringResource(R.string.player_playback_error))
                                                TextButton(onClick = onPlaybackRetry, enabled = !state.actionBusy) { Text(stringResource(R.string.try_again)) }
                                            }
                                        }
                                    }
                                }
                                if (playback.progressSaveFailed) PlayerError(stringResource(R.string.player_position_error))
                                if (state.actionBusy) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Text(
                                            stringResource(if (state.refreshingSources) R.string.player_source_refreshing else R.string.player_action_working),
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = onCancelAction) { Text(stringResource(R.string.cancel)) }
                                    }
                                }
                                state.actionError?.let { error ->
                                    PlayerError(stringResource(when (error) {
                                        PlayerActionError.SOURCES -> R.string.player_source_refresh_failed
                                        PlayerActionError.PLAYBACK -> R.string.player_retry_failed
                                        PlayerActionError.DOWNLOAD -> R.string.player_download_action_failed
                                    }))
                                }
                                HorizontalDivider()
                                DownloadControls(summary, state, onDownload, onPauseDownloads, onRemoveDownloads)
                            }
                        }
                        item {
                            Text(
                                pluralStringResource(R.plurals.player_chapter_count, state.tracks.size, state.tracks.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                    .semantics { heading() },
                            )
                        }
                        items(state.tracks, key = { it.id }) { track ->
                            ListItem(
                                headlineContent = { Text(track.title) },
                                supportingContent = if (downloads[track.id]?.status == DownloadStatus.COMPLETED) {
                                    { Text(stringResource(R.string.player_download_ready)) }
                                } else null,
                                modifier = Modifier.clickable(
                                    enabled = playback.connection == PlaybackConnectionStatus.CONNECTED && !state.actionBusy,
                                    role = Role.Button,
                                    onClick = { onTrack(track.id) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadControls(
    summary: BookDownloadSummary,
    state: BookPlayerUiState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    if (!state.downloadsReady) {
        Text(stringResource(R.string.player_download_checking))
        return
    }
    Text(pluralStringResource(R.plurals.player_download_status, summary.totalTracks, summary.completedTracks, summary.totalTracks))
    if (summary.queuedTracks > 0) Text(pluralStringResource(R.plurals.player_download_queued, summary.queuedTracks, summary.queuedTracks))
    if (summary.downloadingTracks > 0) Text(pluralStringResource(R.plurals.player_download_running, summary.downloadingTracks, summary.downloadingTracks))
    if (summary.pausedTracks > 0) Text(pluralStringResource(R.plurals.player_download_paused, summary.pausedTracks, summary.pausedTracks))
    if (summary.hasFailures) PlayerError(stringResource(R.string.player_download_failed))
    if (summary.removing) Text(stringResource(R.string.player_download_removing))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!summary.complete) {
            Button(onClick = onDownload, enabled = !state.actionBusy && !summary.removing && !summary.active) {
                Text(stringResource(when {
                    summary.hasFailures -> R.string.player_retry_download
                    summary.hasDownloads -> R.string.player_resume_download
                    else -> R.string.player_download
                }))
            }
        }
        if (summary.active) {
            TextButton(onClick = onPause, enabled = !state.actionBusy) { Text(stringResource(R.string.player_pause_download)) }
        }
        if (summary.hasDownloads) {
            TextButton(onClick = onRemove, enabled = !state.actionBusy && !summary.removing) {
                Text(stringResource(R.string.player_remove_download))
            }
        }
    }
}

@Composable
private fun PlayerError(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
