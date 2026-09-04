package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRecognitionRankerTest {
    @Test fun registeredPromoSourceEquatesPrintedPrefixAndNumericLocalId() {
        mapOf("MEP071" to "mep", "SVP173" to "svp").forEach { (printed, source) ->
            val localId = printed.filter(Char::isDigit)
            val analysis = promoAnalysis(printed).copy(setIdentifier = source)
            val card = promoCandidate(localId, source).copy(setCode = source.uppercase())

            assertEquals(100, CardRecognitionRanker.score(analysis, card))
            assertEquals(100, CardRecognitionRanker.score(analysis.copy(collectorNumber = localId), card.copy(collectorNumber = printed)))
        }
    }

    @Test fun sourceNamespaceExpansionCanSupportAnUnpricedPromo() {
        assertEquals(100, CardRecognitionRanker.score(promoAnalysis("MEP071"), promoCandidate("071", null, "mep")))
    }

    @Test fun foreignOrMissingSourceCannotAuthorizePromoPrefixRemoval() {
        val analysis = promoAnalysis("MEP071")
        listOf(
            promoCandidate("071", "svp", "mep"),
            promoCandidate("071", "sv08.5"),
            promoCandidate("071", null),
        ).forEach { card ->
            assertEquals("No number-match points without the matching source: $card", 45, CardRecognitionRanker.score(analysis, card))
        }
    }

    @Test fun subsetPrefixesRemainSignificantEvenInsideAPromoSource() {
        listOf("TG05" to "005", "GG71" to "071").forEach { (printed, localId) ->
            val analysis = promoAnalysis(printed)
            assertEquals(45, CardRecognitionRanker.score(analysis, promoCandidate(localId, "mep")))
            assertEquals(100, CardRecognitionRanker.score(analysis, promoCandidate(printed, "mep")))
        }
    }

    private fun promoAnalysis(number: String) = CardTextAnalysis(
        lines = emptyList(), collectorNumber = number, setIdentifier = "MEP",
        likelyScript = CardScript.LATIN, searchQueries = listOf("Promokarte"),
        numberConfidence = 1f, hasNameRoiEvidence = true,
    )

    private fun promoCandidate(number: String, source: String?, expansion: String = "1234") = CatalogSearchResult(
        productId = 1, name = "Promokarte", expansionId = expansion, trendPriceEur = null, currency = null,
        collectorNumber = number, setCode = "MEP", sourceSetId = source,
    )

    @Test fun exactPrintedNumberAndNameRankAboveUnrelatedName() {
        val analysis = CardTextParser.parse(mapOf(CardScript.LATIN to "Pikachu ex\nSV3 122/088"))
        val matching = CatalogSearchResult(1, "Pikachu ex", "sv3", null, null, collectorNumber = "122")
        val other = CatalogSearchResult(2, "Charizard ex", "sv3", null, null, collectorNumber = "122")
        assertTrue(CardRecognitionRanker.score(analysis, matching) > CardRecognitionRanker.score(analysis, other))
    }

    @Test fun readableContradictoryGermanNameIsNeverHighConfidence() {
        val analysis = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Glurak ex\nSV3 122/088"),
            preferredNameText = "Glurak ex",
        )
        val unrelated = CatalogSearchResult(2, "Pikachu ex", "sv3", null, null, collectorNumber = "122")
        assertTrue(CardRecognitionRanker.score(analysis, unrelated) < 65)
    }

    @Test fun germanDiacriticsDoNotLowerAnOtherwiseExactMatch() {
        val analysis = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Flabebe\nSV2 082/193"),
            preferredNameText = "Flabebe",
        )
        val accented = CatalogSearchResult(1, "Flabébé", "sv2", null, null, collectorNumber = "082")

        assertTrue(CardRecognitionRanker.score(analysis, accented) >= 90)
    }

    @Test fun printedTotalKeepsExactCandidateWhenSetCodeIsUnreadable() {
        val analysis = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Flamara"),
            preferredNameText = "amar",
            preferredNumberText = "087/086",
            preferredSetText = "RÌM 087/086",
        )
        val matching = CatalogSearchResult(
            1, "Flamara", "me04", null, null,
            collectorNumber = "087", mainSetSize = 86, setCode = "CRI",
        )

        assertTrue(CardRecognitionRanker.score(analysis, matching) >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE)
    }

    @Test fun sourceLanguageFallbackReachesArtworkVerificationWithoutCreatingANameContradiction() {
        val analysis = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Deutscher Kartenname"),
            preferredNameText = "Deutscher Kartenname",
            preferredNumberText = "007/100",
        )
        val sourceFallback = CatalogSearchResult(
            1, "English Card Name", "future", null, null,
            collectorNumber = "007", language = "SOURCE_FALLBACK", mainSetSize = 100,
        )

        assertTrue(CardRecognitionRanker.score(analysis, sourceFallback) >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE)
    }
}
