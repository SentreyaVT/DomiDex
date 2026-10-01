package com.dominik.cardvault.ui

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Actual production callback helper with controlled Call/ResponseBody doubles, no network/Bitmap.
 * Semaphore(2) mirrors the real caller's permit boundary; this is not a full verifier/device test.
 * Real callback threads must exit after finally releases the gates, without interrupting readers. */
class ReferenceArtworkHttpLifetimeTest {
    @Test fun cancellationKeepsTheDownloadSlotUntilItsBodyReaderActuallyEnds() = lifetimeCase(Stall.READ)

    @Test fun cancellationKeepsTheDownloadSlotUntilItsResponseCleanupActuallyEnds() = lifetimeCase(Stall.CLOSE)

    @Test fun successfulResponsePreservesBytesAndUserAgent() {
        val call = ControlledCall()
        try {
            assertArrayEquals(byteArrayOf(7, 9), runBlocking { downloadReferenceArtwork(URL, call.factory) })
            assertEquals("DomiDex scanner artwork verification", call.actualRequest.get().header("User-Agent"))
            assertTrue(call.bodyClosed.await(2, TimeUnit.SECONDS))
        } finally { call.releaseAndJoin() }
    }

    @Test fun nonSuccessfulResponseRemainsNullAndClosesWithoutReading() {
        val call = ControlledCall(status = 503)
        try {
            assertNull(runBlocking { downloadReferenceArtwork(URL, call.factory) })
            assertEquals(1L, call.readEntered.count)
            assertTrue(call.bodyClosed.await(2, TimeUnit.SECONDS))
        } finally { call.releaseAndJoin() }
    }

    @Test fun oversizedDeclaredBodyRemainsNullWithoutReading() {
        val call = ControlledCall(length = MAX_REFERENCE_ARTWORK_BYTES.toLong() + 1)
        try {
            assertNull(runBlocking { downloadReferenceArtwork(URL, call.factory) })
            assertEquals(1L, call.readEntered.count)
            assertTrue(call.bodyClosed.await(2, TimeUnit.SECONDS))
        } finally { call.releaseAndJoin() }
    }

    @Test fun bodyIoFailureRemainsNullAndStillCloses() {
        val call = ControlledCall(readFailure = true)
        try {
            assertNull(runBlocking { downloadReferenceArtwork(URL, call.factory) })
            assertTrue(call.bodyClosed.await(2, TimeUnit.SECONDS))
        } finally { call.releaseAndJoin() }
    }

    @Test fun ordinaryOnFailureStillReturnsNull() {
        val call = ControlledCall(transportFailure = IOException("Synthetic network failure"))
        try {
            assertNull(runBlocking { downloadReferenceArtwork(URL, call.factory) })
            assertEquals(1L, call.readEntered.count)
        } finally { call.releaseAndJoin() }
    }

