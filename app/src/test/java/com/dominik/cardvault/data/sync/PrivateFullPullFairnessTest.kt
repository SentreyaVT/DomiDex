package com.dominik.cardvault.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual FULL-runner control flow with synthetic rows; not SDK, Room, latency or billing evidence. */
class PrivateFullPullFairnessTest {
    @Test fun continuouslyGrowingBindersCannotStarveExistingLaterGroupsAcrossBoundedRuns() = runBlocking {
        val port = FakePort(rowsPerPhase = 1, growingGroup = 0)
        val existingLaterRows = port.allRows().filter { it.first != 0 }.toSet()
        val budgets = mutableListOf<Int>()
        val outcomes = mutableListOf<PrivateFullPullRunResult>()

        // Every phase-0 query receives at least its requested number of newer IDs.
        // A run cannot escape the fixture by reaching a temporarily empty binder tail.
        repeat(4) {
            val budget = budget()
            outcomes += runPrivateFullPull(port, budget, maxPageSize = 200)
            budgets += budget.usedReads
            assertTrue("Each invocation must remain within its logical read budget", budget.usedReads in 1..1_000)
        }

        assertTrue("The fixture must actually have appended binders", port.appendedRows > 0)
        assertTrue(port.queries.all { it.limit in 1..200 })
        assertTrue("The initially present binder must still be applied", 0 to id(0, 0) in port.applied)
        val missing = existingLaterRows - port.applied.toSet()
        assertTrue(
            "Existing later groups must not starve behind an ever-growing binder tail. " +
                "Missing=$missing; schedule=${port.schedule}; queryPhases=${port.queries.map { it.phase }}; " +
                "logicalReads=$budgets; outcomes=$outcomes",
            missing.isEmpty(),
        )
    }

