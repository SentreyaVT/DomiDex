package com.dominik.cardvault.data.catalog

import com.squareup.moshi.JsonReader
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Actual streaming HTTP/Moshi against 127.0.0.1:0 only. Never contacts a provider.
 * Cancellation must complete BEFORE the harness closes its server socket; forced cleanup cannot
 * count as transport cancellation. All sockets are freed before joining the real reader thread.
 * The short observation window is a bounded local diagnostic, not an app/network latency SLA. */
class PokemonCatalogHttpCancellationTest {
    @Test fun cancellingWhileResponseHeadersAreStalledClosesTheReader() = cancellationCase(Stall.HEADERS)

    @Test fun cancellingWhileAJsonStringBodyIsStalledClosesTheReader() = cancellationCase(Stall.JSON_BODY)

    @Test fun successfulResponsePreservesStreamingHeadersAndConfiguredTimeouts() {
        val server = LoopbackServer(Stall.NONE, status = 201)
        val client = AtomicReference<OkHttpClient>()
        try {
            val value = runBlocking {
                withContext(Dispatchers.IO) {
                    readPokemonCatalogHttp(server.url, localCallFactory(client)) { readValue(it) }
                }
            }
            assertEquals("loopback", value)
            assertTrue(server.requestReceived.await(2, TimeUnit.SECONDS))
            val headers = server.requestHeaders.get()
            assertTrue(headers.any { it.equals("User-Agent: DomiDex/1.0 private Android app", ignoreCase = true) })
            assertTrue(headers.any { it.equals("Cache-Control: no-cache", ignoreCase = true) })
            assertTrue(headers.any { it.equals("Pragma: no-cache", ignoreCase = true) })
            assertNull(client.get().cache)
            assertEquals(12_000, client.get().connectTimeoutMillis)
            assertEquals(20_000, client.get().readTimeoutMillis)
        } finally { server.closeAndJoin() }
    }

