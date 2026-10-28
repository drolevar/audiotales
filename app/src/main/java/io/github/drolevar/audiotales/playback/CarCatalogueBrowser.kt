package io.github.drolevar.audiotales.playback

import io.github.drolevar.audiotales.data.online.CatalogueBook
import io.github.drolevar.audiotales.data.online.CataloguePage
import io.github.drolevar.audiotales.data.online.LibraryProtocol
import io.github.drolevar.audiotales.data.online.OnlineCatalogue
import io.github.drolevar.audiotales.data.online.OnlineLibrary
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CarCatalogueItem {
    val mediaId: String
    data class Book(override val mediaId: String, val book: CatalogueBook) : CarCatalogueItem
    data class Folder(override val mediaId: String, val label: CarCatalogueLabel) : CarCatalogueItem
}

/** Cache meaning and library names; resolve app-owned wording in the host's current locale. */
internal sealed interface CarCatalogueLabel {
    data class Retry(val libraryName: String) : CarCatalogueLabel
    data class MoreFrom(val libraryName: String) : CarCatalogueLabel
    data object MoreResults : CarCatalogueLabel
}

/**
 * Service-main-thread-owned catalogue state. Suspending calls serialize reentry; no scopes,
 * controllers, players or saved-library mutations are retained. IDs expire on a new search for
 * the same query, source identity changes, five-minute expiry, or least-recently-used eviction.
 */
internal class CarCatalogueBrowser(private val catalogue: OnlineCatalogue) {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, SearchSession>()

    suspend fun search(query: String): List<CarCatalogueItem> = bounded { mutex.withLock {
        val term = query.trim()
        require(term.length <= MAX_QUERY_LENGTH) { "Search query is too long" }
        expireOldSessions()
        if (term.isEmpty()) return@withLock emptyList()
        // Explicit searches refresh; the session callback owns each controller's result snapshot.
        sessions.remove(term)
        val sources = selectedSources()
        if (sources.isEmpty()) return@withLock emptyList()
        val session = SearchSession(term, sources.map(::sourceIdentity).toSet())
        val permits = Semaphore(MAX_CONCURRENT_PROVIDERS)
        val pages = supervisorScope {
            sources.map { source -> async {
                source to readPage(source, term, 0, permits)
            } }.awaitAll()
        }
        if (pages.all { it.second == null }) throw IOException("All selected catalogues failed")
        val items = pages.flatMap { (source, page) ->
            if (page == null) listOf(session.folder(CarCatalogueLabel.Retry(source.displayName), PageRequest(source, 0)))
            else pageItems(session, source, 0, page)
        }
        val result = present(session, items)
        currentCoroutineContext().ensureActive()
        sessions[term] = session
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        result
    } }

    suspend fun children(mediaId: String): List<CarCatalogueItem>? = bounded { mutex.withLock {
        val (session, node) = find(mediaId) ?: return@withLock null
        val folder = node.item as? CarCatalogueItem.Folder ?: return@withLock null
        revalidate(session)
        node.children?.let { return@withLock it }
        // Stage additions so cancellation cannot leave unseen books marked as already returned.
        val staged = session.staged()
        val children = try {
            when (val request = node.request) {
                is CachedItems -> present(staged, request.items)
                is PageRequest -> {
                    val page = readPage(request.source, session.query, request.offset)
                        ?: throw IOException("Catalogue page failed; reopen this folder to retry")
                    present(staged, pageItems(staged, request.source, request.offset, page))
                }
                null -> error("Folder has no contents: ${folder.mediaId}")
            }
        } catch (capacity: CacheCapacityException) {
            sessions.remove(session.query)
            throw capacity
        }
        currentCoroutineContext().ensureActive()
        session.nodes.putAll(staged.nodes)
        session.books.putAll(staged.books)
        node.children = children
        children
    } }

    fun item(mediaId: String): CarCatalogueItem? = find(mediaId)?.second?.item

    suspend fun selectedBook(mediaId: String): CatalogueBook = bounded { mutex.withLock {
        val (session, node) = requireNotNull(find(mediaId)) { "Unknown or expired catalogue ID" }
        val book = requireNotNull(node.item as? CarCatalogueItem.Book) { "Catalogue ID is not a book" }
        revalidate(session)
        book.book
    } }

    private suspend fun revalidate(session: SearchSession) {
        val currentSources = selectedSources().map(::sourceIdentity).toSet()
        expireOldSessions()
        require(sessions[session.query] === session) { "Catalogue search expired; search again" }
        if (currentSources != session.sources) {
            sessions.remove(session.query)
            throw IllegalArgumentException("Selected catalogues changed; search again")
        }
    }

    private suspend fun selectedSources(): List<OnlineLibrary> =
        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { catalogue.observeLibraries().first() }
            ?.filter { it.isSelected }?.distinctBy { it.id }
            ?: throw IOException("Reading selected catalogues timed out")

