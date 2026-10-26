package io.github.drolevar.audiotales.ui.links

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.drolevar.audiotales.data.online.OnlineBookError
import io.github.drolevar.audiotales.ui.theme.AudioTalesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingLinkContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun registrationNamesTheSourceAndRequiresAnExplicitNativeAction() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent { AudioTalesTheme(darkTheme = false) {
            IncomingLinkContent(IncomingLinkState.ConfirmRegistration("provider.example", "delivery"), { assertEquals("delivery", it); confirmed++ }, { cancelled++ }, {})
        } }
        compose.onNodeWithText("provider.example", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("Register and open").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun loadingCanCancelAndHasNoPlaybackOrSaveActions() {
        var cancelled = 0
        compose.setContent { AudioTalesTheme(darkTheme = false) {
            IncomingLinkContent(IncomingLinkState.Loading, {}, { cancelled++ }, {})
        } }
        compose.onNodeWithText("Opening book").assertIsDisplayed()
        compose.onNodeWithText("Listen").assertDoesNotExist()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun recoverableFailureOffersRetry() {
        var retried = 0
        compose.setContent { AudioTalesTheme(darkTheme = false) {
            IncomingLinkContent(IncomingLinkState.Error(OnlineBookError.NETWORK, true), {}, {}, { retried++ })
        } }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun invalidInputOnlyOffersClose() {
        var closed = 0
        compose.setContent { AudioTalesTheme(darkTheme = false) {
            IncomingLinkContent(IncomingLinkState.Error(OnlineBookError.INVALID_LINK, false), {}, { closed++ }, {})
        } }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Register and open").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun compact200PercentConfirmationCanScrollToCompleteSourceLinesAndReachBothActions() {
        var confirmed = 0
        var cancelled = 0
        setCompactContent(
            IncomingLinkState.ConfirmRegistration("audioknigi.com.ua", "compact-delivery"),
            onConfirm = { assertEquals("compact-delivery", it); confirmed++ },
            onCancel = { cancelled++ },
        )
        compose.onNodeWithText("Register library?", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTextFullyVisible("Register library?")
        assertCompleteMarkerLinesCanBeRead("audioknigi.com.ua")
        assertTextFullyVisible("Register and open")
        assertTextFullyVisible("Cancel")
        compose.onNodeWithText("Register and open").assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(1, cancelled) }
    }

    @Test fun compact200PercentErrorCanScrollToRecoveryInformationAndReachBothActions() {
        var retried = 0
        var closed = 0
        setCompactContent(IncomingLinkState.Error(OnlineBookError.NETWORK, true),
            onCancel = { closed++ }, onRetry = { retried++ })
        compose.onNodeWithText("Couldn’t open book link", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertCompleteMarkerLinesCanBeRead("connection")
        assertTextFullyVisible("Retry")
        assertTextFullyVisible("Close")
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        compose.onNodeWithText("Close").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, closed) }
    }

    @Test fun compact200PercentLoadingCanReadProgressInformationAndCancel() {
        var cancelled = 0
        setCompactContent(IncomingLinkState.Loading, onCancel = { cancelled++ })
        assertCompleteMarkerLinesCanBeRead("book link")
        assertTextFullyVisible("Cancel")
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    private fun setCompactContent(
        state: IncomingLinkState,
        onConfirm: (String) -> Unit = {},
        onCancel: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                AudioTalesTheme(darkTheme = false) {
                    // A dialog owns a separate window: a surrounding Box cannot constrain it.
                    // Match the 320dp-wide dialog in a 640x320dp screen after native margins.
                    IncomingLinkContent(state, onConfirm, onCancel, onRetry,
                        modifier = Modifier.width(320.dp).heightIn(max = 272.dp))
                }
            }
        }
    }

    /** Check real laid-out line geometry inside the clipped viewport, then scroll to the marker. */
    private fun assertCompleteMarkerLinesCanBeRead(marker: String) {
        val text = compose.onNodeWithText(marker, substring = true, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val markerOffset = layout.layoutInput.text.text.indexOf(marker)
        assertTrue("The rendered message must contain $marker", markerOffset >= 0)
        val firstLine = layout.getLineForOffset(markerOffset)
        val lastLine = layout.getLineForOffset(markerOffset + marker.length - 1)
        val lineTop = layout.getLineTop(firstLine)
        val lineBottom = layout.getLineBottom(lastLine)
        val scroll = compose.onNode(hasScrollAction())
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        assertTrue("Viewport $viewport must fit the complete source/information lines (${lineBottom - lineTop}px)",
            viewport.height >= lineBottom - lineTop)
        val scrollBy = text.fetchSemanticsNode().positionInRoot.y + lineTop - viewport.top
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollBy) }
        compose.waitForIdle()
        val textTop = text.fetchSemanticsNode().positionInRoot.y
        val visible = scroll.fetchSemanticsNode().boundsInRoot
        assertTrue("First $marker line must be fully visible in $visible", textTop + lineTop >= visible.top - 1f)
        assertTrue("Last $marker line must be fully visible in $visible", textTop + lineBottom <= visible.bottom + 1f)
        assertLineExtentsAreVisible(text, layout, "Information containing $marker")
    }

    private fun assertTextFullyVisible(label: String) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val visible = text.fetchSemanticsNode().boundsInRoot
        assertEquals("$label must have its entire laid-out text height visible", layout.size.height.toFloat(), visible.height, 1f)
        assertLineExtentsAreVisible(text, layout, label)
    }

    private fun assertLineExtentsAreVisible(text: SemanticsNodeInteraction, layout: TextLayoutResult, label: String) {
        val node = text.fetchSemanticsNode()
        val visible = node.boundsInRoot
        val origin = node.positionInRoot
        val edges = (0 until layout.lineCount).map { layout.getLineLeft(it) to layout.getLineRight(it) }
        val diagnostics = "$label: size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
            "lineEdges=$edges, position=$origin, visibleBounds=$visible, overflowFlag=${layout.didOverflowWidth}"
        // didOverflowWidth compares the allocated paragraph width against the cached IntSize,
        // which may shrink to intrinsic width. Check actual line extents against the visible
        // text viewport instead, allowing at most one physical pixel for layout rounding.
        for ((line, edge) in edges.withIndex()) {
            assertTrue("Line $line begins outside the visible width; $diagnostics", origin.x + edge.first >= visible.left - 1f)
            assertTrue("Line $line ends outside the visible width; $diagnostics", origin.x + edge.second <= visible.right + 1f)
            assertFalse("Line $line must not be ellipsized; $diagnostics", layout.isLineEllipsized(line))
        }
    }
}
