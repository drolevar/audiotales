package io.github.drolevar.audiotales.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.data.library.commonReaderCredits
import io.github.drolevar.audiotales.data.library.readerCreditNames
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.playback.BookDownloadSummary
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.SleepTimerState
import io.github.drolevar.audiotales.playback.TrackDownloadState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_player_close), stringResource(R.string.player_sheet_close))
            }
        }
        content()
    }
}

@Composable
internal fun PlayerInformationSheet(
    state: BookPlayerUiState,
    hasSource: Boolean,
    onOpenSource: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(stringResource(R.string.player_information), onDismiss) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.book?.displayTitle.orEmpty(), style = MaterialTheme.typography.titleLarge)
            state.book?.author?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            readerCreditNames(commonReaderCredits(state.tracks))?.let { names ->
                Text(stringResource(R.string.reader_credit_read_by, names), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(pluralStringResource(R.plurals.player_chapter_count, state.tracks.size, state.tracks.size))
            state.book?.language?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(state.book?.description?.takeIf(String::isNotBlank) ?: stringResource(R.string.player_no_description), style = MaterialTheme.typography.bodyMedium)
            if (state.metadataRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.player_metadata_refreshing))
            }
            if (state.metadataFailed) {
                PlayerError(stringResource(R.string.player_metadata_failed))
                TextButton(onClick = onRefresh, enabled = !state.metadataRefreshing && !state.actionBusy) {
                    Text(stringResource(R.string.try_again))
                }
            }
            if (hasSource) TextButton(onClick = onOpenSource, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_player_open_source), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.player_open_source))
            }
        }
    }
}

@Composable
internal fun PlayerDownloadsSheet(
    state: BookPlayerUiState,
    summary: BookDownloadSummary,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(stringResource(R.string.player_downloads), onDismiss) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.book?.displayTitle.orEmpty(), style = MaterialTheme.typography.titleMedium)
            when {
                state.downloadsFailed -> {
                    PlayerError(stringResource(R.string.player_download_check_failed))
                    TextButton(onClick = onRetry, enabled = !state.actionBusy) { Text(stringResource(R.string.try_again)) }
                }
                !state.downloadsReady -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.player_download_checking))
                }
                else -> {
                    Text(pluralStringResource(R.plurals.player_download_status, summary.totalTracks, summary.completedTracks, summary.totalTracks))
                    if (summary.complete) Text(stringResource(R.string.player_download_ready))
                    if (summary.queuedTracks > 0) Text(pluralStringResource(R.plurals.player_download_queued, summary.queuedTracks, summary.queuedTracks))
                    if (summary.downloadingTracks > 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(pluralStringResource(R.plurals.player_download_running, summary.downloadingTracks, summary.downloadingTracks))
                    }
                    if (summary.pausedTracks > 0) Text(pluralStringResource(R.plurals.player_download_paused, summary.pausedTracks, summary.pausedTracks))
                    if (summary.hasFailures) PlayerError(stringResource(R.string.player_download_failed))
                    if (summary.removing) Text(stringResource(R.string.player_download_removing))
                    if (!summary.complete && !summary.active) Button(onClick = onDownload, enabled = !state.actionBusy && !summary.removing) {
                        Text(stringResource(when { summary.hasFailures -> R.string.player_retry_download; summary.hasDownloads -> R.string.player_resume_download; else -> R.string.player_download }))
                    }
                    if (summary.active) TextButton(onClick = onPause, enabled = !state.actionBusy) { Text(stringResource(R.string.player_pause_download)) }
                    if (summary.hasDownloads) TextButton(onClick = onRemove, enabled = !state.actionBusy && !summary.removing) { Text(stringResource(R.string.player_remove_download)) }
                }
            }
            if (state.actionBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (state.refreshingSources) R.string.player_source_refreshing else R.string.player_action_working))
            }
            state.actionError?.let { PlayerError(stringResource(playerActionErrorString(it))) }
        }
    }
}

@Composable
internal fun PlayerTracksSheet(
    state: BookPlayerUiState,
    downloads: Map<String, TrackDownloadState>,
    currentTrackId: String?,
    enabled: Boolean,
    onTrack: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(stringResource(R.string.player_tracks), onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                val current = track.id == currentTrackId
                ListItem(
                    headlineContent = { Text(track.title) },
                    supportingContent = {
                        val details = listOfNotNull(
                            if (current) stringResource(R.string.player_current) else null,
                            track.durationMs?.takeIf { it > 0 }?.let(::playerDurationLabel),
                            if (state.downloadsReady) downloads[track.id]?.status?.let { stringResource(playerTrackDownloadString(it)) } else null,
                        )
                        Column {
                            if (details.isNotEmpty()) Text(details.joinToString(" · "))
                            readerCreditNames(track.readerCredits)?.let { Text(stringResource(R.string.reader_credit_read_by, it)) }
                        }
                    },
                    leadingContent = { Text((index + 1).toString()) },
                    trailingContent = { Icon(painterResource(if (current) R.drawable.ic_check else R.drawable.ic_play), null) },
                    colors = ListItemDefaults.colors(containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().semantics { selected = current }
                        .clickable(enabled = enabled, role = Role.Button) { onTrack(track.id) },
                )
            }
        }
    }
}

@Composable
internal fun PlayerSleepSheet(
    timer: SleepTimerState?,
    enabled: Boolean,
    onSleepTimer: (Long) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(stringResource(R.string.player_sleep_timer), onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                when {
                    timer?.endOfTrack == true -> stringResource(R.string.player_timer_end_active)
                    timer?.remainingMs != null -> stringResource(R.string.player_timer_remaining, playerDurationLabel(timer.remainingMs))
                    else -> stringResource(R.string.player_timer_off)
                }, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
            )
            listOf(15, 30, 45, 60).forEach { minutes ->
                ListItem(headlineContent = { Text(pluralStringResource(R.plurals.player_timer_minutes, minutes, minutes)) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { onSleepTimer(minutes * 60_000L) })
            }
            ListItem(headlineContent = { Text(stringResource(R.string.player_timer_end)) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onEndOfTrack))
            if (timer?.endOfTrack == true || timer?.remainingMs != null) TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.player_timer_cancel))
            }
        }
    }
}

@Composable
internal fun PlayerRemovalDialog(title: String, removeBook: Boolean, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (removeBook) R.string.player_remove_book_title else R.string.player_remove_downloads_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (removeBook) R.string.player_remove_book_description else R.string.player_remove_downloads_description))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun playerTrackDownloadString(status: DownloadStatus?): Int = when (status) {
    DownloadStatus.COMPLETED -> R.string.player_download_ready
    DownloadStatus.QUEUED -> R.string.player_track_queued
    DownloadStatus.DOWNLOADING -> R.string.player_track_downloading
    DownloadStatus.PAUSED -> R.string.player_track_paused
    DownloadStatus.FAILED -> R.string.player_track_failed
    DownloadStatus.REMOVING -> R.string.player_download_removing
    null -> R.string.player_track_not_downloaded
}

internal fun playerDurationLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
