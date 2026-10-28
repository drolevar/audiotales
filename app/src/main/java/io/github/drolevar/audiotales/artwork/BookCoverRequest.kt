package io.github.drolevar.audiotales.artwork

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/** Keep phone and shared-session requests on the same provider headers and Coil cache keys. */
internal fun bookCoverRequestBuilder(context: Context, url: String?, referer: String?): ImageRequest.Builder {
    val headers = NetworkHeaders.Builder().set("User-Agent", "AudioTales").apply {
        referer?.let { set("Referer", it) }
    }.build()
    return ImageRequest.Builder(context).data(url).httpHeaders(headers)
}
