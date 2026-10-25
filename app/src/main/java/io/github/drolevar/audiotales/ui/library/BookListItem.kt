package io.github.drolevar.audiotales.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.ui.BookCover
import java.util.Locale

@Composable
internal fun BookListItem(
    row: CollectionRow,
    busy: Boolean,
    onOpenBook: () -> Unit,
    onBookDetails: () -> Unit,
    onDownloadAction: () -> Unit,
    onOpenDownloads: () -> Unit,
    onRemoveDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = row.entry.book
    var menuExpanded by rememberSaveable(book.id) { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable(book.id) { mutableStateOf(false) }
    val action = row.downloadAction
    val actionEnabled = !busy && !row.download.removing && row.entry.tracks.isNotEmpty()
    val actionLabel = downloadActionLabel(action, book.title)
    val actionClick = if (action == CollectionDownloadAction.VIEW) onOpenDownloads else onDownloadAction
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 112.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenBook)
                .padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BookCover(book.coverUrl, Modifier.size(width = 56.dp, height = 80.dp), book.coverReferer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    collectionStatusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column {
            IconButton(onClick = actionClick, enabled = actionEnabled, modifier = Modifier.size(48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(
                    painterResource(when (action) {
                        CollectionDownloadAction.PAUSE -> R.drawable.ic_pause
                        CollectionDownloadAction.VIEW -> R.drawable.ic_download_done
                        CollectionDownloadAction.RETRY -> R.drawable.ic_refresh
                        else -> R.drawable.ic_download
                    }),
                    contentDescription = actionLabel,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.collection_book_actions, book.title))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.book_details_title)) }, onClick = { menuExpanded = false; onBookDetails() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.collection_open_player)) }, onClick = { menuExpanded = false; onOpenBook() })
                    if (!row.download.complete) DropdownMenuItem(
                        text = { Text(downloadMenuLabel(action)) },
                        enabled = actionEnabled,
                        onClick = { menuExpanded = false; onDownloadAction() },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.collection_view_downloads)) }, onClick = { menuExpanded = false; onOpenDownloads() })
                    if (row.download.hasDownloads) DropdownMenuItem(
                        text = { Text(stringResource(R.string.collection_remove_downloads)) },
                        enabled = !busy && !row.download.removing,
                        onClick = { menuExpanded = false; confirmRemoval = true },
                    )
                }
            }
        }
    }
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        title = { Text(stringResource(R.string.collection_remove_title)) },
        text = { Text(stringResource(R.string.collection_remove_description, book.title), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmRemoval = false; onRemoveDownloads() }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text(stringResource(R.string.collection_cancel)) } },
    )
}

@Composable
private fun downloadActionLabel(action: CollectionDownloadAction, title: String): String = when (action) {
    CollectionDownloadAction.DOWNLOAD -> stringResource(R.string.collection_download_book, title)
    CollectionDownloadAction.PAUSE -> stringResource(R.string.collection_pause_book, title)
    CollectionDownloadAction.RESUME -> stringResource(R.string.collection_resume_book, title)
    CollectionDownloadAction.RETRY -> stringResource(R.string.collection_retry_book, title)
    CollectionDownloadAction.VIEW -> stringResource(R.string.collection_view_downloads)
}

@Composable
private fun downloadMenuLabel(action: CollectionDownloadAction): String = stringResource(when (action) {
    CollectionDownloadAction.DOWNLOAD -> R.string.collection_download_action
    CollectionDownloadAction.PAUSE -> R.string.collection_pause_action
    CollectionDownloadAction.RESUME -> R.string.collection_resume_action
    CollectionDownloadAction.RETRY -> R.string.collection_retry_action
    CollectionDownloadAction.VIEW -> R.string.collection_view_downloads
})

@Composable
private fun collectionStatusText(row: CollectionRow): String {
    val listening = row.listening
    val progress = when {
        listening.complete -> stringResource(R.string.collection_finished)
        !listening.started -> stringResource(R.string.collection_not_started)
        listening.percentage != null -> stringResource(R.string.collection_percentage, listening.percentage)
        else -> stringResource(R.string.collection_track_position, listening.trackNumber, listening.trackCount, collectionPosition(listening.positionMs))
    }
    val download = row.download
    val downloaded = when {
        download.removing -> stringResource(R.string.collection_removing)
        download.complete -> stringResource(R.string.collection_downloaded)
        download.active -> stringResource(R.string.collection_downloading, download.completedTracks, download.totalTracks)
        download.hasFailures -> stringResource(R.string.collection_download_failed, download.completedTracks, download.totalTracks)
        download.pausedTracks > 0 -> stringResource(R.string.collection_paused, download.completedTracks, download.totalTracks)
        download.hasDownloads -> stringResource(R.string.collection_partial, download.completedTracks, download.totalTracks)
        else -> null
    }
    return downloaded?.let { stringResource(R.string.collection_status_pair, progress, it) } ?: progress
}

private fun collectionPosition(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
