package io.github.drolevar.audiotales.ui.player

import android.graphics.Bitmap
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ListeningProgress
import io.github.drolevar.audiotales.playback.*
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled UI contracts only; real Media3, deletion and device playback need integration checks. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BookPlayerContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingAndUnavailableKeepNavigationAndRetryHonest() {
        val state = mutableStateOf(BookPlayerUiState())
        var retries = 0
        var backs = 0
        compose.setContent { Screen(state.value, onRetry = { retries++ }, onBack = { backs++ }) }
        compose.onNodeWithText("Opening book…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Player options").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Listen").assertDoesNotExist()
        compose.runOnIdle { state.value = BookPlayerUiState(loading = false, failed = true) }
        compose.onNodeWithText("This book could not be opened.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithContentDescription("Close player").performClick()
        assertEquals(1, retries)
        assertEquals(1, backs)
    }

    @Test
    fun openingAnUnattachedBookDoesNotPlayAndKeepsTimerDisabled() {
        var listens = 0
        var reconnects = 0
        val playback = mutableStateOf(PlaybackState(connection = PlaybackConnectionStatus.CONNECTED, activeBookId = "another-book"))
        compose.setContent { Screen(ready(), playback = playback.value, onListen = { listens++ }, onReconnect = { reconnects++ }) }
        compose.runOnIdle { assertEquals(0, listens) }
        compose.onNodeWithContentDescription("Sleep timer").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Listen").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, listens)
        compose.runOnIdle { playback.value = PlaybackState(connection = PlaybackConnectionStatus.FAILED) }
        compose.onNodeWithContentDescription("Listen").assertIsNotEnabled()
        compose.onNodeWithText("Reconnect player").performScrollTo().performClick()
        assertEquals(1, reconnects)
    }

    @Test
    fun activeNativePauseWorksDuringDownloadWorkAndDetachesForBookRemoval() {
        lateinit var player: ControllablePlayer
        compose.runOnUiThread { player = ControllablePlayer() }
        val state = mutableStateOf(ready().copy(actionBusy = true, refreshingSources = true))
        try {
            compose.setContent {
                Screen(state.value, controller = player,
                    playback = PlaybackState(connection = PlaybackConnectionStatus.CONNECTED, activeBookId = "book", activeTrackId = "track-1", isPlaying = true))
            }
            compose.onNodeWithContentDescription("Pause").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(false, player.playWhenReady) }
            compose.onNodeWithContentDescription("Play").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(true, player.playWhenReady) }
            compose.onNodeWithContentDescription("Sleep timer").assertIsEnabled()
            compose.runOnIdle { state.value = state.value.copy(removingBook = true) }
            compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
            compose.onNodeWithContentDescription("Listen").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Sleep timer").assertIsNotEnabled()
            capture("active-native-controls")
        } finally {
            compose.runOnUiThread { player.release() }
        }
    }

    @Test
    fun uncheckedAndFailedDownloadsNeverClaimOfflineAvailability() {
        val state = mutableStateOf(ready().copy(downloadsReady = false))
        var downloadRetries = 0
        val completed = tracks.associate { it.id to TrackDownloadState(DownloadStatus.COMPLETED) }
        compose.setContent { Screen(state.value, downloads = completed, onDownloadRetry = { downloadRetries++ }) }
        compose.onNodeWithText("Available offline").assertDoesNotExist()
        compose.onNodeWithText("Downloaded").assertDoesNotExist()
        compose.onNodeWithContentDescription("Downloads").performScrollTo().performClick()
        compose.onNodeWithText("Download book").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(downloadsFailed = true) }
        compose.onAllNodesWithText("Downloads could not be checked. Please try again.").onLast().assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, downloadRetries)
        compose.onNodeWithText("Available offline").assertDoesNotExist()
        capture("download-index-error")
    }

    @Test
    fun informationAndSharingUseRealMetadataAndSourceCallbacks() {
        var refreshes = 0
        var opens = 0
        var shares = 0
        compose.setContent { Screen(ready().copy(book = book.copy(description = null)),
            onRefresh = { refreshes++ }, onOpenSource = { opens++ }, onShare = { shares++ }) }
        openOptions()
        compose.onNodeWithText("Book information").performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithText("No description is available.").assertIsDisplayed()
        compose.onNodeWithText("Refresh information").assertDoesNotExist()
        compose.onNodeWithText("2 tracks").assertIsDisplayed()
        compose.onNodeWithText("Open source website").performScrollTo().performClick()
        assertEquals(1, opens)
        compose.onNodeWithContentDescription("Close").performClick()
        openOptions()
        compose.onNodeWithText("Share").performClick()
        assertEquals(1, shares)
        assertNull(safePlayerSourceUrl("content://audio/private"))
        assertNull(safePlayerSourceUrl("javascript:alert(1)"))
        assertNull(safePlayerSourceUrl("https://user:password@example.com/book"))
        assertEquals("https://example.com/book", safePlayerSourceUrl("https://example.com/book"))
    }

    @Test
    fun removingDownloadsAndRemovingBookHaveSeparateExplicitConfirmations() {
        var removedAudio = 0
        var removedBook = 0
        compose.setContent { Screen(ready(), downloads = mapOf(tracks.first().id to TrackDownloadState(DownloadStatus.PAUSED)),
            onRemoveDownloads = { removedAudio++ }, onRemoveBook = { removedBook++ }) }
        openOptions()
        compose.onNodeWithText("Manage downloads").performClick()
        compose.onNodeWithText("First track").assertDoesNotExist()
        compose.onNodeWithText("Second track").assertDoesNotExist()
        compose.onNodeWithText("Remove downloads").performScrollTo().performClick()
        compose.onNodeWithText("Remove audio?").assertIsDisplayed()
        compose.onNodeWithText("Only downloaded audio is removed.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, removedAudio)
        compose.onNodeWithText("Remove downloads").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, removedAudio)
        assertEquals(0, removedBook)
        compose.onNodeWithContentDescription("Close").performClick()
        openOptions()
        compose.onNodeWithText("Remove book").performClick()
        compose.onNodeWithText("Remove book?").assertIsDisplayed()
        compose.onNodeWithText("listening progress and bookmarks", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, removedBook)
        openOptions()
        compose.onNodeWithText("Remove book").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, removedBook)
        assertEquals(1, removedAudio)
    }

    @Test
    fun tracksAreInASheetAndSelectStableTrackIdentity() {
        val selected = mutableListOf<String>()
        compose.setContent { Screen(ready(), onTrack = { selected += it }) }
        compose.onNodeWithText("Second track").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tracks").performScrollTo().performClick()
        compose.onNodeWithText("First track").assertIsSelected()
        compose.onNodeWithText("Second track").performClick()
        assertEquals(listOf("track-2"), selected)
        compose.onNodeWithText("Second track").assertDoesNotExist()
    }

    @Test
    fun downloadSheetActionsFollowActualTransferState() {
        val status = mutableStateOf(DownloadStatus.QUEUED)
        var starts = 0
        var pauses = 0
        compose.setContent {
            AudioTalesTheme(darkTheme = false, dynamicColor = false) {
                val downloads = mapOf("track-1" to TrackDownloadState(status.value))
                PlayerDownloadsSheet(ready(), summarizeBookDownloads(tracks.map { it.id }, downloads),
                    onDownload = { starts++ }, onPause = { pauses++ }, onRemove = {}, onRetry = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Pause downloads").performClick()
        assertEquals(1, pauses)
        compose.onNodeWithText("Download book").assertDoesNotExist()
        compose.runOnIdle { status.value = DownloadStatus.PAUSED }
        compose.onNodeWithText("Resume downloads").performClick()
        assertEquals(1, starts)
        compose.runOnIdle { status.value = DownloadStatus.FAILED }
        compose.onNodeWithText("Retry downloads").performClick()
        assertEquals(2, starts)
        compose.runOnIdle { status.value = DownloadStatus.REMOVING }
        compose.onNodeWithText("Resume downloads").assertIsNotEnabled()
        compose.onNodeWithText("Remove downloads").assertIsNotEnabled()
    }

    @Test
    fun timerErrorsDoNotOfferToRestartPlayback() {
        var dismisses = 0
        compose.setContent {
            AudioTalesTheme(darkTheme = false, dynamicColor = false) {
                BookPlayerContent(ready(), PlaybackState(connection = PlaybackConnectionStatus.CONNECTED, commandError = "rejected"),
                    null, emptyMap(), onBack = {}, onLoadRetry = {}, onListen = {}, onConnectionRetry = {},
                    onPlaybackRetry = { error("Timer failure must not retry playback") }, onCancelAction = {},
                    onTrack = {}, onDownload = {}, onPauseDownloads = {}, onRemoveDownloads = {},
                    onDismissCommandError = { dismisses++ })
            }
        }
        compose.onNodeWithText("The sleep timer could not be updated.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertEquals(1, dismisses)
    }

    @Test
    fun timerPresetsAndCancelOnlyDispatchEventsAndDisplayServiceState() {
        var duration: Long? = null
        var ends = 0
        var cancels = 0
        val timer = mutableStateOf(SleepTimerState("book", remainingMs = 123_000))
        compose.setContent {
            AudioTalesTheme(darkTheme = false, dynamicColor = false) {
                PlayerSleepSheet(timer.value, enabled = true, onSleepTimer = { duration = it }, onEndOfTrack = { ends++ }, onCancel = { cancels++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Sleep timer: 2:03 remaining").assertIsDisplayed()
        compose.onNodeWithText("15 minutes").performClick()
        assertEquals(900_000L, duration)
        compose.onNodeWithText("Sleep timer: 2:03 remaining").assertIsDisplayed()
        compose.onNodeWithText("End of track").performClick()
        assertEquals(1, ends)
        compose.runOnIdle { timer.value = SleepTimerState("book", endOfTrack = true) }
        compose.onNodeWithText("Sleep timer: end of track").assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").performScrollTo().performClick()
        assertEquals(1, cancels)
        capture("sleep-timer")
    }

    @Test
    fun shortDarkLargeTextKeepsNavigationAndToolsReachable() {
        var backs = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Screen(ready(), modifier = Modifier.size(320.dp, 320.dp), dark = true, onBack = { backs++ })
            }
        }
        compose.onNodeWithContentDescription("Close player").assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp).performClick()
        assertEquals(1, backs)
        compose.onNodeWithContentDescription("Player options").assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Tracks").performScrollTo().assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Downloads").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Download book").performScrollTo().assertIsDisplayed()
        capture("short-dark-large-text-downloads")
    }

    private fun openOptions() = compose.onNodeWithContentDescription("Player options").performClick()

    @Composable
    private fun Screen(
        state: BookPlayerUiState,
        modifier: Modifier = Modifier,
        playback: PlaybackState = PlaybackState(connection = PlaybackConnectionStatus.CONNECTED),
        controller: Player? = null,
        downloads: Map<String, TrackDownloadState> = emptyMap(),
        dark: Boolean = false,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onListen: () -> Unit = {},
        onReconnect: () -> Unit = {},
        onTrack: (String) -> Unit = {},
        onRemoveDownloads: () -> Unit = {},
        onRemoveBook: () -> Unit = {},
        onDownloadRetry: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onOpenSource: () -> Unit = {},
        onShare: () -> Unit = {},
    ) {
        AudioTalesTheme(darkTheme = dark, dynamicColor = false) {
            BookPlayerContent(state, playback, controller, downloads,
                    onBack, onRetry, onListen, onReconnect, onPlaybackRetry = {}, onCancelAction = {},
                    onTrack, onDownload = {}, onPauseDownloads = {}, onRemoveDownloads,
                    modifier = modifier, onRemoveBook = onRemoveBook, onDownloadRetry = onDownloadRetry,
                    onRefreshMetadata = onRefresh, onOpenSource = onOpenSource, onShare = onShare)
        }
    }

    private fun ready() = BookPlayerUiState(book, tracks, loading = false, downloadsReady = true,
        progress = ListeningProgress("book", "track-1", 12_000, 0))

    private val book = LibraryBook("book", "A real book", "An author", sourceUrl = "https://example.com/book", description = "Saved description")
    private val tracks = listOf(
        LibraryTrack("track-1", "book", "First track", "https://example.com/1.mp3", 0, durationMs = 120_000),
        LibraryTrack("track-2", "book", "Second track", "https://example.com/2.mp3", 1, durationMs = 240_000),
    )

    /** Media3's real state/command contract without audio output or an application service. */
    private class ControllablePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var snapshot = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            ).build())
            .setPlaylist(listOf(MediaItemData.Builder("track-1")
                .setMediaItem(MediaItem.Builder().setMediaId("track-1").build())
                .setDurationUs(120_000_000L).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        override fun getState(): State = snapshot

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    /** Opt-in audit images; captures are evidence, not golden-image comparisons. */
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePlayer") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.cacheDir, "player-ui-audit/$name.png")
        output.parentFile?.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
