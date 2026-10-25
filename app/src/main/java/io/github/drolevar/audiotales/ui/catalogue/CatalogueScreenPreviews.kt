package io.github.drolevar.audiotales.ui.catalogue

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.LibraryProtocol
import io.github.drolevar.audiotales.data.online.OnlineLibrary
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme

// These fixtures are used only in previews; runtime content is repository-backed.
private val PreviewCatalogue = CatalogueUiState(
    libraries = listOf(OnlineLibrary("preview", "https://example.org/", "Example library", LibraryProtocol.LIBRIVOX)),
    librariesLoading = false,
    selectedLibraryId = "preview",
    books = listOf(
        CatalogueBook("preview", "1", "Dramatic Reading Scene and Story Collection, Volume 003", "Various authors", "A collection of stories.", null, null),
        CatalogueBook("preview", "2", "Роберт Шеклі — Через травний тракт у космос", null, "", null, null),
    ),
    nextOffset = 2,
)

@Preview(name = "Browse · catalogue", widthDp = 393, heightDp = 852, showSystemUi = true)
@Preview(name = "Browse · large text", widthDp = 320, heightDp = 700, fontScale = 2f, showSystemUi = true)
@Preview(name = "Browse · short window", widthDp = 720, heightDp = 360, showSystemUi = true)
@Composable
private fun CataloguePreview() {
    AudioTalesTheme(darkTheme = false, dynamicColor = false) {
        CatalogueScreen(
            state = PreviewCatalogue,
            onQueryChanged = {},
            onSearch = {},
            onLibrarySelected = {},
            onAddLibrary = {},
            onRetryLibraries = {},
            onLoadMore = {},
            onInspect = {},
        )
    }
}

@Preview(name = "Browse · empty", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun EmptyCataloguePreview() {
    AudioTalesTheme(darkTheme = false, dynamicColor = false) {
        CatalogueScreen(
            state = CatalogueUiState(librariesLoading = false),
            onQueryChanged = {},
            onSearch = {},
            onLibrarySelected = {},
            onAddLibrary = {},
            onRetryLibraries = {},
            onLoadMore = {},
            onInspect = {},
        )
    }
}
