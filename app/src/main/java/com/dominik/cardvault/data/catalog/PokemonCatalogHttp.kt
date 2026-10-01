package com.dominik.cardvault.data.catalog

import com.squareup.moshi.JsonReader
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/** Preserve the existing Android catalogue transport policy without weakening TLS validation.
 * Platform URLConnection and app OkHttp still have different TLS/pool implementations. */
internal val pokemonCatalogHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(HttpURLConnection.getFollowRedirects())
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
}

/** Streaming HTTP body used by PokemonVisualCatalog. The call factory is an internal local-test seam.
 * The request remains owned until its actual IO reader and response cleanup have both ended.
 * URLConnection.disconnect could not unblock an actual partial JSON read in the local regression;
 * Call.cancel closes the socket independently of that reader. This is not a speed guarantee. */
internal suspend fun <T> readPokemonCatalogHttp(
    url: String,
    callFactory: Call.Factory? = null,
    block: (JsonReader) -> T,
): T = coroutineScope {
    // Independent completion signal, not an independent worker: it must remain awaitable during
    // cancellation, including the race where the call factory has not returned yet.
    val callReady = CompletableDeferred<Call?>()
    val reader = async(Dispatchers.IO) {
        // Expected socket/parser errors after cancellation must not independently fail the parent.
        runCatching {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url)
                .header("User-Agent", "DomiDex/1.0 private Android app")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .get().build()
            // Resolve the lazy TLS/client state only on IO, never during catalogue/UI creation.
            val call = (callFactory ?: pokemonCatalogHttpClient).newCall(request)
            callReady.complete(call)
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (response.code !in 200..299) error("Katalogdienst: HTTP ${response.code}")
                val body = checkNotNull(response.body) { "Katalogdienst: Leere HTTP-Antwort" }
                JsonReader.of(body.source()).use(block)
            }
        }
    }
    // Covers failure before publication and cancellation before the IO child even starts.
    reader.invokeOnCompletion { callReady.complete(null) }
    try {
        reader.await().getOrThrow()
    } catch (cancelled: CancellationException) {
        // Cancellation/cleanup never runs on the caller/UI thread. Do not release a cache or
        // single-flight lock while either the reader or cleanup is still alive.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                callReady.await()?.cancel()
            } catch (cleanupFailure: Throwable) {
                cancelled.addSuppressed(cleanupFailure)
            } finally { reader.join() }
        }
        throw cancelled
    }
}