    private suspend fun readPage(
        source: OnlineLibrary,
        query: String,
        offset: Int,
        permits: Semaphore? = null,
    ): CataloguePage? = try {
        withTimeoutOrNull(PROVIDER_BUDGET_MS) {
            suspend fun fetch() = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                catalogue.browse(source.id, query, offset)
            }
            val page = (if (permits == null) fetch() else permits.withPermit { fetch() })
                ?: return@withTimeoutOrNull null
            currentCoroutineContext().ensureActive()
            require(page.books.all { it.libraryId == source.id && it.remoteId.isNotBlank() }) {
                "Catalogue returned inconsistent book identities"
            }
            page
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    private suspend fun <T> bounded(block: suspend () -> T): T =
        (withTimeoutOrNull(OVERALL_TIMEOUT_MS) { Completed(block()) }
            ?: throw IOException("Catalogue request timed out; retry")).value

    private class Completed<T>(val value: T)

    private fun pageItems(
        session: SearchSession,
        source: OnlineLibrary,
        offset: Int,
        page: CataloguePage,
    ): List<CarCatalogueItem> {
        val items = mutableListOf<CarCatalogueItem>()
        for (book in page.books.distinctBy { it.libraryId to it.remoteId }) {
            val key = book.libraryId to book.remoteId
            if (key !in session.books) {
                val item = CarCatalogueItem.Book(newId(), book)
                session.register(Node(item))
                session.books[key] = item
                items.add(item)
            }
        }
        page.nextOffset?.takeIf { it > offset }?.let { next ->
            items.add(session.folder(CarCatalogueLabel.MoreFrom(source.displayName), PageRequest(source, next)))
        }
        return items
    }

    private fun present(session: SearchSession, items: List<CarCatalogueItem>): List<CarCatalogueItem> =
        if (items.size <= MAX_CHILDREN) items
        else items.take(MAX_CHILDREN - 1) + session.folder(CarCatalogueLabel.MoreResults, CachedItems(items.drop(MAX_CHILDREN - 1)))

    private fun find(mediaId: String): Pair<SearchSession, Node>? {
        expireOldSessions()
        val session = sessions.values.firstOrNull { mediaId in it.nodes } ?: return null
        // Access updates eviction order but does not extend the five-minute lifetime.
        sessions.remove(session.query)
        sessions[session.query] = session
        return session to requireNotNull(session.nodes[mediaId])
    }

    private fun expireOldSessions() {
        val now = System.nanoTime()
        sessions.entries.removeAll { now - it.value.createdAt >= CACHE_TTL_NANOS }
    }

    private data class SourceIdentity(val id: String, val baseUrl: String, val protocol: LibraryProtocol)
    private fun sourceIdentity(source: OnlineLibrary) = SourceIdentity(source.id, source.baseUrl, source.protocol)
    private sealed interface FolderRequest
    private data class PageRequest(val source: OnlineLibrary, val offset: Int) : FolderRequest
    private data class CachedItems(val items: List<CarCatalogueItem>) : FolderRequest
    private class Node(
        val item: CarCatalogueItem,
        val request: FolderRequest? = null,
        var children: List<CarCatalogueItem>? = null,
    )
    private class SearchSession(val query: String, val sources: Set<SourceIdentity>) {
        val createdAt = System.nanoTime()
        val nodes = linkedMapOf<String, Node>()
        val books = mutableMapOf<Pair<String, String>, CarCatalogueItem.Book>()
        fun staged() = SearchSession(query, sources).also {
            it.nodes.putAll(nodes)
            it.books.putAll(books)
        }
        fun register(node: Node) {
            if (nodes.size >= MAX_NODES_PER_SESSION) throw CacheCapacityException()
            nodes[node.item.mediaId] = node
        }
        fun folder(label: CarCatalogueLabel, request: FolderRequest): CarCatalogueItem.Folder =
            CarCatalogueItem.Folder(newId(), label).also { register(Node(it, request)) }
    }
    private class CacheCapacityException : IOException("Catalogue cache is full; refine or restart the search")
    private companion object {
        const val MAX_QUERY_LENGTH = 300
        const val MAX_CONCURRENT_PROVIDERS = 3
        const val SOURCE_TIMEOUT_MS = 1_500L
        const val PROVIDER_TIMEOUT_MS = 6_000L
        const val PROVIDER_BUDGET_MS = 8_000L
        const val OVERALL_TIMEOUT_MS = 10_000L
        const val MAX_CHILDREN = 100
        const val MAX_SESSIONS = 4
        const val MAX_NODES_PER_SESSION = 512
        const val CACHE_TTL_NANOS = 300_000_000_000L
        fun newId(): String = "audiotales:catalogue:${UUID.randomUUID()}"
    }
}
