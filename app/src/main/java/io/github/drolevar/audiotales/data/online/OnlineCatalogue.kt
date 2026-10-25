package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.library.LibraryAddition
import kotlinx.coroutines.flow.Flow

/** Registered catalogue sources and their browsing operations, independent of UI ownership. */
internal interface OnlineCatalogue {
    fun observeLibraries(): Flow<List<OnlineLibrary>>
    suspend fun addLibrary(input: String): OnlineLibrary
    suspend fun browse(libraryId: String, query: String = "", offset: Int = 0): CataloguePage
    suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition
    suspend fun setLibrarySelected(id: String, selected: Boolean)
    suspend fun renameLibrary(id: String, name: String)
    suspend fun removeLibrary(id: String)
}
