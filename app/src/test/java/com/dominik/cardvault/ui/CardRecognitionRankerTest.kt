package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRecognitionRankerTest {
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
}
