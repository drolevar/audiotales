package io.github.drolevar.audiotales.data.online

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Identity only. Incoming URLs never become playback locations. */
internal data class IncomingBookLink(val baseUrl: String, val remoteId: String? = null, val pageUrl: String? = null)

internal object IncomingBookLinkParser {
    const val MAX_TEXT_LENGTH = 16_384
    const val MAX_URL_LENGTH = 4096
    private val urlToken = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|(?:javascript|data|intent|file|content|media):)[^\\s\\p{Z}<>\\\"]+")
    private val numericId = Regex("[1-9][0-9]{0,19}")

    fun parseSharedText(text: String): IncomingBookLink {
        if (text.length > MAX_TEXT_LENGTH) invalid()
        val candidates = urlToken.findAll(text).map { it.value }.distinct().toList()
        if (candidates.size != 1) invalid()
        return parseUrl(candidates.single())
    }

    fun parseUrl(value: String): IncomingBookLink {
        if (value.length > MAX_URL_LENGTH || value.any { it.isWhitespace() || it.isISOControl() }) invalid()
        val uri = try { URI(value) } catch (_: Exception) { invalid() }
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null ||
            Regex("(?i)%2f|%5c|%2e|\\\\").containsMatchIn(uri.rawPath.orEmpty()) ||
            uri.path.orEmpty().split('/').any { it == "." || it == ".." }
        ) invalid()
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> {
                val host = host(uri.host ?: invalid())
                if (uri.rawAuthority?.lowercase(Locale.ROOT) != host) invalid()
                val base = "https://$host/"
                when {
                    uri.rawPath == "/api" -> identity(base, query(uri.rawQuery), host)
                    uri.rawQuery != null -> invalid()
                    host == "librivox.org" && Regex("/[A-Za-z0-9][A-Za-z0-9_-]*/?").matches(uri.rawPath.orEmpty()) ->
                        IncomingBookLink(base, pageUrl = "https://$host${uri.rawPath}")
                    else -> {
                        val match = Regex("/audio(?:/|-)([1-9][0-9]{0,19})-[^/]+/?").matchEntire(uri.rawPath.orEmpty()) ?: invalid()
                        IncomingBookLink(base, remoteId = match.groupValues[1])
                    }
                }
            }
            "audiotales" -> {
                val legacy = uri.rawAuthority == null && uri.rawPath == "/TabScreen/Favorite/Review" && value.startsWith("audiotales:///", ignoreCase = true)
                val canonical = uri.rawAuthority == "book" && uri.rawPath.orEmpty().isEmpty()
                if (!legacy && !canonical) invalid()
                val params = query(uri.rawQuery)
                val host = host(params["link"] ?: invalid())
                identity("https://$host/", params, host)
            }
            else -> invalid()
        }
    }

    private fun identity(base: String, params: Map<String, String>, expectedHost: String): IncomingBookLink {
        if (params.keys != setOf("link", "id") || host(params["link"] ?: invalid()) != expectedHost) invalid()
        val id = params["id"]?.takeIf { numericId.matches(it) } ?: invalid()
        return IncomingBookLink(base, remoteId = id)
    }

    private fun query(raw: String?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in (raw ?: invalid()).split('&')) {
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) invalid()
            fun decode(input: String) = try { URLDecoder.decode(input, "UTF-8") } catch (_: IllegalArgumentException) { invalid() }
            val key = decode(parts[0])
            if (result.put(key, decode(parts[1])) != null) invalid()
        }
        return result
    }

    private fun host(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        if (normalized.length > 253 || !Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?").matches(normalized) ||
            normalized.split('.').any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }
        ) invalid()
        return normalized
    }

    private fun invalid(): Nothing = throw OnlineBookException(OnlineBookError.INVALID_LINK)
}
