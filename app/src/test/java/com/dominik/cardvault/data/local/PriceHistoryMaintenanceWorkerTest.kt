package com.dominik.cardvault.data.local

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PriceHistoryMaintenanceWorkerTest {
    private fun more() = PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.MORE, 1024, 17)

    @Test fun noTicketCompletesWithoutEnqueue(): Unit = runBlocking {
        var calls = 0
        val result = runPriceHistoryMaintenanceSlice(
            { calls++; PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.NO_TICKET) },
            { fail("No successor without pending work") }, { 0L },
        )
        assertEquals(1, calls)
        assertEquals(MaintenanceSlice(MaintenanceSliceStatus.COMPLETE, 1, 0, 0), result)
    }

    @Test fun completedPassWithNoPendingCompletesWithoutEnqueue(): Unit = runBlocking {
        val result = runPriceHistoryMaintenanceSlice(
            { PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.PASS_COMPLETE, 3, 2) },
            { fail("Completed pass must not append") }, { 0L },
        )
        assertEquals(MaintenanceSlice(MaintenanceSliceStatus.COMPLETE, 1, 3, 2), result)
    }

    @Test fun newerPendingTicketIsDrainedInsteadOfLostAtOldPassCompletion(): Unit = runBlocking {
        var calls = 0
        val result = runPriceHistoryMaintenanceSlice(
            {
                calls++
                if (calls == 1) PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.PASS_COMPLETE,
                    9, 2, pendingAnotherPass = true)
                else PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.PASS_COMPLETE, 4, 1)
            }, { fail("Both passes fit the slice") }, { 0L },
        )
        assertEquals(MaintenanceSlice(MaintenanceSliceStatus.COMPLETE, 2, 13, 3), result)
    }

    @Test fun invalidTicketPausesWithoutContinuingOrLooping(): Unit = runBlocking {
        var calls = 0
        val result = runPriceHistoryMaintenanceSlice(
            { calls++; PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.INVALID_TICKET) },
            { fail("Invalid clock must not hot-loop") }, { 0L },
        )
        assertEquals(1, calls)
        assertEquals(MaintenanceSliceStatus.PAUSED_INVALID_TICKET, result.status)
    }

    @Test fun invalidTicketAfterCommittedBatchIsNotComplete(): Unit = runBlocking {
        var calls = 0
        val result = runPriceHistoryMaintenanceSlice(
            { if (++calls == 1) more() else PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.INVALID_TICKET) },
            { fail("Clock pause must not self-enqueue") }, { 0L },
        )
        assertEquals(MaintenanceSlice(MaintenanceSliceStatus.PAUSED_INVALID_TICKET, 2, 1024, 17), result)
    }

    @Test fun rowBoundRequiresDurableContinuationAcknowledgement(): Unit = runBlocking {
        var calls = 0
        var enqueues = 0
        val acknowledged = CompletableDeferred<Unit>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runPriceHistoryMaintenanceSlice({ calls++; more() }, { enqueues++; acknowledged.await() }, { 0L })
        }
        assertEquals(128, calls)
        assertEquals(1, enqueues)
        assertFalse(result.isCompleted)
        acknowledged.complete(Unit)
        assertEquals(MaintenanceSlice(MaintenanceSliceStatus.CONTINUED, 128, 131072, 2176), result.await())
    }

    @Test fun elapsedBudgetStopsOnlyBetweenBatches(): Unit = runBlocking {
        var now = 0L
        var calls = 0
        var enqueues = 0
        val result = runPriceHistoryMaintenanceSlice(
            { calls++; now = 30_000L; more() }, { enqueues++ }, { now },
        )
        assertEquals(1, calls)
        assertEquals(1, enqueues)
        assertEquals(MaintenanceSliceStatus.CONTINUED, result.status)
    }

    @Test fun slowFinalBatchStillCompletesWithoutSpuriousContinuation(): Unit = runBlocking {
        var now = 0L
        val result = runPriceHistoryMaintenanceSlice(
            { now = 65_000L; PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.PASS_COMPLETE) },
            { fail("A SQL deadline must not be fabricated") }, { now },
        )
        assertEquals(MaintenanceSliceStatus.COMPLETE, result.status)
    }

    @Test fun unknownEnqueueFailureNeverReportsContinued(): Unit = runBlocking {
        val expected = IOException("synthetic unknown enqueue outcome")
        var calls = 0
        try {
            runPriceHistoryMaintenanceSlice({ calls++; more() }, { throw expected }, { 0L })
            fail("Enqueue was not confirmed")
        } catch (actual: IOException) { assertSame(expected, actual) }
        assertEquals(128, calls)
    }

    @Test fun batchFailurePropagatesWithoutEnqueue(): Unit = runBlocking {
        val expected = IOException("synthetic transaction failure")
        try {
            runPriceHistoryMaintenanceSlice({ throw expected }, { fail("No false continuation") }, { 0L })
            fail("Transaction error was swallowed")
        } catch (actual: IOException) { assertSame(expected, actual) }
    }

    @Test fun actualCancellationAfterBatchPreventsContinuation(): Unit = runBlocking {
        var enqueues = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runPriceHistoryMaintenanceSlice({ currentCoroutineContext().cancel(); more() }, { enqueues++ }, { 0L })
        }
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(0, enqueues)
    }

    @Test fun actualCancellationWhileAwaitingEnqueueDoesNotClaimAcknowledgement(): Unit = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var enqueues = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runPriceHistoryMaintenanceSlice({ more() }, { enqueues++; pending.await() }, { 0L })
        }
        assertEquals(1, enqueues)
        result.cancel()
        result.join()
        assertTrue(result.isCancelled)
        assertFalse(pending.isCompleted) // Cancellation does not assert that an enqueue never committed.
    }

    @Test fun invalidMonotonicClockFailsClosedBeforeBatch(): Unit = runBlocking {
        var reads = 0
        try {
            runPriceHistoryMaintenanceSlice({ fail("Clock invalid"); more() }, { fail("Clock invalid") },
                { if (reads++ == 0) 10L else 9L })
            fail("Backwards monotonic clock accepted")
        } catch (_: IllegalStateException) { /* Explicit fail-closed result. */ }
    }

    @Test fun retriesHaveExactFiniteAttemptCap() {
        assertFalse(maintenanceCanRetry(-1))
        assertTrue(maintenanceCanRetry(0))
        assertTrue(maintenanceCanRetry(1))
        assertFalse(maintenanceCanRetry(2))
        assertFalse(maintenanceCanRetry(3))
        assertFalse(maintenanceCanRetry(Int.MAX_VALUE))
    }
}
