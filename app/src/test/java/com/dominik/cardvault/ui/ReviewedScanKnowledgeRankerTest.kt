package com.dominik.cardvault.ui

import com.dominik.cardvault.data.knowledge.ReviewedScanKnowledgeHint
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.*
import org.junit.Test

class ReviewedScanKnowledgeRankerTest {
    private val fingerprint = "a".repeat(64)
    private val hint = ReviewedScanKnowledgeHint(fingerprint, DOMIDEX_SCANNER_VERSION, 1, "sv1", "sv1-002", "002", "normal")

    @Test fun reviewedMappingReordersOnlyExistingCloseOfficialCandidatesWithoutAutoAccepting() {
        val candidates = listOf(candidate("sv1-001", 76), candidate("sv1-002", 75))
        val ranked = ReviewedScanKnowledgeRanker.apply(candidates, hint, fingerprint)
        assertEquals("sv1-002", ranked.first().card.sourceCardId)
        assertEquals(candidates.map { it.card }.toSet(), ranked.map { it.card }.toSet())
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
        assertEquals(77, ranked.first().evidence?.finalConfidence)
    }

    @Test fun mismatchedFingerprintVersionIdentityNumberVariantOrContradictionDoesNothing() {
        val candidates = listOf(candidate("sv1-001", 76), candidate("sv1-002", 75))
        for (invalid in listOf(hint.copy(fingerprint = "b".repeat(64)), hint.copy(scannerVersion = "future"),
            hint.copy(knowledgeVersion = 2), hint.copy(sourceSetId = "sv2"), hint.copy(sourceCardId = "sv1-999"),
            hint.copy(cardNumber = "003"), hint.copy(variant = "holo"), hint.copy(variant = "metal"),
            hint.copy(variant = "lenticular"), hint.copy(variant = null))) {
            assertEquals(candidates, ReviewedScanKnowledgeRanker.apply(candidates, invalid, fingerprint))
        }
        for (contradiction in listOf("set", "collector_number", "artwork", "name")) {
            val bad = listOf(candidate("sv1-002", 75, contradictions = setOf(contradiction)))
            assertEquals(bad, ReviewedScanKnowledgeRanker.apply(bad, hint, fingerprint))
        }
        assertEquals(candidates, ReviewedScanKnowledgeRanker.apply(candidates, null, fingerprint))
        assertEquals(candidates, ReviewedScanKnowledgeRanker.apply(candidates, hint, null))
    }

    @Test fun missingEvidenceWeakIdentityAndSpecialPrintsCannotReceiveABonus() {
        val card = candidate("sv1-002", 75)
        for (invalid in listOf(card.copy(evidence = null), card.copy(evidence = card.evidence!!.copy(numberScore = 85)),
            card.copy(evidence = card.evidence.copy(setScore = 64)),
            card.copy(card = card.card.copy(variant = "1. Edition")),
            card.copy(card = card.card.copy(variant = "Standard · Stempel: Pokemon-Center")),
            card.copy(card = card.card.copy(sourceCardId = null)))) {
            assertEquals(listOf(invalid), ReviewedScanKnowledgeRanker.apply(listOf(invalid), hint, fingerprint))
        }
        assertEquals(listOf(card, card), ReviewedScanKnowledgeRanker.apply(listOf(card, card), hint, fingerprint))
    }

    @Test fun anExistingAcceptedResultIsUnchangedAndBonusCannotCreateAcceptance() {
        val accepted = listOf(candidate("sv1-002", 96, RecognitionConfidence.HIGH), candidate("sv1-001", 74))
        assertEquals(ScannerDecision.ACCEPTED, HybridCandidateFusion.decide(accepted).decision)
        assertEquals(accepted, ReviewedScanKnowledgeRanker.apply(accepted, hint, fingerprint))
        for (secondScore in 70..94) {
            val original = listOf(candidate("sv1-002", 94, RecognitionConfidence.HIGH), candidate("sv1-001", secondScore))
            val ranked = ReviewedScanKnowledgeRanker.apply(original, hint, fingerprint)
            assertEquals(HybridCandidateFusion.decide(original).decision, HybridCandidateFusion.decide(ranked).decision)
        }
    }

    @Test fun holoAndReverseOnlyMatchTheirExistingOrdinarySourceVariant() {
        for ((reviewed, finish) in listOf("holo" to "Holo", "reverse" to "Reverse Holo")) {
            val other = candidate("sv1-001", 76)
            val matching = candidate("sv1-002", 75).let { it.copy(card = it.card.copy(finish = finish, variant = finish)) }
            assertEquals(matching.card, ReviewedScanKnowledgeRanker.apply(listOf(other, matching), hint.copy(variant = reviewed), fingerprint).first().card)
        }
    }

    private fun candidate(id: String, score: Int, confidence: RecognitionConfidence = RecognitionConfidence.MEDIUM,
        contradictions: Set<String> = emptySet()) = ScanCandidate(
        card = CatalogSearchResult(productId = id.hashCode().toLong(), name = "Card", expansionId = "sv1",
            trendPriceEur = null, currency = null, finish = "Normal", variant = "Standard", sourceSetId = "sv1",
            sourceCardId = id, collectorNumber = id.substringAfter('-')),
        score = score, evidence = CandidateSignalScores(50, 90, 100, 50, 100, score, confidence, contradictions),
    )
}
