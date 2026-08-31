package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridCandidateFusionTest {
    private val analysis = CardTextParser.parse(
        mapOf(CardScript.LATIN to "Pikachu ex\nPBL DE 117/084"),
        preferredNameText = "Pikachu ex",
        preferredSetText = "PBL DE 117/084",
        preferredNumberText = "117/084",
    )
    private fun candidate(id: Long, finish: String = "Normal") = CatalogSearchResult(
        productId = id,
        name = "Pikachu ex",
        expansionId = "source-set",
        trendPriceEur = 2.5,
        currency = "EUR",
        collectorNumber = "117",
        finish = finish,
        setCode = "PBL",
    )

    @Test fun `corroborating artwork and exact identity become very high`() {
        val evidence = HybridCandidateFusion.score(
            analysis,
            candidate(1),
            ArtworkVerification(ArtworkMatch.CORROBORATES, .94f, .94f),
        )
        assertEquals(RecognitionConfidence.VERY_HIGH, evidence.confidence)
        assertTrue(evidence.finalConfidence >= 92)
    }

    @Test fun `visual contradiction cannot be averaged into acceptance`() {
        val ranked = HybridCandidateFusion.rank(
            analysis,
            listOf(ScanCandidate(candidate(1), 100)),
            mapOf(1L to ArtworkVerification(ArtworkMatch.CONTRADICTS, .80f, .20f)),
        )
        assertEquals(RecognitionConfidence.LOW, ranked.single().evidence?.confidence)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `same identity reprints with equal evidence require review`() {
        val candidates = listOf(
            ScanCandidate(candidate(1, "Holo"), 100),
            ScanCandidate(candidate(2, "Reverse Holo"), 100),
        )
        val artwork = mapOf(
            1L to ArtworkVerification(ArtworkMatch.CORROBORATES, .93f, .93f),
            2L to ArtworkVerification(ArtworkMatch.CORROBORATES, .93f, .93f),
        )
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(HybridCandidateFusion.rank(analysis, candidates, artwork)).decision)
    }

    @Test fun `offline visual miss can still accept exact set number and name`() {
        val ranked = HybridCandidateFusion.rank(analysis, listOf(ScanCandidate(candidate(1), 100)), emptyMap())
        assertEquals(RecognitionConfidence.HIGH, ranked.single().evidence?.confidence)
        assertEquals(ScannerDecision.ACCEPTED, HybridCandidateFusion.decide(ranked).decision)
    }
}
