package io.github.drolevar.audiotales.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.indicators.TimeText
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
    MiniPlayerBarContent(
        player = controller,
        title = playback.title.orEmpty(),
        trackTitle = playback.chapterTitle,
        book = book,
        onOpenBook = { onOpenBook(bookId) },
        modifier = modifier,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MiniPlayerBarContent(
    player: Player?,
    title: String,
    trackTitle: String?,
    book: LibraryBook?,
    onOpenBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(modifier = modifier, color = containerColor) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = containerColor),
            headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { MiniPlayerSubtitle(player, trackTitle) },
            leadingContent = {
                BookCover(book?.coverUrl, Modifier.size(width = 40.dp, height = 56.dp), referer = book?.coverReferer)
            },
            trailingContent = { PlayPauseButton(player) },
            modifier = Modifier.fillMaxWidth().clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.player_open),
                onClick = onOpenBook,
            ),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MiniPlayerSubtitle(player: Player?, trackTitle: String?) {
    // Keep Media3's progress observation and second-by-second reads local to this text.
    TimeText(player) {
        val unknownTime = stringResource(R.string.mini_player_unknown_time)
        val position = if (player == null) unknownTime else Util.getStringForTime(currentPositionMs)
        val duration = if (durationMs == C.TIME_UNSET) unknownTime else Util.getStringForTime(durationMs)
        val progress = stringResource(R.string.mini_player_progress, position, duration)
        // Bound provider titles separately so progress stays visible in short windows.
        // Flow keeps the compact single line when both pieces fit at the current font size.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!trackTitle.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.mini_player_track, trackTitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = progress,
                // Request timing's full preferred width, bounded by the subtitle width,
                // before FlowRow decides whether it belongs on the next row.
                modifier = Modifier.width(IntrinsicSize.Max),
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}