    @Test fun factoryFailureDoesNotWaitForAnUnregisteredCallback() {
        val failure = runCatching {
            runBlocking {
                downloadReferenceArtwork(URL, Call.Factory { throw IllegalStateException("Synthetic factory failure") })
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Synthetic factory failure", failure?.message)
    }

    @Test fun rejectedEnqueueDoesNotWaitForAnUnregisteredCallback() {
        val call = ControlledCall(enqueueFailure = IllegalStateException("Synthetic rejected enqueue"))
        try {
            val failure = runCatching { runBlocking { downloadReferenceArtwork(URL, call.factory) } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Synthetic rejected enqueue", failure?.message)
            assertFalse(call.isExecuted())
        } finally { call.releaseAndJoin() }
    }

    @Test fun unexpectedCallbackFailureClosesAndEndsInsteadOfLeavingTheCallerWaiting() {
        val call = ControlledCall(metadataFailure = IllegalStateException("Synthetic metadata failure"))
        try {
            val failure = runCatching { runBlocking { downloadReferenceArtwork(URL, call.factory) } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Synthetic metadata failure", failure?.message)
            assertTrue(call.bodyClosed.await(2, TimeUnit.SECONDS))
        } finally { call.releaseAndJoin() }
    }

    @Test fun cancellationDuringFactoryPublicationDoesNotEnqueueOrBlockTheCallerThread() {
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val call = ControlledCall()
        try {
            runBlocking {
                val work = launch(caller) {
                    downloadReferenceArtwork(URL, Call.Factory { request ->
                        entered.countDown()
                        check(releaseFactory.await(5, TimeUnit.SECONDS)) { "Fixture factory gate not released" }
                        call.factory.newCall(request)
                    })
                }
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    work.cancel()
                    assertEquals("alive", async(caller) { "alive" }.await())
                    assertFalse("A factory still running on IO remains owned", work.isCompleted)
                    releaseFactory.countDown()
                    work.join()
                    assertTrue(work.isCancelled)
                    assertTrue(call.isCanceled())
                    assertFalse("A call published after cancellation must never enqueue", call.isExecuted())
                } finally { releaseFactory.countDown(); work.cancel(); work.join() }
            }
        } finally {
            releaseFactory.countDown()
            try { call.releaseAndJoin() } finally { caller.close() }
        }
    }

    private fun lifetimeCase(stall: Stall) {
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val first = ControlledCall(stall = stall)
        val second = ControlledCall(stall = stall)
        val third = ControlledCall(stall = Stall.READ)
        val permits = Semaphore(2)
        val staleResult = AtomicReference<ByteArray>()
        val thirdAcquired = AtomicBoolean(false)
        var oldCompletedBeforeRelease = false
        var thirdAcquiredBeforeRelease = false
        try {
            runBlocking {
                val firstJob = launch(caller) {
                    permits.withPermit { downloadReferenceArtwork(URL, first.factory)?.let(staleResult::set) }
                }
                val secondJob = launch(caller) {
                    permits.withPermit { downloadReferenceArtwork(URL, second.factory)?.let(staleResult::set) }
                }
                var thirdJob: kotlinx.coroutines.Job? = null
                try {
                    assertTrue(first.stallEntered.await(2, TimeUnit.SECONDS))
                    assertTrue(second.stallEntered.await(2, TimeUnit.SECONDS))
                    firstJob.cancel(); secondJob.cancel()
                    assertTrue(first.cancelRequested.await(2, TimeUnit.SECONDS))
                    assertTrue(second.cancelRequested.await(2, TimeUnit.SECONDS))
                    // A queued heartbeat proves the same caller thread remains available.
                    assertEquals("alive", async(caller) { "alive" }.await())
                    thirdJob = launch(caller) {
                        permits.withPermit {
                            thirdAcquired.set(true)
                            downloadReferenceArtwork(URL, third.factory)
                        }
                    }
                    // This single-thread barrier runs after the third attempt either acquires
                    // the permit or suspends on it. No sleep or scheduler-luck assertion needed.
                    async(caller) { Unit }.await()
                    oldCompletedBeforeRelease = firstJob.isCompleted || secondJob.isCompleted
                    thirdAcquiredBeforeRelease = thirdAcquired.get()
                    assertEquals(1L, first.callbackFinished.count)
                    assertEquals(1L, second.callbackFinished.count)
                } finally {
                    first.release(); second.release(); third.release()
                    firstJob.cancel(); secondJob.cancel(); thirdJob?.cancel()
                    firstJob.join(); secondJob.join(); thirdJob?.join()
                }
                assertTrue(firstJob.isCancelled && secondJob.isCancelled)
            }
        } finally {
            val cleanupFailures = try {
                listOf(first, second, third).mapNotNull { call -> runCatching { call.releaseAndJoin() }.exceptionOrNull() }
            } finally { caller.close() }
            cleanupFailures.firstOrNull()?.let { failure ->
                cleanupFailures.drop(1).forEach(failure::addSuppressed)
                throw failure
            }
        }
        assertNull("Old callbacks must never publish a cancelled result", staleResult.get())
        assertFalse("Download returned while its actual callback/response still owned resources ($stall)", oldCompletedBeforeRelease)
        assertFalse("A third download took a slot before the old callback/response ended ($stall)", thirdAcquiredBeforeRelease)
    }

    private enum class Stall { NONE, READ, CLOSE }

    private class ControlledCall(
        private val stall: Stall = Stall.NONE,
        private val status: Int = 200,
        private val length: Long = 2,
        private val readFailure: Boolean = false,
        private val transportFailure: IOException? = null,
        private val enqueueFailure: RuntimeException? = null,
        private val metadataFailure: RuntimeException? = null,
    ) : Call {
        val readEntered = CountDownLatch(1)
        private val closeEntered = CountDownLatch(1)
        val bodyClosed = CountDownLatch(1)
        val cancelRequested = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val actualRequest = AtomicReference<Request>()
        val stallEntered get() = if (stall == Stall.CLOSE) closeEntered else readEntered
        private val releaseRead = CountDownLatch(if (stall == Stall.READ) 1 else 0)
        private val releaseClose = CountDownLatch(if (stall == Stall.CLOSE) 1 else 0)
        private val failure = AtomicReference<Throwable>()
        private val callbackThread = AtomicReference<Thread>()
        private val cancelled = AtomicBoolean(false)
        private val enqueued = AtomicBoolean(false)
        val factory = Call.Factory { request -> actualRequest.set(request); this }

        override fun request() = actualRequest.get() ?: Request.Builder().url(URL).build()
        override fun execute(): Response = error("Original artwork path uses callbacks")
        override fun enqueue(responseCallback: Callback) {
            enqueueFailure?.let { throw it }
            check(enqueued.compareAndSet(false, true))
            val reader = Thread({
                try {
                    transportFailure?.let { responseCallback.onFailure(this, it); return@Thread }
                    var emitted = false
                    val source = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            readEntered.countDown()
                            check(releaseRead.await(5, TimeUnit.SECONDS)) { "Fixture read gate not released" }
                            if (readFailure) throw IOException("Synthetic body failure")
                            if (emitted) return -1
                            emitted = true
                            sink.write(byteArrayOf(7, 9))
                            return 2
                        }
                        override fun close() {
                            closeEntered.countDown()
                            check(releaseClose.await(5, TimeUnit.SECONDS)) { "Fixture close gate not released" }
                            bodyClosed.countDown()
                        }
                        override fun timeout() = Timeout.NONE
                    }.buffer()
                    val body = object : ResponseBody() {
                        override fun contentType(): MediaType? = null
                        override fun contentLength(): Long { metadataFailure?.let { throw it }; return length }
                        override fun source() = source
                    }
                    responseCallback.onResponse(this, Response.Builder().request(request())
                        .protocol(Protocol.HTTP_1_1).code(status).message("Synthetic").body(body).build())
                } catch (error: Throwable) { failure.set(error) }
                finally { callbackFinished.countDown() }
            }, "artwork-callback-fixture-${stall.name}")
            callbackThread.set(reader)
            reader.start()
        }
        override fun cancel() { cancelled.set(true); cancelRequested.countDown() }
        override fun isExecuted() = enqueued.get()
        override fun isCanceled() = cancelled.get()
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = error("Fixture calls are single use")
        fun release() { releaseRead.countDown(); releaseClose.countDown() }
        fun releaseAndJoin() {
            release()
            callbackThread.get()?.let {
                it.join(5_000)
                check(!it.isAlive) { "Actual artwork callback thread did not finish" }
            }
            failure.get()?.let { throw AssertionError("Controlled callback failed", it) }
        }
    }

    private companion object { const val URL = "https://synthetic.invalid/reference.png" }
}
