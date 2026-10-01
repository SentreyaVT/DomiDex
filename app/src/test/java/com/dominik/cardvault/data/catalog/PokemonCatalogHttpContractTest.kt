package com.dominik.cardvault.data.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class PokemonCatalogHttpContractTest {
    @Test fun clientPreservesTheInspectedAndroidTransportPolicy() {
        val client = pokemonCatalogHttpClient
        assertEquals(12_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(0, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertNull(client.cache)
        assertNull(client.proxy) // Platform/system ProxySelector, not forced direct in production.
        assertEquals(java.net.ProxySelector.getDefault(), client.proxySelector)
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertEquals(java.net.HttpURLConnection.getFollowRedirects(), client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.retryOnConnectionFailure)
    }

    @Test fun gzipResponseIsStreamedAndDecoded() {
        val encoded = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write("{\"value\":\"gzip\"}".toByteArray()) }
        }.toByteArray()
        val server = ScriptedLoopback { _, _ -> Reply(200, encoded, "Content-Encoding: gzip\r\n") }
        try {
            assertEquals("gzip", readValue(server))
            assertEquals(1, server.requests.size)
            assertTrue(server.requests.single().contains("Accept-Encoding: gzip", ignoreCase = true))
        } finally { server.closeAndJoin() }
    }

    @Test fun sameSchemeRedirectRetainsStreamingAndRequestHeaders() {
        val server = ScriptedLoopback { _, index ->
            if (index == 0) Reply(302, ByteArray(0), "Location: /final%20card\r\n")
            else Reply(200, "{\"value\":\"redirected\"}".toByteArray())
        }
        try {
            assertEquals("redirected", readValue(server))
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.last().startsWith("GET /final%20card HTTP/1.1"))
            server.requests.forEach {
                assertTrue(it.contains("User-Agent: DomiDex/1.0 private Android app", ignoreCase = true))
                assertTrue(it.contains("Cache-Control: no-cache", ignoreCase = true))
                assertTrue(it.contains("Pragma: no-cache", ignoreCase = true))
            }
        } finally { server.closeAndJoin() }
    }

    @Test fun crossSchemeRedirectIsReturnedAsTheExistingHttpError() {
        val server = ScriptedLoopback { address, _ ->
            Reply(302, ByteArray(0), "Location: ${address.replace("http:", "https:")}/forbidden\r\n")
        }
        try {
            val failure = runCatching { readValue(server) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Katalogdienst: HTTP 302", failure?.message)
            assertEquals(1, server.requests.size)
        } finally { server.closeAndJoin() }
    }

    @Test fun ordinaryGermanDetailFailureStillUsesEnglishDetail() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val catalogue = PokemonVisualCatalog.withHttpCalls(PokemonCatalogMetadataCache(), Call.Factory { request ->
            paths += request.url.encodedPath
            SyntheticCall(request) {
                if (request.url.encodedPath.contains("/de/")) response(request, 503, "{}")
                else response(request, 200, "{\"rarity\":\"Rare\",\"variants_detailed\":[]}")
            }
        })
        assertEquals("Rare", catalogue.withDeclaredPrintVariants(card()).rarity)
        assertEquals(listOf("/v2/de/cards/sv08.5-068", "/v2/en/cards/sv08.5-068"), paths)
    }

    @Test fun cancelledGermanDetailNeverStartsEnglishOrWarmsTheActualCatalogueCache() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fresh = AtomicBoolean(false)
        val catalogue = PokemonVisualCatalog.withHttpCalls(PokemonCatalogMetadataCache(), Call.Factory { request ->
            paths += request.url.encodedPath
            SyntheticCall(request, cancelAction = { release.countDown() }) {
                if (!fresh.get()) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Test did not release stalled call" }
                    throw IOException("Synthetic cancelled read")
                }
                response(request, 200, "{\"rarity\":\"Fresh\",\"variants_detailed\":[]}")
            }
        })
        val work = launch(kotlinx.coroutines.Dispatchers.Default) { catalogue.withDeclaredPrintVariants(card()) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            work.cancel()
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(listOf("/v2/de/cards/sv08.5-068"), paths)
            fresh.set(true)
            assertEquals("Fresh", catalogue.withDeclaredPrintVariants(card()).rarity)
            assertEquals(listOf("/v2/de/cards/sv08.5-068", "/v2/de/cards/sv08.5-068"), paths)
        } finally { release.countDown(); work.cancel(); work.join() }
    }

    private fun card() = PokemonVisualCard(
        "sv08.5-068", "Original", "068", null, "", "",
        PokemonSet("sv08.5", "Fixture", "Deutsch · TCGdex", 131, 180, "", null, "PRE"),
    )

    private fun response(request: Request, code: Int, json: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .body(json.toResponseBody()).build()

    private class SyntheticCall(
        private val request: Request,
        private val cancelAction: () -> Unit = {},
        private val executeAction: () -> Response,
    ) : Call {
        private val cancelled = AtomicBoolean(false)
        override fun request() = request
        override fun execute() = executeAction()
        override fun cancel() { cancelled.set(true); cancelAction() }
        override fun isCanceled() = cancelled.get()
        override fun isExecuted() = false
        override fun enqueue(responseCallback: Callback) = error("Synchronous synthetic calls only")
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = error("No clone in fixture")
    }

    private fun readValue(server: ScriptedLoopback): String = runBlocking {
        val local = pokemonCatalogHttpClient.newBuilder().proxy(Proxy.NO_PROXY).build()
        val calls = Call.Factory { request ->
            check(request.url.host == "127.0.0.1" && request.url.scheme == "http")
            local.newCall(request)
        }
        readPokemonCatalogHttp(server.url, calls) { reader ->
            reader.beginObject()
            assertEquals("value", reader.nextName())
            reader.nextString().also { reader.endObject() }
        }
    }

    private data class Reply(val status: Int, val body: ByteArray, val headers: String = "")

    private class ScriptedLoopback(private val reply: (String, Int) -> Reply) {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        private val closed = AtomicBoolean(false)
        private val accepted = AtomicReference<Socket>()
        private val failure = AtomicReference<Throwable>()
        private val thread = Thread({
            try {
                while (!closed.get()) server.accept().use { socket ->
                    accepted.set(socket)
                    if (closed.get()) return@use
                    socket.soTimeout = 5_000
                    val request = StringBuilder()
                    val input = socket.getInputStream()
                    while (!request.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && request.length < 8192)
                        request.append(next.toChar())
                    }
                    check(requests.size < 4) { "Unexpected local follow-up loop" }
                    requests += request.toString()
                    val response = reply(url, requests.size - 1)
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 ${response.status} Synthetic\r\nContent-Length: ${response.body.size}\r\n" +
                        "Connection: close\r\n${response.headers}\r\n").toByteArray())
                    output.write(response.body)
                    output.flush()
                }
            } catch (error: Throwable) { if (!closed.get()) failure.set(error) }
            finally { server.close() }
        }, "catalog-contract-loopback").apply { start() }

        fun closeAndJoin() {
            closed.set(true)
            try { accepted.get()?.close() }
            finally { server.close(); thread.join(5_000) }
            check(!thread.isAlive) { "Loopback server did not finish" }
            failure.get()?.let { throw AssertionError("Local contract server failed", it) }
        }
    }
}
