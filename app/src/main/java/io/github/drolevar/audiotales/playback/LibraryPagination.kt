package io.github.drolevar.audiotales.playback

/** Use Long arithmetic because browsers may request an unpaged list with Int.MAX_VALUE. */
internal fun <T> List<T>.libraryPage(page: Int, pageSize: Int): List<T> {
    require(page >= 0 && pageSize > 0)
    val fromIndex = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
    val toIndex = (fromIndex.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
    return subList(fromIndex, toIndex)
}
