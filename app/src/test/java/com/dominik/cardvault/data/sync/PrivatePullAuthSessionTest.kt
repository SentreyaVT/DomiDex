package com.dominik.cardvault.data.sync

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/** No Firebase runtime or tokens: checks only the captured-session predicate. */
class PrivatePullAuthSessionTest {
    private val generation = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private var owner: String? = "owner-a"
    private val lease = PrivatePullSessionLease("owner-a", 1)
    private val session = PrivatePullAuthSession(lease, generation, closed) { owner }

    @Test fun sameOwnerAndGenerationAreCurrent() {
        assertTrue(session.isCurrent())
        session.requireCurrent()
    }

    @Test fun differentOwnerIsNotCurrentEvenBeforeEventDelivery() {
        owner = "owner-b"
        assertFalse(session.isCurrent())
    }

    @Test fun logoutIsNotCurrent() {
        owner = null
        assertFalse(session.isCurrent())
    }

    @Test fun observedAccountRoundTripDoesNotReviveOriginalSession() {
        owner = "owner-b"
        generation.incrementAndGet()
        owner = "owner-a"
        generation.incrementAndGet()
        assertFalse(session.isCurrent())
        assertThrows(PrivatePullAccountChangedException::class.java) { session.requireCurrent() }
    }

    @Test fun tokenRefreshInvalidatesCapturedLease() {
        generation.incrementAndGet()
        assertFalse(session.isCurrent())
    }

    @Test fun closedSessionCannotBeReused() {
        closed.set(true)
        assertFalse(session.isCurrent())
    }

    @Test fun differentCandidateCannotBorrowCurrentSession() {
        assertFalse(session.isCurrent(PrivatePullSessionLease("owner-b", 1)))
        assertFalse(session.isCurrent(PrivatePullSessionLease("owner-a", 2)))
    }
}
