package com.dominik.cardvault.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

/** Real coordinator read seam with a local suspend supplier; not a live Firebase transport test. */
class CollectionCloudConflictReadTest {
    @Test fun activeReadReturnsItsValue() = runBlocking {
        assertEquals("snapshot", readCollectionCloudConflict({ true }) { "snapshot" })
    }

    @Test fun alreadyStaleActionDoesNotStartTheRead() = runBlocking {
        var reads = 0
        val failure = runCatching { readCollectionCloudConflict({ false }) { reads++; "snapshot" } }.exceptionOrNull()
        assertTrue(failure is PrivatePullAccountChangedException)
        assertEquals(0, reads)
    }

    @Test fun roundTripDuringReadCannotReturnOldActionSnapshot() = runBlocking {
        var generation = 1
        val failure = runCatching {
            readCollectionCloudConflict({ generation == 1 }) { generation += 2; "snapshot" }
        }.exceptionOrNull()
        assertTrue(failure is PrivatePullAccountChangedException)
    }

    @Test fun cancellationIsNotAnOfflineResult() = runBlocking {
        val cancel = CancellationException("synthetic")
        val failure = runCatching { readCollectionCloudConflict({ true }) { throw cancel } }.exceptionOrNull()
        assertSame(cancel, failure)
    }

    @Test fun ordinaryOfflineFailureLeavesTheActionUnresolved() = runBlocking {
        assertNull(readCollectionCloudConflict({ true }) { throw IllegalStateException("synthetic offline") })
    }

    @Test fun accountChangeDuringFailedReadIsStillNotSwallowed() = runBlocking {
        var current = true
        val failure = runCatching {
            readCollectionCloudConflict({ current }) { current = false; throw IllegalStateException("synthetic offline") }
        }.exceptionOrNull()
        assertTrue(failure is PrivatePullAccountChangedException)
    }

    @Test fun suspendedPreLeaseDaoRoundTripCannotStartFreshPriceLease() = runBlocking {
        var generation = 1
        val capturedGeneration = generation // Production captures before querying conflict rows.
        var newLeases = 0
        val daoCompletes = CompletableDeferred<List<String>>()
        val action = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                val rows = guardedConflictActionRead({ generation == capturedGeneration }) { daoCompletes.await() }
                if (rows.isNotEmpty()) newLeases++
            }.exceptionOrNull()
        }
        generation += 2 // A -> B -> A while DAO is suspended; UID equality would miss this.
        daoCompletes.complete(listOf("synthetic-conflict"))
        assertTrue(action.await() is PrivatePullAccountChangedException)
        assertEquals(0, newLeases)
    }

    @Test fun activePreLeaseDaoReadCanContinueAndCancellationCannot() = runBlocking {
        val result = guardedConflictActionRead({ true }) { listOf("synthetic-conflict") }
        assertEquals(listOf("synthetic-conflict"), result)
        val cancelled = CompletableDeferred<List<String>>()
        cancelled.cancel(CancellationException("synthetic DAO cancellation"))
        val failure = runCatching { guardedConflictActionRead({ true }) { cancelled.await() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun preLeaseLocalDatabaseFailureIsNotMisrepresentedAsEmptyConflictList() = runBlocking {
        val databaseFailure = IllegalStateException("synthetic DAO failure")
        assertSame(databaseFailure,
            runCatching { guardedConflictActionRead({ true }) { throw databaseFailure } }.exceptionOrNull())
    }

    @Test fun capturedActionRejectsRoundTripWhileWaitingInDispatcher() = runBlocking {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        var generation = 1
        var reads = 0
        val action = async(start = CoroutineStart.UNDISPATCHED) {
            val captured = generation // Capture before withContext, as the production entrypoints do.
            runCatching {
                withContext(dispatcher) {
                    guardedConflictActionRead({ generation == captured }) { reads++; "snapshot" }
                }
            }.exceptionOrNull()
        }
        assertEquals(1, queued.size)
        generation += 2
        queued.removeFirst().run() // Deterministic dispatch: no sleep, clock, or scheduler race.
        assertTrue(action.await() is PrivatePullAccountChangedException)
        assertEquals(0, reads)
        assertTrue(queued.isEmpty())
    }
}
