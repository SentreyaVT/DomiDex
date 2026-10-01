package com.dominik.cardvault.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionReconnectGateTest {
    @Test fun noNetworkHintDoesNotRetry() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        repeat(20) { assertFalse(gate.consumeAfterTransientFailure(1)) }
    }

    @Test fun repeatedCapabilitiesAreNotNewEdges() {
        val gate = CollectionReconnectGate()
        assertFalse(gate.observe(false))
        assertTrue(gate.observe(true))
        repeat(20) { assertFalse(gate.observe(true)) }
        assertFalse(gate.observe(false))
        assertTrue(gate.observe(true))
    }

    @Test fun alreadyUsableAtAttemptStartDoesNotCauseFailureLoop() {
        val gate = CollectionReconnectGate()
        gate.observe(true)
        gate.started(1)
        repeat(20) { assertFalse(gate.consumeAfterTransientFailure(1)) }
    }

    @Test fun reconnectDuringCheckAllowsExactlyOneFollowup() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        gate.observe(true)
        assertTrue(gate.consumeAfterTransientFailure(1))
        assertFalse(gate.consumeAfterTransientFailure(1))
        gate.started(2)
        repeat(20) { assertFalse(gate.consumeAfterTransientFailure(2)) }
    }

    @Test fun manyEdgesDuringCheckCoalesceAndFurtherRealEdgeMayRetry() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        repeat(100) { gate.observe(false); gate.observe(true) }
        assertTrue(gate.consumeAfterTransientFailure(1))
        assertFalse(gate.consumeAfterTransientFailure(1))
        gate.started(2)
        gate.observe(false)
        gate.observe(true)
        assertTrue(gate.consumeAfterTransientFailure(2))
        assertFalse(gate.consumeAfterTransientFailure(2))
    }

    @Test fun connectionLostBeforeFailureDoesNotRetryOffline() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        gate.observe(true)
        gate.observe(false)
        assertFalse(gate.consumeAfterTransientFailure(1))
    }

    @Test fun newAuthGenerationDropsPreviousPendingHintAndStaleCompletion() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        gate.observe(true)
        gate.started(2)
        assertFalse(gate.consumeAfterTransientFailure(1))
        assertFalse(gate.consumeAfterTransientFailure(2))
        gate.observe(false)
        gate.observe(true)
        gate.started(3) // A -> B -> A still has a new generation.
        assertFalse(gate.consumeAfterTransientFailure(1))
        assertFalse(gate.consumeAfterTransientFailure(2))
        assertFalse(gate.consumeAfterTransientFailure(3))
    }

    @Test fun successDenialOrCloseDiscardPendingFollowup() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        gate.observe(true)
        gate.clear()
        repeat(20) { assertFalse(gate.consumeAfterTransientFailure(1)) }
        gate.observe(false)
        gate.observe(true)
        assertFalse(gate.consumeAfterTransientFailure(1))
    }

    @Test fun clearingPreviousCheckDoesNotPreventNewRealCheck() {
        val gate = CollectionReconnectGate()
        gate.started(1)
        gate.clear()
        gate.started(2)
        gate.observe(true)
        assertTrue(gate.consumeAfterTransientFailure(2))
    }
}
