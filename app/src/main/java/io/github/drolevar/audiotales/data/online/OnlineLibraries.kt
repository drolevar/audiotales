package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.BuildConfig
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryRepository
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook
import io.github.drolevar.audiotales.data.library.validArtworkUrl
import io.github.drolevar.audiotales.data.library.bookDisplayTitle
import io.github.drolevar.audiotales.data.local.LibraryDao
import io.github.drolevar.audiotales.data.local.OnlineLibraryEntity
import io.github.drolevar.audiotales.data.local.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

enum class LibraryProtocol { PARTNER, LIBRIVOX }

data class OnlineLibrary(
    val id: String,
    val baseUrl: String,
    val displayName: String,
    val protocol: LibraryProtocol,
    val isSelected: Boolean = true,
)
data class CatalogueBook(
    val libraryId: String,
    val remoteId: String,
    val title: String,
    val author: String?,
    /** Null means not provided by the source; empty means explicitly provided without text. */
    val description: String? = null,
    val coverUrl: String?,
    val sourceUrl: String?,
    val coverReferer: String? = null,
    val language: String? = null,
) {
    val displayTitle: String get() = bookDisplayTitle(title, author)
}
data class CataloguePage(val books: List<CatalogueBook>, val nextOffset: Int?)

/** Library instances are persisted separately from the protocol used to communicate with them. */
@Singleton
internal class OnlineLibraries internal constructor(
    private val dao: LibraryDao,
    private val library: LibraryRepository,
    private val http: ProviderHttpClient,
    private val partnerClientQuery: String,
) : OnlineCatalogue, OnlineBookDetailsSource {
    @Inject constructor(dao: LibraryDao, library: LibraryRepository, http: ProviderHttpClient) :
        this(dao, library, http, BuildConfig.PARTNER_CLIENT_QUERY)
    private val artworkRefreshMutex = Mutex()
    // Rotate within this application process, including past permanently coverless records.
    private var artworkRefreshCursor: String? = null

    override fun observeLibraries(): Flow<List<OnlineLibrary>> = dao.observeOnlineLibraries().map { rows ->
        rows.map { it.toLibrary() }
    }

    override suspend fun addLibrary(input: String): OnlineLibrary = withContext(Dispatchers.IO) {
        val base = LibraryProtocolParser.baseUrl(input)
        val id = LibraryProtocolParser.libraryId(base)
        dao.getOnlineLibraryByBaseUrl(base.toString())?.takeIf { it.isRegistered && it.isSelected }?.let {
            return@withContext dao.registerOnlineLibrary(it).toLibrary()
        }
        // LibriVox has a separate documented public protocol. Partner instances share one adapter.
        val protocol = if (base.host == "librivox.org") LibraryProtocol.LIBRIVOX else LibraryProtocol.PARTNER
        val candidate = OnlineLibrary(id, base.toString(), base.host, protocol)
        // Persist only after a correctly shaped catalogue response, never HTTP success alone.
        catalogue(candidate, "", 0)
        currentCoroutineContext().ensureActive()
        dao.registerOnlineLibrary(OnlineLibraryEntity(id, base.toString(), base.host, protocol.name)).toLibrary()
    }

    override suspend fun browse(libraryId: String, query: String, offset: Int): CataloguePage =
        withContext(Dispatchers.IO) {
            if (offset < 0 || query.length > 300) invalid()
            catalogue(getLibrary(libraryId), query.trim(), offset)
        }

    override suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition = withContext(Dispatchers.IO) {
        val source = getLibrary(libraryId)
        LibraryProtocolParser.requireId(remoteId)
        resolveAndSave(source, remoteId)
    }

    override suspend fun inspectBook(libraryId: String, remoteId: String): ResolvedOnlineBook = withContext(Dispatchers.IO) {
        LibraryProtocolParser.requireId(remoteId)
        resolve(getLibrary(libraryId), remoteId)
    }

    override suspend fun inspectBookMetadata(libraryId: String, remoteId: String): LibraryBook = withContext(Dispatchers.IO) {
        LibraryProtocolParser.requireId(remoteId)
        val source = getLibrary(libraryId)
        val details = metadata(source, remoteId)
        LibraryBook("${source.id}:$remoteId", details.title, details.author, source.id, remoteId,
            details.sourceUrl ?: source.baseUrl, details.coverUrl, details.coverReferer, details.description, details.language)
    }

    override suspend fun inspectBookAudio(book: LibraryBook): ResolvedOnlineBook = withContext(Dispatchers.IO) {
        val remoteId = LibraryProtocolParser.requireId(book.providerBookId ?: unexpectedProtocol())
        val source = getLibrary(book.providerId ?: unsupportedLibrary())
        // Provider identity selects transport. A historical saved ID or caller URL is not authority.
        when (source.protocol) {
            LibraryProtocol.PARTNER -> resolvePartnerAudio(source, CatalogueBook(source.id, remoteId,
                book.title, book.author, book.description, book.coverUrl, source.baseUrl, book.coverReferer, book.language))
            LibraryProtocol.LIBRIVOX -> resolveLibrivox(source, remoteId)
        }
    }

    override suspend fun setLibrarySelected(id: String, selected: Boolean): Unit = withContext(Dispatchers.IO) {
        if (dao.setOnlineLibrarySelected(id, selected) != 1) unsupportedLibrary()
    }

    override suspend fun renameLibrary(id: String, name: String): Unit = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 100) { "Library name must contain 1 to 100 characters" }
        if (dao.renameOnlineLibrary(id, trimmed) != 1) unsupportedLibrary()
    }

    override suspend fun removeLibrary(id: String): Unit = withContext(Dispatchers.IO) {
        dao.removeOnlineLibrary(id)
        Unit
    }

    private suspend fun resolveAndSave(source: OnlineLibrary, remoteId: String, expectedPage: String? = null): LibraryAddition {
        val resolved = resolve(source, remoteId)
        if (expectedPage != null && resolved.sourceUrl.trimEnd('/') != expectedPage.trimEnd('/')) unexpectedProtocol()
        return library.addOnlineBook(resolved)
    }

    /** One metadata request; never renews tracks or fetches a playlist/audio body. */
    suspend fun refreshBookArtwork(bookId: String): LibraryBook? = withContext(Dispatchers.IO) {
        val book = library.getBook(bookId) ?: return@withContext null
        val providerId = book.providerId ?: return@withContext null
        val remoteId = book.providerBookId ?: return@withContext null
        LibraryProtocolParser.requireId(remoteId)
        val source = getLibrary(providerId)
        val metadata = metadata(source, remoteId)
        if (metadata.libraryId != providerId || metadata.remoteId != remoteId) unexpectedProtocol()
        val cover = metadata.coverUrl ?: return@withContext book.takeIf { it.coverUrl != null }
        // Match both stored identities so this cannot apply another recording's artwork.
        if (dao.updateBookArtwork(bookId, providerId, remoteId, cover, metadata.coverReferer) != 1) unexpectedProtocol()
        library.getBook(bookId)
    }

    /** A single metadata response updates optional details without inspecting or replacing tracks. */
    override suspend fun refreshBookMetadata(bookId: String): LibraryBook? = withContext(Dispatchers.IO) {
        val book = library.getBook(bookId) ?: return@withContext null
        val providerId = book.providerId ?: return@withContext book
        val remoteId = book.providerBookId ?: return@withContext book
        LibraryProtocolParser.requireId(remoteId)
        val details = metadata(getLibrary(providerId), remoteId)
        if (details.libraryId != providerId || details.remoteId != remoteId) unexpectedProtocol()
        val cover = validArtworkUrl(details.coverUrl)
        if (dao.updateBookMetadata(bookId, providerId, remoteId, details.description, details.language,
                cover, cover?.let { validArtworkUrl(details.coverReferer) }) != 1) unexpectedProtocol()
        library.getBook(bookId)
    }

    /** One rotating, bounded snapshot per invocation. Failures do not starve later books. */
    suspend fun refreshMissingArtwork(): Unit = withContext(Dispatchers.IO) {
        artworkRefreshMutex.withLock {
            val missing = dao.getBooksMissingArtwork(limit = 20, afterBookId = artworkRefreshCursor)
            for (book in missing) {
                currentCoroutineContext().ensureActive()
                try {
                    refreshBookArtwork(book.id)
                } catch (_: OnlineBookException) {
                    // Keep the placeholder, but advance so later records also get a turn.
                }
                currentCoroutineContext().ensureActive()
                artworkRefreshCursor = book.id
            }
        }
    }

    /** Explicit online recovery. Cached playback does not call this method. */
    override suspend fun refreshBookSources(bookId: String): Unit = withContext(Dispatchers.IO) {
        val book = library.getBook(bookId) ?: unexpectedProtocol()
        val source = getLibrary(book.providerId ?: unexpectedProtocol())
        val refreshed = resolve(source, book.providerBookId ?: unexpectedProtocol())
        val stored = library.getTracks(bookId)
        if (stored.isEmpty() || stored.size != refreshed.tracks.size ||
            stored.map { it.providerTrackRef }.toSet() != refreshed.tracks.map { it.providerTrackRef }.toSet()
        ) unexpectedProtocol()
        dao.refreshTrackSources(bookId, refreshed.tracks.map { track ->
            TrackEntity(track.id, bookId, track.title, track.uri, track.sequenceIndex, track.durationMs,
                track.providerTrackRef, track.streamReferer, track.downloadReferer,
                track.sourceStartMs, track.sourceEndMs, track.audioResourceId)
        })
    }

    private suspend fun resolve(source: OnlineLibrary, remoteId: String): ResolvedOnlineBook {
        LibraryProtocolParser.requireId(remoteId)
        return when (source.protocol) {
            LibraryProtocol.PARTNER -> resolvePartner(source, remoteId)
            LibraryProtocol.LIBRIVOX -> resolveLibrivox(source, remoteId)
        }
    }

    suspend fun addBookLink(input: String): LibraryAddition = withContext(Dispatchers.IO) {
        val url = LibraryProtocolParser.httpsUrl(input.trim())
        if (url.query != null || url.fragment != null) invalid()
        val base = url.newBuilder().encodedPath("/").build()
        val source = addLibrary(base.toString())
        val remoteId = if (source.protocol == LibraryProtocol.LIBRIVOX) {
            val html = http.get(url, 1_048_576)
            val document = Jsoup.parse(html, url.toString())
            val rss = document.select("a[href]").mapNotNull { element ->
                url.resolve(element.attr("href"))?.takeIf {
                    it.host == base.host && Regex("/rss/[1-9][0-9]*/?").matches(it.encodedPath)
                }
            }.distinct().singleOrNull() ?: invalid()
            rss.pathSegments.first { it.toLongOrNull() != null }
        } else {
            val match = Regex("/audio(?:/|-)([1-9][0-9]*)-[^/]+/?").matchEntire(url.encodedPath) ?: invalid()
            match.groupValues[1]
        }
        resolveAndSave(source, remoteId, if (source.protocol == LibraryProtocol.LIBRIVOX) url.toString() else null)
    }

    /** Resolve a validated LibriVox page to provider identity without saving or loading audio. */
    suspend fun resolveBookPageId(libraryId: String, page: String): String = withContext(Dispatchers.IO) {
        val source = getLibrary(libraryId)
        val request = IncomingBookLinkParser.parseUrl(page)
        if (source.protocol != LibraryProtocol.LIBRIVOX || request.baseUrl != source.baseUrl || request.pageUrl == null) invalid()
        val url = LibraryProtocolParser.httpsUrl(request.pageUrl)
        val document = Jsoup.parse(http.get(url, 1_048_576), page)
        val rss = document.select("a[href]").mapNotNull { element ->
            val candidate = url.resolve(element.attr("href")) ?: return@mapNotNull null
            try { LibraryProtocolParser.httpsUrl(candidate.toString()) } catch (_: OnlineBookException) { return@mapNotNull null }
            candidate.takeIf { it.host == url.host && it.port == 443 && it.query == null &&
                Regex("/rss/[1-9][0-9]{0,19}/?").matches(it.encodedPath) }
        }.distinct().singleOrNull() ?: invalid()
        val id = LibraryProtocolParser.requireId(rss.pathSegments[1])
        if (metadata(source, id).sourceUrl?.trimEnd('/') != page.trimEnd('/')) unexpectedProtocol()
        id
    }

    override suspend fun getLibrary(libraryId: String): OnlineLibrary {
        if (libraryId.isBlank() || libraryId.length > 1024) unsupportedLibrary()
        return dao.getOnlineLibrary(libraryId)?.toLibrary() ?: unsupportedLibrary()
    }

    private suspend fun catalogue(source: OnlineLibrary, query: String, offset: Int): CataloguePage {
        if (source.protocol == LibraryProtocol.LIBRIVOX && query.isNotBlank()) {
            // Separate searches are required: combining title and author filters would intersect them.
            suspend fun search(field: String): List<CatalogueBook> = catalogueBooks(source,
                librivoxUrl(source).addQueryParameter("limit", "20")
                    .addQueryParameter("offset", offset.toString()).addQueryParameter("sort_order", "desc")
                    .addQueryParameter(field, query).build())
            val titles = search("title")
            val authors = search("author")
            val more = titles.size == 20 || authors.size == 20
            return CataloguePage((titles + authors).distinctBy { it.remoteId },
                if (more) Math.addExact(offset, 20) else null)
        }
        val url = when (source.protocol) {
            LibraryProtocol.PARTNER -> partnerUrl(source, if (query.isBlank()) "favorite" else "search")
                .addQueryParameter("startid", offset.toString())
                .apply { if (query.isNotBlank()) addQueryParameter("text", query) }.build()
            LibraryProtocol.LIBRIVOX -> librivoxUrl(source).addQueryParameter("limit", "20")
                .addQueryParameter("offset", offset.toString()).addQueryParameter("sort_order", "desc")
                .apply { if (query.isNotBlank()) addQueryParameter("title", query) }.build()
        }
        val books = catalogueBooks(source, url)
        // Partner has no total/cursor contract: a nonempty page permits one more user-driven request.
        val more = books.isNotEmpty() && (source.protocol == LibraryProtocol.PARTNER || books.size == 20)
        return CataloguePage(books, if (more) Math.addExact(offset, books.size) else null)
    }

    private suspend fun catalogueBooks(source: OnlineLibrary, url: HttpUrl): List<CatalogueBook> {
        val text = http.get(url, 2_097_152,
            acceptedStatuses = if (source.protocol == LibraryProtocol.LIBRIVOX) setOf(404) else emptySet())
        return LibraryProtocolParser.catalogue(source, text)
    }

    private suspend fun metadata(source: OnlineLibrary, id: String): CatalogueBook {
        val book = when (source.protocol) {
            LibraryProtocol.PARTNER -> {
                val text = http.get(partnerUrl(source, "review").addQueryParameter("storyid", id).build(), 1_048_576)
                LibraryProtocolParser.partnerBook(source, LibraryProtocolParser.objectValue(text))
            }
            LibraryProtocol.LIBRIVOX -> {
                val text = http.get(librivoxUrl(source).addQueryParameter("id", id).build(), 1_048_576)
                LibraryProtocolParser.librivoxMetadata(source, id, text)
            }
        }
        if (book.libraryId != source.id || book.remoteId != id) unexpectedProtocol()
        return book
    }

    private suspend fun resolvePartner(source: OnlineLibrary, id: String): ResolvedOnlineBook {
        val book = metadata(source, id)
        return resolvePartnerAudio(source, book)
    }

    private suspend fun resolvePartnerAudio(source: OnlineLibrary, book: CatalogueBook): ResolvedOnlineBook {
        val story = http.get(partnerUrl(source, "story").addQueryParameter("storyid", book.remoteId).build(), 1_048_576)
        val playlistUrl = LibraryProtocolParser.httpsUrl(LibraryProtocolParser.objectValue(story).requiredText("file"))
        val playlist = http.get(playlistUrl, 2_097_152, source.baseUrl)
        return LibraryProtocolParser.partnerResolution(source, book, playlist)
    }

    private suspend fun resolveLibrivox(source: OnlineLibrary, id: String): ResolvedOnlineBook {
        val text = http.get(librivoxUrl(source).addQueryParameter("id", id)
            .addQueryParameter("extended", "1").build(), 3_145_728)
        return LibraryProtocolParser.librivoxResolution(source, id, text)
    }

    private fun partnerUrl(source: OnlineLibrary, operation: String): HttpUrl.Builder {
        val query = partnerClientQuery
        if (query.isBlank()) throw OnlineBookException(OnlineBookError.ACCESS_NOT_CONFIGURED)
        // Configure the protocol's client parameter locally; never include it in source URLs or errors.
        return source.baseUrl.toHttpUrl().resolve("api")!!.newBuilder()
            .encodedQuery(query).addQueryParameter("func", operation)
    }

    private fun librivoxUrl(source: OnlineLibrary): HttpUrl.Builder = source.baseUrl.toHttpUrl()
        .resolve("api/feed/audiobooks/")!!.newBuilder().addQueryParameter("format", "json")
        .addQueryParameter("coverart", "1")

    private fun OnlineLibraryEntity.toLibrary() = OnlineLibrary(id, baseUrl, displayName, LibraryProtocol.valueOf(protocol), isSelected)
    private fun unsupportedLibrary(): Nothing = throw OnlineBookException(OnlineBookError.UNSUPPORTED_LIBRARY)
    private fun invalid(): Nothing = throw OnlineBookException(OnlineBookError.INVALID_LINK)
}
