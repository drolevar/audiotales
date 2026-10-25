package io.github.drolevar.audiotales.ui.library

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.TrackDownloadState
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme

// Deterministic design fixtures. Runtime library content comes only from LibraryRepository.
private val PreviewBooks = listOf(
    LibraryBook(
        id = "preview-1",
        title = "Dramatic Reading Scene and Story Collection, Volume 003",
        author = "Various authors",
    ),
    LibraryBook(id = "preview-2", title = "101 Mexican Dishes", author = "May Southworth"),
    LibraryBook(id = "preview-3", title = "’Twixt Land and Sea", author = "Joseph Conrad"),
    LibraryBook(id = "preview-4", title = "A book without author metadata"),
)

private val PreviewContent = LibraryUiState.Content(
    books = PreviewBooks,
    rows = collectionRows(
        PreviewBooks.mapIndexed { index, book ->
            LibraryCollectionEntry(
                book,
                listOf(LibraryTrack("${book.id}-track", book.id, "Chapter 1", "https://example.org/preview", 0, 100_000)),
                if (index == 0) ListeningProgress(book.id, "${book.id}-track", 32_000, 1) else null,
            )
        },
        mapOf(
            "preview-1-track" to TrackDownloadState(DownloadStatus.COMPLETED),
            "preview-2-track" to TrackDownloadState(DownloadStatus.DOWNLOADING),
        ),
        CollectionFilter.ALL,
        CollectionSort.RECENT,
    ),
)

@Preview(name = "My books · loading", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun LibraryLoadingPreview() {
    LibraryPreview(LibraryUiState.Loading)
}

@Preview(name = "My books · empty", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun LibraryEmptyPreview() {
    LibraryPreview(LibraryUiState.Empty)
}

@Preview(name = "My books · error", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun LibraryErrorPreview() {
    LibraryPreview(LibraryUiState.Error)
}

@Preview(name = "My books · populated", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun LibraryPopulatedPreview() {
    LibraryPreview(PreviewContent)
}

@Preview(
    name = "My books · dark",
    widthDp = 393,
    heightDp = 852,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun LibraryDarkPreview() {
    LibraryPreview(PreviewContent, darkTheme = true)
}

@Preview(
    name = "My books · large text",
    widthDp = 393,
    heightDp = 852,
    showSystemUi = true,
    fontScale = 2f,
)
@Composable
fun LibraryLargeTextPreview() {
    LibraryPreview(PreviewContent)
}

@Composable
private fun LibraryPreview(state: LibraryUiState, darkTheme: Boolean = false) {
    AudioTalesTheme(darkTheme = darkTheme, dynamicColor = false) {
        LibraryScreen(state = state, onRetry = {}, onBrowse = {})
    }
}
