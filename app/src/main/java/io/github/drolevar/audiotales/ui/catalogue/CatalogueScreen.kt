package io.github.drolevar.audiotales.ui.catalogue

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.ui.BookCover
import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.OnlineBookError

/** Pure screen content: repositories, jobs and saving are owned by CatalogueViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogueScreen(
    state: CatalogueUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLibrarySelected: (String) -> Unit,
    onAddLibrary: () -> Unit,
    onRetryLibraries: () -> Unit,
    onLoadMore: () -> Unit,
    onInspect: (CatalogueBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingLibrary by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val source = state.libraries.firstOrNull { it.id == state.selectedLibraryId }
    val listState = rememberSaveable(state.selectedLibraryId, state.submittedQuery, saver = LazyListState.Saver) {
        LazyListState()
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.catalogue_browse)) },
                actions = {
                    IconButton(onClick = onAddLibrary) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.catalogue_add_library))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (source != null) {
                item(key = "search") {
                    Column(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(shape = SearchBarDefaults.inputFieldShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            SearchBarDefaults.InputField(
                                query = state.query,
                                onQueryChange = onQueryChanged,
                                onSearch = { focusManager.clearFocus(); onSearch() },
                                expanded = false,
                                onExpandedChange = {},
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.catalogue_search_hint)) },
                                trailingIcon = {
                                    TextButton(onClick = { focusManager.clearFocus(); onSearch() }) {
                                        Text(stringResource(R.string.catalogue_search))
                                    }
                                },
                            )
                        }
                        OutlinedButton(onClick = { choosingLibrary = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(source.displayName)
                        }
                        if (state.submittedQuery.isNotBlank()) {
                            Text(stringResource(R.string.catalogue_results_for, state.submittedQuery), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            pluralStringResource(R.plurals.catalogue_shown, state.books.size, state.books.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (state.librariesError) item(key = "libraries_error") {
                    CatalogueMessage(stringResource(R.string.catalogue_library_error), action = {
                        OutlinedButton(onClick = onRetryLibraries) { Text(stringResource(R.string.try_again)) }
                    })
                }
                items(state.books, key = { "${it.libraryId}:${it.remoteId}" }) { book ->
                    ListItem(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().clickable { onInspect(book) },
                        headlineContent = { Text(book.title) },
                        supportingContent = book.author?.takeIf(String::isNotBlank)?.let { { Text(it) } },
                        leadingContent = { BookCover(book.coverUrl, Modifier.size(width = 56.dp, height = 80.dp), referer = book.coverReferer) },
                    )
                }
                if (state.loading) item(key = "loading") { CatalogueLoading() }
                state.error?.let { error ->
                    item(key = "error") {
                        CatalogueMessage(
                            text = stringResource(
                                if (error == OnlineBookError.INVALID_LINK) R.string.catalogue_query_error
                                else error.catalogueMessageResource,
                            ),
                            action = {
                                Button(onClick = if (state.books.isEmpty()) onSearch else onLoadMore) {
                                    Text(stringResource(R.string.try_again))
                                }
                            },
                        )
                    }
                }
                if (!state.loading && state.error == null && state.books.isEmpty()) {
                    item(key = "empty") {
                        CatalogueMessage(
                            text = stringResource(R.string.catalogue_empty),
                            detail = stringResource(R.string.catalogue_empty_help),
                        )
                    }
                }
                if (!state.loading && state.error == null && state.nextOffset != null) {
                    item(key = "more") {
                        OutlinedButton(onClick = onLoadMore, modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.catalogue_load_more))
                        }
                    }
                }
            } else when {
                state.librariesLoading -> item { CatalogueLoading() }
                state.librariesError -> item {
                    CatalogueMessage(stringResource(R.string.catalogue_library_error), action = {
                        Button(onClick = onRetryLibraries) { Text(stringResource(R.string.try_again)) }
                    })
                }
                else -> item {
                    CatalogueMessage(
                        text = stringResource(R.string.catalogue_no_libraries),
                        detail = stringResource(R.string.catalogue_no_libraries_help),
                        action = { Button(onClick = onAddLibrary) { Text(stringResource(R.string.catalogue_add_library)) } },
                    )
                }
            }
        }
    }
    if (choosingLibrary) {
        AlertDialog(
            onDismissRequest = { choosingLibrary = false },
            title = { Text(stringResource(R.string.catalogue_choose_library)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.libraries.forEach { library ->
                        ListItem(
                            headlineContent = { Text(library.displayName) },
                            supportingContent = { Text(library.baseUrl) },
                            leadingContent = { RadioButton(selected = library.id == source?.id, onClick = null) },
                            modifier = Modifier.selectable(
                                selected = library.id == source?.id,
                                role = Role.RadioButton,
                                onClick = {
                                    onLibrarySelected(library.id)
                                    choosingLibrary = false
                                },
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingLibrary = false; onAddLibrary() }) {
                    Text(stringResource(R.string.catalogue_add_library))
                }
            },
            dismissButton = { TextButton(onClick = { choosingLibrary = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun AddLibraryDialog(
    state: AddLibraryUiState,
    onUrlChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.catalogue_add_library)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.catalogue_add_library_help))
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChanged,
                    enabled = !state.adding,
                    label = { Text(stringResource(R.string.catalogue_base_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.error != null,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!state.adding && state.url.isNotBlank()) onSubmit() }),
                )
                if (state.adding) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.catalogue_checking))
                }
                state.error?.let { error ->
                    Text(
                        stringResource(error.catalogueMessageResource),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !state.adding && state.url.isNotBlank()) {
                Text(stringResource(R.string.catalogue_add_library))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun CatalogueDetailsDialog(
    state: CatalogueUiState,
    onSave: () -> Unit,
    onOpenBook: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = state.details ?: return
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.catalogue_details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BookCover(book.coverUrl, Modifier.size(width = 80.dp, height = 112.dp), referer = book.coverReferer)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge)
                        book.author?.takeIf(String::isNotBlank)?.let { Text(it) }
                        state.libraries.firstOrNull { it.id == book.libraryId }?.let {
                            Text(it.displayName, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (state.saved != null) Text(stringResource(R.string.catalogue_saved), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.catalogue_saving))
                }
                state.saveError?.let { CatalogueError(it) }
                HorizontalDivider()
                Text(stringResource(R.string.catalogue_description), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(book.description.ifBlank { stringResource(R.string.catalogue_no_description) })
            }
        },
        confirmButton = {
            val saved = state.saved
            if (saved != null) {
                TextButton(onClick = { onDismiss(); onOpenBook(saved.book.id) }) { Text(stringResource(R.string.catalogue_open_saved)) }
            } else {
                TextButton(onClick = onSave, enabled = !state.saving) { Text(stringResource(R.string.catalogue_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CatalogueLoading() {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.catalogue_loading))
    }
}

@Composable
private fun CatalogueMessage(text: String, detail: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        action?.invoke()
    }
}

@Composable
private fun CatalogueError(error: OnlineBookError) {
    Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

internal val OnlineBookError.messageResource: Int
    @StringRes get() = when (this) {
        OnlineBookError.INVALID_LINK -> R.string.catalogue_invalid_link
        OnlineBookError.NETWORK -> R.string.online_book_network_error
        OnlineBookError.PROVIDER_RESPONSE -> R.string.online_book_response_error
        OnlineBookError.UNEXPECTED_FORMAT -> R.string.online_book_format_error
        OnlineBookError.EMPTY_PLAYLIST -> R.string.online_book_empty_error
        OnlineBookError.UNSUPPORTED_SOURCE -> R.string.online_book_unsupported_error
        OnlineBookError.RESPONSE_TOO_LARGE -> R.string.online_book_size_error
        OnlineBookError.SAVE_FAILED -> R.string.online_book_save_error
        OnlineBookError.ACCESS_NOT_CONFIGURED -> R.string.catalogue_access_error
        OnlineBookError.UNSUPPORTED_LIBRARY -> R.string.catalogue_unsupported_library
    }

private val OnlineBookError.catalogueMessageResource: Int
    @StringRes get() = when (this) {
        OnlineBookError.INVALID_LINK -> R.string.catalogue_invalid_link
        OnlineBookError.NETWORK -> R.string.online_book_network_error
        OnlineBookError.ACCESS_NOT_CONFIGURED -> R.string.catalogue_access_error
        OnlineBookError.UNSUPPORTED_LIBRARY -> R.string.catalogue_unsupported_library
        else -> R.string.catalogue_response_error
    }
