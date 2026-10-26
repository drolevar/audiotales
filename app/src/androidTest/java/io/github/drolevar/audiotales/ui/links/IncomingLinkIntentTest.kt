package io.github.drolevar.audiotales.ui.links

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingLinkIntentTest {
    @Test fun textSharingAndViewingExtractOnlyApprovedScalarInput() {
        val url = "audiotales://book?link=provider.example&id=42"
        val nested = Intent(Intent.ACTION_VIEW, Uri.parse("file:///arbitrary.mp3"))
        assertEquals(url, IncomingLinkIntent.text(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            putExtra(Intent.EXTRA_INTENT, nested)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }))
        assertEquals("Назва\n$url", IncomingLinkIntent.text(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Назва\n$url"); putExtra(Intent.EXTRA_STREAM, Uri.parse("content://arbitrary"))
        }))
        assertNull(IncomingLinkIntent.text(Intent(Intent.ACTION_SEND).apply { type = "audio/mpeg"; putExtra(Intent.EXTRA_TEXT, url) }))
        assertNull(IncomingLinkIntent.text(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }))
    }

    @Test fun missingWrongTypeAndOversizedTextAreRejectedWithoutThrowing() {
        assertEquals("", IncomingLinkIntent.text(Intent(Intent.ACTION_VIEW)))
        assertEquals("", IncomingLinkIntent.text(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, 42) }))
        assertEquals("", IncomingLinkIntent.text(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "x".repeat(20_000)) }))
    }

    @Test fun manifestOnlyAdvertisesTextSharesCustomSchemeAndKnownHttpsProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun matches(intent: Intent): Boolean = context.packageManager.queryIntentActivities(
            intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY,
        ).any { it.activityInfo.name.endsWith(".MainActivity") }
        assertTrue(matches(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertFalse(matches(Intent(Intent.ACTION_SEND).setType("audio/mpeg")))
        for (url in listOf("audiotales://book?link=provider.example&id=42",
            "audiotales:///TabScreen/Favorite/Review?link=audioknigi.com.ua&id=42",
            "https://audioknigi.com.ua/api?link=audioknigi.com.ua&id=42",
            "https://akniga.org/audio-42-book", "https://librivox.club/audio/42-book", "https://librivox.org/a-book/")) {
            assertTrue(url, matches(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)))
        }
        for (url in listOf("http://audioknigi.com.ua/audio-42-book", "https://configured.example/audio-42-book",
            "https://audioknigi.com.ua/about", "file:///arbitrary.mp3", "content://media/book")) {
            assertFalse(url, matches(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)))
        }
    }
}
