package io.github.drolevar.audiotales.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.PlaybackConnection
import io.github.drolevar.audiotales.playback.PlaybackConnectionStatus
import io.github.drolevar.audiotales.ui.BookCover
import io.github.drolevar.audiotales.ui.player.MiniPlayerBar

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DownloadsRoute(
    connection: PlaybackConnection,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenMyBooks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DownloadsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by connection.state.collectAsStateWithLifecycle()
    val controller by connection.connectedController.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    DownloadsContent(
        state = state,
        onBack = onBack,
        onOpenBook = onOpenBook,
        onOpenMyBooks = onOpenMyBooks,
        onRetry = viewModel::retry,
        onPrimary = { item ->
            when (item.status) {
                DownloadBookStatus.COMPLETE -> {
                    if (playback.connection == PlaybackConnectionStatus.CONNECTED) {
                        connection.playBook(item.entry.book.id)
                        onOpenBook(item.entry.book.id)
                    }
                }
                DownloadBookStatus.DOWNLOADING, DownloadBookStatus.QUEUED -> viewModel.pause(item.entry.book.id)
                DownloadBookStatus.REMOVING -> Unit
                else -> viewModel.resume(item.entry.book.id)
            }
        },
        onRemove = viewModel::remove,
        playbackConnected = playback.connection == PlaybackConnectionStatus.CONNECTED,
        connectionFailed = playback.connection == PlaybackConnectionStatus.FAILED,
        onReconnect = connection::connect,
        modifier = modifier,
        activeBookId = playback.activeBookId,
        activePlaybackAction = { PlayPauseButton(controller) },
        bottomBar = {
            MiniPlayerBar(connection, onOpenBook, modifier = Modifier.navigationBarsPadding(), books = state.allBooks)
        },
    )
}

