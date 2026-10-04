package com.dominik.cardvault.data.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Synthetic callback exceptions test Kotlin result semantics, NOT SQLite storage behavior. */
class CardmarketImportResultTest {
    @Test fun successfulOperationNeverInvokesErrorReporter(): Unit = runBlocking {
        var operations = 0
        var reports = 0
        val result = cardmarketImportResult({ operations++ }, { reports++ })
        assertEquals(Unit, result.getOrThrow())
        assertEquals(1, operations)
        assertEquals(0, reports)
    }

    @Test fun reportedPrimaryFailureRemainsTheOriginalFailedResult(): Unit = runBlocking {
        val primary = IOException("synthetic primary")
        var reports = 0
        val result = cardmarketImportResult({ throw primary }, { error ->
            reports++
            assertSame(primary, error)
        })
        assertTrue(result.isFailure)
        assertSame(primary, result.exceptionOrNull())
        assertEquals(1, reports)
        assertTrue(primary.suppressed.isEmpty())
    }

    @Test fun secondaryReporterFailureIsSuppressedOnOriginalWithoutEscapingResult(): Unit = runBlocking {
        val primary = IOException("synthetic primary")
        val secondary = IllegalStateException("synthetic secondary reporter failure")
        var reports = 0
        val returned = runCatching {
            cardmarketImportResult({ throw primary }, { error ->
                reports++
                assertSame(primary, error)
                throw secondary
            })
        }
        assertEquals(1, reports)
        assertTrue("Reporter failure must not escape the Result contract", returned.isSuccess)
        assertTrue(returned.getOrThrow().isFailure)
        assertSame(primary, returned.getOrThrow().exceptionOrNull())
        assertEquals(1, primary.suppressed.size)
        assertSame(secondary, primary.suppressed.single())
    }

    @Test fun sameThrowableFromReporterCannotCreateSelfSuppressionOrEscape(): Unit = runBlocking {
        val primary = IOException("synthetic shared exception")
        val returned = runCatching { cardmarketImportResult({ throw primary }, { throw primary }) }
        assertTrue("The original failure is already retained", returned.isSuccess)
        assertTrue(returned.getOrThrow().isFailure)
        assertSame(primary, returned.getOrThrow().exceptionOrNull())
        assertTrue(primary.suppressed.isEmpty())
    }

    @Test fun primaryCancellationPropagatesUnchangedWithoutReporting(): Unit = runBlocking {
        val cancellation = CancellationException("synthetic primary cancellation")
        var reports = 0
        val thrown = runCatching { cardmarketImportResult({ throw cancellation }, { reports++ }) }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertEquals(0, reports)
    }

    @Test fun secondaryCancellationPropagatesUnchangedInsteadOfReturningPrimary(): Unit = runBlocking {
        val primary = IOException("synthetic primary")
        val cancellation = CancellationException("synthetic reporter cancellation")
        var reports = 0
        val thrown = runCatching {
            cardmarketImportResult({ throw primary }, { reports++; throw cancellation })
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertEquals(1, reports)
        assertTrue(primary.suppressed.isEmpty())
    }

    @Test fun suspendingReporterKeepsExistingImportGuardUntilCompletion(): Unit = runBlocking {
        withTimeout(2_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val primary = IOException("synthetic primary")
            var secondEntered = false
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                CardmarketImportGuard.run {
                    cardmarketImportResult({ throw primary }, { entered.complete(Unit); release.await() })
                }
            }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                CardmarketImportGuard.run { secondEntered = true }
            }
            try { assertFalse(secondEntered) }
            finally { release.complete(Unit); first.await(); second.await() }
            assertSame(primary, first.await().exceptionOrNull())
            assertTrue(secondEntered)
        }
    }

    @Test fun existingImportGuardReleasesEvenWhenReporterFails(): Unit = runBlocking {
        val primary = IOException("synthetic primary")
        val secondary = IllegalStateException("synthetic reporter failure")
        // This is an unchanged mutex control: the old baseline may throw, the fix returns failure.
        runCatching { CardmarketImportGuard.run { cardmarketImportResult({ throw primary }, { throw secondary }) } }
        assertEquals(7, withTimeout(1_000) { CardmarketImportGuard.run { 7 } })
    }

    @Test fun existingImportGuardReleasesAfterReporterCancellation(): Unit = runBlocking {
        val primary = IOException("synthetic primary")
        val cancellation = CancellationException("synthetic reporter cancellation")
        val thrown = runCatching {
            CardmarketImportGuard.run { cardmarketImportResult({ throw primary }, { throw cancellation }) }
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertEquals(9, withTimeout(1_000) { CardmarketImportGuard.run { 9 } })
    }

    @Test fun cancellationMaskedByReporterCleanupCannotEscapeAsOrdinaryFailure(): Unit = runBlocking {
        withTimeout(2_000) {
            val primary = IOException("synthetic primary")
            val secondary = IllegalStateException("synthetic non-cancellation cleanup mask")
            val cancellation = CancellationException("actual fixture job cancellation")
            var escaped: Throwable? = null
            var continued = false
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    CardmarketImportGuard.run {
                        cardmarketImportResult({ throw primary }, {
                            currentCoroutineContext().cancel(cancellation)
                            throw secondary
                        })
                        continued = true
                    }
                } catch (error: Throwable) { escaped = error }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse("Cancelled reporter cannot return normally", continued)
            assertTrue("Actual cancellation must win over its non-CE cleanup mask", escaped is CancellationException)
            assertTrue(primary.suppressed.isEmpty())
            assertEquals(13, CardmarketImportGuard.run { 13 })
        }
    }

    @Test fun actualCancellationWhileReporterSuspendsCannotCompleteNormally(): Unit = runBlocking {
        withTimeout(2_000) {
            val entered = CompletableDeferred<Unit>()
            val reporterFinished = CompletableDeferred<Unit>()
            var returnedNormally = false
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                CardmarketImportGuard.run {
                    cardmarketImportResult({ throw IOException("synthetic primary") }, {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { reporterFinished.complete(Unit) }
                    })
                    returnedNormally = true
                }
            }
            entered.await()
            try { job.cancel(CancellationException("actual fixture job cancellation")) }
            finally { job.cancel(); job.join() }
            reporterFinished.await()
            assertTrue(job.isCancelled)
            assertFalse(returnedNormally)
            assertEquals(11, CardmarketImportGuard.run { 11 })
        }
    }
}
