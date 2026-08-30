package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerDecisionPolicyTest {
    @Test fun acceptsOnlyStrongCandidateWithClearDistance() {
        val result = ScannerDecisionPolicy.decide(listOf(94, 70))
        assertEquals(ScannerDecision.ACCEPTED, result.decision)
    }

    @Test fun nearEqualCandidatesStayAmbiguous() {
        val result = ScannerDecisionPolicy.decide(listOf(94, 93))
        assertEquals(ScannerDecision.AMBIGUOUS, result.decision)
        assertEquals("best_candidate_too_close", result.reason)
    }

    @Test fun weakEvidenceIsRejectedInsteadOfForced() {
        val result = ScannerDecisionPolicy.decide(listOf(64, 40))
        assertEquals(ScannerDecision.REJECTED, result.decision)
    }

    @Test fun oneStrongCandidateCanBeAccepted() {
        val result = ScannerDecisionPolicy.decide(listOf(90))
        assertEquals(ScannerDecision.ACCEPTED, result.decision)
    }
}
