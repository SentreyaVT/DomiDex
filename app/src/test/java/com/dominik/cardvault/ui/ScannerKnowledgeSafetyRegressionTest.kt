package com.dominik.cardvault.ui

import com.dominik.cardvault.data.knowledge.SCAN_KNOWLEDGE_VERSION
import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.knowledge.ScanKnowledgeStatus
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gate-0/7 safety regressions for community knowledge, not ScanLab reviewed hints.
 *
 * These tests deliberately specify the required protection before the production fix. On the
 * preserved Release-27 baseline, the promotion and stale-record tests expose existing failures.
 * They neither contact Firebase nor pretend to exercise its snapshot listener. A server-side
 * missing-document -> local-cache invalidation test needs a separately testable provider seam.
 * The 1970/unknown timestamps below do not prescribe a particular future cache TTL.
 */
class ScannerKnowledgeSafetyRegressionTest {
    @Test fun stableKnowledgeMustNotCreateAcceptanceFromCloseHighCandidates() {
        val candidates = listOf(candidate("sv1-001", 88),
            candidate("sv1-002", 82, RecognitionConfidence.MEDIUM))
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(candidates).decision)

        val ranked = ScanKnowledgeCandidateRanker.apply(
            candidates, knowledge(candidates.first(), ScanKnowledgeStatus.STABLE),
        )

        assertEquals("Community votes must not manufacture a missing acceptance margin",
            ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun candidateKnowledgeMustNotCreateAcceptanceFromCloseHighCandidates() {
        val candidates = listOf(candidate("sv1-001", 88),
            candidate("sv1-002", 78, RecognitionConfidence.MEDIUM))
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(candidates).decision)

        val ranked = ScanKnowledgeCandidateRanker.apply(
            candidates, knowledge(candidates.first(), ScanKnowledgeStatus.CANDIDATE),
        )

        assertEquals("A provisional community record must not turn ambiguity into acceptance",
            ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun knowledgeWithoutAnUpdateTimestampMustNotReorderCandidates() {
        val candidates = mediumCandidates()
        val unknownAge = knowledge(candidates.last(), ScanKnowledgeStatus.STABLE)
            .copy(updatedAtEpochMillis = 0L)

        assertEquals("No update timestamp means no evidence of cache freshness", candidates,
            ScanKnowledgeCandidateRanker.apply(candidates, unknownAge))
    }

    @Test fun clearlyExpiredKnowledgeMustNotReorderCandidates() {
        val candidates = mediumCandidates()
        val historicalRecord = knowledge(candidates.last(), ScanKnowledgeStatus.STABLE)
            .copy(updatedAtEpochMillis = 1L)

        assertEquals("A record last updated in 1970 cannot be a fresh scanner hint", candidates,
            ScanKnowledgeCandidateRanker.apply(candidates, historicalRecord))
    }

    @Test fun absentKnowledgeLeavesIndependentCandidatesUnchanged() {
        val candidates = mediumCandidates()
        assertEquals(candidates, ScanKnowledgeCandidateRanker.apply(candidates, null))
    }

    @Test fun disputedKnowledgeLeavesIndependentCandidatesUnchanged() {
        val candidates = mediumCandidates()
        assertEquals(candidates, ScanKnowledgeCandidateRanker.apply(candidates,
            knowledge(candidates.last(), ScanKnowledgeStatus.DISPUTED)))
    }

    @Test fun incompatibleKnowledgeVersionLeavesCandidatesUnchanged() {
        val candidates = mediumCandidates()
        val incompatible = knowledge(candidates.last(), ScanKnowledgeStatus.STABLE)
            .copy(knowledgeVersion = SCAN_KNOWLEDGE_VERSION + 1)
        assertEquals(candidates, ScanKnowledgeCandidateRanker.apply(candidates, incompatible))
    }

    @Test fun freshKnowledgeCannotRemoveAnIndependentIdentityContradiction() {
        val contradictory = candidate("sv1-001", 45).let {
            it.copy(evidence = it.evidence!!.copy(
                confidence = RecognitionConfidence.LOW, contradictions = setOf("printed_total"),
            ))
        }
        val ranked = ScanKnowledgeCandidateRanker.apply(listOf(contradictory),
            knowledge(contradictory, ScanKnowledgeStatus.STABLE))
        assertEquals(listOf(contradictory), ranked)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun freshMatchingKnowledgeCanStillReorderMediumCandidatesForManualReview() {
        val candidates = mediumCandidates()
        val ranked = ScanKnowledgeCandidateRanker.apply(candidates,
            knowledge(candidates.last(), ScanKnowledgeStatus.STABLE))
        assertEquals(candidates.last().card, ranked.first().card)
        assertEquals(candidates.map { it.card }.toSet(), ranked.map { it.card }.toSet())
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    private fun mediumCandidates() = listOf(
        candidate("sv1-001", 76, RecognitionConfidence.MEDIUM),
        candidate("sv1-002", 75, RecognitionConfidence.MEDIUM),
    )

    private fun candidate(id: String, score: Int,
        confidence: RecognitionConfidence = RecognitionConfidence.HIGH): ScanCandidate {
        val card = CatalogSearchResult(
            productId = id.hashCode().toLong(), name = "Synthetic test card", expansionId = "sv1",
            trendPriceEur = null, currency = null, finish = "Normal", variant = "Standard",
            sourceSetId = "sv1", sourceCardId = id, collectorNumber = id.substringAfter('-'),
        )
        return ScanCandidate(card, score,
            CandidateSignalScores(80, 100, 100, 100, 100, score, confidence))
    }

    private fun knowledge(candidate: ScanCandidate, status: ScanKnowledgeStatus) = ScanKnowledgeRecord(
        fingerprint = "a".repeat(64), cardId = candidate.card.scanIdentityKey(),
        sourceSetId = "sv1", cardNumber = "${candidate.card.collectorNumber}/198",
        status = status, confidence = 90, confirmations = if (status == ScanKnowledgeStatus.STABLE) 3 else 1,
        corrections = 0, knowledgeVersion = SCAN_KNOWLEDGE_VERSION,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )
}
