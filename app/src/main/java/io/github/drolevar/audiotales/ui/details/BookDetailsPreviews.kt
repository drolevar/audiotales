package io.github.drolevar.audiotales.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme

@Preview(showBackground = true)
@Preview(showBackground = true, widthDp = 760, heightDp = 400)
@Preview(showBackground = true, fontScale = 1.5f)
@Composable
private fun BookDetailsPreview() {
    AudioTalesTheme(darkTheme = false) {
        BookDetailsScreen(
            state = BookDetailsUiState(
                book = LibraryBook("preview", "A collection of stories", author = "Various authors", description = "The full description supplied by a library is shown here without a line limit."),
                tracks = listOf(LibraryTrack("track", "preview", "First recording", "", 0, 60_000)),
                sourceName = "Example library", saved = true, loading = false, downloadsReady = true,
            ),
            playbackAvailable = true, onBack = {}, onRetry = {}, onRefresh = {}, onSave = {},
            onListen = {}, onTrack = {}, onDownload = {}, onRemoveDownloads = {}, onDismissError = {}, onShare = {},
        )
    }
}
