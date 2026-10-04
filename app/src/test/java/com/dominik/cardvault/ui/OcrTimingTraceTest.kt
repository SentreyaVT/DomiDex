package com.dominik.cardvault.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OcrTimingTraceTest {
    @Test fun recordsMonotonicOrdinalEventsAndCopiesMetadata() {
        var now = 100L
        val trace = OcrTimingTrace { now++ }
        val regions = mutableListOf("NUMBER")
        val roi = mutableListOf(.18f, .805f, .735f, .985f)
        val call = trace.newCall(true, "orientation_rc3_number", "original", 0, regions, roi)
        regions.clear(); roi.clear()
        trace.record(OcrTimingEvent.BEFORE_PROCESS, call)
        trace.record(OcrTimingEvent.PROCESS_RETURNED, call)
        trace.record(OcrTimingEvent.TASK_COMPLETE, call, outcome = OcrTimingOutcome.SUCCESS)
        val snapshot = trace.snapshot()
        assertEquals(listOf(1, 2, 3), snapshot.entries.map { it.ordinal })
        assertEquals(listOf(1L, 2L, 3L), snapshot.entries.map { it.elapsedNanos })
        assertEquals(listOf("NUMBER"), call.regions)
        assertEquals(4, call.roi.size)
        assertEquals(1, call.id)
        assertFalse(snapshot.clockRegressed)
        assertEquals(0, snapshot.dropped)
    }

    @Test fun successFailureCancellationAndLateDropRemainDistinct() {
        var now = 0L
        val trace = OcrTimingTrace { now++ }
        val calls = (1..3).map { trace.newCall(true, "p", "original", 0, listOf("NUMBER"), emptyList()) }
        calls.zip(OcrTimingOutcome.entries).forEach { (call, outcome) ->
            trace.record(OcrTimingEvent.TASK_COMPLETE, call, outcome = outcome)
        }
        trace.record(OcrTimingEvent.LATE_IGNORED_BEFORE_QUEUE, calls[0])
        trace.record(OcrTimingEvent.LATE_IGNORED_AT_WORKER, calls[1])
        trace.record(OcrTimingEvent.LATE_IGNORED_AFTER_BATCH, calls[2])
        assertEquals(listOf(1, 2, 3), calls.map { it.id })
        assertEquals(OcrTimingOutcome.entries, trace.snapshot().entries.take(3).map { it.outcome })
        assertEquals(3, trace.snapshot().entries.takeLast(3).map { it.event }.toSet().size)
    }

    @Test fun traceIsHardBoundedAndReportsOverflowWithoutRewritingOldRecords() {
        var now = 0L
        val trace = OcrTimingTrace { now++ }
        repeat(300) { trace.record(OcrTimingEvent.SESSION_DEADLINE) }
        val snapshot = trace.snapshot()
        assertEquals(256, snapshot.entries.size)
        assertEquals(300, snapshot.observed)
        assertEquals(44, snapshot.dropped)
        assertEquals((1..256).toList(), snapshot.entries.map { it.ordinal })
    }

    @Test fun snapshotsRemainDetachedWhileRealConcurrentWritersRecord() {
        val now = AtomicLong()
        val trace = OcrTimingTrace(now::getAndIncrement)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val threads = List(2) {
            Thread {
                try {
                    check(start.await(2, TimeUnit.SECONDS))
                    repeat(50) { trace.record(OcrTimingEvent.TASK_COMPLETE) }
                } finally { done.countDown() }
            }
        }
        val before = trace.snapshot()
        threads.forEach(Thread::start); start.countDown()
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS))
            val after = trace.snapshot()
            assertTrue(before.entries.isEmpty())
            assertEquals((1..100).toList(), after.entries.map { it.ordinal })
            assertEquals(0, after.dropped)
            assertFalse(after.clockRegressed)
        } finally {
            threads.forEach { it.interrupt(); it.join(2_000); assertFalse(it.isAlive) }
        }
    }

    @Test fun clockRegressionAndFailureAreExplicitNotClampedOrThrown() {
        var calls = 0
        val trace = OcrTimingTrace {
            when (calls++) { 0 -> 100L; 1 -> 103L; 2 -> 102L; else -> error("synthetic clock failure") }
        }
        trace.record(OcrTimingEvent.BEFORE_PROCESS)
        trace.record(OcrTimingEvent.PROCESS_RETURNED)
        trace.record(OcrTimingEvent.TASK_COMPLETE)
        val snapshot = trace.snapshot()
        assertEquals(listOf(3L, 2L), snapshot.entries.map { it.elapsedNanos })
        assertTrue(snapshot.clockRegressed)
        assertEquals(1, snapshot.clockFailures)
        assertEquals(1, snapshot.dropped)
    }
}
