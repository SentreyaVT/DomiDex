package com.dominik.cardvault.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualSetLookupInstrumentedTest {
    @Test fun selectedSourceSetAndNumberFindTheExactCard() = runBlocking {
        val catalogue = PokemonVisualCatalog()
        val staleOcr = CardTextAnalysis(
            listOf("Absichtlich falscher OCR-Name"), "001/999", "WRONG", CardScript.LATIN,
            listOf("Absichtlich falscher OCR-Name"), hasNameRoiEvidence = true,
        )
        val manual = withManualScanIdentity(staleOcr, "sv08.5", "PRE", "068/131")
        val results = lookupScannerVisualMatches(manual, catalogue::findExact) {
            error("Explicit PRE selection must not search other sets")
        }

        assertTrue("Selected PRE set and number returned no card", results.isNotEmpty())
        assertTrue(results.all { it.set.id.equals("sv08.5", ignoreCase = true) })
        assertEquals("68", results.first().number.filter(Char::isDigit).trimStart('0'))
        val card = results.first()
        assertTrue(CardRecognitionRanker.score(manual, scannerCatalogResult(card, card.printVariants.first(), null)) >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE)
    }

    @Test fun selectedSvp173UsesTheLivePromoSetWithoutAHardcodedTotal() = runBlocking {
        val catalogue = PokemonVisualCatalog()
        val promoSet = catalogue.sets().first { PokemonSetRegistry.normalize(it.id) == "svp" }
        val number = manualCollectorNumber("173", promoSet.printedTotal, promoSet.id, "SVP")!!
        assertEquals("173", number)
        val manual = withManualScanIdentity(null, promoSet.id, "SVP", number)
        val results = lookupScannerVisualMatches(manual, catalogue::findExact) {
            error("Explicit SVP selection must not search other sets")
        }

        assertTrue("Selected SVP 173 returned no source-backed card", results.isNotEmpty())
        assertTrue(results.all { PokemonSetRegistry.normalize(it.set.id) == "svp" })
        assertTrue(results.all { it.id.equals("svp-173", true) })
        assertTrue(results.all { it.number.filter(Char::isDigit).trimStart('0') == "173" })
    }
}
