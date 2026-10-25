package io.github.drolevar.audiotales.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.R

@Composable
internal fun LibraryCollectionContent(
    state: LibraryUiState.Content,
    onFilterChange: (CollectionFilter) -> Unit,
    onSortChange: (CollectionSort) -> Unit,
    onOpenBook: (String) -> Unit,
    onDownloadAction: (String) -> Unit,
    onRemoveDownloads: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onDismissActionError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.widthIn(max = 720.dp).fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CollectionFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(collectionFilterLabel(filter)) },
                    leadingIcon = if (state.filter == filter) {
                        { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                pluralStringResource(R.plurals.library_book_count, state.rows.size, state.rows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                val sortLabel = collectionSortLabel(state.sort)
                val sortDescription = stringResource(R.string.collection_sort, sortLabel)
                TextButton(
                    onClick = { sortExpanded = true },
                    modifier = Modifier.semantics { contentDescription = sortDescription },
                ) {
                    Text(sortLabel, color = MaterialTheme.colorScheme.onSurface)
                    Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.padding(start = 16.dp).size(24.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    CollectionSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(collectionSortLabel(sort)) },
                            leadingIcon = if (state.sort == sort) {
                                { Icon(painterResource(R.drawable.ic_check), null) }
                            } else null,
                            onClick = { sortExpanded = false; onSortChange(sort) },
                        )
                    }
                }
            }
        }
        if (state.actionFailed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.collection_action_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissActionError) { Text(stringResource(R.string.collection_dismiss)) }
            }
        }
        if (state.rows.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.collection_no_matches), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.collection_no_matches_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.rows, key = { it.entry.book.id }) { row ->
                val id = row.entry.book.id
                BookListItem(
                    row = row,
                    busy = id in state.busyBookIds,
                    onOpenBook = { onOpenBook(id) },
                    onDownloadAction = { onDownloadAction(id) },
                    onOpenDownloads = onOpenDownloads,
                    onRemoveDownloads = { onRemoveDownloads(id) },
                )
            }
        }
    }
}

@Composable
private fun collectionFilterLabel(filter: CollectionFilter): String = stringResource(when (filter) {
    CollectionFilter.ALL -> R.string.collection_all
    CollectionFilter.IN_PROGRESS -> R.string.collection_in_progress
    CollectionFilter.DOWNLOADED -> R.string.collection_downloaded
})

@Composable
private fun collectionSortLabel(sort: CollectionSort): String = stringResource(when (sort) {
    CollectionSort.RECENT -> R.string.collection_recent
    CollectionSort.TITLE -> R.string.collection_title
    CollectionSort.AUTHOR -> R.string.collection_author
})