    @Test fun earlierEofGroupReceivesNewRowsWhileAnotherGroupKeepsGrowingForEveryBudget() = runBlocking {
        for (limit in listOf(1, 200, 201, 1_000)) {
            val port = FakePort(rowsPerPhase = 1, growingGroup = 1)
            var warmupRuns = 0
            while (port.schedule.seenEofMask and 1 == 0) {
                assertTrue("Group 0 must first reach EOF for budget $limit", warmupRuns++ < 8)
                val budget = budget(limit)
                assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, budget, 200))
                assertTrue(budget.usedReads in 1..limit)
            }
            assertNull(port.cursors[0])
            val added = 0 to port.addRow(0)
            assertFalse(added in port.applied)
            val appliedBefore = port.applied.size
            repeat(8) {
                val budget = budget(limit)
                assertEquals(PrivateFullPullRunResult.MORE_DOCUMENTS, runPrivateFullPull(port, budget, 200))
                assertTrue(budget.usedReads in 1..limit)
            }
            assertTrue("An EOF bit must not permanently skip new group-0 data, budget=$limit",
                added in port.applied.drop(appliedBefore))
            assertTrue("The endlessly growing group must never be reported at EOF", port.schedule.seenEofMask and 2 == 0)
            assertEquals(0, port.resets)
            assertTrue(port.queries.all { it.limit in 1..minOf(200, limit) })
        }
    }

    @Test fun durableEofEvidenceCompletesFiniteGroupsEvenWithSingleReadInvocations() = runBlocking {
        for (limit in listOf(1, 200, 201, 1_000)) {
            val port = FakePort(rowsPerPhase = 1)
            val expected = port.allRows()
            var result = PrivateFullPullRunResult.MORE_DOCUMENTS
            var runs = 0
            while (result != PrivateFullPullRunResult.COMPLETE) {
                assertTrue("Finite groups must complete across persisted EOF evidence, budget=$limit", runs++ < 8)
                val budget = budget(limit)
                result = runPrivateFullPull(port, budget, 200)
                assertTrue(budget.usedReads in 1..limit)
            }
            assertCompleteCycle(port, expected)
        }
    }

    @Test fun finiteFourGroupsApplyEveryRowAndFinishWithoutResidualBookmarks() = runBlocking {
        val port = FakePort(rowsPerPhase = 3)
        val expected = port.allRows()
        val budget = budget()

        assertEquals(PrivateFullPullRunResult.COMPLETE, runPrivateFullPull(port, budget, maxPageSize = 200))

        assertCompleteCycle(port, expected)
        assertEquals(12, budget.usedReads)
        assertEquals(setOf(0, 1, 2, 3), port.queries.map { it.phase }.toSet())
    }

    @Test fun finiteFourGroupsResumeAcrossBudgetsWithoutSkippingOrApplyingRowsTwice() = runBlocking {
        val port = FakePort(rowsPerPhase = 401)
        val expected = port.allRows()
        var outcome = PrivateFullPullRunResult.MORE_DOCUMENTS
        var runs = 0
        var logicalReads = 0

        while (outcome != PrivateFullPullRunResult.COMPLETE) {
            assertTrue("A finite 1,604-row fixture must finish within six bounded invocations", runs < 6)
            val budget = budget()
            outcome = runPrivateFullPull(port, budget, maxPageSize = 200)
            assertTrue(budget.usedReads in 1..1_000)
            logicalReads += budget.usedReads
            runs++
        }

        assertTrue("This fixture must require continuation", runs > 1)
        assertCompleteCycle(port, expected)
        assertTrue("Charge every document and at most one empty tail per group", logicalReads in 1_604..1_608)
        assertTrue(port.queries.all { it.limit in 1..200 })
        for (phase in 0..3) {
            assertEquals("Keep the ascending exclusive cursor within phase $phase",
                expected.filter { it.first == phase }, port.applied.filter { it.first == phase })
        }
    }

    private fun assertCompleteCycle(port: FakePort, expected: List<Pair<Int, String>>) {
        assertEquals(expected.toSet(), port.applied.toSet())
        assertEquals("No duplicate application in this completed cycle", expected.size, port.applied.size)
        assertEquals(port.applied, port.saved)
        assertEquals(setOf(0, 1, 2, 3), port.finished.toSet())
        assertEquals(4, port.finished.size)
        assertEquals(1, port.resets)
        assertEquals(PrivateFullPullSchedule(), port.schedule)
        assertTrue(port.cursors.isEmpty())
    }

    private data class Query(val phase: Int, val after: String?, val limit: Int)

    private class FakePort(rowsPerPhase: Int, private val growingGroup: Int? = null) : PrivateFullPullPort {
        private val rows = (0..3).associateWith { phase ->
            (0 until rowsPerPhase).map { index -> id(phase, index) }.toMutableList()
        }
        private val nextIndices = IntArray(4) { rowsPerPhase }
        var schedule = PrivateFullPullSchedule()
        val cursors = mutableMapOf<Int, String>()
        val queries = mutableListOf<Query>()
        val applied = mutableListOf<Pair<Int, String>>()
        val saved = mutableListOf<Pair<Int, String>>()
        val finished = mutableListOf<Int>()
        var resets = 0
        var appendedRows = 0

        fun allRows(): List<Pair<Int, String>> = (0..3).flatMap { phase -> rows.getValue(phase).map { phase to it } }
        fun addRow(group: Int): String {
            val newId = id(group, nextIndices[group]++)
            assertTrue("New IDs must extend the lexicographic tail", newId > rows.getValue(group).last())
            rows.getValue(group).add(newId)
            return newId
        }
        override suspend fun readSchedule() = schedule
        override suspend fun readCursor(phase: Int) = cursors[phase]

        override suspend fun queryPage(phase: Int, after: String?, limit: Int): List<PrivateFullPullRow> {
            assertTrue(phase in 0..3)
            assertTrue(limit in 1..200)
            assertEquals("Query must resume from the durable phase cursor", cursors[phase], after)
            queries += Query(phase, after, limit)
            if (phase == growingGroup) {
                repeat(limit) {
                    addRow(phase)
                    appendedRows++
                }
            }
            return rows.getValue(phase).filter { after == null || it > after }.take(limit).map { documentId ->
                PrivateFullPullRow(documentId) {
                    applied += phase to documentId
                    PrivateRemoteApplyResult.Checkpoint
                }
            }
        }

        override suspend fun resolveDependency(dependency: PrivateRemoteApplyResult.RetryDependency) {
            error("Unexpected dependency in independent checkpoint-only fixture: $dependency")
        }

        override suspend fun saveCursor(phase: Int, documentId: String) {
            assertEquals("Only checkpoint the row just applied", phase to documentId, applied.last())
            assertTrue(cursors[phase]?.let { documentId > it } != false)
            saved += phase to documentId
            cursors[phase] = documentId
        }

        override suspend fun advanceGroup(group: Int, reachedEof: Boolean): PrivateFullPullSchedule {
            if (reachedEof) {
                finished += group
                cursors.remove(group)
            }
            schedule = schedule.afterGroup(group, reachedEof)
            return schedule
        }

        override suspend fun resetCycle() {
            assertTrue(schedule.isComplete)
            schedule = schedule.copy(seenEofMask = 0)
            resets++
        }
    }

    companion object {
        private fun budget(limit: Int = 1_000) = PrivateFullPullReadBudget("synthetic-fairness-owner", limit)
        private fun id(phase: Int, index: Int): String =
            "${listOf("binder", "entry", "placement", "price")[phase]}-${index.toString().padStart(8, '0')}"
    }
}
