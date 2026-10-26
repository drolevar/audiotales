package io.github.drolevar.audiotales.data.library

import java.text.Normalizer

/** Presentation only: provider and persisted titles remain unchanged. */
internal fun bookDisplayTitle(title: String, author: String?): String {
    if (author.isNullOrBlank()) return title
    val expected = authorComparisonKey(author)
    // Keep separator offsets in the original string: NFC can change a prefix's length.
    for (separator in title.indices) {
        if (title[separator] !in "-–—" ||
            title.getOrNull(separator - 1)?.isWhitespace() != true ||
            title.getOrNull(separator + 1)?.isWhitespace() != true
        ) continue
        if (!authorComparisonKey(title.substring(0, separator)).equals(expected, ignoreCase = true)) continue
        val remainder = title.substring(separator + 1).trim()
        return remainder.takeIf(String::isNotBlank) ?: title
    }
    return title
}

private fun authorComparisonKey(value: String): String {
    val spaced = buildString {
        for (character in value.trim()) {
            if (!character.isWhitespace()) append(character)
            else if (lastOrNull() != ' ') append(' ')
        }
    }
    return Normalizer.normalize(spaced, Normalizer.Form.NFC)
}
