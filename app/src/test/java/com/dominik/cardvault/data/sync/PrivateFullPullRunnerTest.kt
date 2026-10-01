package com.dominik.cardvault.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production FULL-scan runner through a synthetic, non-network adapter. */
class PrivateFullPullRunnerTest {
    @Test fun fourEmptyPhasesAreChargedAndReset() = runBlocking {
        val port = FakePort()
        val budget = budget(4)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget))
        assertEquals(listOf(0, 1, 2, 3), port.queries.map { it.phase })
        assertEquals(listOf(4, 3, 2, 1), port.queries.map { it.limit })
        assertEquals(4, budget.usedReads)
        assertEquals(0, port.phase)
        assertEquals(1, port.resets)
    }

    @Test fun durablePhaseFourAfterInterruptionResetsWithoutAnotherRead() = runBlocking {
        val port = FakePort(initialPhase = 4)
        val budget = budget(1)
        budget.reserveDependency(ENTRY, "used")
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget))
        assertTrue(port.queries.isEmpty())
        assertEquals(0, port.phase)
        assertEquals(1, port.resets)
    }

    @Test fun lastEmptyPhaseCompletesEvenWhenItsReadExhaustsBudget() = runBlocking {
        val port = FakePort(initialPhase = 3)
        val budget = budget(1)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget))
        assertEquals(0, budget.remainingReads)
        assertEquals(1, port.resets)
        assertEquals(0, port.phase)
    }

    @Test fun emptyEarlierPhaseAtZeroBudgetReturnsMoreInsteadOfSpinning() = runBlocking {
        val port = FakePort()
        assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, budget(1)))
        assertEquals(1, port.queries.size)
        assertEquals(1, port.phase)
        assertEquals(0, port.resets)
        assertNull(port.cursors[0])
    }

    @Test fun pageBoundariesUseLastDurableDocumentAndPreserveAllRows() = runBlocking {
        val port = FakePort(rows = mapOf(0 to (1..5).map { row("a$it") }))
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(20), maxPageSize = 2))
        assertEquals(listOf(null, "a2", "a4"), port.queries.filter { it.phase == 0 }.map { it.after })
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5"), port.saved.map { it.second })
        assertEquals(1, port.resets)
        assertTrue(port.cursors.isEmpty())
    }

    @Test fun fullPageAtExactBudgetContinuesUntilEndIsActuallyObserved() = runBlocking {
        val port = FakePort(initialPhase = 3, rows = mapOf(3 to listOf(row("a"), row("b"))))
        assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, budget(2), maxPageSize = 2))
        assertEquals("b", port.cursors[3])
        assertEquals(0, port.resets)
        // Fair rotation revisits the earlier groups rather than pinning the next run to group 3.
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(4), maxPageSize = 2))
        assertEquals("b", port.queries.last().after)
        assertEquals(1, port.resets)
        assertEquals(listOf("a", "b"), port.saved.map { it.second })
    }

    @Test fun budgetExhaustionBeforeUnreadParentPreservesPriorBookmarkAndResumes() = runBlocking {
        val present = mutableSetOf<String>()
        val port = FakePort(rows = mapOf(0 to listOf(
            row("a") { if ("parent-a" in present) COMPLETE else dependency(ENTRY, "parent-a") },
            row("b") { if ("parent-b" in present) COMPLETE else dependency(ENTRY, "parent-b") },
        )))
        port.onResolve = { present.add(it.id) }
        val firstBudget = budget(3)
        assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, firstBudget, maxPageSize = 2))
        assertEquals("a", port.cursors[0])
        assertEquals(listOf("parent-a"), port.resolved.map { it.id })
        assertEquals(listOf("a"), port.saved.map { it.second })
        assertEquals(3, firstBudget.usedReads)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(20), maxPageSize = 2))
        assertEquals("a", port.queries.filter { it.phase == 0 }[1].after)
        assertEquals(listOf("parent-a", "parent-b"), port.resolved.map { it.id })
        assertEquals(listOf("a", "b"), port.saved.map { it.second })
    }

    @Test fun alreadyAttemptedMissingParentProgressesEvenAtZeroRemainingBudget() = runBlocking {
        val port = FakePort(rows = mapOf(0 to listOf(
            row("a") { dependency(ENTRY, "missing") }, row("b") { dependency(ENTRY, "missing") },
        )))
        val budget = budget(3)
        assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, budget, maxPageSize = 2))
        assertEquals(listOf("missing"), port.resolved.map { it.id })
        assertEquals(listOf("a", "b"), port.saved.map { it.second })
        assertEquals("b", port.cursors[0])
        assertEquals(0, budget.remainingReads)
    }

    @Test fun hundredRowsWithSameMissingParentCauseOnePointReadInTheWholeRun() = runBlocking {
        val port = FakePort(rows = mapOf(0 to (1..100).map { row("row-${it.toString().padStart(3, '0')}") { dependency(ENTRY, "missing") } }))
        val budget = budget(200)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget))
        assertEquals(1, port.resolved.size)
        assertEquals(100, port.saved.size)
        assertEquals(104, budget.usedReads) // 100 rows + one missing parent + three empty phases.
    }

    @Test fun binderThenEntryDependencyResolveBeforeAnyBookmarkIsWritten() = runBlocking {
        var binderPresent = false
        var entryPresent = false
        val port = FakePort(rows = mapOf(0 to listOf(row("placement") {
            when {
                !binderPresent -> dependency(BINDER, "binder")
                !entryPresent -> dependency(ENTRY, "entry")
                else -> COMPLETE
            }
        })))
        port.onResolve = { if (it.type == BINDER) binderPresent = true else entryPresent = true }
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(20)))
        assertEquals(listOf("apply:0:placement", "resolve:BINDER:binder", "apply:0:placement",
            "resolve:COLLECTION_ENTRY:entry", "apply:0:placement", "save:0:placement"),
            port.events.filter { it.startsWith("apply:") || it.startsWith("resolve:") || it.startsWith("save:") })
    }

    @Test fun deferredAndInvalidRecordsDoNotMakeAHotContinuationByThemselves() = runBlocking {
        val port = FakePort(rows = mapOf(0 to listOf(row("a") { PrivateRemoteApplyResult.DeferredLocal },
            row("b") { PrivateRemoteApplyResult.Invalid })))
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(20)))
        assertEquals(listOf("a", "b"), port.saved.map { it.second })
        assertTrue(port.resolved.isEmpty())
    }

    @Test fun newFullCycleRetriesMissingParentInsteadOfKeepingNegativeCacheForever() = runBlocking {
        val port = FakePort(rows = mapOf(0 to listOf(row("a") { dependency(ENTRY, "missing") })))
        repeat(2) { assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(20))) }
        assertEquals(2, port.resolved.size)
        assertEquals(2, port.resets)
    }

    @Test fun queryFailurePropagatesWithoutBookmarkOrReset() = runBlocking {
        val port = FakePort()
        val expected = IllegalStateException("synthetic query failure")
        port.queryFailure = expected
        assertSame(expected, runCatching { runPrivateFullPull(port, budget(20)) }.exceptionOrNull())
        assertTrue(port.saved.isEmpty())
        assertEquals(0, port.resets)
        assertEquals(0, port.phase)
    }

    @Test fun applyFailureKeepsLastSuccessfulBookmarkAndDoesNotProcessLaterRows() = runBlocking {
        val expected = IllegalStateException("synthetic apply failure")
        val port = FakePort(rows = mapOf(0 to listOf(row("a"), row("b") { throw expected }, row("c"))))
        assertSame(expected, runCatching { runPrivateFullPull(port, budget(20)) }.exceptionOrNull())
        assertEquals("a", port.cursors[0])
        assertFalse("apply:0:c" in port.events)
        assertEquals(0, port.resets)
    }

    @Test fun dependencyFailureCannotAdvanceCurrentDocumentBookmark() = runBlocking {
        val expected = IllegalStateException("synthetic dependency failure")
        val port = FakePort(rows = mapOf(0 to listOf(row("a") { dependency(ENTRY, "parent") })))
        port.onResolve = { throw expected }
        val budget = budget(20)
        assertSame(expected, runCatching { runPrivateFullPull(port, budget) }.exceptionOrNull())
        assertEquals(2, budget.usedReads)
        assertTrue(port.saved.isEmpty())
        assertNull(port.cursors[0])
    }

    @Test fun bookmarkFailureDoesNotVisitFollowingDocumentOrResetPhase() = runBlocking {
        val port = FakePort(rows = mapOf(0 to listOf(row("a"), row("b"))))
        val expected = IllegalStateException("synthetic bookmark failure")
        port.saveFailure = expected
        assertSame(expected, runCatching { runPrivateFullPull(port, budget(20)) }.exceptionOrNull())
        assertFalse("apply:0:b" in port.events)
        assertTrue(port.cursors.isEmpty())
        assertEquals(0, port.resets)
    }

    @Test fun cancellationPropagatesWithoutCheckpointingSuspendedApply() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val port = FakePort(rows = mapOf(0 to listOf(row("a") { entered.complete(Unit); awaitCancellation() })))
        val task = launch { runPrivateFullPull(port, budget(20)) }
        entered.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(port.saved.isEmpty())
        assertEquals(0, port.resets)
    }

    @Test fun partialPagePaysForAllFetchedRowsBeforeAnyApplyFailure() = runBlocking {
        val port = FakePort(rows = mapOf(0 to listOf(row("a") { error("stop") }, row("b"), row("c"))))
        val budget = budget(20)
        assertNotNull(runCatching { runPrivateFullPull(port, budget) }.exceptionOrNull())
        assertEquals(3, budget.usedReads)
    }

    @Test fun syntheticThousandFiveThousandAndTwentyThousandRowsReachTwoSweepMilestones() = runBlocking {
        // This is pagination/control-flow scale evidence, not Room, Firebase, latency or billing.
        for (count in listOf(1_000, 5_000, 20_000)) {
            val rows = (0 until count).groupBy { it % 4 }.mapValues { (_, ids) ->
                ids.map { row("id-${it.toString().padStart(5, '0')}") }
            }
            val expected = (0..3).flatMap { phase -> rows.getValue(phase).sortedBy { it.id }.map { phase to it.id } }
            val port = FakePort(rows = rows)
            val traces = mutableListOf<List<Triple<Int, Int, Int>>>()
            repeat(2) { milestone ->
                val trace = mutableListOf<Triple<Int, Int, Int>>()
                val maxRuns = (count + 999) / 1_000 + 4
                var result = PrivateFullPullRunResult.MORE_DOCUMENTS
                while (result != PrivateFullPullRunResult.COMPLETE) {
                    assertTrue("$count rows milestone $milestone must terminate within the fixture run bound", trace.size < maxRuns)
                    val queriesBefore = port.queries.size
                    val savedBefore = port.saved.size
                    val budget = budget(1_000)
                    result = runPrivateFullPull(port, budget, maxPageSize = 200)
                    val queries = port.queries.drop(queriesBefore)
                    assertTrue(queries.all { it.limit in 1..200 })
                    assertTrue(budget.usedReads in 1..1_000)
                    assertTrue(port.saved.size - savedBefore <= 1_000)
                    trace.add(Triple(budget.usedReads, queries.size, port.saved.size - savedBefore))
                }
                assertEquals("Every identity has been visited by the first milestone", expected.toSet(), port.saved.toSet())
                assertEquals(milestone + 1, port.resets)
                assertEquals(0, port.schedule.seenEofMask)
                // Milestones are independent per-group sweeps, not an ordered global snapshot.
                // Already finished groups may be revisited while another one still progresses.
                for (phase in 0..3) {
                    val sorted = expected.filter { it.first == phase }
                    val actual = port.saved.filter { it.first == phase }
                    assertTrue("Group $phase must have completed each acknowledged sweep", actual.size >= sorted.size * (milestone + 1))
                    actual.forEachIndexed { index, item ->
                        assertEquals("No skips or repetitions within an ascending group sweep", sorted[index % sorted.size], item)
                    }
                }
                traces.add(trace)
            }
            // Keep observed costs visible instead of preserving the old, now false N..N+4 bound.
            println("FULL-pull rotation rows=$count logicalReadsByMilestone=${traces.map { runs -> runs.map { it.first } }} " +
                "queriesByMilestone=${traces.map { runs -> runs.map { it.second } }} legacyTwoFullSweepUpper=${2 * (count + 4)}")
        }
    }

    @Test fun exactBudgetRereadsAreExplicitAndCompletionPreservesActiveCursors() = runBlocking {
        val port = FakePort(rows = (0..3).associateWith { phase -> (0 until 250).map { row("$phase-${it.toString().padStart(3, '0')}") } })
        val first = budget(1_000)
        assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, first))
        assertEquals(1_000, first.usedReads)
        assertEquals(PrivateFullPullSchedule(0, 7), port.schedule)
        val second = budget(1_000)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, second))
        assertEquals("Three fair revisits plus the missing final EOF are real additional reads", 601, second.usedReads)
        assertEquals(mapOf(0 to "0-199", 1 to "1-199", 2 to "2-199"), port.cursors)
        assertEquals(1_600, port.saved.size)
        assertEquals(1_000, port.saved.toSet().size)
        val third = budget(1_000)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, third))
        assertEquals(400, third.usedReads)
        assertEquals(2_000, port.saved.size)
        assertTrue(port.saved.groupingBy { it }.eachCount().values.all { it == 2 })
        assertTrue(port.cursors.isEmpty())
    }

    @Test fun oneReadBudgetPersistsEofAcrossFourInvocations() = runBlocking {
        val port = FakePort()
        repeat(3) { assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, budget(1))) }
        assertEquals(PrivateFullPullSchedule(3, 7), port.schedule)
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(1)))
        assertEquals(listOf(0, 1, 2, 3), port.queries.map { it.phase })
        assertEquals(1, port.resets)
    }

    @Test fun scheduleCodecAcceptsOnlyExactLegacyOrCanonicalVersionedValues() {
        assertEquals(PrivateFullPullSchedule(), PrivateFullPullScheduleCodec.decode(null))
        for (phase in 0..3) assertEquals(PrivateFullPullSchedule(phase, (1 shl phase) - 1),
            PrivateFullPullScheduleCodec.decode(phase.toString()))
        assertEquals(PrivateFullPullSchedule(0, 15), PrivateFullPullScheduleCodec.decode("4"))
        for (next in 0..3) for (mask in 0..15) {
            val state = PrivateFullPullSchedule(next, mask)
            assertEquals(state, PrivateFullPullScheduleCodec.decode(PrivateFullPullScheduleCodec.encode(state)))
        }
        for (raw in listOf("", "-1", "5", "00", " 0", "0 ", "rr1|4|0", "rr1|0|16", "rr1|00|1",
            "rr1|0|01", "rr1|0|-1", "rr2|0|0", "rr1|0|0|", "rr1|0|0\n", "garbage")) {
            assertTrue("Must fail closed without inventing progress: $raw", runCatching { PrivateFullPullScheduleCodec.decode(raw) }.isFailure)
        }
    }

    @Test fun finalEofOutsideGroupThreePreservesNextTurnOnReset() = runBlocking {
        val port = FakePort(initialPhase = 0, rows = mapOf(0 to listOf(row("a"), row("b"), row("c"))))
        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget(20), maxPageSize = 2))
        assertEquals(listOf(0, 1, 2, 3, 0), port.queries.map { it.phase })
        assertEquals(PrivateFullPullSchedule(1, 0), port.schedule)
    }

    private data class QueryCall(val phase: Int, val after: String?, val limit: Int)
    private class FakePort(initialPhase: Int = 0, val rows: Map<Int, List<PrivateFullPullRow>> = emptyMap()) : PrivateFullPullPort {
        var schedule = PrivateFullPullScheduleCodec.decode(initialPhase.toString())
        val phase: Int get() = schedule.nextGroup
        val cursors = mutableMapOf<Int, String>()
        val queries = mutableListOf<QueryCall>()
        val saved = mutableListOf<Pair<Int, String>>()
        val resolved = mutableListOf<PrivateRemoteApplyResult.RetryDependency>()
        val events = mutableListOf<String>()
        var resets = 0
        var queryFailure: Throwable? = null
        var saveFailure: Throwable? = null
        var onResolve: suspend (PrivateRemoteApplyResult.RetryDependency) -> Unit = {}

        override suspend fun readSchedule() = schedule
        override suspend fun readCursor(phase: Int) = cursors[phase]
        override suspend fun queryPage(phase: Int, after: String?, limit: Int): List<PrivateFullPullRow> {
            queries.add(QueryCall(phase, after, limit))
            queryFailure?.let { throw it }
            return rows[phase].orEmpty().sortedBy { it.id }.filter { after == null || it.id > after }.take(limit).map { source ->
                PrivateFullPullRow(source.id) { events.add("apply:$phase:${source.id}"); source.apply() }
            }
        }
        override suspend fun resolveDependency(dependency: PrivateRemoteApplyResult.RetryDependency) {
            resolved.add(dependency)
            events.add("resolve:${dependency.type}:${dependency.id}")
            onResolve(dependency)
        }
        override suspend fun saveCursor(phase: Int, documentId: String) {
            saveFailure?.let { throw it }
            events.add("save:$phase:$documentId")
            saved.add(phase to documentId)
            cursors[phase] = documentId
        }
        override suspend fun advanceGroup(group: Int, reachedEof: Boolean): PrivateFullPullSchedule {
            if (reachedEof) cursors.remove(group)
            schedule = schedule.afterGroup(group, reachedEof)
            return schedule
        }
        override suspend fun resetCycle() { check(schedule.isComplete); schedule = schedule.copy(seenEofMask = 0); resets++ }
    }

    companion object {
        private val COMPLETE = PrivateRemoteApplyResult.Checkpoint
        private val BINDER = PrivateRemoteDependencyType.BINDER
        private val ENTRY = PrivateRemoteDependencyType.COLLECTION_ENTRY
        private fun row(id: String, apply: suspend () -> PrivateRemoteApplyResult = { COMPLETE }) = PrivateFullPullRow(id, apply)
        private fun dependency(type: PrivateRemoteDependencyType, id: String) = PrivateRemoteApplyResult.RetryDependency(type, id)
        private fun budget(limit: Int) = PrivateFullPullReadBudget("synthetic-runner-owner", limit)
    }
}
