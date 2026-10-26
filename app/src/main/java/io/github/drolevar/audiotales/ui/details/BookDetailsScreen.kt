package io.github.drolevar.audiotales.ui.details

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.PlaybackConnection
import io.github.drolevar.audiotales.playback.PlaybackConnectionStatus
import java.net.URI
import io.github.drolevar.audiotales.ui.BookCover
import java.util.Locale

@Composable
internal fun BookDetailsRoute(
    target: BookDetailsTarget,
    connection: PlaybackConnection,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
    onDownloads: () -> Unit,
    viewModel: BookDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller by connection.connectedController.collectAsStateWithLifecycle()
    val playback by connection.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleStartEffect(viewModel, target) {
        viewModel.activate(target)
        onStopOrDispose { viewModel.deactivate() }
    }
    val play: (String, String?) -> Boolean = { bookId, trackId ->
        if (connection.connectedController.value == null) false else {
            if (trackId == null) connection.playBook(bookId) else connection.playTrack(trackId)
            true
        }
    }
    BookDetailsScreen(
        state = state, playbackAvailable = controller != null,
        onBack = { viewModel.deactivate(); onBack() }, onRetry = viewModel::retry, onRefresh = viewModel::refreshDetails,
        onRetryAudio = viewModel::retryAudio,
        playbackFailed = playback.connection == PlaybackConnectionStatus.FAILED,
        onRetryPlayback = connection::connect,
        onSave = viewModel::save,
        onListen = { viewModel.listen(play = play, onOpened = onPlayer) },
        onTrack = { viewModel.listen(it, play, onPlayer) },
        onDownload = { viewModel.downloadAction(onDownloads) },
        onRemoveDownloads = viewModel::removeDownloads,
        onDismissError = viewModel::dismissError,
        onShare = {
            state.book?.let { book ->
                book.sourceUrl?.let { url ->
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, listOfNotNull(book.displayTitle, book.author?.takeIf(String::isNotBlank), url).joinToString("\n"))
                    }, null))
                }
            }
        },
    )
}

