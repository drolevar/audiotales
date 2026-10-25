package io.github.drolevar.audiotales.data.online

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
internal class ProviderHttpClient internal constructor(private val client: OkHttpClient) {
    @Inject constructor() : this(
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build(),
    )

    // Callers supply only adapter-validated locations. This client never follows a Location header.
    suspend fun get(url: HttpUrl, maxBytes: Int, referer: String? = null, acceptedStatuses: Set<Int> = emptySet()): String =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; AudioTales)")
                .header("Accept", "text/html,application/json,text/plain;q=0.9")
                .apply { if (referer != null) header("Referer", referer) }
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(OnlineBookException(OnlineBookError.NETWORK))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful && it.code !in acceptedStatuses) throw OnlineBookException(OnlineBookError.PROVIDER_RESPONSE)
                            val body = it.body
                            if (body.contentLength() > maxBytes) tooLarge()
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (bytes.size() + count > maxBytes) tooLarge()
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            bytes.toByteArray().toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: OnlineBookException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } catch (_: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(OnlineBookException(OnlineBookError.NETWORK))
                        }
                    }
                }
            })
        }

    private fun tooLarge(): Nothing = throw OnlineBookException(OnlineBookError.RESPONSE_TOO_LARGE)
}
