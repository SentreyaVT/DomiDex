package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextParserTest {
    @Test fun extractsCollectorNumberAndUsefulNameQuery() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Pikachu ex\nBasis\n122 / 088\nKP 120"))

        assertEquals("122/088", result.collectorNumber)
        assertEquals(CardScript.LATIN, result.likelyScript)
        assertTrue("Pikachu ex" in result.searchQueries)
        assertTrue("Basis" !in result.searchQueries)
    }

    @Test fun detectsJapaneseKanaWithoutClaimingExactLanguageFromLatin() {
        val result = CardTextParser.parse(mapOf(CardScript.JAPANESE to "ピカチュウ\n025/165"))

        assertEquals(CardScript.JAPANESE, result.likelyScript)
        assertEquals("025/165", result.collectorNumber)
    }

    @Test fun latinCardWinsOverSmallJapaneseModelHallucination() {
        val result = CardTextParser.parse(mapOf(
            CardScript.LATIN to "Igamaro\nVerprügler\n004/182",
            CardScript.JAPANESE to "Igamaro の",
            CardScript.CHINESE to "Igamaro",
        ))

        assertEquals(CardScript.LATIN, result.likelyScript)
        assertTrue("Igamaro" in result.searchQueries)
    }
}