/** Stateless app boundary; only transient menu/sheet visibility belongs to this composition. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BookDetailsScreen(
    state: BookDetailsUiState,
    playbackAvailable: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onListen: () -> Unit,
    onTrack: (Int) -> Unit,
    onDownload: () -> Unit,
    onRemoveDownloads: () -> Unit,
    onDismissError: () -> Unit,
    onShare: () -> Unit,
    onRetryAudio: () -> Unit,
    modifier: Modifier = Modifier,
    playbackFailed: Boolean = false,
    onRetryPlayback: () -> Unit = {},
) {
    var menu by rememberSaveable(state.book?.id) { mutableStateOf(false) }
    var tracksVisible by rememberSaveable(state.book?.id) { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable(state.book?.id) { mutableStateOf(false) }
    val book = state.book
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_details_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.book_details_up)) } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.book_details_options)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (!state.saved && book != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_details_save)) }, enabled = !state.busy && state.audioReady,
                                onClick = { menu = false; onSave() },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.book_details_refresh)) }, enabled = !state.refreshing && !state.busy, onClick = { menu = false; if (book == null) onRetry() else onRefresh() })
                            if (book?.sourceUrl != null) DropdownMenuItem(text = { Text(stringResource(R.string.book_details_share)) }, onClick = { menu = false; onShare() })
                            if (state.saved && state.download.hasDownloads) DropdownMenuItem(
                                text = { Text(stringResource(R.string.collection_remove_downloads)) }, enabled = !state.busy && !state.download.removing,
                                onClick = { menu = false; confirmRemoval = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            if (book == null) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading) CircularProgressIndicator()
                Text(stringResource(if (state.loadFailed) R.string.book_details_load_failed else R.string.book_details_loading), style = MaterialTheme.typography.bodyLarge)
                if (state.loadFailed) Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
            } else Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    BookCover(book.coverUrl, Modifier.size(80.dp, 112.dp), book.coverReferer)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.displayTitle, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp))
                        book.author?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        (state.sourceName ?: book.sourceUrl?.let { runCatching { URI(it).host }.getOrNull() })?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(stringResource(if (state.saved) R.string.book_details_saved else R.string.book_details_unsaved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        book.language?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onListen, enabled = !state.busy && state.audioReady && playbackAvailable, modifier = Modifier.weight(1f).widthIn(min = 144.dp * LocalDensity.current.fontScale).heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.progress != null) R.string.book_details_resume else R.string.book_details_listen))
                    }
                    OutlinedButton(onClick = onDownload, enabled = !state.busy && state.audioReady && !state.download.removing && (state.downloadsReady || state.downloadsFailed), modifier = Modifier.weight(1f).widthIn(min = 144.dp * LocalDensity.current.fontScale).heightIn(min = 48.dp)) {
                        Icon(painterResource(when { state.download.complete -> R.drawable.ic_download_done; state.download.active -> R.drawable.ic_pause; state.download.hasFailures -> R.drawable.ic_refresh; else -> R.drawable.ic_download }), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(when {
                            !state.downloadsReady && state.downloadsFailed -> R.string.collection_retry_action
                            !state.downloadsReady -> R.string.book_details_checking_downloads
                            state.download.complete -> R.string.collection_downloaded
                            state.download.active -> R.string.collection_pause_action
                            state.download.hasFailures -> R.string.collection_retry_action
                            state.download.hasDownloads -> R.string.collection_resume_action
                            else -> R.string.collection_download_action
                        }))
                    }
                }
                if (!state.saved) Text(stringResource(R.string.book_details_actions_save), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!playbackAvailable) {
                    Text(stringResource(if (playbackFailed) R.string.book_details_connection_failed else R.string.book_details_connecting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (playbackFailed) TextButton(onClick = onRetryPlayback) { Text(stringResource(R.string.try_again)) }
                }
                if (state.busy || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.download.hasDownloads) Text(
                    pluralStringResource(if (state.download.complete) R.plurals.book_details_offline else R.plurals.book_details_download_count, state.download.totalTracks, state.download.completedTracks, state.download.totalTracks),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.actionError?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(when (error) { DetailsActionError.SAVE -> R.string.book_details_save_failed; DetailsActionError.PLAYBACK -> R.string.book_details_play_failed; DetailsActionError.DOWNLOAD -> R.string.book_details_download_failed }), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.collection_dismiss)) }
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.book_details_tracks), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp)) },
                    supportingContent = {
                        when {
                            state.audioReady -> Text(pluralStringResource(R.plurals.book_details_track_count, state.tracks.size, state.tracks.size))
                            state.audioLoading -> Text(stringResource(R.string.book_details_audio_loading))
                            state.audioFailed -> Text(stringResource(R.string.book_details_audio_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            else -> Text(stringResource(R.string.book_details_audio_unresolved))
                        }
                    },
                    leadingContent = { Icon(painterResource(R.drawable.ic_tracks), null) },
                    trailingContent = { if (state.audioReady) Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.graphicsLayer { rotationZ = -90f }) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = state.audioReady, role = Role.Button) { tracksVisible = true },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (state.audioLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.audioFailed) TextButton(onClick = onRetryAudio, enabled = !state.busy) { Text(stringResource(R.string.book_details_retry_audio)) }
                HorizontalDivider()
                Text(stringResource(R.string.book_details_description), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                Text(book.description?.takeIf(String::isNotBlank) ?: stringResource(R.string.book_details_no_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.collectionFailed) {
                    Text(stringResource(R.string.book_details_saved_refresh_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                }
                if (state.metadataFailed) {
                    Text(stringResource(R.string.book_details_refresh_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh, enabled = !state.refreshing && !state.busy) { Text(stringResource(R.string.try_again)) }
                }
            }
        }
    }
    if (tracksVisible && state.audioReady) ModalBottomSheet(onDismissRequest = { tracksVisible = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.book_details_tracks), Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (!state.saved) Text(stringResource(R.string.book_details_track_saves), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                val status = state.downloads[track.id]?.status
                ListItem(
                    headlineContent = { Text(track.title) },
                    supportingContent = {
                        val details = listOfNotNull(
                            track.durationMs?.takeIf { it > 0 }?.let(::durationLabel),
                            state.progress?.takeIf { it.trackId == track.id }?.let { stringResource(R.string.book_details_current_position, durationLabel(it.positionMs)) },
                            status?.let { stringResource(when (it) { DownloadStatus.COMPLETED -> R.string.collection_downloaded; DownloadStatus.PAUSED -> R.string.book_details_paused; DownloadStatus.FAILED -> R.string.book_details_download_status_failed; DownloadStatus.REMOVING -> R.string.collection_removing; DownloadStatus.QUEUED -> R.string.book_details_queued; DownloadStatus.DOWNLOADING -> R.string.book_details_downloading }) },
                        )
                        if (details.isNotEmpty()) Text(details.joinToString(" · "))
                    },
                    leadingContent = { Text((index + 1).toString()) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_play), null) },
                    modifier = Modifier.clickable(enabled = !state.busy && playbackAvailable, role = Role.Button) { tracksVisible = false; onTrack(index) },
                )
            }
        }
    }
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false }, title = { Text(stringResource(R.string.collection_remove_title)) },
        text = { Text(stringResource(R.string.collection_remove_description, book?.displayTitle.orEmpty()), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmRemoval = false; onRemoveDownloads() }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
private fun durationLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
