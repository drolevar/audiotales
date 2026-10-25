package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.library.LibraryTrack
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal object LibraryProtocolParser {
    fun httpsUrl(input: String): HttpUrl {
        if (input.length > 4096) unexpectedProtocol()
        val uri = try { URI(input) } catch (_: Exception) { unexpectedProtocol() }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.port !in listOf(-1, 443) || uri.fragment != null ||
            Regex("(?i)%2f|%5c|%2e|\\\\").containsMatchIn(uri.rawPath.orEmpty()) ||
            uri.path.orEmpty().split('/').any { it == "." || it == ".." }
        ) unexpectedProtocol()
        return input.toHttpUrlOrNull() ?: unexpectedProtocol()
    }

    fun baseUrl(input: String): HttpUrl {
        val url = try { httpsUrl(input.trim()) } catch (_: OnlineBookException) {
            throw OnlineBookException(OnlineBookError.INVALID_LINK)
        }
        if (url.query != null) throw OnlineBookException(OnlineBookError.INVALID_LINK)
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
    }

    fun libraryId(base: HttpUrl): String = "library-${digest(base.toString())}"
    private fun bookId(source: OnlineLibrary, remoteId: String) = "${source.id}:$remoteId"
    fun requireId(value: String): String = value.takeIf { Regex("[1-9][0-9]{0,19}").matches(it) }
        ?: unexpectedProtocol()

    fun catalogue(source: OnlineLibrary, text: String): List<CatalogueBook> {
        val root = parse(text)
        val rows = when (source.protocol) {
            LibraryProtocol.PARTNER -> root as? JsonArray ?: unexpectedProtocol()
            LibraryProtocol.LIBRIVOX -> {
                val body = root as? JsonObject ?: unexpectedProtocol()
                // LibriVox uses an error object for an empty search (verified as "Audiobooks could not be found").
                if (body.text("error") == "Audiobooks could not be found") return emptyList()
                body["books"] as? JsonArray ?: unexpectedProtocol()
            }
        }
        if (rows.size > 500) unexpectedProtocol()
        val books = rows.map { element ->
            val item = element as? JsonObject ?: unexpectedProtocol()
            when (source.protocol) {
                LibraryProtocol.PARTNER -> partnerBook(source, item)
                LibraryProtocol.LIBRIVOX -> librivoxBook(source, item)
            }
        }
        if (books.map { it.remoteId }.distinct().size != books.size) unexpectedProtocol()
        return books
    }

    fun partnerBook(source: OnlineLibrary, item: JsonObject): CatalogueBook {
        val cover = item.text("cover")?.takeIf { it.isNotBlank() }?.let { cover ->
            val base = source.baseUrl.toHttpUrlOrNull() ?: unexpectedProtocol()
            val path = if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(cover)) cover
                else item.text("img_dir").orEmpty().trimEnd('/') + "/" + cover.trimStart('/')
            base.resolve(path)?.toString()?.let(::optionalImage)
        }
        return CatalogueBook(
            source.id, requireId(item.requiredText("id")), title(item.requiredText("namebook")),
            item.text("author")?.let { clean(it, 1000) },
            item.text("short_story")?.let { clean(it, 100_000) }, cover, null, source.baseUrl,
        )
    }

    fun partnerResolution(source: OnlineLibrary, book: CatalogueBook, playlist: String): ResolvedOnlineBook {
        val entries = parse(playlist) as? JsonArray ?: unexpectedProtocol()
        if (entries.isEmpty()) throw OnlineBookException(OnlineBookError.EMPTY_PLAYLIST)
        if (entries.size > 2000) unexpectedProtocol()
        val id = bookId(source, book.remoteId)
        val references = mutableSetOf<String>()
        val tracks = entries.mapIndexed { index, element ->
            val item = element as? JsonObject ?: unexpectedProtocol()
            // Clipping semantics are unverified; do not silently play a different recording range.
            if (item.containsKey("start") && item.text("start")?.toDoubleOrNull() != 0.0) unexpectedProtocol()
            val uri = httpsUrl(item.requiredText("file"))
            if (!uri.encodedPath.endsWith(".mp3", ignoreCase = true)) {
                throw OnlineBookException(OnlineBookError.UNSUPPORTED_SOURCE)
            }
            val reference = uri.encodedPath
            if (!references.add(reference)) unexpectedProtocol()
            LibraryTrack(
                id = "$id:${digest(reference)}", bookId = id,
                title = title(item.requiredText("title")), uri = uri.toString(), sequenceIndex = index,
                providerTrackRef = reference, streamReferer = source.baseUrl + "mobapp", downloadReferer = source.baseUrl,
            )
        }
        return ResolvedOnlineBook(id, source.id, book.remoteId, source.baseUrl, book.title, book.author, tracks,
            book.coverUrl, book.coverReferer, description = book.description)
    }

    /** The artwork lookup validates one provider record without requiring a playlist or sections. */
    fun librivoxMetadata(source: OnlineLibrary, remoteId: String, text: String): CatalogueBook =
        librivoxBook(source, librivoxItem(remoteId, text))

    private fun librivoxItem(remoteId: String, text: String): JsonObject {
        val rows = objectValue(text)["books"] as? JsonArray ?: unexpectedProtocol()
        val item = rows.singleOrNull() as? JsonObject ?: unexpectedProtocol()
        if (requireId(item.requiredText("id")) != remoteId) unexpectedProtocol()
        return item
    }

    fun librivoxResolution(source: OnlineLibrary, remoteId: String, text: String): ResolvedOnlineBook {
        val item = librivoxItem(remoteId, text)
        val book = librivoxBook(source, item)
        val id = bookId(source, remoteId)
        val sections = item["sections"] as? JsonArray ?: unexpectedProtocol()
        val count = item.text("num_sections")?.toIntOrNull() ?: unexpectedProtocol()
        if (sections.isEmpty() || sections.size != count || count > 2000) unexpectedProtocol()
        val sorted = sections.map { it as? JsonObject ?: unexpectedProtocol() }.sortedBy {
            it.requiredText("section_number").toIntOrNull()?.takeIf { n -> n >= 0 } ?: unexpectedProtocol()
        }
        val refs = mutableSetOf<String>()
        val ordinals = mutableSetOf<Int>()
        val tracks = sorted.mapIndexed { index, section ->
            val ref = requireId(section.requiredText("id"))
            val ordinal = section.requiredText("section_number").toInt()
            if (!refs.add(ref) || !ordinals.add(ordinal)) unexpectedProtocol()
            val seconds = section.text("playtime")?.toLongOrNull()?.takeIf { it in 0..604800 }
            LibraryTrack(
                id = "$id:$ref", bookId = id, title = title(section.requiredText("title")),
                uri = httpsUrl(section.requiredText("listen_url")).toString(), sequenceIndex = index,
                durationMs = seconds?.times(1000), providerTrackRef = ref,
            )
        }
        return ResolvedOnlineBook(id, source.id, remoteId, book.sourceUrl ?: unexpectedProtocol(), book.title, book.author, tracks,
            book.coverUrl, book.coverReferer, description = book.description, language = book.language)
    }

    private fun librivoxBook(source: OnlineLibrary, item: JsonObject): CatalogueBook {
        val authors = item["authors"] as? JsonArray
        val author = authors?.joinToString(", ") { element ->
            val person = element as? JsonObject ?: unexpectedProtocol()
            listOfNotNull(person.text("first_name"), person.text("last_name")).joinToString(" ")
        }?.takeIf { it.isNotBlank() }
        // Catalogue rows can precede their published page. They remain selectable by
        // provider ID; full resolution below still requires a verified canonical page.
        val page = item.text("url_librivox")?.takeIf { it.isNotBlank() }?.let(::httpsUrl)
        if (page != null && page.host != source.baseUrl.toHttpUrlOrNull()?.host) unexpectedProtocol()
        return CatalogueBook(source.id, requireId(item.requiredText("id")), title(item.requiredText("title")),
            author?.let { clean(it, 1000) }, item.text("description")?.let { clean(it, 100_000) },
            item.text("coverart_jpg")?.let(::optionalImage)
                ?: item.text("coverart_thumbnail")?.let(::optionalImage), page?.toString(),
            language = item.text("language")?.let { clean(it, 1000) }?.takeIf { it.isNotBlank() })
    }

    fun objectValue(text: String): JsonObject = parse(text) as? JsonObject ?: unexpectedProtocol()
    private fun parse(text: String): JsonElement = try { Json.parseToJsonElement(text) }
    catch (_: SerializationException) { unexpectedProtocol() }
    private fun title(text: String): String = clean(text, 1000).takeIf { it.isNotBlank() } ?: unexpectedProtocol()
    private fun clean(text: String, limit: Int): String {
        if (text.length > limit) unexpectedProtocol()
        return Jsoup.parse(text).text().trim()
    }
    private fun optionalImage(text: String): String? = try { httpsUrl(text).toString() }
    catch (_: OnlineBookException) { null }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.requiredText(key: String): String = text(key)?.takeIf { it.isNotBlank() } ?: unexpectedProtocol()
internal fun unexpectedProtocol(): Nothing = throw OnlineBookException(OnlineBookError.UNEXPECTED_FORMAT)
