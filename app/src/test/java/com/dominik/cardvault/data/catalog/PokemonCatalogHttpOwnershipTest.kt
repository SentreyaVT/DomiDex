package com.dominik.cardvault.data.catalog

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** Deterministic Call doubles supplement, never replace, the real loopback stall tests. */
class PokemonCatalogHttpOwnershipTest {
    @Test fun delayedCancelDoesNotBlockCallerThreadOrReleaseTheCacheKeyEarly() {
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val responseEntered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val readerExited = CountDownLatch(1)
        val call = object : FakeCall() {
            override fun execute(): Response {
                responseEntered.countDown()
                try {
                    check(releaseResponse.await(5, TimeUnit.SECONDS)) { "Test did not release response" }
                    throw IOException("Synthetic closed connection")
                } finally { readerExited.countDown() }
            }

            override fun cancel() {
                super.cancel()
                disconnectEntered.countDown()
                check(releaseDisconnect.await(5, TimeUnit.SECONDS)) { "Test did not release disconnect" }
                releaseResponse.countDown()
            }
        }
        val cache = CatalogMemoryCache<String, String>(1, 60_000)
        try {
            runBlocking {
                val first = launch(caller) {
                    cache.getOrLoad("same-key") {
                        readPokemonCatalogHttp("http://local.invalid", Call.Factory { call }) { "obsolete" }
                    }
                }
                val successorStarted = CompletableDeferred<Unit>()
                var second: kotlinx.coroutines.Deferred<String>? = null
                try {
                    assertTrue(responseEntered.await(2, TimeUnit.SECONDS))
                    first.cancel()
                    assertTrue(disconnectEntered.await(2, TimeUnit.SECONDS))
                    second = async(caller) {
                        cache.getOrLoad("same-key") {
                            assertEquals("Old reader must finish before successor takes ownership", 0L, readerExited.count)
                            successorStarted.complete(Unit)
                            "current"
                        }
                    }
                    // This is the caller/UI-thread analogue, not an assertion about cloud speed.
                    assertEquals("responsive", withTimeout(2_000) { async(caller) { "responsive" }.await() })
                    assertFalse("Cleanup still owns the first request", first.isCompleted)
                    assertFalse("The cache key stays locked until actual cleanup finishes", successorStarted.isCompleted)
                    releaseDisconnect.countDown()
                    first.join()
                    assertTrue(first.isCancelled)
                    assertEquals("current", second.await())
                    assertEquals("current", cache.getOrLoad("same-key") { error("Successor was not cached") })
                } finally {
                    releaseDisconnect.countDown()
                    releaseResponse.countDown()
                    first.cancel()
                    first.join()
                    second?.cancel()
                    second?.join()
                }
            }
        } finally {
            releaseDisconnect.countDown()
            releaseResponse.countDown()
            caller.close()
        }
    }

    @Test fun cancellationDuringCallPublicationDoesNotStartARequestOrWarmTheCache() {
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val requests = AtomicInteger()
        val disconnects = AtomicInteger()
        val call = object : FakeCall() {
            override fun execute(): Response { requests.incrementAndGet(); return response() }
            override fun cancel() { super.cancel(); disconnects.incrementAndGet() }
        }
        val cache = CatalogMemoryCache<String, String>(1, 60_000)
        try {
            runBlocking {
                val work = launch(caller) {
                    cache.getOrLoad("same-key") {
                        readPokemonCatalogHttp("http://local.invalid", Call.Factory {
                            factoryEntered.countDown()
                            check(releaseFactory.await(5, TimeUnit.SECONDS)) { "Test did not release factory" }
                            call
                        }) { "obsolete" }
                    }
                }
                try {
                    assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
                    work.cancel()
                    assertEquals("responsive", withTimeout(2_000) { async(caller) { "responsive" }.await() })
                    assertFalse("Do not abandon a connection factory still running on IO", work.isCompleted)
                    releaseFactory.countDown()
                    work.join()
                    assertTrue(work.isCancelled)
                    assertEquals(0, requests.get())
                    assertTrue("A connection published after cancellation still needs cleanup", disconnects.get() > 0)
                    assertEquals("fresh", cache.getOrLoad("same-key") { "fresh" })
                } finally {
                    releaseFactory.countDown()
                    work.cancel()
                    work.join()
                }
            }
        } finally {
            releaseFactory.countDown()
            caller.close()
        }
    }

    private abstract class FakeCall : Call {
        private val cancelled = AtomicBoolean(false)
        override fun request() = Request.Builder().url("http://127.0.0.1/unused").build()
        override fun enqueue(responseCallback: Callback) = error("Synchronous test transport only")
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted() = false
        override fun isCanceled() = cancelled.get()
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = error("Test calls must not be cloned")
        protected fun response(): Response = Response.Builder().request(request())
            .protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body("{}".toResponseBody()).build()
    }
}
