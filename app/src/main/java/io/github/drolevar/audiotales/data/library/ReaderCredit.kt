package io.github.drolevar.audiotales.data.library

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

data class ReaderCredit(val providerReaderId: String, val displayName: String)

/** A book-wide credit is safe only when every section has the same complete attribution. */
internal fun commonReaderCredits(tracks: List<LibraryTrack>): List<ReaderCredit>? {
    val first = tracks.firstOrNull()?.readerCredits?.takeIf { it.isNotEmpty() } ?: return null
    return first.takeIf { tracks.all { track -> track.readerCredits == first } }
}

internal fun readerCreditNames(credits: List<ReaderCredit>?): String? =
    credits?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.displayName }

/** One bounded codec preserves unknown (null) versus explicitly empty ([]) credits. */
internal object ReaderCreditsCodec {
    private const val MAX_CREDITS = 32
    private const val MAX_JSON_LENGTH = 65_536

    private fun normalize(credits: List<ReaderCredit>?, html: Boolean = false): List<ReaderCredit>? {
        if (credits == null || credits.size > MAX_CREDITS) return null
        val normalized = credits.map { credit ->
            val id = credit.providerReaderId.trim()
            if (id.isEmpty() || id.length > 64 || id.any { it.isISOControl() }) return null
            if (credit.displayName.length > 1000) return null
            val name = (if (html) Jsoup.parse(credit.displayName).text() else credit.displayName)
                .replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ").trim()
            if (name.isEmpty() || name.length > 200) return null
            ReaderCredit(id, name)
        }
        return normalized.distinct()
    }

    fun encode(credits: List<ReaderCredit>?): String? = normalize(credits)?.let { normalized ->
        JsonArray(normalized.map { credit -> JsonObject(mapOf(
            "providerReaderId" to JsonPrimitive(credit.providerReaderId),
            "displayName" to JsonPrimitive(credit.displayName),
        )) }).toString()
    }

    fun decode(json: String?): List<ReaderCredit>? {
        if (json == null || json.length > MAX_JSON_LENGTH) return null
        val value = try { Json.parseToJsonElement(json) } catch (_: SerializationException) { return null }
        return read(value, "providerReaderId", "displayName", html = false)
    }

    /** Optional provider fields must never make a valid book or playlist fail. */
    fun fromProviderJson(value: JsonElement?): List<ReaderCredit>? =
        read(value, "reader_id", "display_name", html = true)

    private fun read(value: JsonElement?, idKey: String, nameKey: String, html: Boolean): List<ReaderCredit>? {
        val array = value as? JsonArray ?: return null
        if (array.size > MAX_CREDITS) return null
        val credits = array.map { element ->
            val person = element as? JsonObject ?: return null
            val id = person[idKey] as? JsonPrimitive ?: return null
            val name = person[nameKey] as? JsonPrimitive ?: return null
            if (!id.isString || !name.isString) return null
            ReaderCredit(id.content, name.content)
        }
        return normalize(credits, html)
    }
}
