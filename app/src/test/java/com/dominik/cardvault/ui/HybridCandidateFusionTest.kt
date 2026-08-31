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

    @Test fun `strong artwork number and set tolerate weak special card name`() {
        val weakName = analysis.copy(searchQueries = listOf("Pikacu"), hasNameRoiEvidence = true)
        val evidence = HybridCandidateFusion.score(weakName, candidate(1), ArtworkVerification(ArtworkMatch.CORROBORATES, .95f, .95f))

        assertEquals(RecognitionConfidence.VERY_HIGH, evidence.confidence)
        assertTrue("name" !in evidence.contradictions)
    }

    @Test fun `artwork and name without printed identity remain review only`() {
        val noBorder = analysis.copy(collectorNumber = null, setIdentifier = null, numberConfidence = 0f)
        val ranked = HybridCandidateFusion.rank(
            noBorder,
            listOf(ScanCandidate(candidate(1), 80)),
            mapOf(1L to ArtworkVerification(ArtworkMatch.CORROBORATES, .93f, .93f)),
        )

        assertEquals(RecognitionConfidence.MEDIUM, ranked.single().evidence?.confidence)
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `visual similarity can never override collector contradiction`() {
        val wrongNumber = analysis.copy(collectorNumber = "042/084")
        val ranked = HybridCandidateFusion.rank(
            wrongNumber,
            listOf(ScanCandidate(candidate(1), 90)),
            mapOf(1L to ArtworkVerification(ArtworkMatch.CORROBORATES, .96f, .96f)),
        )

        assertTrue("collector_number" in ranked.single().evidence!!.contradictions)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `exact printed identity wins with inconclusive artwork`() {
        val ranked = HybridCandidateFusion.rank(
            analysis,
            listOf(ScanCandidate(candidate(1), 90)),
            mapOf(1L to ArtworkVerification(ArtworkMatch.INCONCLUSIVE, .65f, .65f)),
        )

        assertEquals(RecognitionConfidence.HIGH, ranked.single().evidence?.confidence)
        assertEquals(ScannerDecision.ACCEPTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `temporal visual conflict remains inconclusive instead of forcing a match`() {
        val combined = CardArtworkVerifier.combineTemporal(listOf(
            ArtworkVerification(ArtworkMatch.CORROBORATES, .90f, .90f),
            ArtworkVerification(ArtworkMatch.CONTRADICTS, .75f, .25f),
        ))

        assertEquals(ArtworkMatch.INCONCLUSIVE, combined.match)
    }
}
