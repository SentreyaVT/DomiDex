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

    @Test fun registeredPromoSourceEquatesPrintedPrefixAndNumericLocalId() {
        mapOf("MEP071" to "mep", "SVP173" to "svp").forEach { (printed, source) ->
            val localId = printed.filter(Char::isDigit)
            val promo = analysis.copy(collectorNumber = printed, setIdentifier = source, numberConfidence = 1f)
            val card = candidate(1).copy(collectorNumber = localId, sourceSetId = source, expansionId = "1234", setCode = source.uppercase())
            val evidence = HybridCandidateFusion.score(promo, card, null)

            assertEquals(100, evidence.numberScore)
            assertTrue("collector_number" !in evidence.contradictions)
            assertEquals(ScannerDecision.ACCEPTED, HybridCandidateFusion.decide(HybridCandidateFusion.rank(promo, listOf(ScanCandidate(card, 100)), emptyMap())).decision)
            assertEquals(100, HybridCandidateFusion.score(promo.copy(collectorNumber = localId), card.copy(collectorNumber = printed), null).numberScore)
        }
    }

    @Test fun sourceNamespaceExpansionCanSupportAnUnpricedPromo() {
        val promo = analysis.copy(collectorNumber = "MEP071", setIdentifier = "MEP", numberConfidence = 1f)
        val card = candidate(1).copy(collectorNumber = "071", expansionId = "mep", setCode = "MEP")

        assertEquals(100, HybridCandidateFusion.score(promo, card, null).numberScore)
    }

    @Test fun foreignOrMissingSourceCannotAuthorizePromoPrefixRemoval() {
        val promo = analysis.copy(collectorNumber = "MEP071", setIdentifier = "MEP", numberConfidence = 1f)
        val card = candidate(1).copy(collectorNumber = "071", expansionId = "1234", setCode = "MEP")
        listOf(
            card.copy(sourceSetId = "svp", expansionId = "mep"),
            card.copy(sourceSetId = "sv08.5"),
            card,
        ).forEach { wrongSource ->
            val ranked = HybridCandidateFusion.rank(promo, listOf(ScanCandidate(wrongSource, 100)), emptyMap())
            assertEquals(0, ranked.single().evidence?.numberScore)
            assertTrue("collector_number" in ranked.single().evidence!!.contradictions)
            assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
        }
    }

    @Test fun subsetPrefixesRemainSignificantEvenInsideAPromoSource() {
        listOf("TG05" to "005", "GG71" to "071").forEach { (printed, localId) ->
            val subset = analysis.copy(collectorNumber = printed, numberConfidence = 1f)
            val card = candidate(1).copy(collectorNumber = localId, sourceSetId = "mep")
            val evidence = HybridCandidateFusion.score(subset, card, null)

            assertEquals(0, evidence.numberScore)
            assertTrue("collector_number" in evidence.contradictions)
            assertEquals(100, HybridCandidateFusion.score(subset, card.copy(collectorNumber = printed), null).numberScore)
        }
    }

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
            mapOf(candidate(1).scanIdentityKey() to ArtworkVerification(ArtworkMatch.CONTRADICTS, .80f, .20f)),
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
            candidates[0].card.scanIdentityKey() to ArtworkVerification(ArtworkMatch.CORROBORATES, .93f, .93f),
            candidates[1].card.scanIdentityKey() to ArtworkVerification(ArtworkMatch.CORROBORATES, .93f, .93f),
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
            mapOf(candidate(1).scanIdentityKey() to ArtworkVerification(ArtworkMatch.CORROBORATES, .93f, .93f)),
        )

        assertEquals(RecognitionConfidence.MEDIUM, ranked.single().evidence?.confidence)
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `visual similarity can never override collector contradiction`() {
        val wrongNumber = analysis.copy(collectorNumber = "042/084")
        val ranked = HybridCandidateFusion.rank(
            wrongNumber,
            listOf(ScanCandidate(candidate(1), 90)),
            mapOf(candidate(1).scanIdentityKey() to ArtworkVerification(ArtworkMatch.CORROBORATES, .96f, .96f)),
        )

        assertTrue("collector_number" in ranked.single().evidence!!.contradictions)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `exact printed identity wins with inconclusive artwork`() {
        val ranked = HybridCandidateFusion.rank(
            analysis,
            listOf(ScanCandidate(candidate(1), 90)),
            mapOf(candidate(1).scanIdentityKey() to ArtworkVerification(ArtworkMatch.INCONCLUSIVE, .65f, .65f)),
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

    @Test fun `printed denominator safely replaces unreadable set code after catalogue reduction`() {
        val noSetCode = analysis.copy(setIdentifier = null)
        val sourceCandidate = candidate(1).copy(mainSetSize = 84)
        val ranked = HybridCandidateFusion.rank(noSetCode, listOf(ScanCandidate(sourceCandidate, 95)), emptyMap())

        assertEquals(65, ranked.single().evidence?.setScore)
        assertEquals(ScannerDecision.ACCEPTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `wrong printed denominator never becomes set evidence`() {
        val noSetCode = analysis.copy(setIdentifier = null)
        val wrongSet = candidate(1).copy(mainSetSize = 86)
        val ranked = HybridCandidateFusion.rank(noSetCode, listOf(ScanCandidate(wrongSet, 80)), emptyMap())

        assertEquals(0, ranked.single().evidence?.setScore)
        assertTrue("printed_total" in ranked.single().evidence!!.contradictions)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `classic virtual subset total does not contradict its historic printed denominator`() {
        val classic = analysis.copy(
            collectorNumber = "89/149",
            setIdentifier = "30C",
            searchQueries = listOf("Solgaleo GX"),
        )
        val sourceCard = candidate(1).copy(
            name = "Solgaleo GX",
            collectorNumber = "089/149",
            setCode = "30C",
            sourceSetId = "30th-c",
            mainSetSize = 0,
        )

        val evidence = HybridCandidateFusion.score(classic, sourceCard, null)

        assertTrue(evidence.numberScore > 0)
        assertTrue(evidence.setScore >= 65)
        assertTrue("printed_total" !in evidence.contradictions)
    }

    @Test fun `missing shared set code keeps historic original and classic candidates ambiguous`() {
        val noSetCode = analysis.copy(
            collectorNumber = "89/149",
            setIdentifier = null,
            searchQueries = listOf("Solgaleo GX"),
            hasNameRoiEvidence = true,
        )
        val historicOriginal = candidate(1).copy(
            name = "Solgaleo GX",
            collectorNumber = "089",
            sourceSetId = "historic",
            mainSetSize = 149,
            setCode = "HIS",
        )
        val anniversaryClassic = candidate(2).copy(
            name = "Solgaleo GX",
            collectorNumber = "89/149",
            sourceSetId = "30th-c",
            mainSetSize = 0,
            setCode = "30C",
        )

        assertEquals(
            CardRecognitionRanker.score(noSetCode, historicOriginal),
            CardRecognitionRanker.score(noSetCode, anniversaryClassic),
        )
        val ranked = HybridCandidateFusion.rank(
            noSetCode,
            listOf(ScanCandidate(historicOriginal, 95), ScanCandidate(anniversaryClassic, 95)),
            emptyMap(),
        )
        assertEquals(listOf(65, 65), ranked.map { it.evidence?.setScore })
        assertEquals(ScannerDecision.AMBIGUOUS, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `unrelated zero-size catalogue cannot bypass a physical denominator contradiction`() {
        val sourceCard = candidate(1).copy(
            collectorNumber = "117",
            sourceSetId = "another-virtual-set",
            mainSetSize = 0,
        )

        val evidence = HybridCandidateFusion.score(analysis, sourceCard, null)

        assertTrue("printed_total" in evidence.contradictions)
    }

    @Test fun `verified set and collector cannot override contradictory physical denominator`() {
        val noisyPrintedTotal = analysis.copy(collectorNumber = "117/080")
        val canonicalSourceCard = candidate(1).copy(mainSetSize = 84, collectorNumber = "117")
        val ranked = HybridCandidateFusion.rank(
            noisyPrintedTotal,
            listOf(ScanCandidate(canonicalSourceCard, 95)),
            emptyMap(),
        )

        assertEquals(0, ranked.single().evidence?.setScore)
        assertTrue("printed_total" in ranked.single().evidence!!.contradictions)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }

    @Test fun `source language fallback waits for artwork without contradicting German name ocr`() {
        val fallback = candidate(1).copy(
            name = "English Source Name",
            language = "SOURCE_FALLBACK",
            mainSetSize = 84,
        )
        val evidence = HybridCandidateFusion.score(
            analysis.copy(setIdentifier = null),
            fallback,
            ArtworkVerification(ArtworkMatch.CORROBORATES, .94f, .94f),
        )

        assertTrue("name" !in evidence.contradictions)
        assertTrue(evidence.finalConfidence >= 85)
    }

    @Test fun `manual catalogue source is matched in source namespace not as printed code`() {
        val manual = analysis.copy(
            setIdentifier = null,
            manualSetSourceId = "sv08.5",
            manualIdentityConfirmed = true,
        )
        val sourceCard = candidate(1).copy(expansionId = "sv08.5", setCode = "PBL", mainSetSize = 84)

        val evidence = HybridCandidateFusion.score(manual, sourceCard, null)

        assertEquals(100, evidence.setScore)
        assertTrue("set" !in evidence.contradictions)
        assertTrue("manual_set_source" !in evidence.contradictions)
    }

    @Test fun `manual catalogue source contradiction is a hard rejection`() {
        val manual = analysis.copy(
            manualSetSourceId = "sv08.5",
            manualIdentityConfirmed = true,
        )
        val wrongSource = candidate(1).copy(expansionId = "sv07", mainSetSize = 84)
        val ranked = HybridCandidateFusion.rank(manual, listOf(ScanCandidate(wrongSource, 95)), emptyMap())

        assertTrue("manual_set_source" in ranked.single().evidence!!.contradictions)
        assertEquals(ScannerDecision.REJECTED, HybridCandidateFusion.decide(ranked).decision)
    }
}
