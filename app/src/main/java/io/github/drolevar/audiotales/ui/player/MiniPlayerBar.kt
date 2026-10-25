package io.github.drolevar.audiotales.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.ui.BookCover
import io.github.drolevar.audiotales.playback.PlaybackConnection

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayerBar(
    connection: PlaybackConnection,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
    books: List<LibraryBook> = emptyList(),
) {
    val playback by connection.state.collectAsStateWithLifecycle()
    val controller by connection.connectedController.collectAsStateWithLifecycle()
    val bookId = playback.activeBookId ?: return
    val book = books.firstOrNull { it.id == bookId }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            headlineContent = { Text(playback.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = playback.chapterTitle?.let { title -> { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            leadingContent = {
                BookCover(book?.coverUrl, Modifier.size(width = 40.dp, height = 56.dp), referer = book?.coverReferer)
            },
            trailingContent = { PlayPauseButton(controller) },
            modifier = Modifier.fillMaxWidth().clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.player_open),
                onClick = { onOpenBook(bookId) },
            ),
        )
    }
}
