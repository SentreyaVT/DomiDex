package com.dominik.cardvault.data.knowledge

import org.junit.Assert.*
import org.junit.Test

class ScanKnowledgeObservationTest {
    @Test fun confirmedDeletionClearsOnlyMatchingCacheAndRunningHint() {
        val harness = Harness()
        harness.observer.publishCached()
        harness.snapshot(exists = false)
        assertNull(harness.cache)
        assertEquals(listOf(harness.record, null), harness.updates)
        assertEquals(listOf(FP), harness.removals)
        assertEquals(0, harness.decodes)
    }

    @Test fun cachedMissingAndPendingSnapshotsCannotClearOrReplaceCache() {
        val harness = Harness()
        harness.observer.publishCached()
        harness.snapshot(exists = false, cached = true)
        harness.snapshot(exists = false, pending = true)
        harness.snapshot(record = harness.record.copy(cardId = "other"), cached = true)
        harness.snapshot(record = harness.record.copy(cardId = "other"), pending = true)
        assertSame(harness.record, harness.cache)
        assertEquals(listOf(harness.record), harness.updates)
        assertEquals(0, harness.decodes)
        assertTrue(harness.removals.isEmpty())
    }

    @Test fun identicalServerMetadataTransitionStillPublishesConfirmedRecord() {
        val harness = Harness()
        harness.snapshot(cached = true)
        harness.snapshot()
        assertEquals(1, harness.writes)
        assertEquals(listOf(harness.record), harness.updates)
    }

    @Test fun malformedConfirmedDecoderClearsHintWithoutThrowing() {
        val harness = Harness()
        harness.observer.onSnapshot(true, false, false) { throw IllegalArgumentException("malformed typed field") }
        assertNull(harness.cache)
        assertEquals(listOf<ScanKnowledgeRecord?>(null), harness.updates)
        assertEquals(listOf(FP), harness.removals)
    }

    @Test fun invalidExpiredFutureOrMismatchedServerRecordsClearHint() {
        val record = freshRecord()
        listOf(null, record.copy(updatedAtEpochMillis = 0),
            record.copy(updatedAtEpochMillis = NOW - SCAN_KNOWLEDGE_MAX_AGE_MILLIS),
            record.copy(updatedAtEpochMillis = NOW + 1), record.copy(fingerprint = "b".repeat(64)),
            record.copy(knowledgeVersion = 2), record.copy(status = ScanKnowledgeStatus.DISPUTED)).forEach { invalid ->
            val harness = Harness()
            harness.snapshot(record = invalid)
            assertNull(harness.cache)
            assertEquals(listOf<ScanKnowledgeRecord?>(null), harness.updates)
            assertEquals(0, harness.writes)
        }
    }

    @Test fun transientErrorKeepsFreshCacheWithoutExtendingSourceTimestamp() {
        val harness = Harness()
        harness.observer.onError()
        assertSame(harness.record, harness.updates.single())
        assertEquals(NOW, harness.cache!!.updatedAtEpochMillis)
        assertEquals(0, harness.writes)
        harness.now += SCAN_KNOWLEDGE_MAX_AGE_MILLIS
        harness.observer.onError()
        assertNull(harness.cache)
        assertNull(harness.updates.last())
    }

    @Test fun terminalTransportErrorPreservesFreshHintButStopsNetworkAndRejectsLateSnapshots() {
        val harness = Harness()
        var networkCleanup = 0
        var authCleanup = 0
        harness.observer.onTransportEnd { networkCleanup++ }
        harness.observer.onClose { authCleanup++ }
        harness.observer.onTerminalError()
        harness.observer.onTerminalError()
        assertEquals(listOf(harness.record), harness.updates)
        assertEquals(1, networkCleanup)
        assertEquals(0, authCleanup)
        harness.snapshot(exists = false)
        assertSame(harness.record, harness.cache)
        assertEquals(0, harness.decodes)
        assertFalse(harness.observer.runIfActive { error("late registration after error") })
        harness.now += SCAN_KNOWLEDGE_MAX_AGE_MILLIS
        assertFalse("The retained hint is not granted a new source age",
            isUsableScanKnowledge(harness.updates.last(), harness.now, FP))
        harness.observer.close()
        assertNull("Explicit consumer close still invalidates the retained fallback", harness.updates.last())
        assertEquals(1, networkCleanup)
        assertEquals(1, authCleanup)
        harness.observer.close()
        assertEquals(1, authCleanup)
    }

    @Test fun observedAccountChangeAfterTransportErrorClearsHintAndAuthWatcher() {
        val harness = Harness()
        var networkCleanup = 0
        var authCleanup = 0
        harness.observer.onTransportEnd { networkCleanup++ }
        harness.observer.onClose { authCleanup++ }
        harness.observer.onAuthStateEvent()
        harness.observer.onTerminalError()
        harness.owner = "b"
        harness.observer.onAuthStateEvent()
        harness.owner = "a"
        harness.snapshot()
        assertEquals(listOf(harness.record, null), harness.updates)
        assertEquals(1, networkCleanup)
        assertEquals(1, authCleanup)
        assertEquals(0, harness.decodes)
    }

