package com.dominik.cardvault.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic protocol tests: no network, Firebase SDK, Room, clock or device. */
class PrivateBinderPullProtocolTest {
    private val scope = PrivatePullScope("owner-A", PrivatePullCollection.BINDERS)
    private val lease = PrivatePullSessionLease(scope.ownerUid, 7)
    private val trusted = PrivatePullReadTrust.CONFIRMED_SERVER
    private val epoch = "test-epoch"
    private fun stamp(seconds: Long = 100, nanos: Int = 0) = PrivatePullStamp(seconds, nanos)
    private fun cursor(id: String, seconds: Long = 100, nanos: Int = 0) = PrivatePullCursor(stamp(seconds, nanos), id)
    private fun document(id: String, seconds: Long = 100, nanos: Int = 0, value: String = id) =
        PrivatePullDocument(id, stamp(seconds, nanos), value)
    private fun bootstrap(after: String? = null, floor: PrivatePullStamp? = stamp()) =
        PrivatePullState(scope, 3, PrivatePullPhase.Bootstrap(floor, after), epoch)
    private fun delta(upper: PrivatePullCursor = cursor("z", 200), after: PrivatePullCursor? = null, lower: PrivatePullStamp? = stamp()) =
        PrivatePullState(scope, 5, PrivatePullPhase.Delta(lower, upper, after), epoch)
    private fun checkpoint(state: PrivatePullState) = PrivatePullCodec.decode(PrivatePullCodec.encode(state), state.scope)
    private fun page(state: PrivatePullState, rows: List<PrivatePullDocument<String>>, limit: Int = 200) =
        PrivatePullPage(privatePullPlan(state, limit) as PrivatePullPlan.Page, rows, trusted)
    private inline fun rejects(block: () -> Unit) {
        try { block(); throw AssertionError("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun timestampPreservesNanosWithinTheSameMillisecond() {
        val first = stamp(100, 123_456_000)
        val next = stamp(100, 123_456_001)
        assertTrue(first < next)
        assertNotEquals(first, next)
        assertTrue(stamp(99, 999_999_999) < first)
    }

    @Test fun timestampChecksRepresentableDomainNotDeviceClock() {
        assertEquals(PrivatePullStamp(253_402_300_799L, 999_999_999).seconds, 253_402_300_799L)
        assertEquals(-62_135_596_800L, PrivatePullStamp(-62_135_596_800L, 0).seconds)
        rejects { PrivatePullStamp(253_402_300_800L, 0) }
        rejects { PrivatePullStamp(-62_135_596_801L, 0) }
        rejects { stamp(0, -1) }
        rejects { stamp(0, 1_000_000_000) }
    }

    @Test fun tupleOrderingUsesTimestampThenAsciiDocumentId() {
        val rows = listOf(cursor("z"), cursor("a", 99), cursor("a"), cursor("A"), cursor("a", nanos = 1))
        assertEquals(listOf(cursor("a", 99), cursor("A"), cursor("a"), cursor("z"), cursor("a", nanos = 1)), rows.sorted())
        rejects { cursor("bad/id") }
        rejects { cursor("ümlaut") }
    }

    @Test fun everyCollectionHasAnExistingCloudPath() {
        assertEquals(setOf("privateBinders", "privateBinderPlacements", "collectionLayouts", "collectionPriceSelections"),
            PrivatePullCollection.entries.map { it.cloudPath }.toSet())
    }

    @Test fun bootstrapRequiresFenceBeforeReadingAnyPage() {
        val state = PrivatePullState(scope, epoch = epoch)
        assertEquals(PrivatePullPlan.CaptureFence(state), privatePullPlan(state, 1000))
        val fenced = privatePullAfterFence(state, PrivatePullFenceRead(cursor("z", nanos = 912), trusted))
        assertEquals(PrivatePullPhase.Bootstrap(stamp(100, 912), null), fenced.phase)
        assertEquals(1L, fenced.revision)
    }

    @Test fun emptyBootstrapFenceUsesNoSyntheticClockValue() {
        val state = privatePullAfterFence(PrivatePullState(scope, epoch = epoch), PrivatePullFenceRead(null, trusted))
        assertEquals(PrivatePullPhase.Bootstrap(null, null), state.phase)
        val done = privatePullAfterPage(state, page(state, emptyList()))
        assertEquals(PrivatePullPhase.Idle(null), done.phase)
        val started = privatePullAfterFence(done, PrivatePullFenceRead(cursor("later", 1234), trusted))
        assertNull((started.phase as PrivatePullPhase.Delta).lowerInclusive)
    }

    @Test fun bootstrapIncludesConfirmedLegacyDocumentsWithoutTimestamps() {
        val state = bootstrap()
        val row = PrivatePullDocument("legacy", null, "legacy value")
        assertEquals(PrivatePullPhase.Idle(stamp()), privatePullAfterPage(state, page(state, listOf(row))).phase)
    }

    @Test fun bootstrapKeepsThePreScanLowerFenceForChangesBehindItsIdCursor() {
        val state = bootstrap(floor = stamp(100, 3))
        val first = privatePullAfterPage(state, page(state, listOf(document("m", 200)), limit = 1))
        assertEquals(PrivatePullPhase.Bootstrap(stamp(100, 3), "m"), first.phase)
        val done = privatePullAfterPage(first, page(first, listOf(document("z", 201))))
        assertEquals(PrivatePullPhase.Idle(stamp(100, 3)), done.phase)
        val next = privatePullAfterFence(done, PrivatePullFenceRead(cursor("a", 300), trusted))
        // "a" changed after bootstrap had already passed it; delta does not inherit bootstrap's ID.
        val result = privatePullAfterPage(next, page(next, listOf(document("a", 300))))
        assertEquals(PrivatePullPhase.Idle(stamp(300)), result.phase)
    }

    @Test fun pagePlansRespectPageAndRunBudgets() {
        val state = bootstrap()
        assertEquals(200, (privatePullPlan(state, 1000) as PrivatePullPlan.Page).limit)
        assertEquals(13, (privatePullPlan(state, 13) as PrivatePullPlan.Page).limit)
        assertEquals(PrivatePullPlan.BudgetExhausted, privatePullPlan(state, 0))
        rejects { privatePullPlan(state, 1001) }
        rejects { privatePullPlan(state, -1) }
    }

    @Test fun oversizedAndOutOfOrderBootstrapPagesAreRejected() {
        val state = bootstrap("a")
        rejects { privatePullAfterPage(state, page(state, listOf(document("b"), document("c")), 1)) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("a")))) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("c"), document("b")))) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("b"), document("b")))) }
    }

    @Test fun fullBootstrapPagePersistsExactIdAndEmptyNextPageCompletes() {
        val state = bootstrap()
        val rows = (0 until 200).map { document("id-${it.toString().padStart(3, '0')}") }
        val next = privatePullAfterPage(state, page(state, rows))
        assertEquals("id-199", (next.phase as PrivatePullPhase.Bootstrap).afterId)
        assertEquals(PrivatePullPhase.Idle(stamp()), privatePullAfterPage(next, page(next, emptyList())).phase)
    }

    @Test fun deltaResumesMoreThanTwoHundredEqualTimestampsExactly() {
        val rows = (0 until 451).map { document("id-${it.toString().padStart(3, '0')}", nanos = 789) }
        val initial = delta(rows.last().cursor(), lower = stamp(100, 789))
        val first = privatePullAfterPage(initial, page(initial, rows.take(200)))
        val restored = PrivatePullCodec.decode(PrivatePullCodec.encode(first), scope).state
        assertEquals(rows[199].cursor(), (restored.phase as PrivatePullPhase.Delta).after)
        val second = privatePullAfterPage(restored, page(restored, rows.drop(200).take(200)))
        val third = privatePullAfterPage(second, page(second, rows.drop(400)))
        assertEquals(PrivatePullPhase.Idle(stamp(100, 789)), third.phase)
    }

    @Test fun completedDeltaReplaysEqualTimestampWithSmallerIdInNextPass() {
        val initial = delta(cursor("z", nanos = 1), lower = stamp(100, 1))
        val done = privatePullAfterPage(initial, page(initial, listOf(document("z", nanos = 1))))
        val next = privatePullAfterFence(done, PrivatePullFenceRead(cursor("z", nanos = 1), trusted))
        assertNull((next.phase as PrivatePullPhase.Delta).after)
        val replay = privatePullAfterPage(next, page(next, listOf(document("a", nanos = 1), document("z", nanos = 1))))
        assertEquals(done.phase, replay.phase)
    }

    @Test fun nanosecondResumeNeverLosesSameMillisecondRows() {
        val state = delta(cursor("a", nanos = 100), after = cursor("z", nanos = 1), lower = stamp())
        val result = privatePullAfterPage(state, page(state, listOf(document("a", nanos = 2)), 1))
        assertEquals(cursor("a", nanos = 2), (result.phase as PrivatePullPhase.Delta).after)
    }

    @Test fun fixedUpperFenceRejectsNewerRowsAndNextPassCanReadThem() {
        val state = delta(cursor("m", 101))
        rejects { privatePullAfterPage(state, page(state, listOf(document("n", 101)))) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("a", 102)))) }
        val done = privatePullAfterPage(state, page(state, listOf(document("m", 101))))
        val next = privatePullAfterFence(done, PrivatePullFenceRead(cursor("a", 102), trusted))
        assertEquals(PrivatePullPhase.Idle(stamp(102)), privatePullAfterPage(next, page(next, listOf(document("a", 102)))).phase)
    }

    @Test fun missingTimestampOrRowsBeforeDeltaFloorOrResumeAreRejected() {
        val state = delta(after = cursor("m", 101))
        rejects { privatePullAfterPage(state, page(state, listOf(PrivatePullDocument("z", null, "legacy")))) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("a", 99)))) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("m", 101)))) }
        rejects { privatePullAfterPage(state, page(state, listOf(document("a", 101)))) }
    }

    @Test fun untrustedReadNeverAdvancesEvenWhenTimestampIsRepresentable() {
        val state = PrivatePullState(scope, epoch = epoch)
        rejects { privatePullAfterFence(state, PrivatePullFenceRead(cursor("future", 253_402_300_799L), PrivatePullReadTrust.UNTRUSTED)) }
        val active = delta()
        rejects { privatePullAfterPage(active, page(active, listOf(document("a"))).copy(trust = PrivatePullReadTrust.UNTRUSTED)) }
    }

    @Test fun pendingWriteOverlayIsRejectedEvenWithConfirmedServerSource() {
        val state = bootstrap()
        rejects { privatePullAfterPage(state, page(state, listOf(document("a").copy(hasPendingWrites = true)))) }
        val active = delta()
        rejects { privatePullAfterPage(active, page(active, listOf(document("a").copy(hasPendingWrites = true)))) }
    }

    @Test fun emptyDeltaFinishesOnlyAtTheCapturedUpperBoundary() {
        val state = delta(cursor("last", 333, 999))
        val next = privatePullAfterPage(state, page(state, emptyList()))
        assertEquals(PrivatePullPhase.Idle(stamp(333, 999)), next.phase)
        val stillEmpty = privatePullAfterFence(next, PrivatePullFenceRead(null, trusted))
        assertEquals(next.phase, stillEmpty.phase)
        rejects { privatePullAfterFence(next, PrivatePullFenceRead(cursor("older", 332), trusted)) }
    }

    @Test fun stalePageStateAndWrongRequestTypeAreRejected() {
        val state = delta()
        rejects { privatePullAfterPage(state.advance(), page(state, listOf(document("a")))) }
        val wrong = PrivatePullPage(PrivatePullPlan.BootstrapPage(state, 200), listOf(document("a")), trusted)
        rejects { privatePullAfterPage(state, wrong) }
    }

    @Test fun codecRoundTripsEveryPhaseAndCollectionIncludingUnicodeUid() {
        val phases = listOf(PrivatePullPhase.NeedsBootstrapFence, PrivatePullPhase.Bootstrap(null, null),
            PrivatePullPhase.Bootstrap(stamp(100, 999_999_999), "last-id"), PrivatePullPhase.Idle(null),
            PrivatePullPhase.Idle(stamp(-100, 1)), PrivatePullPhase.Delta(null, cursor("upper", 100, 9), null),
            PrivatePullPhase.Delta(stamp(90), cursor("upper", 100, 9), cursor("after", 99, 123)))
        for (collection in PrivatePullCollection.entries) for (phase in phases) {
            val ownerScope = PrivatePullScope("owner|ä-☀", collection)
            val state = PrivatePullState(ownerScope, 99, phase, epoch)
            val raw = PrivatePullCodec.encode(state)
            val decoded = PrivatePullCodec.decode(raw, ownerScope)
            assertEquals(state, decoded.state)
            assertEquals(raw, decoded.raw)
            assertFalse(decoded.recovered)
        }
    }

    @Test fun malformedStateRecoveryKeepsExactRawCasToken() {
        val valid = PrivatePullCodec.encode(delta())
        val inputs = listOf("", "not a checkpoint", valid.replaceFirst("2|", "3|"), "$valid|trailing",
            valid.replace("|5|", "|-1|"), valid.replace("|5|", "|05|"),
            valid.replace("|5|", "|${Long.MAX_VALUE}|"), "x".repeat(1025))
        for (raw in inputs) {
            val decoded = PrivatePullCodec.decode(raw, scope, epoch)
            assertTrue(raw, decoded.recovered)
            assertEquals(raw, decoded.raw)
            assertEquals(PrivatePullState(scope, epoch = epoch), decoded.state)
        }
        assertFalse(PrivatePullCodec.decode(null, scope).recovered)
    }

    @Test fun wrongOwnerAndWrongCollectionCannotReuseCheckpoint() {
        val raw = PrivatePullCodec.encode(delta())
        val foreignOwner = PrivatePullScope("owner-B", scope.collection)
        val foreignGroup = scope.copy(collection = PrivatePullCollection.PRICE_SELECTIONS)
        for (other in listOf(foreignOwner, foreignGroup)) {
            val decoded = PrivatePullCodec.decode(raw, other, epoch)
            assertTrue(decoded.recovered)
            assertEquals(PrivatePullState(other, epoch = epoch), decoded.state)
        }
    }

    @Test fun malformedTimestampAndIllegalPhaseFieldsCannotSurviveCodec() {
        val state = delta(cursor("z", 100, 123), lower = stamp(100, 100))
        val raw = PrivatePullCodec.encode(state)
        val fields = raw.split('|').toMutableList()
        fields[8] = "1000000000"
        assertTrue(PrivatePullCodec.decode(fields.joinToString("|"), scope).recovered)
        fields[8] = "123"
        fields[5] = "101"
        assertTrue(PrivatePullCodec.decode(fields.joinToString("|"), scope).recovered)
        fields[4] = "idle" // Extra upper/resume fields may not be silently ignored.
        assertTrue(PrivatePullCodec.decode(fields.joinToString("|"), scope).recovered)
    }

    @Test fun markerCodecIsOwnerScopedVersionedAndContainsNoPayload() {
        val marker = PrivatePullDeferredMarker(scope, "placement", 3, PrivatePullDeferReason.BINDER_DEPENDENCY, "binder", epoch)
        val encoded = PrivatePullCodec.encodeMarker(marker)
        assertEquals(marker, PrivatePullCodec.decodeMarker(encoded, scope))
        assertNull(PrivatePullCodec.decodeMarker(encoded, scope.copy(ownerUid = "other")))
        assertNull(PrivatePullCodec.decodeMarker("$encoded|payload", scope))
        rejects { marker.copy(dependencyId = null) }
        rejects { marker.copy(documentId = "../../foreign") }
    }

    @Test fun pageApplyDeferredMarkerAndCheckpointAreOneAtomicCommit() = runBlocking {
        val state = delta(cursor("b", 102))
        val store = FakeStore(state)
        val rows = listOf(document("a", 101), document("b", 102))
        val result = privatePullCommitPage(store, store.checkpoint(), lease, page(state, rows)) { row ->
            if (row.documentId == "a") PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            else { store.apply(row); PrivatePullApplyDecision.Complete }
        }
        assertEquals(PrivatePullCommitResult.COMMITTED, result)
        assertNull(store.values["a"])
        assertEquals("b", store.values["b"])
        assertEquals(PrivatePullDeferReason.LOCAL_MUTATION, store.markers.getValue("a").reason)
        assertEquals(PrivatePullPhase.Idle(stamp(102)), store.checkpoint().state.phase)
    }

    @Test fun deferredOlderDocumentIsRecheckedEvenWithoutANewTimestamp() = runBlocking {
        val state = delta(cursor("b", 102))
        val store = FakeStore(state)
        val row = document("a", 101)
        privatePullCommitPage(store, store.checkpoint(), lease, page(state, listOf(row, document("b", 102)))) {
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        val marker = store.markers.getValue("a")
        val phaseBefore = store.checkpoint().state.phase
        val result = privatePullCommitRecheck(store, store.checkpoint(), lease, marker, row, trusted) {
            store.apply(it); PrivatePullApplyDecision.Complete
        }
        assertEquals(PrivatePullCommitResult.COMMITTED, result)
        assertEquals("a", store.values["a"])
        assertFalse(store.markers.containsKey("a"))
        assertTrue(store.markers.containsKey("b"))
        assertEquals(phaseBefore, store.checkpoint().state.phase)
    }

    @Test fun stillMissingParentRetainsMarkerAndDoesNotBlockUnrelatedRows() = runBlocking {
        val state = delta(cursor("b", 102))
        val store = FakeStore(state)
        val dependency = PrivatePullApplyDecision.Defer(PrivatePullDeferReason.BINDER_DEPENDENCY, "parent")
        privatePullCommitPage(store, store.checkpoint(), lease, page(state, listOf(document("a", 101), document("b", 102)))) {
            if (it.documentId == "a") dependency else { store.apply(it); PrivatePullApplyDecision.Complete }
        }
        val first = store.markers.getValue("a")
        repeat(2) {
            privatePullCommitRecheck(store, store.checkpoint(), lease, store.markers.getValue("a"), document("a", 101), trusted) { dependency }
        }
        assertTrue(store.markers.getValue("a").version > first.version)
        assertEquals("b", store.values["b"])
        assertNull(store.values["a"])
        // Rechecks preserve the phase and do not create an APPEND scheduling decision.
        assertEquals(PrivatePullPhase.Idle(stamp(102)), store.checkpoint().state.phase)
    }

    @Test fun staleRecheckCannotDeleteAReplacementMarker() = runBlocking {
        val state = bootstrap()
        val store = FakeStore(state)
        val old = PrivatePullDeferredMarker(scope, "a", 1, PrivatePullDeferReason.LOCAL_MUTATION, epoch = epoch)
        store.markers["a"] = old.copy(version = 2)
        var called = false
        val result = privatePullCommitRecheck(store, store.checkpoint(), lease, old, document("a"), trusted) {
            called = true; PrivatePullApplyDecision.Complete
        }
        assertEquals(PrivatePullCommitResult.STALE_MARKER, result)
        assertFalse(called)
        assertEquals(2L, store.markers.getValue("a").version)
    }

    @Test fun oldWorkerCannotOverwriteCheckpointAfterAnotherWorkerCommits() = runBlocking {
        val state = bootstrap()
        val store = FakeStore(state)
        val oldRead = store.checkpoint()
        val result = privatePullCommitPage(store, oldRead, lease, page(state, listOf(document("a")))) {
            store.apply(it); PrivatePullApplyDecision.Complete
        }
        assertEquals(PrivatePullCommitResult.COMMITTED, result)
        var called = false
        val stale = privatePullCommitPage(store, oldRead, lease, page(state, listOf(document("z")))) {
            called = true; PrivatePullApplyDecision.Complete
        }
        assertEquals(PrivatePullCommitResult.STALE_CHECKPOINT, stale)
        assertFalse(called)
        assertEquals(setOf("a"), store.values.keys)
    }

    @Test fun staleSessionAndAccountRoundTripCannotApplyOldNetworkResponse() = runBlocking {
        for (current in listOf(PrivatePullSessionLease("owner-B", 8), PrivatePullSessionLease("owner-A", 9))) {
            val state = bootstrap()
            val store = FakeStore(state)
            store.session = current
            var called = false
            val result = privatePullCommitPage(store, store.checkpoint(), lease, page(state, listOf(document("a")))) {
                called = true; PrivatePullApplyDecision.Complete
            }
            assertEquals(PrivatePullCommitResult.STALE_SESSION, result)
            assertFalse(called)
            assertEquals(state, store.checkpoint().state)
        }
    }

    @Test fun ownerChangeInsideTransactionRollsBackPayloadAndMarkerAndCheckpoint() = runBlocking {
        val state = bootstrap()
        val store = FakeStore(state)
        val result = privatePullCommitPage(store, store.checkpoint(), lease, page(state, listOf(document("a")))) {
            store.apply(it)
            store.session = lease.copy(generation = 9) // A -> B -> A while a local suspension completes.
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        assertEquals(PrivatePullCommitResult.STALE_SESSION, result)
        assertTrue(store.values.isEmpty())
        assertTrue(store.markers.isEmpty())
        assertEquals(state, store.checkpoint().state)
    }

    @Test fun crashesAtEveryWriteBoundaryRollBackThenRetrySucceeds() = runBlocking {
        // apply(a), remove-marker(a), apply(b), remove-marker(b), checkpoint; including post-write crash.
        for (failAt in 1..5) {
            val state = bootstrap()
            val store = FakeStore(state)
            store.failAt = failAt
            val rows = page(state, listOf(document("a"), document("b")))
            try {
                privatePullCommitPage(store, store.checkpoint(), lease, rows) { store.apply(it); PrivatePullApplyDecision.Complete }
                throw AssertionError("Expected crash at $failAt")
            } catch (_: InjectedFailure) { }
            assertEquals(state, store.checkpoint().state)
            assertTrue(store.values.isEmpty())
            assertTrue(store.markers.isEmpty())
            store.failAt = null
            assertEquals(PrivatePullCommitResult.COMMITTED,
                privatePullCommitPage(store, store.checkpoint(), lease, rows) { store.apply(it); PrivatePullApplyDecision.Complete })
            assertEquals(setOf("a", "b"), store.values.keys)
        }
    }

    @Test fun failureWritingDeferredMarkerCannotAdvancePastBlockedDocument() = runBlocking {
        val state = delta()
        val store = FakeStore(state)
        store.failAt = 1
        try {
            privatePullCommitPage(store, store.checkpoint(), lease, page(state, listOf(document("a")))) {
                PrivatePullApplyDecision.Defer(PrivatePullDeferReason.COLLECTION_DEPENDENCY, "parent")
            }
            throw AssertionError("Expected marker failure")
        } catch (_: InjectedFailure) { }
        assertEquals(state, store.checkpoint().state)
        assertTrue(store.markers.isEmpty())
    }

    @Test fun invalidPageIsRejectedBeforeTheStoreOrApplyCallbackRuns() = runBlocking {
        val state = delta()
        val store = FakeStore(state)
        try {
            privatePullCommitPage(store, store.checkpoint(), lease,
                page(state, listOf(document("a").copy(hasPendingWrites = true)))) { throw AssertionError("must not apply") }
            throw AssertionError("Expected invalid page")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, store.transactions)
    }

    @Test fun corruptCheckpointRecoveryUsesRawCasInsteadOfBlindReset() = runBlocking {
        val store = FakeStore(PrivatePullState(scope, epoch = epoch))
        store.raw = "corrupt-A"
        val expected = store.checkpoint()
        store.raw = "corrupt-B"
        val result = privatePullCommitFence(store, expected, lease, PrivatePullFenceRead(null, trusted))
        assertEquals(PrivatePullCommitResult.STALE_CHECKPOINT, result)
        assertEquals("corrupt-B", store.raw)
        val repaired = privatePullCommitFence(store, store.checkpoint(), lease, PrivatePullFenceRead(null, trusted))
        assertEquals(PrivatePullCommitResult.COMMITTED, repaired)
        assertFalse(store.checkpoint().recovered)
    }

    @Test fun revisionOverflowFailsBeforeLocalMutation() = runBlocking {
        val exhausted = bootstrap().copy(revision = Long.MAX_VALUE)
        val store = FakeStore(bootstrap())
        val raw = PrivatePullCodec.encode(exhausted)
        val direct = PrivatePullCheckpoint(raw, exhausted, false)
        try {
            privatePullCommitPage(store, direct, lease, page(exhausted, listOf(document("a")))) { throw AssertionError("must not apply") }
            throw AssertionError("Expected overflow")
        } catch (_: ArithmeticException) { }
        assertEquals(0, store.transactions)
        assertTrue(PrivatePullCodec.decode(raw, scope).recovered)
    }

    @Test fun recoveryEpochPreventsCorruptionAndSameFenceFromRestoringAnOldCasToken() = runBlocking {
        val starting = PrivatePullState(scope, epoch = "epoch-before-corruption")
        val fence = PrivatePullFenceRead(cursor("z", 100), trusted)
        val oldState = privatePullAfterFence(starting, fence)
        val store = FakeStore(oldState)
        val oldResponseCheckpoint = store.checkpoint()
        val oldResponsePage = page(oldState, listOf(document("a")))
        store.raw = "corrupted"
        val recovered = PrivatePullCodec.decode(store.raw, scope, "epoch-after-corruption")
        assertEquals(recovered, PrivatePullCodec.decode(recovered.raw, scope, recovered.state.epoch))
        assertEquals(PrivatePullCommitResult.COMMITTED, privatePullCommitFence(store, recovered, lease, fence))
        assertEquals(oldState.revision, store.checkpoint().state.revision)
        assertEquals(oldState.phase, store.checkpoint().state.phase)
        assertNotEquals(oldResponseCheckpoint.raw, store.raw)
        var applied = false
        assertEquals(PrivatePullCommitResult.STALE_CHECKPOINT,
            privatePullCommitPage(store, oldResponseCheckpoint, lease, oldResponsePage) {
                applied = true; PrivatePullApplyDecision.Complete
            })
        assertFalse(applied)
    }

    @Test fun markerEpochPreventsOldRecheckMatchingARecreatedSameVersionMarker() = runBlocking {
        val store = FakeStore(bootstrap())
        val stale = PrivatePullDeferredMarker(scope, "a", 4, PrivatePullDeferReason.LOCAL_MUTATION, epoch = "old")
        store.markers["a"] = stale.copy(epoch = "new")
        assertEquals(PrivatePullCommitResult.STALE_MARKER,
            privatePullCommitRecheck(store, store.checkpoint(), lease, stale, document("a"), trusted) {
                throw AssertionError("Stale marker must not apply")
            })
        assertEquals("new", store.markers.getValue("a").epoch)
    }

    @Test fun payloadSemanticsIncludingTombstonesAreNotFilteredByProtocol() = runBlocking {
        val state = delta(cursor("d", 104))
        val store = FakeStore(state)
        val rows = listOf(document("a", 101, value = "deleted=true"), document("b", 102, value = "cleared=true"),
            document("c", 103, value = "visibleInSetBinder=false"), document("d", 104, value = "active"))
        privatePullCommitPage(store, store.checkpoint(), lease, page(state, rows)) { store.apply(it); PrivatePullApplyDecision.Complete }
        assertEquals(rows.associate { it.documentId to it.payload }, store.values)
    }

    @Test fun syntheticOneFiveAndTwentyThousandRowsRespectBudgetAndReduceUnchangedReads() {
        for (count in listOf(1_000, 5_000, 20_000)) {
            val rows = (0 until count).map { document("id-${it.toString().padStart(5, '0')}", seconds = it.toLong()) }
            var state = privatePullAfterFence(PrivatePullState(scope, epoch = epoch), PrivatePullFenceRead(rows.last().cursor(), trusted))
            var totalReads = 0
            var runs = 0
            while (state.phase is PrivatePullPhase.Bootstrap) {
                runs++
                var remaining = PRIVATE_PULL_RUN_ROWS
                while (remaining > 0 && state.phase is PrivatePullPhase.Bootstrap) {
                    val phase = state.phase as PrivatePullPhase.Bootstrap
                    val request = privatePullPlan(state, remaining) as PrivatePullPlan.Page
                    val batch = rows.filter { phase.afterId == null || it.documentId > phase.afterId }.take(request.limit)
                    totalReads += batch.size
                    remaining -= batch.size
                    state = privatePullAfterPage(state, PrivatePullPage(request, batch, trusted))
                }
            }
            assertEquals(count, totalReads)
            assertEquals(count / 1000 + 1, runs) // Exact full pages need the final empty bootstrap query.
            state = privatePullAfterFence(state, PrivatePullFenceRead(rows.last().cursor(), trusted))
            val active = state.phase as PrivatePullPhase.Delta
            val unchanged = rows.filter { it.stamp!! >= active.lowerInclusive!! && it.cursor() <= active.upper }
            assertEquals(1, unchanged.size)
            assertTrue(unchanged.size < count)
            state = privatePullAfterPage(state, page(state, unchanged))
            assertEquals(PrivatePullPhase.Idle(rows.last().stamp), state.phase)
        }
    }

    @Test fun unchangedTimestampTieGroupIsReplayedRatherThanClaimingConstantCost() {
        val rows = (0 until 451).map { document("id-${it.toString().padStart(3, '0')}") }
        var state = delta(rows.last().cursor())
        var reads = 0
        while (state.phase is PrivatePullPhase.Delta) {
            val active = state.phase as PrivatePullPhase.Delta
            val next = rows.filter { active.after == null || it.cursor() > active.after }.take(200)
            reads += next.size
            state = privatePullAfterPage(state, page(state, next))
        }
        assertEquals(451, reads)
    }

    private class InjectedFailure : RuntimeException()

    /** Tests the port's REQUIRED atomicity, not a claim about any real Room adapter. */
    private inner class FakeStore(initial: PrivatePullState) : PrivatePullStorePort {
        private val mutex = Mutex()
        var raw: String? = PrivatePullCodec.encode(initial)
        var session = lease
        val values = linkedMapOf<String, String>()
        val markers = linkedMapOf<String, PrivatePullDeferredMarker>()
        var transactions = 0
        var failAt: Int? = null
        private var writes = 0
        fun checkpoint() = PrivatePullCodec.decode(raw, initial.scope, epoch)
        private val initial = initial
        private fun written() { writes++; if (writes == failAt) throw InjectedFailure() }
        fun apply(row: PrivatePullDocument<String>) { values[row.documentId] = row.payload; written() }

        override suspend fun compareAndCommit(
            expected: PrivatePullCheckpoint,
            lease: PrivatePullSessionLease,
            expectedMarker: PrivatePullDeferredMarker?,
            changes: suspend PrivatePullTransaction.() -> PrivatePullState,
        ): PrivatePullCommitResult = mutex.withLock {
            transactions++
            if (session != lease || lease.ownerUid != expected.state.scope.ownerUid) return@withLock PrivatePullCommitResult.STALE_SESSION
            if (raw != expected.raw) return@withLock PrivatePullCommitResult.STALE_CHECKPOINT
            if (expectedMarker != null && markers[expectedMarker.documentId] != expectedMarker) return@withLock PrivatePullCommitResult.STALE_MARKER
            val beforeRaw = raw
            val beforeValues = values.toMap()
            val beforeMarkers = markers.toMap()
            fun rollback() {
                raw = beforeRaw
                values.clear(); values.putAll(beforeValues)
                markers.clear(); markers.putAll(beforeMarkers)
            }
            writes = 0
            val transaction = object : PrivatePullTransaction {
                override suspend fun putDeferred(marker: PrivatePullDeferredMarker) {
                    require(marker.scope == expected.state.scope)
                    markers[marker.documentId] = marker
                    written()
                }
                override suspend fun removeDeferred(documentId: String) { markers.remove(documentId); written() }
            }
            try {
                val next = transaction.changes()
                require(next.scope == expected.state.scope && next.epoch == expected.state.epoch && next.revision == Math.addExact(expected.state.revision, 1L))
                if (session != lease) { rollback(); return@withLock PrivatePullCommitResult.STALE_SESSION }
                raw = PrivatePullCodec.encode(next)
                written()
                PrivatePullCommitResult.COMMITTED
            } catch (error: Throwable) {
                rollback()
                throw error
            }
        }
    }
}
