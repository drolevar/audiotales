package io.github.drolevar.audiotales.ui.catalogue

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.ui.BookCover
import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.OnlineBookError

/** Pure content. Each source retains its own paging, retry and language selection. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CatalogueScreen(
    state: CatalogueUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLibrarySelected: (String, Boolean) -> Unit,
    onManageLibraries: () -> Unit,
    onRetryLibraries: () -> Unit,
    onRetrySource: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    onSort: (CatalogueSort) -> Unit,
    onLanguage: (String, String?) -> Unit,
    onInspect: (CatalogueBook) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var choosingLibraries by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val search = { focus.clearFocus(); onSearch() }
    val listState = rememberSaveable(state.singleLibraryId, state.submittedQuery, saver = LazyListState.Saver) { LazyListState() }
    Scaffold(
        modifier = modifier,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                if (onBack != null) {
                    TopAppBar(
                        title = { Text(state.libraries.firstOrNull { it.id == state.singleLibraryId }?.displayName ?: stringResource(R.string.catalogue_browse), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.catalogue_up)) } },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(Modifier.weight(1f), shape = SearchBarDefaults.inputFieldShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        SearchBarDefaults.InputField(
                            query = state.query, onQueryChange = onQueryChanged,
                            onSearch = { search() }, expanded = false, onExpandedChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.catalogue_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { IconButton(onClick = search) { Icon(painterResource(R.drawable.ic_catalogue_search), stringResource(R.string.catalogue_search)) } },
                        )
                    }
                    Box {
                        IconButton(onClick = { options = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.catalogue_options)) }
                        DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.catalogue_manage_libraries)) }, onClick = { options = false; onManageLibraries() })
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "filters") {
                FlowRow(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    if (state.singleLibraryId == null) FilterChip(
                        selected = state.browseLibraries.isNotEmpty(), onClick = { choosingLibraries = true },
                        label = { Text(stringResource(R.string.catalogue_libraries_count, state.browseLibraries.size)) },
                    ) else Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { sorting = true }) {
                            Text(stringResource(state.sort.label))
                            Icon(painterResource(R.drawable.ic_chevron_down), null)
                        }
                        DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                            Text(stringResource(R.string.catalogue_sort_shown), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp))
                            CatalogueSort.entries.forEach { sort ->
                                DropdownMenuItem(text = { Text(stringResource(sort.label)) }, onClick = { sorting = false; onSort(sort) }, trailingIcon = {
                                    if (state.sort == sort) Icon(painterResource(R.drawable.ic_check), null)
                                })
                            }
                        }
                    }
                }
            }
            if (state.submittedQuery.isNotBlank()) item(key = "query") {
                Text(stringResource(R.string.catalogue_results_for, state.submittedQuery), style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (state.libraryActionError != null) item(key = "selection_error") { CatalogueError(state.libraryActionError) }
            if (state.librariesLoading) item(key = "libraries_loading") { CatalogueLoading() }
            if (state.librariesError) item(key = "libraries_error") {
                CatalogueMessage(stringResource(R.string.catalogue_library_error), action = { OutlinedButton(onClick = onRetryLibraries) { Text(stringResource(R.string.try_again)) } })
            }
            if (!state.librariesLoading && !state.librariesError && state.browseLibraries.isEmpty()) item(key = "choose_libraries") {
                CatalogueMessage(
                    text = stringResource(if (state.singleLibraryId != null) R.string.catalogue_library_removed else R.string.catalogue_choose_libraries),
                    detail = stringResource(R.string.catalogue_choose_libraries_help),
                    action = { Button(onClick = if (state.libraries.isEmpty() || state.singleLibraryId != null) onManageLibraries else ({ choosingLibraries = true })) { Text(stringResource(if (state.libraries.isEmpty() || state.singleLibraryId != null) R.string.catalogue_manage_libraries else R.string.catalogue_choose_libraries)) } },
                )
            }
            state.browseLibraries.forEach { library ->
                val source = state.sources[library.id] ?: CatalogueSourceState(loading = true)
                val shown = source.shown(state.sort)
                item(key = "${library.id}:heading") {
                    CatalogueSourceHeading(library.displayName, source, shown.size) { onLanguage(library.id, it) }
                }
                items(shown, key = { "book:${it.libraryId}:${it.remoteId}" }) { book -> CatalogueBookRow(book, { onInspect(book) }) }
                if (source.loading) item(key = "${library.id}:loading") { CatalogueLoading() }
                if (!source.loading && shown.isEmpty() && source.error == null) item(key = "${library.id}:empty") {
                    CatalogueMessage(
                        text = source.language?.let { stringResource(R.string.catalogue_no_language_results, it) } ?: stringResource(R.string.catalogue_empty),
                        detail = stringResource(if (source.language != null) R.string.catalogue_language_help else R.string.catalogue_empty_help),
                    )
                }
                source.error?.let { error -> item(key = "${library.id}:error") {
                    CatalogueMessage(stringResource(error.catalogueMessageResource), action = { OutlinedButton(onClick = { onRetrySource(library.id) }) { Text(stringResource(R.string.try_again)) } })
                } }
                if (!source.loading && source.error == null && source.nextOffset != null) item(key = "${library.id}:more") {
                    OutlinedButton(onClick = { onLoadMore(library.id) }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.catalogue_load_more)) }
                }
            }
        }
    }
    if (choosingLibraries) ModalBottomSheet(
        onDismissRequest = { choosingLibraries = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(stringResource(R.string.catalogue_choose_libraries), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(state.libraries, key = { it.id }) { library ->
                ListItem(
                    headlineContent = { Text(library.displayName) }, supportingContent = { Text(library.baseUrl) },
                    leadingContent = { Checkbox(library.isSelected, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = library.isSelected, role = Role.Checkbox, enabled = !state.changingLibrary, onValueChange = { onLibrarySelected(library.id, it) }),
                )
            }
        }
        state.libraryActionError?.let { CatalogueError(it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { choosingLibraries = false; onManageLibraries() }) { Text(stringResource(R.string.catalogue_manage_libraries)) }
            TextButton(onClick = { choosingLibraries = false }) { Text(stringResource(R.string.catalogue_done)) }
        }
    }
}

@Composable
private fun CatalogueSourceHeading(name: String, source: CatalogueSourceState, count: Int, onLanguage: (String?) -> Unit) {
    var languages by remember { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale > 1.2f
    val identity: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false).semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.catalogue_shown, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val language: @Composable () -> Unit = {
        if (source.languages.isNotEmpty() || source.language != null) Box {
            TextButton(onClick = { languages = true }) {
                Text(source.language ?: stringResource(R.string.catalogue_all_languages), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                Icon(painterResource(R.drawable.ic_chevron_down), null)
            }
            DropdownMenu(expanded = languages, onDismissRequest = { languages = false }) {
                Text(stringResource(R.string.catalogue_language_shown), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp))
                DropdownMenuItem(text = { Text(stringResource(R.string.catalogue_all_languages)) }, onClick = { languages = false; onLanguage(null) })
                source.languages.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { languages = false; onLanguage(value) }) }
            }
        }
    }
    BoxWithConstraints(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp)) {
        if (largeText || maxWidth < 340.dp) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            identity(Modifier.fillMaxWidth().padding(end = 12.dp))
            language()
        } else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            identity(Modifier.weight(1f))
            language()
        }
    }
}

private val CatalogueSort.label: Int get() = when (this) {
    CatalogueSort.LIBRARY -> R.string.catalogue_library_order
    CatalogueSort.TITLE -> R.string.catalogue_sort_title
    CatalogueSort.AUTHOR -> R.string.catalogue_sort_author
}

@Composable
private fun CatalogueBookRow(book: CatalogueBook, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().clickable(onClick = onClick).heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.coverUrl, Modifier.size(width = 56.dp, height = 80.dp), referer = book.coverReferer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            book.language?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
