package io.github.drolevar.audiotales.playback

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadHttpDataSourceTest {
    // Removing response validation makes Media3 complete at the first capped response.
    @Test
    fun cappedResponsesResumePersistedPartialCacheUntilAllBytesAreComplete() = runBlocking {
        assertCappedDownload(partialBytes = 217)
    }

    @Test
    fun cappedResponsesFromFirstRequestDownloadAllBytes() = runBlocking {
        assertCappedDownload(partialBytes = 0)
    }

    private suspend fun assertCappedDownload(partialBytes: Int) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "download-http-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(base, root)
        val bytes = ByteArray(10_243) { (it * 31 + it / 7).toByte() }
        val provider = StandaloneDatabaseProvider(context)
        var cache = SimpleCache(File(root, "audio"), NoOpCacheEvictor(), provider)
        var manager: DownloadManager? = null
        try {
            // Reopen a persisted partial cache to model the next process's resume.
            withContext(Dispatchers.IO) {
                if (partialBytes > 0) CacheWriter(CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory { CappedHttpSource(bytes) }.createDataSourceForDownloading(),
                    DataSpec.Builder().setUri(URI).setKey(KEY).setLength(partialBytes.toLong()).build(), null, null).cache()
                assertEquals(partialBytes.toLong(), cache.cacheSpace)
                cache.release()
                cache = SimpleCache(File(root, "audio"), NoOpCacheEvictor(), provider)
            }
            val terminal = CompletableDeferred<Download>()
            withContext(Dispatchers.Main.immediate) {
                manager = DownloadManager(context, DefaultDownloadIndex(provider), DefaultDownloaderFactory(
                    CacheDataSource.Factory().setCache(cache)
                        .setUpstreamDataSourceFactory { downloadSource(CappedHttpSource(bytes)) },
                    Executor { it.run() },
                )).apply {
                    setRequirements(Requirements(0))
                    addListener(object : DownloadManager.Listener {
                        override fun onDownloadChanged(manager: DownloadManager, download: Download,
                            finalException: Exception?) {
                            if (download.state == Download.STATE_COMPLETED || download.state == Download.STATE_FAILED) {
                                terminal.complete(download)
                            }
                        }
                    })
                    addDownload(DownloadRequest.Builder(KEY, URI).setMimeType("audio/mpeg")
                        .setCustomCacheKey(KEY).build())
                    resumeDownloads()
                }
            }
            val result = withTimeout(15_000) { terminal.await() }
            assertEquals(Download.STATE_COMPLETED, result.state)
            assertEquals("A capped response is not the complete resource", 10_243L, result.contentLength)
            assertEquals(10_243L, result.bytesDownloaded)
            withContext(Dispatchers.IO) {
                assertEquals(10_243L, ContentMetadata.getContentLength(cache.getContentMetadata(KEY)))
                assertTrue(cache.isCached(KEY, 0, 10_243))
                val offline = CacheDataSource.Factory().setCache(cache).createDataSource()
                try {
                    offline.open(DataSpec.Builder().setUri(URI).setKey(KEY).build())
                    assertArrayEquals(bytes, readAll(offline))
                } finally { offline.close() }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { manager?.release() }
            withContext(Dispatchers.IO) { cache.release(); provider.close() }
            context.closeDatabases()
            root.deleteRecursively()
        }
    }

    @Test
    fun validRangeReportsWholeRemainderAndCappedEofThrows() {
        val source = downloadSource(ResponseHttpSource(206, "bytes 3-5/10", "3", byteArrayOf(3, 4, 5), 3))
        assertEquals(7L, source.open(spec(position = 3)))
        assertEquals(3, source.read(ByteArray(20), 0, 20))
        assertThrows(EOFException::class.java) { source.read(ByteArray(1), 0, 1) }
        source.close()
    }

    @Test
    fun earlyEofCannotCompleteKnownFullOrRangeResponses() {
        for (code in listOf(200, 206)) {
            val source = downloadSource(ResponseHttpSource(code,
                if (code == 206) "bytes 0-9/10" else null, "10", byteArrayOf(1, 2), 10))
            assertEquals(10L, source.open(spec()))
            assertEquals(2, source.read(ByteArray(10), 0, 10))
            assertThrows(EOFException::class.java) { source.read(ByteArray(1), 0, 1) }
            source.close()
        }
    }

    @Test
    fun invalidRangesAreRejectedBeforeAnyBytesAreRead() {
        val invalid = listOf(null, "nonsense", "bytes 2-5/10", "bytes 3-2/10",
            "bytes 3-10/10", "bytes 3-5/5", "bytes 3-5/*", "bytes 3-5/9223372036854775808",
            "bytes 0-9223372036854775807/*")
        for (range in invalid) {
            val http = ResponseHttpSource(206, range, null, byteArrayOf(1, 2, 3), 3)
            val source = downloadSource(http)
            assertThrows("range=$range", IOException::class.java) { source.open(spec(position = 3)) }
            assertEquals(0, http.readCalls)
            assertTrue(http.closed)
            source.close()
        }
    }

    @Test
    fun contradictoryOrRepeatedHeadersAreRejected() {
        for (headers in listOf(
            mapOf("Content-Range" to listOf("bytes 3-5/10"), "Content-Length" to listOf("4")),
            mapOf("Content-Range" to listOf("bytes 3-5/10", "bytes 3-5/10")),
            mapOf("Content-Range" to listOf("bytes 3-5/10"), "content-range" to listOf("bytes 3-5/10")),
            mapOf("Content-Range" to listOf("bytes 3-5/10"), "Content-Encoding" to listOf("gzip")),
        )) {
            val source = downloadSource(ResponseHttpSource(206, null, null, byteArrayOf(1, 2, 3), 3, headers))
            assertThrows(IOException::class.java) { source.open(spec(position = 3)) }
            source.close()
        }
    }

    @Test
    fun boundedRequestsFinishOnlyAtTheirValidatedExtent() {
        for (range in listOf("bytes 3-5/10", "bytes 3-5/*")) {
            val source = downloadSource(ResponseHttpSource(206, range, "3", byteArrayOf(3, 4, 5), 3))
            assertEquals(3L, source.open(spec(position = 3, length = 3)))
            assertArrayEquals(byteArrayOf(3, 4, 5), readAll(source))
            assertEquals(0, source.read(ByteArray(0), 0, 0))
            source.close()
        }
        val tooWide = downloadSource(ResponseHttpSource(206, "bytes 3-6/10", "4", ByteArray(4), 3))
        assertThrows(IOException::class.java) { tooWide.open(spec(position = 3, length = 3)) }
        tooWide.close()
        val smallerTotal = downloadSource(ResponseHttpSource(206, "bytes 3-5/6", "3", ByteArray(3), 7))
        assertThrows(IOException::class.java) { smallerTotal.open(spec(position = 3, length = 7)) }
        smallerTotal.close()
    }

    @Test
    fun fullAndIgnoredRangeResponsesUseDelegateSkipAndUnknownLengthRemainsUnknown() {
        for (position in listOf(0L, 3L)) for (length in listOf(4L, C.LENGTH_UNSET.toLong())) {
            // DefaultHttpDataSource has already skipped bytes before the nonzero requested start.
            val source = downloadSource(ResponseHttpSource(200, null, null, byteArrayOf(3, 4, 5, 6), length))
            assertEquals(length, source.open(spec(position = position)))
            assertEquals(0, source.read(ByteArray(0), 0, 0))
            assertArrayEquals(byteArrayOf(3, 4, 5, 6), readAll(source))
            source.close()
        }
    }

    @Test
    fun requestIdentityHeadersFlagsListenersAndCloseReachHttpSource() {
        val http = ResponseHttpSource(200, null, null, byteArrayOf(1), 1)
        val source = downloadSource(http)
        val spec = spec().buildUpon().setKey("saved-resource")
            .setHttpRequestHeaders(mapOf("Referer" to "https://example.test/library"))
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, count: Int) = Unit
            override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
        }
        source.addTransferListener(listener)
        source.open(spec)
        assertSame(spec, http.receivedSpec)
        assertSame(listener, http.listener)
        assertEquals(URI, source.uri)
        assertEquals(http.responseHeaders, source.responseHeaders)
        source.close()
        assertTrue(http.closed)
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): DataSpec =
        DataSpec.Builder().setUri(URI).setPosition(position).setLength(length).build()

    private class ResponseHttpSource(
        private val code: Int,
        range: String?,
        length: String?,
        private val bytes: ByteArray,
        private val openLength: Long,
        private val headers: Map<String, List<String>> = buildMap {
            if (range != null) put("content-range", listOf(range))
            if (length != null) put("content-length", listOf(length))
        },
    ) : HttpDataSource {
        var receivedSpec: DataSpec? = null
        var listener: TransferListener? = null
        var readCalls = 0
        var closed = false
        private var position = 0
        override fun open(dataSpec: DataSpec): Long { receivedSpec = dataSpec; return openLength }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            if (length == 0) return 0
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun getResponseCode(): Int = code
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun getUri(): Uri? = receivedSpec?.uri
        override fun addTransferListener(transferListener: TransferListener) { listener = transferListener }
        override fun setRequestProperty(name: String, value: String) = Unit
        override fun clearRequestProperty(name: String) = Unit
        override fun clearAllRequestProperties() = Unit
        override fun close() { closed = true }
    }

    private fun downloadSource(http: HttpDataSource): DataSource = DownloadHttpDataSource(http)

    private fun readAll(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(311)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    /** HTTP double mirrors open/read for a provider capping each 206 at 1 KiB. */
    private class CappedHttpSource(private val bytes: ByteArray) : HttpDataSource {
        private var spec: DataSpec? = null
        private var position = 0
        private var end = 0
        private var headers: Map<String, List<String>> = emptyMap()
        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            position = dataSpec.position.toInt()
            end = minOf(bytes.size.toLong(), dataSpec.position + 1_024,
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataSpec.position + dataSpec.length).toInt()
            headers = mapOf("Content-Range" to listOf("bytes $position-${end - 1}/${bytes.size}"),
                "Content-Length" to listOf("${end - position}"))
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) (end - position).toLong() else dataSpec.length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun getResponseCode(): Int = 206
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun getUri(): Uri? = spec?.uri
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun setRequestProperty(name: String, value: String) = Unit
        override fun clearRequestProperty(name: String) = Unit
        override fun clearAllRequestProperties() = Unit
        override fun close() { spec = null }
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = mutableListOf<SQLiteDatabase>()
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory).also { databases.add(it) }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler).also { databases.add(it) }
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        fun closeDatabases() = databases.forEach { it.close() }
    }

    private companion object {
        val URI = "https://example.test/audio.mp3".toUri()
        const val KEY = "book-audio"
    }
}
