package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.local.LibraryDao
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class IncomingBookTarget(val bookId: String? = null, val libraryId: String, val remoteId: String)
internal sealed interface IncomingBookResolution {
    data class Ready(val target: IncomingBookTarget) : IncomingBookResolution
    data class NeedsRegistration(val baseUrl: String) : IncomingBookResolution
}

internal interface IncomingBookLinkSource {
    suspend fun resolve(request: IncomingBookLink): IncomingBookResolution
    suspend fun register(request: IncomingBookLink)
}

/** All retained/saved lookups precede network work. This resolver never saves a book. */
internal class IncomingBookLinkResolver internal constructor(
    private val dao: LibraryDao,
    private val registerLibrary: suspend (String) -> Unit,
    private val resolvePage: suspend (String, String) -> String,
) : IncomingBookLinkSource {
    @Inject constructor(dao: LibraryDao, online: OnlineLibraries) : this(
        dao, { online.addLibrary(it); Unit }, online::resolveBookPageId,
    )

    override suspend fun resolve(request: IncomingBookLink): IncomingBookResolution = withContext(Dispatchers.IO) {
        val source = dao.getOnlineLibraryByBaseUrl(request.baseUrl)
        if (source != null) {
            val saved = request.remoteId?.let { dao.getProviderBook(source.id, it) }
                ?: request.pageUrl?.let { page -> dao.observeBooks().first().firstOrNull {
                    it.providerId == source.id && it.providerBookId != null && it.sourceUrl?.trimEnd('/') == page.trimEnd('/')
                } }
            if (saved != null) return@withContext IncomingBookResolution.Ready(
                IncomingBookTarget(saved.id, source.id, requireNotNull(saved.providerBookId)),
            )
        }
        if (source == null || !source.isRegistered) {
            return@withContext IncomingBookResolution.NeedsRegistration(request.baseUrl)
        }
        val id = request.remoteId ?: resolvePage(source.id, requireNotNull(request.pageUrl))
        currentCoroutineContext().ensureActive()
        // Page resolution may identify a historical saved row whose ID is not synthesized.
        val saved = dao.getProviderBook(source.id, id)
        IncomingBookResolution.Ready(IncomingBookTarget(saved?.id, source.id, id))
    }

    override suspend fun register(request: IncomingBookLink) = registerLibrary(request.baseUrl)
}
