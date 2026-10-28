package io.github.drolevar.audiotales

import android.app.LocaleConfig
import android.content.res.Configuration
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    private fun resources(language: String) = InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }).resources
    }

    @Test
    fun regionalLanguagesResolveAppAndNativeControlsAndUnknownLanguagesFallBackToEnglish() {
        val english = resources("en")
        val nativeSeek = androidx.media3.ui.compose.material3.R.plurals.seek_back_by_amount_button
        for ((tag, title) in mapOf("ru-RU" to "Мои книги", "uk-UA" to "Мої книги", "nb-NO" to "Bøkene mine")) {
            val localized = resources(tag)
            assertEquals(title, localized.getString(R.string.my_books))
            assertEquals("AudioTales", localized.getString(R.string.app_name))
            assertNotEquals(english.getQuantityString(nativeSeek, 15, 15), localized.getQuantityString(nativeSeek, 15, 15))
        }
        assertEquals("My books", resources("de-DE").getString(R.string.my_books))
    }

    @Test
    fun slavicBookCountsAndSavedTrackTotalsUseTheirOwnPluralForms() {
        val expected = listOf("0 книг", "1 книга", "2 книги", "5 книг", "11 книг", "21 книга", "22 книги", "25 книг", "101 книга", "111 книг")
        val counts = listOf(0, 1, 2, 5, 11, 21, 22, 25, 101, 111)
        for (tag in listOf("ru", "uk")) {
            val localized = resources(tag)
            for ((count, text) in counts.zip(expected)) {
                assertEquals(text, localized.getQuantityString(R.plurals.library_book_count, count, count))
            }
            val singular = if (tag == "ru") "трека" else "доріжки"
            val plural = if (tag == "ru") "треков" else "доріжок"
            assertTrue(localized.getQuantityString(R.plurals.player_download_status, 21, 1, 21).endsWith(singular))
            assertTrue(localized.getQuantityString(R.plurals.player_download_status, 22, 1, 22).endsWith(plural))
        }
        assertEquals("1 bok", resources("nb-NO").getQuantityString(R.plurals.library_book_count, 1, 1))
        assertEquals("2 bøker", resources("nb-NO").getQuantityString(R.plurals.library_book_count, 2, 2))
    }

    @Test
    fun formatArgumentsKeepProviderNamesAndLiteralPercentCharactersIntact() {
        val name = "Ліс & Гай — 100%"
        for (language in listOf("en", "ru", "uk", "nb")) {
            val localized = resources(language)
            assertTrue(localized.getString(R.string.car_catalogue_more_from_library, name).contains(name))
            assertTrue(localized.getString(R.string.car_catalogue_retry_library, name).contains(name))
            assertTrue(localized.getString(R.string.downloads_remove_message, name).contains(name))
            assertEquals("X · 42%", localized.getString(R.string.downloads_percent, "X", 42))
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun androidAppLanguageSettingsExposeOnlyCompleteTranslations() {
        if (Build.VERSION.SDK_INT < 33) return
        val config = LocaleConfig(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        val locales = requireNotNull(config.supportedLocales)
        assertEquals(setOf("en", "ru", "uk", "nb"), (0 until locales.size()).map { locales[it].toLanguageTag() }.toSet())
    }
}
