package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualScanIdentityTest {
    @Test fun formatsSelectedSetNumberWithOfficialTotal() {
        assertEquals("068/131", manualCollectorNumber("68", 131))
        assertEquals("0173/1025", manualCollectorNumber("173", 1025))
    }

    @Test fun rejectsMissingNumber() {
        assertEquals(null, manualCollectorNumber("", 131))
        assertEquals(null, manualCollectorNumber("12345", 131))
    }

    @Test fun explicitSelectionKeepsPrintedCodeAndStableSourceId() {
        val result = withManualScanIdentity(null, "sv08.5", "PRE", "068/131")

        assertEquals("sv08.5", result.manualSetSourceId)
        assertEquals("PRE", result.setIdentifier)
        assertEquals("068/131", result.collectorNumber)
        assertEquals(1f, result.numberConfidence)
        assertTrue(result.manualIdentityConfirmed)
        assertFalse(result.imageQuality.cardDetected)
    }

    @Test fun explicitSetAndNumberClearContradictoryOcrNameBeforeRanking() {
        val failedOcr = CardTextAnalysis(
            lines = listOf("Glurak ex"), collectorNumber = "068/131", setIdentifier = "PRE",
            likelyScript = CardScript.LATIN, searchQueries = listOf("Glurak ex"), hasNameRoiEvidence = true,
            roiEvidence = listOf(CardOcrEvidence(CardOcrRegion.NAME, listOf("Glurak ex"), "Glurak ex", 1f)),
        )
        val visualCard = visualCard().copy(name = "Pikachu")
        val candidate = scannerCatalogResult(visualCard, visualCard.printVariants.first(), null)
        assertTrue(CardRecognitionRanker.score(failedOcr, candidate) < ScannerDecisionPolicy.MIN_CANDIDATE_SCORE)

        val manual = withManualScanIdentity(failedOcr, "sv08.5", "PRE", "068/131")
        assertTrue(manual.searchQueries.isEmpty())
        assertFalse(manual.hasNameRoiEvidence)
        assertTrue(manual.roiEvidence.none { it.region == CardOcrRegion.NAME })
        assertTrue(CardRecognitionRanker.score(manual, candidate) >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE)
    }

    @Test fun promoLookupNeverInventsADenominatorFromTheChangingCatalogueTotal() {
        listOf(0, 173, 300, 1025).forEach { total ->
            assertEquals("173", manualCollectorNumber("173", total, "svp", "SVP"))
            val previouslyFormatted = manualCollectorNumber("173", total)!!
            val manual = withManualScanIdentity(null, "svp", "SVP", previouslyFormatted)
            assertFalse(manual.collectorNumber!!.contains('/'))
            assertEquals("173", manual.collectorNumber.trimStart('0'))
        }
        assertEquals("173", withManualScanIdentity(null, "svp", "SVP", "SVP173").collectorNumber)
    }

    @Test fun sharedPrintedCodeDoesNotTurnTheNormalDpExpansionIntoAPromoSet() {
        assertEquals("068/130", manualCollectorNumber("68", 130, "dp1", "DP"))
        assertEquals("068/131", manualCollectorNumber("68", 131, "sv08.5", "PRE"))
    }

    @Test fun explicitSetNeverFallsBackToAnotherSetWhenNoExactCardExists() = runBlocking {
        val manual = withManualScanIdentity(null, "sv08.5", "PRE", "068/131")
        var fallbackInvocations = 0
        val matches = lookupScannerVisualMatches(
            manual,
            findExact = { setId, number ->
                assertEquals("sv08.5", setId)
                assertEquals("068/131", number)
                emptyList()
            },
            findByPrintedTotal = { fallbackInvocations++; listOf(visualCard(setId = "other-set")) },
        )
        assertTrue(matches.isEmpty())
        assertEquals(0, fallbackInvocations)
    }

    @Test fun explicitLookupKeepsOnlyTheChosenSourceSetAndNumber() = runBlocking {
        val expected = visualCard()
        val manual = withManualScanIdentity(null, "sv08.5", "PRE", "068/131")
        val matches = lookupScannerVisualMatches(
            manual,
            findExact = { _, _ -> listOf(expected, visualCard(setId = "other-set"), visualCard(number = "069")) },
            findByPrintedTotal = { error("An explicit selection must never broaden the search") },
        )
        assertEquals(listOf(expected), matches)
        assertEquals("sv08.5-068", scannerCatalogResult(expected, expected.printVariants.first(), null).sourceCardId)
    }

    @Test fun manualPrintedDenominatorMismatchRemainsEmptyWithoutFallback() = runBlocking {
        val manual = withManualScanIdentity(null, "sv08.5", "PRE", "068/130")
        val matches = lookupScannerVisualMatches(
            manual, findExact = { _, _ -> listOf(visualCard()) },
            findByPrintedTotal = { error("Contradictory explicit selection cannot fall back") },
        )
        assertTrue(matches.isEmpty())
    }

    @Test fun automaticOcrKeepsItsExistingDenominatorFallback() = runBlocking {
        val automatic = CardTextAnalysis(emptyList(), "068/131", "WRONG", null, emptyList())
        val expected = visualCard()
        var fallbackInvocations = 0
        val matches = lookupScannerVisualMatches(
            automatic, findExact = { _, _ -> emptyList() },
            findByPrintedTotal = { fallbackInvocations++; listOf(expected) },
        )
        assertEquals(listOf(expected), matches)
        assertEquals(1, fallbackInvocations)
    }

    @Test fun manualPromoUsesSourceIdentityEvenWhenTheCatalogueHasNoPrintedTotal() = runBlocking {
        val expected = visualCard(setId = "svp", number = "173", printedTotal = 0)
        val manual = withManualScanIdentity(null, "svp", "SVP", "173/1025")
        val matches = lookupScannerVisualMatches(
            manual, findExact = { _, number -> assertEquals("173", number); listOf(expected) },
            findByPrintedTotal = { error("Promo lookup must stay inside its selected source set") },
        )
        assertEquals(listOf(expected), matches)
        val candidate = scannerCatalogResult(expected, expected.printVariants.first(), null)
        assertEquals("svp-173", candidate.sourceCardId)
        assertTrue(CardRecognitionRanker.score(manual, candidate) >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE)
    }

    @Test fun manualIdentityAlwaysRequiresCardSelectionEvenWithAPerfectScore() {
        val manual = withManualScanIdentity(null, "sv08.5", "PRE", "068/131")
        val accepted = ScannerDecisionPolicy.decide(listOf(100))
        val constrained = constrainManualWholeImageDecision(manual, accepted)
        assertEquals(ScannerDecision.AMBIGUOUS, constrained.decision)
        assertEquals("manual_set_number_requires_selection", constrained.reason)
        val rejected = ScannerDecisionPolicy.decide(emptyList())
        assertEquals(rejected, constrainManualWholeImageDecision(manual, rejected))
    }

    private fun visualCard(setId: String = "sv08.5", number: String = "068", printedTotal: Int = 131) = PokemonVisualCard(
        id = "$setId-$number", name = "Testkarte", number = number, rarity = null,
        smallImageUrl = "", largeImageUrl = "",
        set = PokemonSet(setId, "Gewähltes Testset", "Test", printedTotal, printedTotal, "", null, "PRE"),
    )
}
