package io.github.drolevar.audiotales.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.data.online.OnlineBookError

/** Native, previewable modal content; navigation and provider work belong to its caller. */
@Composable
internal fun IncomingLinkContent(
    state: IncomingLinkState,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is IncomingLinkState.ConfirmRegistration -> AlertDialog(
            modifier = modifier,
            onDismissRequest = onCancel,
            text = { IncomingLinkInformation(
                title = stringResource(R.string.incoming_register_title),
                message = stringResource(R.string.incoming_register_message, state.host),
            ) },
            confirmButton = { TextButton(onClick = { onConfirm(state.deliveryId) }) { Text(stringResource(R.string.incoming_register)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.incoming_cancel)) } },
        )
        IncomingLinkState.Loading -> AlertDialog(
            modifier = modifier,
            onDismissRequest = onCancel,
            text = { IncomingLinkInformation(
                title = stringResource(R.string.incoming_opening),
                message = stringResource(R.string.incoming_loading),
                loading = true,
            ) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.incoming_cancel)) } },
        )
        is IncomingLinkState.Error -> AlertDialog(
            modifier = modifier,
            onDismissRequest = onCancel,
            text = { IncomingLinkInformation(
                title = stringResource(R.string.incoming_error_title),
                message = stringResource(when (state.reason) {
                    OnlineBookError.INVALID_LINK -> R.string.incoming_invalid
                    OnlineBookError.ACCESS_NOT_CONFIGURED -> R.string.incoming_not_configured
                    OnlineBookError.UNSUPPORTED_LIBRARY, OnlineBookError.UNEXPECTED_FORMAT, OnlineBookError.UNSUPPORTED_SOURCE -> R.string.incoming_unsupported
                    else -> R.string.incoming_network
                }),
            ) },
            confirmButton = { if (state.canRetry) TextButton(onClick = onRetry) { Text(stringResource(R.string.incoming_retry)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.incoming_close)) } },
        )
        else -> Unit
    }
}

/** Scroll the heading with its message so short windows retain a readable text viewport. */
@Composable
private fun IncomingLinkInformation(title: String, message: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (loading) CircularProgressIndicator()
    }
}
