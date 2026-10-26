package io.github.drolevar.audiotales.ui.player

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native progress/control contracts with a controlled Player; no audio or service integration. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MiniPlayerBarTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var player: ProgressPlayer
    private val showPlayer = mutableStateOf(true)

    @Before
    fun createPlayer() {
        compose.runOnUiThread { player = ProgressPlayer() }
    }

    @After
    fun releasePlayer() {
        // Dispose Media3's composition observers on the UI lifecycle before v2 test teardown
        // cancels its effect dispatcher from the instrumentation thread.
        compose.runOnIdle { showPlayer.value = false }
        compose.waitForIdle()
        compose.runOnUiThread { player.release() }
    }

    @Test
    fun pausedSeekUpdatesElapsedTimeWithoutReplacingScreenState() {
        compose.setContent { Content() }
        compose.onNodeWithText("12:36 / 38:20").assertIsDisplayed()
        compose.runOnIdle { player.seekTo(321_000) }
        compose.onNodeWithText("05:21 / 38:20").assertIsDisplayed()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
    }

    @Test
    fun trackTransitionUsesNewTrackPositionAndDuration() {
        val title = mutableStateOf("First track")
        compose.setContent { Content(trackTitle = title.value) }
        compose.onNodeWithText("First track ·").assertIsDisplayed()
        compose.onNodeWithText("12:36 / 38:20").assertIsDisplayed()
        compose.runOnIdle {
            player.seekTo(1, 3_000)
            title.value = "Second track"
        }
        compose.onNodeWithText("Second track ·").assertIsDisplayed()
        compose.onNodeWithText("00:03 / 03:00").assertIsDisplayed()
        compose.onNodeWithText("First track", substring = true).assertDoesNotExist()
    }

    @Test
    fun unknownDurationAndDisconnectedControllerNeverShowInventedDuration() {
        val attached = mutableStateOf<Player?>(player)
        compose.runOnUiThread { player.seekTo(2, 9_000) }
        compose.setContent { Content(controller = attached.value, trackTitle = null) }
        compose.onNodeWithText("00:09 / --:--").assertIsDisplayed()
        compose.onNodeWithText("Chapter", substring = true).assertDoesNotExist()
        compose.runOnIdle { attached.value = null }
        compose.onNodeWithText("--:-- / --:--").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
    }

    @Test
    fun openingRowDoesNotPlayAndNativeControlsDoNotOpenRow() {
        var opens = 0
        lateinit var inputModeManager: InputModeManager
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            Content(onOpen = { opens++ })
        }
        compose.onNodeWithText("A real book").performClick()
        assertEquals(1, opens)
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Pause").assertIsEnabled().performClick()
        assertEquals(1, opens)
        compose.runOnIdle { assertFalse(player.playWhenReady) }

        // Each action remains independently reachable and operable with keyboard focus.
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText("A real book")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        assertEquals(2, opens)
        compose.onNodeWithContentDescription("Play")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
        assertEquals(2, opens)
    }

    @Test
    fun compactSubtitleKeepsTrackAndTimingOnSameLineWhenTheyFit() {
        compose.setContent { Content(modifier = Modifier.width(393.dp)) }
        val track = compose.onNodeWithText("First track ·", useUnmergedTree = true).fetchSemanticsNode()
        val timing = compose.onNodeWithText("12:36 / 38:20", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(track.boundsInRoot.top, timing.boundsInRoot.top, 1f)
    }

    @Test
    fun hugeProviderTitleAt200PercentDoesNotConsumeShortParentOrHideTiming() {
        val title = "A long provider track title with details ".repeat(20)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Column(Modifier.size(320.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("mainContent")) {
                        Text("Main content")
                    }
                    Content(trackTitle = title)
                }
            }
        }
        compose.onNodeWithText("Main content").assertIsDisplayed()
        compose.onNodeWithTag("mainContent").assertHeightIsAtLeast(48.dp)
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("$title ·", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        assertTrue(titleLayouts.single().isLineEllipsized(0))
        val text = compose.onNodeWithText("12:36 / 38:20", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val diagnostics = "timingBounds=${text.fetchSemanticsNode().boundsInRoot}, " +
            "layoutSize=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
            "lines=${layout.lineCount}, lineWidths=" +
            (0 until layout.lineCount).map { layout.getLineRight(it) - layout.getLineLeft(it) } +
            ", trackBounds=${compose.onNodeWithText("$title ·", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}"
        captureLargeText()
        assertTrue(diagnostics, layout.lineCount <= 2)
        assertFalse(diagnostics, layout.didOverflowWidth)
        assertFalse(diagnostics, layout.didOverflowHeight)
        compose.onNodeWithText("A real book").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Play")
            .assertIsDisplayed().assertIsEnabled()
            .assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
    }

    /** Optional inspected audit capture; not a golden screenshot comparison. */
    private fun captureLargeText() {
        if (InstrumentationRegistry.getArguments().getString("captureMiniPlayer") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.cacheDir, "mini-player-ui-audit/large-text.png")
        output.parentFile?.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Composable
    private fun Content(
        modifier: Modifier = Modifier,
        controller: Player? = player,
        trackTitle: String? = "First track",
        onOpen: () -> Unit = {},
    ) {
        if (!showPlayer.value) return
        AudioTalesTheme(darkTheme = false, dynamicColor = false) {
            MiniPlayerBarContent(controller, "A real book", trackTitle, null, onOpen, modifier)
        }
    }

    private class ProgressPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var snapshot = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            ).build())
            .setPlaylist(listOf(
                item("first", 2_300_000_000L),
                item("second", 180_000_000L),
                item("untimed", C.TIME_UNSET),
            ))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(756_000)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        override fun getState(): State = snapshot

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            snapshot = snapshot.buildUpon()
                .setCurrentMediaItemIndex(mediaItemIndex)
                .setContentPositionMs(positionMs)
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            snapshot = snapshot.buildUpon()
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

        private fun item(id: String, durationUs: Long): MediaItemData = MediaItemData.Builder(id)
            .setMediaItem(MediaItem.Builder().setMediaId(id).build())
            .setDurationUs(durationUs)
            .setIsSeekable(true)
            .build()
    }
}