    @Test fun delayedInitialAuthEventAfterTransportFailureStillAllowsLaterAbaInvalidation() {
        val harness = Harness()
        var authCleanup = 0
        harness.observer.onClose { authCleanup++ }
        harness.observer.onTerminalError()
        harness.observer.onAuthStateEvent() // Delayed initial callback, owner still A.
        assertSame(harness.record, harness.updates.last())
        assertEquals(0, authCleanup)
        harness.observer.onAuthStateEvent() // A -> B -> A already completed when event delivered.
        assertNull(harness.updates.last())
        assertEquals(1, authCleanup)
    }

    @Test fun lateTransportCleanupRegistrationAfterErrorIsImmediatelyRemoved() {
        val harness = Harness()
        harness.observer.onTerminalError()
        var count = 0
        harness.observer.onTransportEnd { count++ }
        assertEquals(1, count)
        harness.observer.close()
        assertEquals(1, count)
    }

    @Test fun terminalTransportErrorCannotPreserveExpiredOrWrongOwnerHint() {
        val expired = Harness()
        expired.now += SCAN_KNOWLEDGE_MAX_AGE_MILLIS
        expired.observer.onTerminalError()
        assertNull(expired.cache)
        assertEquals(listOf<ScanKnowledgeRecord?>(null), expired.updates)
        val switched = Harness()
        switched.owner = "b"
        switched.observer.onTerminalError()
        assertEquals(listOf<ScanKnowledgeRecord?>(null), switched.updates)
    }

    @Test fun closeRejectsLateCallbacksAndRunsAllRegisteredCleanupOnce() {
        val harness = Harness()
        var cleanups = 0
        harness.observer.onClose { cleanups++ }
        harness.observer.onClose { throw IllegalStateException("one cleanup failed") }
        harness.observer.onClose { cleanups++ }
        harness.observer.close()
        harness.observer.close()
        harness.snapshot()
        harness.observer.onError()
        harness.observer.publishCached()
        assertFalse(harness.observer.runIfActive { error("closed registration") })
        harness.observer.onClose { cleanups++ }
        assertEquals(3, cleanups)
        assertEquals(0, harness.decodes)
        assertEquals(listOf<ScanKnowledgeRecord?>(null), harness.updates)
        assertTrue(harness.removals.isEmpty())
    }

    @Test fun observedAuthAbaTerminatesEvenWhenCurrentOwnerAlreadyReturnedToA() {
        val harness = Harness()
        var cleanup = 0
        harness.observer.onClose { cleanup++ }
        harness.observer.onAuthStateEvent() // Initial callback for A.
        harness.owner = "b"
        harness.owner = "a" // Delayed auth callback reads the same mutable FirebaseAuth instance.
        harness.observer.onAuthStateEvent()
        harness.snapshot()
        assertEquals(1, cleanup)
        assertEquals(0, harness.decodes)
        assertEquals(listOf<ScanKnowledgeRecord?>(null), harness.updates)
    }

    @Test fun mismatchedInitialAuthCallbackTerminatesImmediately() {
        val harness = Harness()
        var cleanup = 0
        harness.observer.onClose { cleanup++ }
        harness.owner = "b"
        harness.observer.onAuthStateEvent()
        harness.owner = "a"
        harness.snapshot()
        assertEquals(1, cleanup)
        assertEquals(0, harness.decodes)
        assertEquals(listOf<ScanKnowledgeRecord?>(null), harness.updates)
    }

    @Test fun callbackOwnerMismatchClosesEvenBeforeAuthEventDelivery() {
        val harness = Harness()
        harness.owner = null
        harness.snapshot()
        harness.owner = "a"
        harness.snapshot()
        assertEquals(0, harness.decodes)
        assertEquals(listOf<ScanKnowledgeRecord?>(null), harness.updates)
    }

    @Test fun terminalFailurePreventsDelayedRegistrationAndDoesNotDeletePublicCache() {
        val harness = Harness()
        harness.observer.close()
        assertFalse(harness.observer.runIfActive { error("late entitlement response") })
        assertSame(harness.record, harness.cache)
        assertTrue(harness.removals.isEmpty())
    }

    private class Harness {
        var now = NOW
        var owner: String? = "a"
        val record = freshRecord()
        var cache: ScanKnowledgeRecord? = record
        var decodes = 0
        var writes = 0
        val removals = mutableListOf<String>()
        val updates = mutableListOf<ScanKnowledgeRecord?>()
        val observer = ScanKnowledgeObservation(FP, "a", { owner }, { now },
            readCache = { cache }, writeCache = { cache = it; writes++ },
            removeCache = { removals += it; cache = null }, onUpdate = { updates += it })
        fun snapshot(exists: Boolean = true, cached: Boolean = false, pending: Boolean = false,
            record: ScanKnowledgeRecord? = this.record) {
            observer.onSnapshot(exists, cached, pending) { decodes++; record }
        }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private val FP = "a".repeat(64)
        private fun freshRecord() = ScanKnowledgeRecord(FP, "card-a", "sv1", "001",
            ScanKnowledgeStatus.STABLE, 90, 3, 0, 1, NOW)
    }
}
