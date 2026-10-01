package com.dominik.cardvault.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic application-level read accounting; no Firestore requests or billing assertions. */
class PrivateFullPullSafetyTest {
    @Test fun pagesAndBothParentKindsShareTheSameBudget() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 1_000)
        repeat(4) {
            assertEquals(200, budget.nextPageSize(200))
            budget.recordPageResult(requestedSize = 200, returnedDocuments = 200)
        }
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(BINDER, "binder"))
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "entry"))
        assertEquals(198, budget.nextPageSize(200))
        budget.recordPageResult(requestedSize = 198, returnedDocuments = 198)
        assertEquals(1_000, budget.usedReads)
        assertEquals(0, budget.remainingReads)
        assertEquals(0, budget.nextPageSize(200))
        assertEquals(PrivateDependencyReadAllowance.BudgetExhausted, budget.reserveDependency(ENTRY, "next"))
    }

    @Test fun aHundredMissingSameParentsCostOnlyOnePointReadInTheRun() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 1_000)
        // Reservation precedes the request. A missing server document gets no refund.
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "missing"))
        repeat(99) {
            assertEquals(PrivateDependencyReadAllowance.AlreadyAttempted, budget.reserveDependency(ENTRY, "missing"))
        }
        assertEquals(1, budget.usedReads)
        assertEquals(1, budget.attemptedDependencyCount)
    }

    @Test fun sameIdInDifferentParentKindsNeedsSeparateReads() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 3)
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(BINDER, "same"))
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "same"))
        assertEquals(2, budget.usedReads)
    }

    @Test fun newRunAndOtherOwnerNeverReuseMissingParentCache() {
        for (budget in listOf(PrivateFullPullReadBudget(OWNER), PrivateFullPullReadBudget(OWNER), PrivateFullPullReadBudget("owner-b"))) {
            assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "missing"))
            assertEquals(1, budget.usedReads)
        }
    }

    @Test fun alreadyAttemptedAndNeverAttemptedRemainDistinctAtZeroBudget() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 1)
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "attempted"))
        assertEquals(PrivateDependencyReadAllowance.AlreadyAttempted, budget.reserveDependency(ENTRY, "attempted"))
        assertEquals(PrivateDependencyReadAllowance.BudgetExhausted, budget.reserveDependency(ENTRY, "not-yet-read"))
        // The exhausted key was NOT memoized: a continuation run must actually try it.
        assertEquals(1, budget.attemptedDependencyCount)
        assertEquals(PrivateDependencyReadAllowance.Allowed,
            PrivateFullPullReadBudget(OWNER, limit = 1).reserveDependency(ENTRY, "not-yet-read"))
    }

    @Test fun eachEmptyPhaseCostsOneAndStopsAtZeroWithoutNegativeAccounting() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 4)
        repeat(4) { index ->
            assertEquals(4 - index, budget.nextPageSize(200))
            budget.recordPageResult(requestedSize = 4 - index, returnedDocuments = 0)
        }
        assertEquals(4, budget.usedReads)
        assertEquals(0, budget.nextPageSize(200))
    }

    @Test fun shortPageChargesReturnedDocumentsNotItsRequestedCapacity() {
        val budget = PrivateFullPullReadBudget(OWNER)
        budget.recordPageResult(requestedSize = 200, returnedDocuments = 7)
        assertEquals(7, budget.usedReads)
        assertEquals(993, budget.remainingReads)
    }

    @Test fun fetchedPageIsFullyChargedEvenIfLaterApplicationStopsMidPage() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 6)
        budget.recordPageResult(requestedSize = 4, returnedDocuments = 4)
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(BINDER, "b"))
        assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "e"))
        assertEquals(PrivateDependencyReadAllowance.BudgetExhausted, budget.reserveDependency(ENTRY, "later"))
        assertEquals(6, budget.usedReads)
    }

    @Test fun invalidPageAccountingCannotChangeTheBudget() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 5)
        for ((requested, returned) in listOf(0 to 0, -1 to 0, 6 to 0, 3 to 4, 3 to -1)) {
            assertThrows(IllegalArgumentException::class.java) { budget.recordPageResult(requested, returned) }
            assertEquals(5, budget.remainingReads)
        }
        assertThrows(IllegalArgumentException::class.java) { budget.nextPageSize(0) }
        assertThrows(IllegalArgumentException::class.java) { budget.nextPageSize(-1) }
    }

    @Test fun invalidIdentityDoesNotConsumeAReadOrCreateCacheEntries() {
        val budget = PrivateFullPullReadBudget(OWNER)
        for (id in listOf("", "bad/id", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { budget.reserveDependency(ENTRY, id) }
        }
        assertEquals(0, budget.usedReads)
        assertEquals(0, budget.attemptedDependencyCount)
        assertThrows(IllegalArgumentException::class.java) { PrivateFullPullReadBudget(" ") }
        assertThrows(IllegalArgumentException::class.java) { PrivateFullPullReadBudget(OWNER, limit = 0) }
    }

    @Test fun dependencyMemoizationCannotGrowPastTheReadBudget() {
        val budget = PrivateFullPullReadBudget(OWNER, limit = 5)
        repeat(5) { assertEquals(PrivateDependencyReadAllowance.Allowed, budget.reserveDependency(ENTRY, "entry-$it")) }
        repeat(100) { assertEquals(PrivateDependencyReadAllowance.BudgetExhausted, budget.reserveDependency(ENTRY, "extra-$it")) }
        assertEquals(5, budget.attemptedDependencyCount)
        assertEquals(5, budget.usedReads)
    }

    @Test fun concurrentSameOwnerRunsOnlyOneBlock() = runBlocking {
        val gate = PrivateFullPullSingleFlight()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async {
            gate.tryRun(OWNER) { calls++; entered.complete(Unit); release.await(); "first" }
        }
        entered.await()
        assertEquals(PrivateFullPullFlightResult.OwnerBusy, gate.tryRun(OWNER) { calls++; "second" })
        assertEquals(1, calls)
        assertEquals(1, gate.activeOwnerCount)
        release.complete(Unit)
        assertEquals(PrivateFullPullFlightResult.Ran("first"), first.await())
        assertEquals(0, gate.activeOwnerCount)
    }

    @Test fun differentOwnerCanRunWhileFirstOwnerWaits() = runBlocking {
        val gate = PrivateFullPullSingleFlight()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.tryRun(OWNER) { entered.complete(Unit); release.await() } }
        entered.await()
        assertEquals(PrivateFullPullFlightResult.Ran("other"), gate.tryRun("owner-b") { "other" })
        release.complete(Unit)
        first.await()
        assertEquals(0, gate.activeOwnerCount)
    }

    @Test fun blockFailureReleasesOwnerAndDoesNotHideException() = runBlocking {
        val gate = PrivateFullPullSingleFlight()
        val expected = IllegalStateException("synthetic failure")
        val actual = runCatching { gate.tryRun(OWNER) { throw expected } }.exceptionOrNull()
        assertSame(expected, actual)
        assertEquals(0, gate.activeOwnerCount)
        assertEquals(PrivateFullPullFlightResult.Ran(7), gate.tryRun(OWNER) { 7 })
    }

    @Test fun cancellationReleasesOwnerAndAllowsLaterRun() = runBlocking {
        val gate = PrivateFullPullSingleFlight()
        val entered = CompletableDeferred<Unit>()
        val job = launch { gate.tryRun(OWNER) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, gate.activeOwnerCount)
        assertEquals(PrivateFullPullFlightResult.Ran("new"), gate.tryRun(OWNER) { "new" })
    }

    @Test fun activeOwnerMapHasHardCapacityAndDoesNotRetainHistoricalOwners() = runBlocking {
        val gate = PrivateFullPullSingleFlight(maxConcurrentOwners = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.tryRun(OWNER) { entered.complete(Unit); release.await() } }
        entered.await()
        assertEquals(PrivateFullPullFlightResult.CapacityReached, gate.tryRun("owner-b") { error("must not run") })
        assertEquals(PrivateFullPullFlightResult.OwnerBusy, gate.tryRun(OWNER) { error("must not run") })
        assertEquals(1, gate.activeOwnerCount)
        release.complete(Unit)
        first.await()
        repeat(100) { index ->
            assertEquals(PrivateFullPullFlightResult.Ran(index), gate.tryRun("owner-$index") { index })
            assertEquals(0, gate.activeOwnerCount)
        }
    }

    @Test fun reentrantSameOwnerSkipsInsteadOfDeadlocking() = runBlocking {
        val gate = PrivateFullPullSingleFlight()
        val result = gate.tryRun(OWNER) { gate.tryRun(OWNER) { error("must not run") } }
        assertEquals(PrivateFullPullFlightResult.Ran(PrivateFullPullFlightResult.OwnerBusy), result)
    }

    @Test fun malformedOwnerAndUnboundedCapacityAreRejectedWithoutRunning() = runBlocking<Unit> {
        val gate = PrivateFullPullSingleFlight()
        assertTrue(runCatching { gate.tryRun(" ") { error("must not run") } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, gate.activeOwnerCount)
        assertThrows(IllegalArgumentException::class.java) { PrivateFullPullSingleFlight(0) }
        assertThrows(IllegalArgumentException::class.java) { PrivateFullPullSingleFlight(Int.MAX_VALUE) }
    }

    companion object {
        private const val OWNER = "synthetic-pull-owner"
        private val BINDER = PrivateRemoteDependencyType.BINDER
        private val ENTRY = PrivateRemoteDependencyType.COLLECTION_ENTRY
    }
}
