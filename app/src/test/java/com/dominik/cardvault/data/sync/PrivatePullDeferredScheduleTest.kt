package com.dominik.cardvault.data.sync

import org.junit.Assert.*
import org.junit.Test

/** New local scheduling contract; not a regression claim about the active full-pull worker. */
class PrivatePullDeferredScheduleTest {
    @Test fun codecRoundTripPreservesEveryGroupAndExactCaseSensitiveIds() {
        var state = PrivatePullDeferredSchedule.empty("owner-with:delimiter")
        for (group in PrivatePullCollection.entries) {
            state = state.advance(group, PrivatePullDeferredSweep("z-upper", "A-after"))
        }
        val raw = PrivatePullDeferredScheduleCodec.encode(state)
        assertEquals(state, PrivatePullDeferredScheduleCodec.decode(raw, state.ownerUid))
    }

    @Test fun absentCorruptForeignAndNoncanonicalStateResetOnlyTheSchedule() {
        val empty = PrivatePullDeferredSchedule.empty("owner-A")
        for (raw in listOf(null, "broken", "x".repeat(2_049),
            PrivatePullDeferredScheduleCodec.encode(PrivatePullDeferredSchedule.empty("owner-B")),
            PrivatePullDeferredScheduleCodec.encode(empty) + "|extra")) {
            assertEquals(empty, PrivatePullDeferredScheduleCodec.decode(raw, "owner-A"))
        }
    }

    @Test fun roundRobinKeepsOtherSweepsUnchanged() {
        val initial = PrivatePullDeferredSchedule.empty("owner-A")
        val changed = initial.advance(PrivatePullCollection.BINDERS, PrivatePullDeferredSweep("z", "a"))
        assertEquals(PrivatePullCollection.PLACEMENTS, changed.nextGroup)
        assertEquals(PrivatePullDeferredSweep("z", "a"), changed.sweep(PrivatePullCollection.BINDERS))
        for (group in PrivatePullCollection.entries.drop(1)) assertEquals(PrivatePullDeferredSweep(), changed.sweep(group))
        val wrapped = changed.advance(PrivatePullCollection.PRICE_SELECTIONS, PrivatePullDeferredSweep())
        assertEquals(PrivatePullCollection.BINDERS, wrapped.nextGroup)
    }

    @Test fun invalidSweepCannotEscapeTheFixedUpperBound() {
        for ((upper, after) in listOf(null to "a", "a" to "b", "bad/slash" to null, "a" to "")) {
            assertNotNull(runCatching { PrivatePullDeferredSweep(upper, after) }.exceptionOrNull())
        }
        assertEquals("a", PrivatePullDeferredSweep("a", "a").afterId)
    }

    @Test fun inclusiveUpperKeyExcludesEveryAllowedIdExtension() {
        val upper = "A-9._z"
        val end = privatePullDeferredUpperExclusive(upper)
        assertTrue(upper < end)
        for (character in "-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz") {
            assertTrue(upper + character > end)
        }
        assertNotNull(runCatching { privatePullDeferredUpperExclusive("bad/slash") }.exceptionOrNull())
    }

    @Test fun batchCopiesAndProtectsItsExactMarkerList() {
        val scope = PrivatePullScope("owner-A", PrivatePullCollection.BINDERS)
        val marker = PrivatePullDeferredMarker(scope, "a", 9, PrivatePullDeferReason.LOCAL_MUTATION, epoch = "fixed-epoch")
        val input = mutableListOf(marker)
        val batch = PrivatePullDeferredBatch.Batch(scope, input)
        input.clear()
        assertEquals(listOf(marker), batch.markers)
        assertTrue(runCatching { (batch.markers as MutableList<PrivatePullDeferredMarker>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertNotNull(runCatching { PrivatePullDeferredBatch.Batch(scope, emptyList()) }.exceptionOrNull())
        assertNotNull(runCatching { PrivatePullDeferredBatch.Batch(scope.copy(ownerUid = "owner-B"), listOf(marker)) }.exceptionOrNull())
    }
}
