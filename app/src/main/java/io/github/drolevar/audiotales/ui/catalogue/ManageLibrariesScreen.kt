package io.github.drolevar.audiotales.ui.catalogue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.data.online.OnlineLibrary

/** Checkbox and library identity are separate targets: selecting never navigates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageLibrariesScreen(
    state: CatalogueUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelected: (String, Boolean) -> Unit,
    onBrowse: (String) -> Unit,
    onRename: (OnlineLibrary) -> Unit,
    onShare: (OnlineLibrary) -> Unit,
    onRemove: (OnlineLibrary) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.catalogue_manage_libraries)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.catalogue_up)) } },
            actions = { IconButton(onClick = onAdd) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.catalogue_add_library)) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                val count = state.libraries.count { it.isSelected }
                Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.catalogue_your_libraries), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    Text(pluralStringResource(R.plurals.catalogue_selected, count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.librariesLoading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            if (state.librariesError) item {
                Column(Modifier.padding(24.dp)) { Text(stringResource(R.string.catalogue_library_error)); TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) } }
            }
            if (!state.librariesLoading && !state.librariesError && state.libraries.isEmpty()) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.catalogue_no_libraries_help))
                    Button(onClick = onAdd) { Text(stringResource(R.string.catalogue_add_library)) }
                }
            }
            if (state.libraryActionError != null && state.editingLibrary == null && state.removingLibrary == null) item {
                Text(stringResource(state.libraryActionError.messageResource), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            items(state.libraries, key = { it.id }) { library ->
                var actions by remember { mutableStateOf(false) }
                val checkboxLabel = stringResource(R.string.catalogue_include_library, library.displayName)
                val browseLabel = stringResource(R.string.catalogue_browse_library, library.displayName)
                Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = library.isSelected, onCheckedChange = { onSelected(library.id, it) }, enabled = !state.changingLibrary, modifier = Modifier.semantics { contentDescription = checkboxLabel })
                    Column(
                        Modifier.weight(1f).clickable(onClickLabel = browseLabel) { onBrowse(library.id) }.heightIn(min = 64.dp).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Text(library.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(library.baseUrl.removePrefix("https://").removeSuffix("/"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { actions = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.catalogue_library_options, library.displayName)) }
                        DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.catalogue_rename)) }, onClick = { actions = false; onRename(library) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.catalogue_share)) }, onClick = { actions = false; onShare(library) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.catalogue_remove)) }, onClick = { actions = false; onRemove(library) })
                        }
                    }
                }
            }
            if (state.libraries.isNotEmpty()) item {
                Text(stringResource(R.string.catalogue_manage_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp))
            }
        }
    }
}

@Composable
internal fun LibraryManagementDialogs(
    state: CatalogueUiState,
    onNameChanged: (String) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    state.editingLibrary?.let { library ->
        AlertDialog(
            modifier = Modifier.imePadding(), onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.catalogue_rename_library)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.libraryName, onValueChange = onNameChanged,
                        label = { Text(stringResource(R.string.catalogue_library_name)) },
                        singleLine = true, enabled = !state.changingLibrary,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!state.changingLibrary && state.libraryName.isNotBlank()) onRename() }),
                    )
                    Text(library.baseUrl, style = MaterialTheme.typography.bodySmall)
                    state.libraryActionError?.let { Text(stringResource(it.messageResource), color = MaterialTheme.colorScheme.error) }
                    if (state.changingLibrary) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = onRename, enabled = !state.changingLibrary && state.libraryName.isNotBlank()) { Text(stringResource(R.string.catalogue_rename)) } },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !state.changingLibrary) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.removingLibrary?.let { library ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.catalogue_remove_library, library.displayName)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.catalogue_remove_library_help))
                state.libraryActionError?.let { Text(stringResource(it.messageResource), color = MaterialTheme.colorScheme.error) }
                if (state.changingLibrary) LinearProgressIndicator(Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = onRemove, enabled = !state.changingLibrary) { Text(stringResource(R.string.catalogue_remove)) } },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !state.changingLibrary) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
