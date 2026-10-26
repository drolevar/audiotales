package io.github.drolevar.audiotales.data.online

import org.junit.Assert.*
import org.junit.Test

class IncomingBookLinkParserTest {
    private val expected = IncomingBookLink("https://audioknigi.com.ua/", "102023")

    @Test fun suppliedShareAndUnicodeTitleResolveToTheSameIdentity() {
        val url = "https://audioknigi.com.ua/api?link=audioknigi.com.ua&id=102023"
        assertEquals(expected, IncomingBookLinkParser.parseSharedText(url))
        assertEquals(expected, IncomingBookLinkParser.parseSharedText("Марія — Історія 📚\n$url"))
        assertEquals(expected, IncomingBookLinkParser.parseSharedText("Title:Book One\u00a0$url\u2003"))
        assertEquals(expected, IncomingBookLinkParser.parseSharedText(url.replace("https:", "HTTPS:")))
        assertEquals(expected, IncomingBookLinkParser.parseSharedText("$url\n$url"))
    }

    @Test fun legacyCanonicalAndPartnerPathsResolveLocallyWithoutAllowlistingHosts() {
        for (url in listOf(
            "audiotales:///TabScreen/Favorite/Review?link=audioknigi.com.ua&id=102023",
            "audiotales://book?link=audioknigi.com.ua&id=102023",
            "https://audioknigi.com.ua/audio-102023-title/",
            "https://audioknigi.com.ua/audio/102023-title",
        )) assertEquals(url, expected, IncomingBookLinkParser.parseUrl(url))
        assertEquals(IncomingBookLink("https://configured.example/", "42"),
            IncomingBookLinkParser.parseUrl("https://configured.example/api?link=configured.example&id=42"))
    }

    @Test fun librivoxPageKeepsItsPageIdentityUntilAnEnabledSourceResolvesRss() {
        assertEquals(IncomingBookLink("https://librivox.org/", pageUrl = "https://librivox.org/book-by-author/"),
            IncomingBookLinkParser.parseUrl("https://librivox.org/book-by-author/"))
    }

    @Test fun rejectsAmbiguousMissingDuplicateAndTransportAuthorityInputs() {
        val good = "https://audioknigi.com.ua/api?link=audioknigi.com.ua&id=102023"
        for (bad in listOf(
            "", "Some book title", "$good\nhttps://other.example/audio-43-title", "$good\nfile:///tmp/book",
            "$good&id=8", "$good&%69d=8", "$good&url=https://evil.example", "$good#x",
            good.replace("link=audioknigi.com.ua", "link=other.example"),
            good.replace("link=audioknigi.com.ua", "link=https%3A%2F%2Faudioknigi.com.ua"),
            good.replace("link=audioknigi.com.ua", "link=audioknigi.com.ua%3A443"),
            good.replace("102023", "0"), good.replace("102023", "-1"), good.replace("102023", "01"),
            good.replace("102023", "1e6"), good.replace("102023", "1".repeat(21)),
            good.substringBefore("&id"), good.replace("id=102023", "id="),
            good.replace("https://", "https://user@"), good.replace(".ua/api", ".ua:443/api"),
            good.replace("/api", "/%2e%2e/api"), good.replace("/api", "/../api"), good.replace("/api", "/x%2fapi"),
            "https://audioknigi.com.ua//api?link=audioknigi.com.ua&id=102023",
            "https://audioknigi.com.ua/audio/102023-title/extra", "https://audioknigi.com.ua/audio-102023-title?file=x",
            "https://evil.example@audioknigi.com.ua/audio-42-title", "https://audioknigi.com.ua\\@evil.example/audio-42-title",
            "https://librivox.org/book#rss", "https://librivox.org/book?rss=42",
            "audiotales://TabScreen/Favorite/Review?link=audioknigi.com.ua&id=42",
            "audiotales:///Other?link=audioknigi.com.ua&id=42", "audiotales://evil/book?link=audioknigi.com.ua&id=42",
            "intent://book?link=audioknigi.com.ua&id=42", "javascript:alert(1)", "file:///tmp/book.mp3", "content://media/book", "http://audioknigi.com.ua/audio-42-title",
            "x".repeat(IncomingBookLinkParser.MAX_TEXT_LENGTH + 1),
            "https://audioknigi.com.ua/audio-42-" + "x".repeat(IncomingBookLinkParser.MAX_URL_LENGTH),
        )) {
            try { IncomingBookLinkParser.parseSharedText(bad); fail("Accepted: $bad") }
            catch (error: OnlineBookException) { assertEquals(bad, OnlineBookError.INVALID_LINK, error.reason) }
        }
    }
}
