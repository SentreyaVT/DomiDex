package com.dominik.cardvault.ui

import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.knowledge.ScanKnowledgeStatus
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScannerKnowledgeRankingIntegrityTest {
    @Test fun manualReorderingPreservesEveryCandidateAndEvidenceObject() {
        val candidates = listOf(candidate("one", 76), candidate("two", 75))
        val ranked = ScanKnowledgeCandidateRanker.apply(candidates, knowledge(candidates[1]))
        assertEquals(listOf("two", "one"), ranked.map { it.card.sourceCardId })
        candidates.forEach { original ->
            val actual = ranked.single { it.card == original.card }
            assertSame("Knowledge is a rank hint, not new OCR evidence", original, actual)
            assertSame(original.evidence, actual.evidence)
            assertEquals(original.score, actual.score)
        }
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun alreadyAcceptedIndependentResultRemainsIdentical() {
        val candidates = listOf(candidate("one", 96, RecognitionConfidence.HIGH), candidate("two", 75))
        assertEquals(ScannerDecision.ACCEPTED, HybridCandidateFusion.decide(candidates).decision)
        assertSame(candidates, ScanKnowledgeCandidateRanker.apply(candidates, knowledge(candidates[1])))
    }

    @Test fun rejectedIndependentResultRemainsIdentical() {
        val candidates = listOf(candidate("one", 55, RecognitionConfidence.LOW), candidate("two", 54, RecognitionConfidence.LOW))
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(candidates).decision)
        assertSame(candidates, ScanKnowledgeCandidateRanker.apply(candidates, knowledge(candidates[1])))
    }

    @Test fun unmatchedKnowledgeCannotPenalizeAnyCandidate() {
        val candidates = listOf(candidate("one", 76), candidate("two", 75))
        val ranked = ScanKnowledgeCandidateRanker.apply(candidates, knowledge(candidate("other", 75)))
        assertEquals(candidates, ranked)
        candidates.indices.forEach { assertSame(candidates[it], ranked[it]) }
    }

    @Test fun futureSourceTimestampCannotBecomeFreshByBeingRead() {
        val candidates = listOf(candidate("one", 76), candidate("two", 75))
        val hint = knowledge(candidates[1]).copy(updatedAtEpochMillis = System.currentTimeMillis() + 86_400_000L)
        assertEquals(candidates, ScanKnowledgeCandidateRanker.apply(candidates, hint))
    }

    @Test fun sourceOlderThanSevenDaysCannotReorder() {
        val candidates = listOf(candidate("one", 76), candidate("two", 75))
        val hint = knowledge(candidates[1]).copy(updatedAtEpochMillis = System.currentTimeMillis() - 8 * 86_400_000L)
        assertEquals(candidates, ScanKnowledgeCandidateRanker.apply(candidates, hint))
    }

    private fun candidate(id: String, score: Int, confidence: RecognitionConfidence = RecognitionConfidence.MEDIUM): ScanCandidate {
        val card = CatalogSearchResult(productId = id.hashCode().toLong(), name = "Synthetic card", expansionId = "sv1",
            trendPriceEur = null, currency = null, finish = "Normal", variant = "Standard", sourceSetId = "sv1", sourceCardId = id)
        return ScanCandidate(card, score, CandidateSignalScores(80, 100, 100, 100, 100, score, confidence))
    }

    private fun knowledge(candidate: ScanCandidate) = ScanKnowledgeRecord(
        fingerprint = "a".repeat(64), cardId = candidate.card.scanIdentityKey(), sourceSetId = "sv1", cardNumber = "001",
        status = ScanKnowledgeStatus.STABLE, confidence = 90, confirmations = 3, corrections = 0,
        knowledgeVersion = 1, updatedAtEpochMillis = System.currentTimeMillis(),
    )
}
