package io.github.drolevar.audiotales.data.library

import java.net.URI

/** Artwork is optional metadata; invalid or absent values must not replace known artwork. */
internal fun validArtworkUrl(value: String?): String? {
    if (value.isNullOrBlank() || value.length > 4096) return null
    val uri = try { URI(value) } catch (_: Exception) { return null }
    return value.takeIf {
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.port in listOf(-1, 443) && uri.fragment == null
    }
}
