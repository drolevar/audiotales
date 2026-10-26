package io.github.drolevar.audiotales.data.library

import io.github.drolevar.audiotales.data.online.CatalogueBook
import org.junit.Assert.assertEquals
import org.junit.Test

class BookTitlesTest {
    @Test fun actualCyrillicProviderTitleDisplaysWithoutDuplicatedAuthorAndRetainsRawMetadata() {
        val rawTitle = "Фоллетт Кен - Игольное ушко"
        val rawAuthor = "Фоллетт Кен"
        val catalogue = CatalogueBook("provider", "86717", rawTitle, rawAuthor, coverUrl = null, sourceUrl = null)
        val saved = LibraryBook("historical-book", rawTitle, rawAuthor, "provider", "86717")

        assertEquals("Игольное ушко", catalogue.displayTitle)
        assertEquals("Игольное ушко", saved.displayTitle)
        assertEquals(rawTitle, catalogue.title)
        assertEquals(rawTitle, saved.title)
        assertEquals(rawAuthor, catalogue.author)
        assertEquals(rawAuthor, saved.author)
        assertEquals("86717", catalogue.remoteId)
        assertEquals("86717", saved.providerBookId)
        assertEquals("historical-book", saved.id)
    }

    @Test fun matchingNormalizesCaseAndWhitespaceIncludingNbspForAllThreeDashes() {
        for (dash in listOf("-", "–", "—")) {
            assertEquals("Игольное ушко", bookDisplayTitle("  ФОЛЛЕТТ\tКЕН\u00a0$dash\u00a0Игольное ушко  ", " Фоллетт\u00a0 Кен "))
        }
    }

    @Test fun authorHyphensAndSpacedDashesAreMatchedAsPartOfTheWholeAuthor() {
        assertEquals("A Story", bookDisplayTitle("Mary-Jane Smith - A Story", "Mary-Jane Smith"))
        assertEquals("A Story", bookDisplayTitle("Mary - Jane Smith – A Story", "Mary - Jane Smith"))
        assertEquals("A Story", bookDisplayTitle("Doe, Jane — A Story", "Doe, Jane"))
    }

    @Test fun ambiguousOrMissingMatchesPreserveTheExactOriginalTitle() {
        val cases = listOf(
            " Author - Story " to null,
            " Author - Story " to "",
            " Author - Story " to " \u00a0 ",
            " Other Author - Story " to "Author",
            "Author Name Extra - Story" to "Author Name",
            "Author Name - Story" to "Author",
            "Name Author - Story" to "Author Name",
            "Author: Story" to "Author",
            "Author- Story" to "Author",
            "Author -Story" to "Author",
            "Author -- Story" to "Author",
            "Author - \u00a0 " to "Author",
            "First Author - Story" to "First Author; Second Author",
            "Doe Jane - Story" to "Doe, Jane",
        )
        for ((title, author) in cases) assertEquals("title=$title author=$author", title, bookDisplayTitle(title, author))
    }

    @Test fun removalPreservesSuffixCaseInternalWhitespaceAndPunctuationAndRemovesOnlyOnePrefix() {
        assertEquals("Well-known — A  Story!", bookDisplayTitle("Author - Well-known — A  Story!", "Author"))
        assertEquals("Author - Story", bookDisplayTitle("Author - Author - Story", "Author"))
    }

    @Test fun anExactCompleteMultiAuthorPrefixCanBeRemovedWithoutGuessingIndividualNames() {
        assertEquals("Story", bookDisplayTitle("First Author; Second Author - Story", "First Author; Second Author"))
    }

    @Test fun canonicallyEquivalentLatinNamesMatchInBothDirectionsWithoutNormalizingTheSuffix() {
        val suffix = "Cafe\u0301 — A  Story!"
        assertEquals(suffix, bookDisplayTitle("Jose\u0301 - $suffix", "José"))
        assertEquals(suffix, bookDisplayTitle("José – $suffix", "Jose\u0301"))
    }

    @Test fun canonicallyEquivalentCyrillicNamesMatchInBothDirectionsWithCaseAndWhitespaceDifferences() {
        val suffix = "Е\u0308лка и маи\u0306 — рассказ"
        assertEquals(suffix, bookDisplayTitle("  СЕРГЕИ\u0306\u00a0ФЕ\u0308ДОРОВ — $suffix", "Сергей Фёдоров"))
        assertEquals(suffix, bookDisplayTitle("Сергей Фёдоров - $suffix", "Сергеи\u0306\tФе\u0308доров"))
    }

    @Test fun canonicalComparisonPreservesExactRawFallbackAndDoesNotStripDiacriticsOrPartialNames() {
        val cases = listOf(
            " Jose\u0301 - \u00a0 " to "José",
            " Jose\u0301: Story " to "José",
            " Jose - Story " to "José",
            " Jose\u0301 Extra - Story " to "José",
        )
        for ((title, author) in cases) assertEquals(title, bookDisplayTitle(title, author))
    }
}
