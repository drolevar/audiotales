package io.github.drolevar.audiotales.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException

/**
 * Download-only HTTP validation. Some origins cap each 206 response even when the request asks
 * for the complete remainder. Its Content-Range total describes the resource, while Content-Length
 * describes only that response. Exposing a capped EOF to CacheDataSource/CacheWriter would replace
 * the resource length with that response's end and incorrectly complete the download.
 *
 * Keep the validated resource remainder visible and throw at a short EOF. DownloadManager's normal
 * progress-aware IOException retry then reopens at the next uncached byte, using the same cache,
 * request identity and cancellation path. Playback retains Media3's ordinary HTTP source.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DownloadHttpDataSource(private val upstream: HttpDataSource) : DataSource {
    private var remaining = C.LENGTH_UNSET.toLong()
    private var responseRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        remaining = C.LENGTH_UNSET.toLong()
        responseRemaining = C.LENGTH_UNSET.toLong()
        try {
            val resolvedLength = upstream.open(dataSpec)
            if (upstream.responseCode == 206) {
                val encoding = header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw IOException("Encoded download range cannot be validated")
                }
                val range = header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
                    ?: throw IOException("Missing or invalid download Content-Range")
                val start = number(range.groupValues[1])
                val end = number(range.groupValues[2])
                if (start != dataSpec.position || end < start || end - start == Long.MAX_VALUE) {
                    throw IOException("Download range does not match requested position")
                }
                val responseLength = end - start + 1
                val contentLength = header("Content-Length")?.let(::number)
                if (contentLength != null && contentLength != responseLength) {
                    throw IOException("Contradictory download response lengths")
                }
                val requestedLength = dataSpec.length
                if (requestedLength != C.LENGTH_UNSET.toLong() && responseLength > requestedLength) {
                    throw IOException("Download response exceeds requested range")
                }
                val total = range.groupValues[3]
                val validatedLength = if (total == "*") {
                    // With no total, only an independently complete bounded request is provable.
                    if (requestedLength == C.LENGTH_UNSET.toLong() || requestedLength != responseLength) {
                        throw IOException("Download range has no complete resource length")
                    }
                    requestedLength
                } else {
                    val resourceLength = number(total)
                    if (end >= resourceLength) throw IOException("Download range exceeds resource length")
                    val resourceRemaining = resourceLength - start
                    if (requestedLength != C.LENGTH_UNSET.toLong() && requestedLength > resourceRemaining) {
                        throw IOException("Download resource is shorter than the requested extent")
                    }
                    if (requestedLength == C.LENGTH_UNSET.toLong()) resourceRemaining else requestedLength
                }
                remaining = validatedLength
                responseRemaining = responseLength
            } else {
                // Media3 already handles a 200 that ignores Range by skipping the requested offset,
                // and resolves compressed/unknown lengths. Keep that behavior, enforcing known EOFs.
                if (header("Content-Range") != null && upstream.responseCode != 416) {
                    throw IOException("Unexpected download Content-Range")
                }
                remaining = resolvedLength
            }
            return remaining
        } catch (failure: IOException) {
            try {
                upstream.close()
            } catch (closeFailure: IOException) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (responseRemaining == 0L) throw EOFException("Download response ended before requested extent")
        var readLength = length.toLong()
        if (remaining != C.LENGTH_UNSET.toLong()) readLength = minOf(readLength, remaining)
        if (responseRemaining != C.LENGTH_UNSET.toLong()) readLength = minOf(readLength, responseRemaining)
        val count = upstream.read(buffer, offset, readLength.toInt())
        if (count == C.RESULT_END_OF_INPUT) {
            if (remaining != C.LENGTH_UNSET.toLong() && remaining > 0) {
                throw EOFException("Download response ended before requested extent")
            }
        } else {
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
            if (responseRemaining != C.LENGTH_UNSET.toLong()) responseRemaining -= count
        }
        return count
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        remaining = C.LENGTH_UNSET.toLong()
        responseRemaining = C.LENGTH_UNSET.toLong()
        upstream.close()
    }

    private fun header(name: String): String? {
        val entries = upstream.responseHeaders.entries.filter { it.key.equals(name, ignoreCase = true) }
        if (entries.isEmpty()) return null
        val values = entries.flatMap { it.value }
        if (values.size != 1) throw IOException("Ambiguous download $name")
        return values.single().trim()
    }

    private fun number(value: String): Long = value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toLongOrNull() ?: throw IOException("Invalid download range length")

    private companion object {
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)", RegexOption.IGNORE_CASE)
    }
}