    @Test fun nonSuccessStatusKeepsTheExistingFailureInsteadOfParsingABody() {
        val server = LoopbackServer(Stall.NONE, status = 503)
        var parsed = false
        try {
            val failure = runCatching {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        readPokemonCatalogHttp(server.url, localCallFactory()) {
                            parsed = true
                            readValue(it)
                        }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Katalogdienst: HTTP 503", failure?.message)
            assertFalse(parsed)
        } finally { server.closeAndJoin() }
    }

    private fun cancellationCase(stall: Stall) {
        val server = LoopbackServer(stall)
        val task = AtomicReference<Job>()
        val taskCreated = CountDownLatch(1)
        val readerCompleted = CountDownLatch(1)
        val parserWaitingForValue = CountDownLatch(1)
        val returnedValue = AtomicReference<String>()
        val threadFailure = AtomicReference<Throwable>()
        val reader = Thread({
            try {
                runBlocking {
                    val work = launch(Dispatchers.IO) {
                        val value = readPokemonCatalogHttp(server.url, localCallFactory()) { json ->
                            readValue(json) { parserWaitingForValue.countDown() }
                        }
                        returnedValue.set(value)
                    }
                    task.set(work)
                    taskCreated.countDown()
                    work.join() // Real structured reader completion, not only Job.isCancelled.
                }
            } catch (failure: Throwable) {
                threadFailure.set(failure)
            } finally { readerCompleted.countDown() }
        }, "catalog-loopback-reader-${stall.name}")
        var completedBeforeForcedCleanup = false
        var peerClosedBeforeForcedCleanup = false
        reader.start()
        try {
            assertTrue("Coroutine must exist before cancellation", taskCreated.await(5, TimeUnit.SECONDS))
            assertTrue("The loopback server must have received a real HTTP request", server.stalled.await(5, TimeUnit.SECONDS))
            if (stall == Stall.JSON_BODY) {
                assertTrue("Moshi must be reading the intentionally incomplete JSON body", parserWaitingForValue.await(5, TimeUnit.SECONDS))
            }
            server.cancellationRequested.set(true)
            task.get().cancel()
            completedBeforeForcedCleanup = readerCompleted.await(2, TimeUnit.SECONDS)
            peerClosedBeforeForcedCleanup = completedBeforeForcedCleanup && server.peerClosed.await(2, TimeUnit.SECONDS)
        } finally {
            // Do not interrupt the reader and do not use withTimeout { cancelAndJoin() }.
            // Real sockets are released first so even the deliberately failing old path can exit.
            try { server.closeAndJoin() }
            finally {
                task.get()?.cancel()
                reader.join(25_000)
                assertFalse("Actual reader thread must be joined after socket cleanup", reader.isAlive)
            }
        }
        assertNull("A cancelled request must not publish a successful body", returnedValue.get())
        assertTrue("Cancellation must finish the reader before forced server cleanup ($stall); failure=${threadFailure.get()?.javaClass?.simpleName}", completedBeforeForcedCleanup)
        assertTrue("Cancellation must close the real peer connection before forced server cleanup", peerClosedBeforeForcedCleanup)
        assertTrue("The completed work must be cancelled, not a successful response", task.get().isCancelled)
        assertNull("Cancellation must not fail the parent with a transport or parsing exception", threadFailure.get())
    }

    private fun readValue(reader: JsonReader, beforeValue: () -> Unit = {}): String {
        reader.beginObject()
        assertEquals("value", reader.nextName())
        beforeValue()
        val value = reader.nextString()
        reader.endObject()
        return value
    }

    private fun localCallFactory(record: AtomicReference<OkHttpClient>? = null): Call.Factory {
        // A developer machine's proxy configuration must never forward this local fixture.
        val client = pokemonCatalogHttpClient.newBuilder().proxy(Proxy.NO_PROXY).build()
        record?.set(client)
        return Call.Factory { request ->
            check(request.url.scheme == "http" && request.url.host == "127.0.0.1" && request.url.port > 0)
            client.newCall(request)
        }
    }

    private enum class Stall { NONE, HEADERS, JSON_BODY }

    private class LoopbackServer(private val stall: Stall, private val status: Int = 200) {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val url = "http://127.0.0.1:${listener.localPort}/synthetic-catalog.json"
        val requestReceived = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val cancellationRequested = AtomicBoolean(false)
        val requestHeaders = AtomicReference<List<String>>(emptyList())
        private val accepted = AtomicReference<Socket>()
        private val closing = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable>()
        private val thread = Thread({
            try {
                listener.accept().use { socket ->
                    accepted.set(socket)
                    if (closing.get()) return@use
                    socket.soTimeout = 8_000
                    val input = socket.getInputStream()
                    val request = StringBuilder()
                    while (!request.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && request.length < 8_192) { "Invalid local request" }
                        request.append(next.toChar())
                    }
                    requestHeaders.set(request.toString().split("\r\n").filter(String::isNotEmpty))
                    requestReceived.countDown()
                    if (stall != Stall.HEADERS) {
                        val fullBody = "{\"value\":\"loopback\"}".toByteArray(Charsets.UTF_8)
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 $status Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${fullBody.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(if (stall == Stall.JSON_BODY) "{\"value\":\"".toByteArray(Charsets.UTF_8) else fullBody)
                        output.flush()
                    }
                    if (stall != Stall.NONE) {
                        stalled.countDown()
                        try {
                            if (input.read() == -1) peerClosed.countDown()
                        } catch (error: SocketException) {
                            // A real peer reset can replace EOF on an aborted HTTP response.
                            // Only count it after this test requested cancellation and before
                            // forced server cleanup. SocketTimeoutException is not caught here.
                            if (!closing.get() && cancellationRequested.get()) peerClosed.countDown()
                            else throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closing.get()) failure.set(error)
            }
        }, "catalog-loopback-server-${stall.name}").apply { start() }

        fun closeAndJoin() {
            closing.set(true)
            try { accepted.get()?.close() }
            finally {
                listener.close()
                thread.join(5_000)
            }
            check(!thread.isAlive) { "Local HTTP server did not stop" }
            failure.get()?.let { throw AssertionError("Local HTTP server failed", it) }
        }
    }
}
