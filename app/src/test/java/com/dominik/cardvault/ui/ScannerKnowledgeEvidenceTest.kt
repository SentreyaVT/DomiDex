package com.dominik.cardvault.ui

import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.knowledge.ScanKnowledgeStatus
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerKnowledgeEvidenceTest {
    @Test fun stableKnowledgeOnlyReordersOfficialCandidatesAndCannotAutoAcceptMediumEvidence() {
        val first = candidate("sv1-001", 76)
        val second = candidate("sv1-002", 75)
        val record = knowledge(second.card.scanIdentityKey(), ScanKnowledgeStatus.STABLE)

        val ranked = ScanKnowledgeCandidateRanker.apply(listOf(first, second), record)

        assertEquals(second.card.scanIdentityKey(), ranked.first().card.scanIdentityKey())
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun disputedKnowledgeAndContradictionsNeverBoostAResult() {
        val contradictory = candidate("sv1-001", 80, setOf("set"))
        assertEquals(
            contradictory,
            ScanKnowledgeCandidateRanker.apply(listOf(contradictory), knowledge(contradictory.card.scanIdentityKey(), ScanKnowledgeStatus.STABLE)).single(),
        )
        val clean = candidate("sv1-002", 78)
        assertEquals(listOf(clean), ScanKnowledgeCandidateRanker.apply(listOf(clean), knowledge(clean.card.scanIdentityKey(), ScanKnowledgeStatus.DISPUTED)))
    }

    private fun candidate(sourceCardId: String, score: Int, contradictions: Set<String> = emptySet()): ScanCandidate {
        val card = CatalogSearchResult(
            productId = sourceCardId.hashCode().toLong(), name = "Karte", expansionId = "sv1",
            trendPriceEur = null, currency = null, finish = "Normal", variant = "Standard",
            sourceSetId = "sv1", sourceCardId = sourceCardId,
        )
        return ScanCandidate(
            card = card,
            score = score,
            evidence = CandidateSignalScores(50, 90, 100, 50, 100, score, RecognitionConfidence.MEDIUM, contradictions),
        )
    }

    private fun knowledge(cardId: String, status: ScanKnowledgeStatus) = ScanKnowledgeRecord(
        fingerprint = "a".repeat(64), cardId = cardId, sourceSetId = "sv1", cardNumber = "001/198",
        status = status, confidence = 90, confirmations = 3, corrections = 0,
        knowledgeVersion = 1, updatedAtEpochMillis = 1,
    )
}
