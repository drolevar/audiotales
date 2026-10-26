package io.github.drolevar.audiotales.ui.details

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ReaderCredit
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plain rendering/action checks; no provider, playback, or application graph. */
@RunWith(AndroidJUnit4::class)
class BookDetailsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun metadataRemainsVisibleWhileAudioLoadsAndSaveListenDownloadAreUnavailable() {
        compose.setContent { Content(BookDetailsUiState(book = book, loading = false, audioLoading = true, downloadsReady = true)) }
        compose.onNodeWithText("A book").assertIsDisplayed()
        compose.onNodeWithText("A description").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading audio…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 tracks").assertDoesNotExist()
        compose.onNodeWithText("Listen").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Download").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Book options").performClick()
        compose.onNodeWithText("Save to My books").assertIsNotEnabled()
    }

    @Test fun audioFailureOffersOnlyAudioRetryAndKeepsDescriptionAvailable() {
        var audioRetries = 0
        var metadataRefreshes = 0
        compose.setContent {
            Content(BookDetailsUiState(book = book, loading = false, audioFailed = true, downloadsReady = true),
                onRetryAudio = { audioRetries++ }, onRefresh = { metadataRefreshes++ })
        }
        compose.onNodeWithText("A description").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Couldn’t load audio.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 tracks").assertDoesNotExist()
        compose.onNodeWithText("Retry audio").performScrollTo().assertIsEnabled().performClick()
        capture("metadata-audio-error")
        compose.runOnIdle {
            assertEquals(1, audioRetries)
            assertEquals(0, metadataRefreshes)
        }
    }

    @Test fun audioRetryAndDescriptionRemainReachableInSmallWindowWithLargeText() {
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    Content(BookDetailsUiState(book = book, loading = false, audioFailed = true, downloadsReady = true),
                        onRetryAudio = { retries++ })
                }
            }
        }
        compose.onNodeWithText("Retry audio").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        capture("large-text-audio-error")
        compose.onNodeWithText("A description").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun identicalCompleteReaderCreditsHaveABookSummarySeparateFromAuthor() {
        val credits = listOf(ReaderCredit("7", "First Reader"), ReaderCredit("8", "Second Reader"))
        compose.setContent { Content(BookDetailsUiState(book = book.copy(author = "The author"),
            tracks = listOf(track.copy(readerCredits = credits), track.copy(id = "other", sequenceIndex = 1, readerCredits = credits)), loading = false)) }
        compose.onNodeWithText("The author").assertIsDisplayed()
        compose.onNodeWithText("Read by First Reader, Second Reader").assertIsDisplayed()
    }

    @Test fun mixedOrUnknownReadersAppearOnlyOnTheirOwnTracks() {
        val credits = listOf(ReaderCredit("7", "Known Reader"))
        compose.setContent { Content(BookDetailsUiState(book = book,
            tracks = listOf(track, track.copy(id = "other", title = "Other track", sequenceIndex = 1, readerCredits = credits)), loading = false)) }
        compose.onNodeWithText("Read by Known Reader").assertDoesNotExist()
        compose.onNodeWithText("Tracks").performScrollTo().performClick()
        compose.onNodeWithText("Read by Known Reader").assertIsDisplayed()
        compose.onNodeWithText("First track").assertIsDisplayed()
        compose.onNodeWithText("Other track").assertIsDisplayed()
    }

    @Test fun unknownAndExplicitlyEmptyCreditsDoNotExposeAnEmptyReadByLabel() {
        compose.setContent { Content(BookDetailsUiState(book = book,
            tracks = listOf(track, track.copy(id = "other", sequenceIndex = 1, readerCredits = emptyList())), loading = false)) }
        compose.onNodeWithText("Read by", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tracks").performScrollTo().performClick()
        compose.onNodeWithText("Read by", substring = true).assertDoesNotExist()
    }

    /** Optional inspected evidence; no golden screenshot comparison. */
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDetails") != "true") return
        compose.waitForIdle()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-details-ui-audit/$name.png")
        output.parentFile?.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun resolvedTracksEnableExplicitActions() {
        var saves = 0
        var listens = 0
        var downloads = 0
        compose.setContent {
            Content(BookDetailsUiState(book = book, tracks = listOf(track), loading = false, downloadsReady = true),
                onSave = { saves++ }, onListen = { listens++ }, onDownload = { downloads++ })
        }
        compose.onNodeWithText("1 track").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Download").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Book options").performClick()
        compose.onNodeWithText("Save to My books").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, listens)
            assertEquals(1, downloads)
        }
    }

    @Composable private fun Content(
        state: BookDetailsUiState,
        onRetryAudio: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onSave: () -> Unit = {},
        onListen: () -> Unit = {},
        onDownload: () -> Unit = {},
    ) {
        AudioTalesTheme(darkTheme = false, dynamicColor = false) {
            BookDetailsScreen(state, playbackAvailable = true, onBack = {}, onRetry = {}, onRefresh = onRefresh,
                onSave = onSave, onListen = onListen, onTrack = {}, onDownload = onDownload,
                onRemoveDownloads = {}, onDismissError = {}, onShare = {}, onRetryAudio = onRetryAudio)
        }
    }

    companion object {
        private val book = LibraryBook("book", "A book", providerId = "source", providerBookId = "42", description = "A description")
        private val track = LibraryTrack("track", "book", "First track", "https://example.org/audio.mp3", 0)
    }
}
