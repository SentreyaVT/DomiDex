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

    @Test fun extractsLowerBorderSetCodeAlongsidePrintedNumber() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Pikachu ex\nSV3 EN\n122/088"))
        assertEquals("SV3", result.setIdentifier)
        assertEquals("122/088", result.collectorNumber)
    }

    @Test fun separatesModernThreeLetterSetCodeFromCollectorNumber() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Hornliu\nMEG 001/122\nIllus. Saya Tsuruta"))
        assertEquals("MEG", result.setIdentifier)
        assertEquals("001/122", result.collectorNumber)
    }

    @Test fun recognizesInternationalSetCodeOnTheLowerBorder() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Charizard ex\nOBF 198/197"))
        assertEquals("OBF", result.setIdentifier)
        assertEquals("198/197", result.collectorNumber)
    }

    @Test fun ignoresGermanLanguageMarkerAndKeepsSetCodeAndNumber() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Raupy\nCRI DE 122/084"))

        assertEquals("CRI", result.setIdentifier)
        assertEquals("122/084", result.collectorNumber)
        assertTrue("CRI DE 122/084" !in result.searchQueries)
    }

    @Test fun ignoresLeadingLanguageMarkerBeforeSetCode() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Raupy\nDE POR 005/162"))

        assertEquals("POR", result.setIdentifier)
        assertEquals("005/162", result.collectorNumber)
    }

    @Test fun ignoresGermanMarkerWhenItIsHyphenatedToTheSetCode() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Bibor-ex\nCRI-DE 014/084"))

        assertEquals("CRI", result.setIdentifier)
        assertEquals("014/084", result.collectorNumber)
        assertTrue("Bibor-ex" in result.searchQueries)
    }

    @Test fun repairsTinyBorderOcrWithoutTurningAttackTextIntoAName() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Glurak ex\nAttacke Flammenwurf\nOBF O98/I97"))
        assertEquals("098/197", result.collectorNumber)
        assertTrue("Attacke Flammenwurf" !in result.searchQueries)
        assertTrue("Glurak ex" in result.searchQueries)
    }

    @Test fun numberParserRepairsOnlyTheDedicatedLowerBorderEvidence() {
        val result = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Pikachu ex\nKP 120"),
            preferredNumberText = "CRI DE O73/O64",
        )
        assertEquals("073/064", result.collectorNumber)
        assertTrue(result.numberConfidence >= .8f)
    }

    @Test fun prefersDedicatedSetRoiOverUnrelatedNameRegionText() {
        val result = CardTextParser.parse(
            textByScript = mapOf(CardScript.LATIN to "Tropius\nFruchtiges Aroma\n001/084"),
            preferredSetText = "PBL DE 001/084",
            preferredNumberText = "001/084",
        )
        assertEquals("PBL", result.setIdentifier)
        assertEquals("001/084", result.collectorNumber)
    }

    @Test fun recognizesCompactGermanLowerBorderCodeAndFoilOcrDigits() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Glurak ex\nOBF Z98/G97"))

        assertEquals("OBF", result.setIdentifier)
        assertEquals("298/697", result.collectorNumber)
    }

    @Test fun ignoresLeadingHRegulationMarkAndUsesOnlyThreeLetterSetCode() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Tropius\nH PBL DE 001/084"))

        assertEquals("PBL", result.setIdentifier)
        assertEquals("001/084", result.collectorNumber)
    }

    @Test fun ignoresLeadingJRegulationMarkAndUsesOnlyThreeLetterSetCode() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Morpeko ex\nJ PBL 117/084"))

        assertEquals("PBL", result.setIdentifier)
        assertEquals("117/084", result.collectorNumber)
    }

    @Test fun neverTreatsAStandaloneRegulationMarkAsASetCode() {
        val result = CardTextParser.parse(mapOf(CardScript.LATIN to "Tropius\nH DE 001/084"))

        assertEquals(null, result.setIdentifier)
        assertEquals("001/084", result.collectorNumber)
    }

    @Test fun acceptsSetBoundSingleCollectorNumberOnlyAsWeakEvidence() {
        val result = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Mew ex"),
            preferredSetText = "PAF DE 232",
            preferredNumberText = "PAF DE 232",
        )

        assertEquals("PAF", result.setIdentifier)
        assertEquals("232", result.collectorNumber)
        assertTrue(result.numberConfidence in .60f..(.70f))
    }

    @Test fun isolatedAttackNumberIsNeverRepairedIntoCollectorEvidence() {
        val result = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Glurak ex"),
            preferredSetText = "DE 330",
            preferredNumberText = "330",
        )

        assertEquals(null, result.collectorNumber)
        assertEquals(null, result.setIdentifier)
    }
}
