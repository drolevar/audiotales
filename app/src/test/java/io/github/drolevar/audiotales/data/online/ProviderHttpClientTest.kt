package io.github.drolevar.audiotales.data.online

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpClientTest {
    @Test
    fun decodesUtf8AndSendsOnlyExpectedProviderHeaders() = runBlocking {
        withServer { server, base ->
            server.createContext("/page") { request ->
                assertTrue(request.requestHeaders.getFirst("User-Agent").startsWith("Mozilla/5.0"))
                assertEquals("https://audioknigi.com.ua/", request.requestHeaders.getFirst("Referer"))
                val body = "Книга".toByteArray()
                request.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                request.sendResponseHeaders(200, body.size.toLong())
                request.responseBody.use { it.write(body) }
            }
            val text = client().get("$base/page".toHttpUrl(), 1024, "https://audioknigi.com.ua/")
            assertEquals("Книга", text)
        }
    }

    @Test
    fun rejectsRedirectsAndUnsuccessfulResponsesWithoutFollowingThem() = runBlocking {
        withServer { server, base ->
            var redirected = false
            server.createContext("/redirect") { request ->
                request.responseHeaders.add("Location", "$base/elsewhere")
                request.sendResponseHeaders(302, -1)
                request.close()
            }
            server.createContext("/elsewhere") { request ->
                redirected = true
                request.sendResponseHeaders(200, -1)
                request.close()
            }
            server.createContext("/missing") { request ->
                request.sendResponseHeaders(404, -1)
                request.close()
            }
            assertFailure(OnlineBookError.PROVIDER_RESPONSE) { client().get("$base/redirect".toHttpUrl(), 1024) }
            assertFailure(OnlineBookError.PROVIDER_RESPONSE) { client().get("$base/missing".toHttpUrl(), 1024) }
            assertTrue(!redirected)
        }
    }

    @Test
    fun capsBothDeclaredAndChunkedResponseBodies() = runBlocking {
        withServer { server, base ->
            listOf("/declared", "/chunked").forEach { path ->
                server.createContext(path) { request ->
                    request.sendResponseHeaders(200, if (path == "/chunked") 0 else 256)
                    runCatching { request.responseBody.use { it.write(ByteArray(256)) } }
                    request.close()
                }
                assertFailure(OnlineBookError.RESPONSE_TOO_LARGE) { client().get("$base$path".toHttpUrl(), 64) }
            }
        }
    }

    @Test
    fun cancellationCancelsTheUnderlyingCallAndTimeoutIsReported() = runBlocking {
        withServer { server, base ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.createContext("/slow") { request ->
                arrived.countDown()
                release.await(2, TimeUnit.SECONDS)
                request.close()
            }
            val okhttp = OkHttpClient.Builder().followRedirects(false).callTimeout(100, TimeUnit.MILLISECONDS).build()
            try {
                val pending = async { ProviderHttpClient(okhttp).get("$base/slow".toHttpUrl(), 64) }
                kotlinx.coroutines.yield()
                assertTrue(arrived.await(1, TimeUnit.SECONDS))
                pending.cancel()
                pending.join()
                assertTrue(pending.isCancelled)
                assertFailure(OnlineBookError.NETWORK) { ProviderHttpClient(okhttp).get("$base/slow".toHttpUrl(), 64) }
            } finally {
                release.countDown()
            }
        }
    }

    private fun client() = ProviderHttpClient(OkHttpClient.Builder().followRedirects(false).build())

    private suspend fun assertFailure(reason: OnlineBookError, block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $reason")
        } catch (error: OnlineBookException) {
            assertEquals(reason, error.reason)
        }
    }

    private suspend fun withServer(block: suspend (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") }
        finally { server.stop(0) }
    }
}
