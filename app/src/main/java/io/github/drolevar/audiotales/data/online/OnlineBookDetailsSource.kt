package io.github.drolevar.audiotales.data.online

import io.github.drolevar.audiotales.data.library.LibraryAddition
import io.github.drolevar.audiotales.data.library.LibraryBook
import io.github.drolevar.audiotales.data.library.ResolvedOnlineBook

/** Detail inspection never saves a book; saving and source renewal are explicit actions. */
internal interface OnlineBookDetailsSource {
    suspend fun inspectBookMetadata(libraryId: String, remoteId: String): LibraryBook
    suspend fun inspectBookAudio(book: LibraryBook): ResolvedOnlineBook
    suspend fun inspectBook(libraryId: String, remoteId: String): ResolvedOnlineBook
    /** Includes sources removed from catalogue discovery but retained for saved books. */
    suspend fun getLibrary(libraryId: String): OnlineLibrary
    suspend fun refreshBookMetadata(bookId: String): LibraryBook?
    suspend fun saveBook(libraryId: String, remoteId: String): LibraryAddition
    suspend fun refreshBookSources(bookId: String)
}
