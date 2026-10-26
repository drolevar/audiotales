package io.github.drolevar.audiotales.ui.downloads

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryCollectionEntry
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.playback.DownloadStatus
import io.github.drolevar.audiotales.playback.TrackDownloadState
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled presentation/callback checks; these do not exercise Media3 or provider recovery. */
@RunWith(AndroidJUnit4::class)
class DownloadsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun transferStatesExposeTheCorrectBookAction() {
        val item = mutableStateOf(book("One book", DownloadStatus.QUEUED))
        val requests = mutableListOf<Pair<String, DownloadBookStatus>>()
        compose.setContent {
            Screen(state(item.value), onPrimary = { requests += it.entry.book.id to it.status })
        }
        val cases = listOf(
            Triple(DownloadStatus.QUEUED, "Queued", "Pause downloads for One book"),
            Triple(DownloadStatus.DOWNLOADING, "Downloading", "Pause downloads for One book"),
            Triple(DownloadStatus.PAUSED, "Paused", "Resume downloads for One book"),
            Triple(DownloadStatus.FAILED, "Download failed", "Retry downloads for One book"),
            Triple(DownloadStatus.COMPLETED, "Available offline", "Play downloaded book: One book"),
        )
        cases.forEach { (status, text, action) ->
            compose.runOnIdle { item.value = book("One book", status) }
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithContentDescription(action).assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(item.value.entry.book.id to item.value.status, requests.last())
            }
            capture(status.name.lowercase())
        }
        assertEquals(cases.size, requests.size)
    }

    @Test
    fun onlyAnActiveUnknownLengthTransferShowsIndeterminateProgress() {
        val item = mutableStateOf(book("Unknown length", DownloadStatus.DOWNLOADING))
        compose.setContent { Screen(state(item.value)) }
        val indeterminate = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )
        compose.onNode(indeterminate, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0 of 2 tracks saved").assertIsDisplayed()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.REMOVING)) {
            compose.runOnIdle { item.value = book("Unknown length", status) }
            compose.onNode(indeterminate, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { item.value = book("Known length", DownloadStatus.DOWNLOADING, total = 100) }
        compose.onNodeWithText("Downloading · 25%").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f),
        ), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun loadingAndLoadFailureNeverOfferTheEmptyCollectionAction() {
        val current = mutableStateOf(DownloadsUiState())
        var retries = 0
        var reconnects = 0
        compose.setContent {
            Screen(current.value, connectionFailed = true,
                onRetry = { retries++ }, onReconnect = { reconnects++ })
        }
        compose.onNodeWithText("Checking downloads…").assertIsDisplayed()
        compose.onNodeWithText("Go to My books").assertDoesNotExist()
        compose.runOnIdle { current.value = DownloadsUiState(loading = false, failed = true) }
        compose.onNodeWithText("Downloads could not be loaded. Please try again.").assertIsDisplayed()
        compose.onNodeWithText("Go to My books").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Reconnect player").performClick()
        assertEquals(1, retries)
        assertEquals(1, reconnects)
        capture("load-error")
    }

    @Test
    fun perBookErrorsKeepRetryAvailableAndBusyStatePreventsDuplicateRequests() {
        val item = book("Failed book", DownloadStatus.FAILED)
        val id = item.entry.book.id
        val current = mutableStateOf(state(item).copy(errors = mapOf(id to DownloadActionError.SOURCES)))
        var requests = 0
        compose.setContent { Screen(current.value, onPrimary = { requests++ }) }
        compose.onNodeWithText("Audio sources could not be refreshed. Check your connection and try again.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry downloads for Failed book").performClick()
        assertEquals(1, requests)
        capture("source-error")
        compose.runOnIdle { current.value = state(item).copy(busyBooks = setOf(id)) }
        compose.onNodeWithContentDescription("Retry downloads for Failed book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Updating downloads for Failed book…").assertIsDisplayed()
        compose.runOnIdle { current.value = state(item).copy(errors = mapOf(id to DownloadActionError.REQUEST)) }
        compose.onNodeWithText("The download request could not be completed. Please try again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry downloads for Failed book").assertIsEnabled()
    }

    @Test
    fun removalRequiresConfirmationAndDetailsShowPerTrackState() {
        val item = book("Paused book", DownloadStatus.PAUSED)
        val removals = mutableListOf<String>()
        compose.setContent { Screen(state(item), onRemove = { removals += it }) }
        fun openMenu() = compose.onNodeWithContentDescription("Download options for Paused book").performClick()
        openMenu()
        compose.onNodeWithText("Download details").performClick()
        compose.onNodeWithText("Track 1").assertIsDisplayed()
        compose.onNodeWithText("Track 2").assertIsDisplayed()
        capture("track-details")
        compose.onNodeWithText("Close").performClick()
        openMenu()
        compose.onNodeWithText("Remove downloads").performClick()
        compose.onNodeWithText("The book and your listening history stay in My books.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), removals)
        openMenu()
        compose.onNodeWithText("Remove downloads").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(item.entry.book.id), removals)
    }

    @Test
    fun completedPlaybackAndRemovalRespectAvailability() {
        val item = mutableStateOf(book("One book", DownloadStatus.COMPLETED))
        compose.setContent { Screen(state(item.value), playbackConnected = false) }
        compose.onNodeWithContentDescription("Play downloaded book: One book").assertIsNotEnabled()
        compose.runOnIdle { item.value = book("One book", DownloadStatus.REMOVING) }
        compose.onNodeWithContentDescription("Removing downloads for One book…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Download options for One book").performClick()
        compose.onNodeWithText("Remove downloads").assertIsNotEnabled()
    }

    @Test
    fun largeTextShortWindowKeepsEveryTransferActionReachable() {
        val items = listOf(
            book("Queued book", DownloadStatus.QUEUED),
            book("Paused book", DownloadStatus.PAUSED),
            book("Failed book", DownloadStatus.FAILED),
        )
        val requests = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Screen(state(*items.toTypedArray()), darkTheme = true,
                    modifier = Modifier.size(320.dp, 320.dp),
                    onPrimary = { requests += it.entry.book.id })
            }
        }
        for (action in listOf("Pause downloads for Queued book", "Resume downloads for Paused book", "Retry downloads for Failed book")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(action))
            val button = compose.onNodeWithContentDescription(action).performScrollTo().assertIsDisplayed()
            // Material's visible container and expanded touch target have different sizes.
            val visibleBounds = button.fetchSemanticsNode().boundsInRoot
            val layoutBounds = button.getUnclippedBoundsInRoot()
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            assertEquals((layoutBounds.bottom - layoutBounds.top).value * density, visibleBounds.height, 0.5f)
            assertEquals((layoutBounds.right - layoutBounds.left).value * density, visibleBounds.width, 0.5f)
            button.assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp).performClick()
            capture("large-${requests.size}")
        }
        assertEquals(items.map { it.entry.book.id }, requests)
    }

    @Composable
    private fun Screen(
        state: DownloadsUiState,
        modifier: Modifier = Modifier,
        darkTheme: Boolean = false,
        playbackConnected: Boolean = true,
        connectionFailed: Boolean = false,
        onRetry: () -> Unit = {},
        onReconnect: () -> Unit = {},
        onPrimary: (DownloadBookItem) -> Unit = {},
        onRemove: (String) -> Unit = {},
    ) {
        AudioTalesTheme(darkTheme = darkTheme, dynamicColor = false) {
            DownloadsContent(state, onBack = {}, onOpenBook = {}, onOpenMyBooks = {},
                onRetry = onRetry, onPrimary = onPrimary, onRemove = onRemove,
                playbackConnected = playbackConnected, connectionFailed = connectionFailed,
                onReconnect = onReconnect, modifier = modifier)
        }
    }

    private fun state(vararg items: DownloadBookItem) = DownloadsUiState(books = items.toList(), loading = false)

    private fun book(title: String, status: DownloadStatus, total: Long = -1): DownloadBookItem {
        val id = title.lowercase().replace(' ', '-')
        val entry = LibraryCollectionEntry(LibraryBook(id, title, author = "Author"),
            (1..2).map { LibraryTrack("$id-$it", id, "Track $it", "https://example.invalid/$it.mp3", it - 1) }, null)
        return downloadOverview(listOf(entry), entry.tracks.associate {
            it.id to TrackDownloadState(status, bytesDownloaded = 25, totalBytes = total)
        }).single()
    }

    /** Opt-in audit artifacts, not golden comparisons. Null covers avoid all image/network work. */
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDownloads") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.cacheDir, "download-ui-audit/$name.png")
        output.parentFile?.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