/** State-only screen boundary; playback controls and the persistent mini-player are route slots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenMyBooks: () -> Unit,
    onRetry: () -> Unit,
    onPrimary: (DownloadBookItem) -> Unit,
    onRemove: (String) -> Unit,
    playbackConnected: Boolean,
    connectionFailed: Boolean,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    activeBookId: String? = null,
    activePlaybackAction: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (connectionFailed) item {
                DownloadsMessage(stringResource(R.string.downloads_connection_failed)) {
                    TextButton(onClick = onReconnect) { Text(stringResource(R.string.downloads_reconnect)) }
                }
            }
            when {
                state.loading -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.downloads_loading))
                    }
                }
                state.failed -> item {
                    DownloadsMessage(stringResource(R.string.downloads_load_failed)) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                    }
                }
                state.books.isEmpty() -> item {
                    Column(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.downloads_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.downloads_empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenMyBooks, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.downloads_my_books))
                        }
                    }
                }
                else -> items(state.books, key = { it.entry.book.id }) { item ->
                    DownloadRow(
                        item = item,
                        busy = item.entry.book.id in state.busyBooks,
                        error = state.errors[item.entry.book.id],
                        onOpen = { onOpenBook(item.entry.book.id) },
                        onPrimary = { onPrimary(item) },
                        onDetails = { detailsId = item.entry.book.id },
                        onRemove = { removeId = item.entry.book.id },
                        playbackConnected = playbackConnected,
                        activePlaybackAction = activePlaybackAction.takeIf { activeBookId == item.entry.book.id },
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
    state.books.firstOrNull { it.entry.book.id == detailsId }?.let { item ->
        DownloadDetails(item, onDismiss = { detailsId = null })
    }
    state.books.firstOrNull { it.entry.book.id == removeId }?.let { item ->
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text(stringResource(R.string.downloads_remove_title)) },
            text = { Text(stringResource(R.string.downloads_remove_message, item.entry.book.title), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(
                    enabled = item.entry.book.id !in state.busyBooks && !item.summary.removing,
                    onClick = { removeId = null; onRemove(item.entry.book.id) },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeId = null }) { Text(stringResource(R.string.downloads_cancel)) }
            },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadBookItem,
    busy: Boolean,
    error: DownloadActionError?,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    playbackConnected: Boolean,
    activePlaybackAction: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val complete = item.status == DownloadBookStatus.COMPLETE
    val workingLabel = stringResource(R.string.downloads_working)
    Row(
        modifier = modifier.heightIn(min = 112.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.player_open),
                onClick = onOpen,
            ).padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BookCover(item.entry.book.coverUrl, Modifier.size(56.dp, 80.dp), referer = item.entry.book.coverReferer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.entry.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp,
                        letterSpacing = 0.sp, fontWeight = FontWeight.Medium))
                item.entry.book.author?.takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DownloadStatusText(item)
                if (!complete) {
                    Text(pluralStringResource(R.plurals.downloads_tracks_saved, item.summary.totalTracks,
                        item.summary.completedTracks, item.summary.totalTracks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.byteProgress?.takeUnless { item.summary.removing }?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let {
                    Text(stringResource(if (it == DownloadActionError.SOURCES) R.string.downloads_sources_failed
                        else R.string.downloads_request_failed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                busy -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = workingLabel },
                        strokeWidth = 2.dp)
                }
                complete && activePlaybackAction != null && playbackConnected -> activePlaybackAction()
                else -> IconButton(
                    onClick = onPrimary,
                    enabled = !item.summary.removing && (!complete || playbackConnected),
                ) {
                    val icon = when (item.status) {
                        DownloadBookStatus.COMPLETE -> R.drawable.ic_play
                        DownloadBookStatus.DOWNLOADING, DownloadBookStatus.QUEUED -> R.drawable.ic_pause
                        DownloadBookStatus.FAILED -> R.drawable.ic_refresh
                        else -> R.drawable.ic_download
                    }
                    val label = when (item.status) {
                        DownloadBookStatus.COMPLETE -> R.string.downloads_play
                        DownloadBookStatus.DOWNLOADING, DownloadBookStatus.QUEUED -> R.string.downloads_pause
                        DownloadBookStatus.FAILED -> R.string.downloads_retry
                        DownloadBookStatus.REMOVING -> R.string.downloads_removing
                        else -> R.string.downloads_resume
                    }
                    Icon(painterResource(icon), stringResource(label))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert),
                        stringResource(R.string.downloads_options, item.entry.book.title))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.downloads_details)) },
                        onClick = { menuOpen = false; onDetails() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.downloads_remove)) },
                        enabled = !busy && !item.summary.removing,
                        onClick = { menuOpen = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusText(item: DownloadBookItem) {
    var label = stringResource(when (item.status) {
        DownloadBookStatus.COMPLETE -> R.string.downloads_offline
        DownloadBookStatus.DOWNLOADING -> R.string.downloads_downloading
        DownloadBookStatus.QUEUED -> R.string.downloads_queued
        DownloadBookStatus.PAUSED -> R.string.downloads_paused
        DownloadBookStatus.FAILED -> R.string.downloads_failed
        DownloadBookStatus.REMOVING -> R.string.downloads_removing
        DownloadBookStatus.PARTIAL -> R.string.downloads_partial
    })
    if (item.status in listOf(DownloadBookStatus.DOWNLOADING, DownloadBookStatus.QUEUED, DownloadBookStatus.PAUSED)) {
        item.byteProgress?.let { label = stringResource(R.string.downloads_percent, label, (it * 100).toInt()) }
    }
    if (item.failedTracks > 0 && item.status != DownloadBookStatus.FAILED) {
        label = stringResource(R.string.downloads_status_failures, label,
            stringResource(R.string.downloads_failed_count, item.failedTracks))
    }
    Text(label, style = MaterialTheme.typography.bodySmall,
        color = if (item.status == DownloadBookStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DownloadsMessage(text: String, action: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        action()
    }
}

@Composable
private fun DownloadDetails(item: DownloadBookItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_details)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.entry.book.title, style = MaterialTheme.typography.titleMedium)
                item.entry.tracks.forEach { track ->
                    val state = item.tracks[track.id]
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(track.title, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(when (state?.status) {
                            DownloadStatus.COMPLETED -> R.string.downloads_offline
                            DownloadStatus.DOWNLOADING -> R.string.downloads_downloading
                            DownloadStatus.QUEUED -> R.string.downloads_queued
                            DownloadStatus.PAUSED -> R.string.downloads_paused
                            DownloadStatus.FAILED -> R.string.downloads_failed
                            DownloadStatus.REMOVING -> R.string.downloads_removing
                            null -> R.string.downloads_not_downloaded
                        }), style = MaterialTheme.typography.bodySmall)
                        if (state != null) {
                            val bytes = android.text.format.Formatter.formatShortFileSize(context, state.bytesDownloaded.coerceAtLeast(0))
                            Text(if (state.totalBytes > 0) stringResource(R.string.downloads_track_bytes, bytes,
                                android.text.format.Formatter.formatShortFileSize(context, state.totalBytes))
                            else stringResource(R.string.downloads_track_saved_bytes, bytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.downloads_close)) } },
    )
}
