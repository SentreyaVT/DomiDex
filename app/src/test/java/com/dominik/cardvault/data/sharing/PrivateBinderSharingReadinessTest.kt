package com.dominik.cardvault.data.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivateBinderSharingReadinessTest {
    @Test fun acknowledgedBinderDoesNotScheduleMoreWork() = runBlocking {
        val readiness = PrivateBinderSharingReadiness(
            { owner, binder ->
                assertEquals("owner-a", owner)
                assertEquals("selected", binder)
                MutableStateFlow(1)
            },
            { _, _ -> fail("Already acknowledged") },
        )
        readiness.awaitReady("owner-a", "selected")
    }

    @Test(timeout = 5_000) fun pendingBinderWakesExistingSyncOnceAndWaitsForAcknowledgement() = runBlocking {
        val state = MutableStateFlow(0)
        val requested = CompletableDeferred<Unit>()
        val targets = mutableListOf<Pair<String, String>>()
        val readiness = PrivateBinderSharingReadiness({ _, _ -> state }, { owner, binder ->
            targets += owner to binder
            requested.complete(Unit)
        })
        val pending = async { readiness.awaitReady("owner-a", "selected") }
        requested.await()
        assertFalse(pending.isCompleted)
        state.value = 1
        pending.await()
        assertEquals(listOf("owner-a" to "selected"), targets)
    }

    @Test fun missingBinderFailsWithoutSyncOrChangingItsOwner() = runBlocking {
        val readiness = PrivateBinderSharingReadiness({ _, _ -> MutableStateFlow(-1) }, { _, _ -> fail("Missing binder") })
        val failure = failure { readiness.awaitReady("owner-a", "missing") }
        assertTrue(failure.message.orEmpty().contains("nicht mehr verfügbar"))
    }

    @Test(timeout = 5_000) fun pendingSyncHasABoundedActionableFailure() = runBlocking {
        var scheduled = 0
        val readiness = PrivateBinderSharingReadiness({ _, _ -> MutableStateFlow(0) }, { _, _ -> scheduled++ }, timeoutMillis = 50)
        val failure = failure { readiness.awaitReady("owner-a", "selected") }
        assertEquals(1, scheduled)
        assertTrue(failure.message.orEmpty().contains("Internetverbindung"))
        assertTrue(failure.message.orEmpty().contains("Freigaben aktualisieren"))
        assertNull(failure.activeShares)
    }

    @Test(timeout = 5_000) fun callerCancellationStopsWaitingWithoutAFalseSyncFailure() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val readiness = PrivateBinderSharingReadiness({ _, _ -> MutableStateFlow(0) }, { _, _ -> requested.complete(Unit) })
        val pending = launch {
            try { readiness.awaitReady("owner-a", "selected"); fail("Must cancel") }
            catch (failure: BinderSharingException) { fail("Cancellation is not a sync failure") }
        }
        requested.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
    }

    private suspend fun failure(block: suspend () -> Unit): BinderSharingSyncException {
        try { block() } catch (failure: BinderSharingSyncException) { return failure }
        throw AssertionError("Expected sync failure")
    }
}
