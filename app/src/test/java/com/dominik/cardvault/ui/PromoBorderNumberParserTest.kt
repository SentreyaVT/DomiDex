package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lower-border OCR variants of German promos; no whole-card number fallback. */
class PromoBorderNumberParserTest {
    @Test fun ordinaryRuleWordsAreNotSetBoundCollectorNumbers() {
        listOf("VON 3", "UND 3", "ALS 3", "Mega-Entwicklungs-ex-Regel\nVON 3").forEach { text ->
            assertNull(text, CardNumberParser.find(text))
        }
    }

    @Test fun megaPromoWithGermanLanguageMarkerKeepsItsCollectorPrefix() {
        assertPromo("J MEP DE 071", "MEP071", "mep")
    }

    @Test fun lowercaseMegaPromoWithGermanLanguageMarkerKeepsItsCollectorPrefix() {
        assertPromo("j mep de 071", "MEP071", "mep")
    }

    @Test fun realOcrWithAttachedRegulationMarkKeepsMegaPromoIdentity() {
        assertPromo("JMEP DE 071", "MEP071", "mep")
    }

    @Test fun realOcrWithMisreadRegulationAndLanguageMarksKeepsMegaPromoIdentity() {
        assertPromo("OMEP D 071", "MEP071", "mep")
    }

    @Test fun megaPromoNumberOnNextOcrLineRemainsBoundToItsPrefix() {
        assertPromo("J MEP DE\n071", "MEP071", "mep")
    }

    @Test fun megaPromoBorderSplitIntoIndividualOcrLinesRemainsIdentifiable() {
        assertPromo("J\nMEP\nDE\n071", "MEP071", "mep")
    }

    @Test fun compactScarletVioletPromoWithGermanLanguageMarkerIsRecognized() {
        assertPromo("SVPDE173", "SVP173", "svp")
    }

    @Test fun compactTwoLetterPromoPrefixesDoNotAbsorbTheGermanMarker() {
        mapOf("DP" to "dpp", "BW" to "bwp", "XY" to "xyp", "SM" to "smp").forEach { (prefix, setId) ->
            assertPromo("${prefix}DE01", "${prefix}01", setId)
        }
    }

    @Test fun existingCompactPromoNumbersRemainRecognized() {
        assertPromo("MEP071", "MEP071", "mep")
        assertPromo("SVP173", "SVP173", "svp")
    }

    @Test fun registeredPrefixLettersAreNotConsumedAsRepairedNumberDigits() {
        assertPromo("HGSS01", "HGSS01", "hgssp")
        assertPromo("HGSS DE 001", "HGSS01", "hgssp")
    }

    @Test fun invalidEarlierMatchDoesNotHideLaterRegisteredPromo() {
        assertPromo("QQ DE 999 MEP DE 071", "MEP071", "mep")
    }

    @Test fun unregisteredPrefixesWithGermanMarkersCannotInventPromoIdentity() {
        listOf("QQ DE 071", "QQQ DE 071", "XMEP DE 071", "XYZZ DE 071").forEach { border ->
            val evidence = CardNumberParser.find(border)
            // The existing weak generic set-number fallback is not changed by this fix.
            assertTrue("Unregistered prefix became a promo: $border -> $evidence", (evidence?.confidence ?: 0f) < .90f)
            assertTrue("Unregistered prefix invented a collector identity: $border -> $evidence", evidence == null || evidence.value == "071")
        }
    }

    @Test fun tooLongPromoNumberCannotBeTruncatedIntoValidCollectorIdentity() {
        mapOf(
            "MEP DE 0710" to "0710",
            "MEPDE0710" to "0710",
            "MEP DE 12345" to "12345",
        ).forEach { (border, fullDigits) ->
            val evidence = CardNumberParser.find(border)
            assertTrue("Too-long number became a strong promo: $border -> $evidence", (evidence?.confidence ?: 0f) < .90f)
            assertTrue("Too-long number was truncated: $border -> $evidence", evidence == null || evidence.value == fullDigits)
        }
    }

    @Test fun ordinaryPreCollectorPairKeepsItsNumberAndSet() {
        val border = "PRE DE 052/131"
        val evidence = CardNumberParser.find(border)
        assertEquals("052/131", evidence?.value)
        assertTrue((evidence?.confidence ?: 0f) >= .95f)

        val analysis = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Testkarte"),
            preferredNameText = "Testkarte",
            preferredSetText = border,
            preferredNumberText = border,
        )
        assertEquals("052/131", analysis.collectorNumber)
        assertEquals("PRE", analysis.setIdentifier)
        assertTrue(analysis.numberConfidence >= .95f)
    }

    @Test fun isolatedNumberOrLanguageMarkerCannotInventAPromoPrefix() {
        listOf("071", "DE 071", "J DE 071", "J\nDE\n071").forEach { border ->
            assertNull(border, CardNumberParser.find(border))
        }
    }

    private fun assertPromo(border: String, expectedNumber: String, expectedSet: String) {
        val evidence = CardNumberParser.find(border)
        assertEquals("Collector identity from lower border: $border", expectedNumber, evidence?.value)
        assertTrue("A recognized promo must not use weak bare-number fallback: $border", (evidence?.confidence ?: 0f) >= .90f)

        val analysis = CardTextParser.parse(
            mapOf(CardScript.LATIN to "Mega-Zygarde ex"),
            preferredNameText = "Mega-Zygarde ex",
            preferredSetText = border,
            preferredNumberText = border,
        )
        assertEquals(expectedNumber, analysis.collectorNumber)
        assertEquals(expectedSet, analysis.setIdentifier)
        assertTrue(analysis.numberConfidence >= .90f)
    }
}
