package com.dominik.cardvault.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pure deterministic observations, no camera/OCR/Room/network/device data or fixture installation.
 * Audit 2026-10-01: artifacts/scanner-v27/optimized.json and the Gate0 baseline-scanner-ocr.json
 * have 60 common input hashes and unchanged legacy result fields. That is historical harness
 * evidence, NOT an official signed-release-26 comparison or new physical-printing ground truth.
 */
@RunWith(AndroidJUnit4::class)
class ScannerFixtureIdentityEvaluationInstrumentedTest {
    private val reference = ScannerFixtureIdentityReference("swsh12-047", "swsh12", "047/195", "de",
        setOf("Normal" to "Standard", "Reverse Holo" to "Reverse Holo"))
    private val candidate = ScannerFixtureObservedCandidate("swsh12-047", "swsh12", "047", "Normal", "Standard", "GERMAN")
    private fun evaluate(
        candidates: List<ScannerFixtureObservedCandidate> = listOf(candidate),
        decision: ScannerDecision = ScannerDecision.ACCEPTED,
        number: String? = "047/195", case: String = "clean",
        truth: ScannerFixtureIdentityReference = reference,
    ) = ScannerFixtureIdentityEvaluator.evaluate(truth, candidates, decision, number, null, "LATIN", case)

    @Test fun exactCatalogueIdentityDoesNotPretendPhysicalOrPrintedCodeGroundTruth() {
        val result = evaluate()
        assertEquals("CORRECT_MEASURED_IDENTITY", result.acceptedCatalogueIdentity)
        assertEquals(ScannerFixtureIdentityStatus.MATCH, result.dimensions.getValue("catalogueLanguage").status)
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, result.dimensions.getValue("physicalPrinting").status)
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, result.dimensions.getValue("printedSetCode").status)
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, result.dimensions.getValue("decisionSafety").status)
        assertEquals(true, result.top3CandidateCardIdRecall)
    }

    @Test fun wrongIdSetCollectorLanguageAndPrintingAreIndependentlyRejected() {
        val cases = listOf(
            "cardId" to candidate.copy(cardId = "wrong-047"),
            "sourceSetId" to candidate.copy(sourceSetId = "swsh11"),
            "candidateCollectorLocalId" to candidate.copy(collectorNumber = "048"),
            "catalogueLanguage" to candidate.copy(language = "ENGLISH"),
            "cataloguePrintingCompatibility" to candidate.copy(finish = "Holo"),
        )
        cases.forEach { (dimension, observed) ->
            val result = evaluate(listOf(observed))
            assertEquals(dimension, ScannerFixtureIdentityStatus.MISMATCH, result.dimensions.getValue(dimension).status)
            assertEquals("INCORRECT_MEASURED_IDENTITY", result.acceptedCatalogueIdentity)
        }
    }

    @Test fun missingCandidateCannotCountAsSuccessfulIdentity() {
        val result = evaluate(emptyList())
        assertEquals("INCOMPLETE_MEASURED_IDENTITY", result.acceptedCatalogueIdentity)
        assertEquals(ScannerFixtureIdentityStatus.NO_OUTPUT, result.dimensions.getValue("cardId").status)
        assertEquals(false, result.top3CandidateCardIdRecall)
    }

    @Test fun missingLanguageIsNoOutputAndUnknownAvailabilityIsNotTested() {
        val result = evaluate(listOf(candidate.copy(language = null)), truth = reference.copy(availablePrintings = emptySet()))
        assertEquals(ScannerFixtureIdentityStatus.NO_OUTPUT, result.dimensions.getValue("catalogueLanguage").status)
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, result.dimensions.getValue("cataloguePrintingCompatibility").status)
        assertEquals("INCOMPLETE_MEASURED_IDENTITY", result.acceptedCatalogueIdentity)
    }

    @Test fun bothAvailableFinishesRemainCompatibleButNeverPhysicallyVerified() {
        listOf(candidate, candidate.copy(finish = "Reverse Holo", variant = "Reverse Holo")).forEach { observed ->
            val result = evaluate(listOf(observed))
            assertEquals(ScannerFixtureIdentityStatus.MATCH, result.dimensions.getValue("cataloguePrintingCompatibility").status)
            assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, result.dimensions.getValue("physicalPrinting").status)
        }
        val single = evaluate(truth = reference.copy(availablePrintings = setOf("Normal" to "Standard")))
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, single.dimensions.getValue("physicalPrinting").status)
    }

    @Test fun collectorLocalMatchDoesNotHideWrongOrMissingDenominator() {
        listOf("047/999", "047", "047/invalid").forEach { number ->
            val result = evaluate(number = number)
            assertEquals(ScannerFixtureIdentityStatus.MATCH, result.dimensions.getValue("ocrCollectorLocalId").status)
            assertEquals(ScannerFixtureIdentityStatus.MISMATCH, result.dimensions.getValue("ocrFullCollectorNumber").status)
        }
        assertEquals(ScannerFixtureIdentityStatus.NO_OUTPUT, evaluate(number = null).dimensions.getValue("ocrFullCollectorNumber").status)
    }

    @Test fun verifiedPromoNamespaceKeepsPrefixAndNoInventedDenominator() {
        val promo = ScannerFixtureIdentityReference("swshp-SWSH020", "swshp", "SWSH020", "en", setOf("Holo" to "Standard"))
        val observed = ScannerFixtureObservedCandidate(promo.cardId, promo.sourceSetId, "SWSH020", "Holo", "Standard", "ENGLISH")
        assertEquals(ScannerFixtureIdentityStatus.MATCH, evaluate(listOf(observed), number = "SWSH020", truth = promo).dimensions.getValue("ocrFullCollectorNumber").status)
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, evaluate(listOf(observed), number = "20", truth = promo).dimensions.getValue("ocrFullCollectorNumber").status)
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, evaluate(listOf(observed), number = "SWSH020/307", truth = promo).dimensions.getValue("ocrFullCollectorNumber").status)
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, evaluate(listOf(observed.copy(collectorNumber = "SM020")), number = "SWSH020", truth = promo).dimensions.getValue("candidateCollectorLocalId").status)
    }

    @Test fun ambiguousAndRejectedMatchesAreNotAcceptedSuccesses() {
        listOf(ScannerDecision.AMBIGUOUS, ScannerDecision.REJECTED).forEach { decision ->
            val result = evaluate(decision = decision)
            assertEquals("NOT_ACCEPTED", result.acceptedCatalogueIdentity)
            assertEquals(true, result.top3CandidateCardIdRecall)
        }
        val review = evaluate(listOf(candidate.copy(cardId = "wrong"), candidate), ScannerDecision.AMBIGUOUS)
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, review.dimensions.getValue("cardId").status)
        assertEquals(true, review.top3CandidateCardIdRecall)
        assertEquals("NOT_ACCEPTED", review.acceptedCatalogueIdentity)
    }

    @Test fun blankDoesNotInheritTheSourceFixturesGroundTruth() {
        val accepted = evaluate(case = "blank")
        assertFalse(accepted.targetCardPresent)
        assertNull(accepted.top3CandidateCardIdRecall)
        assertEquals("INCORRECT_MEASURED_IDENTITY", accepted.acceptedCatalogueIdentity)
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, accepted.dimensions.getValue("cardId").status)
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, accepted.dimensions.getValue("decisionSafety").status)
        val rejected = evaluate(emptyList(), ScannerDecision.REJECTED, number = null, case = "blank")
        assertEquals(ScannerFixtureIdentityStatus.MATCH, rejected.dimensions.getValue("decisionSafety").status)
    }

    @Test fun manualWholeRequiresReviewWithoutInventingAStateForOtherTransforms() {
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, evaluate(case = "manual_whole").dimensions.getValue("decisionSafety").status)
        assertEquals(ScannerFixtureIdentityStatus.MATCH, evaluate(decision = ScannerDecision.AMBIGUOUS, case = "manual_whole").dimensions.getValue("decisionSafety").status)
        assertEquals(ScannerFixtureIdentityStatus.NOT_TESTED, evaluate(case = "dim").dimensions.getValue("decisionSafety").status)
    }

    @Test fun scriptIsRecordedButCannotMasqueradeAsDetectedGermanLanguage() {
        val result = evaluate(listOf(candidate.copy(language = "SOURCE_FALLBACK")))
        assertEquals("LATIN", result.observedScript)
        assertEquals(ScannerFixtureIdentityStatus.MISMATCH, result.dimensions.getValue("catalogueLanguage").status)
        assertEquals(ScannerFixtureIdentityStatus.MATCH, evaluate(listOf(candidate.copy(language = "de"))).dimensions.getValue("catalogueLanguage").status)
    }

    @Test fun serializationPreservesNullsActualFieldsAndNoSyntheticMarketIdentity() {
        val result = evaluate(listOf(candidate.copy(language = null)))
        val json = JSONObject(result.toJson().toString())
        assertEquals(1, json.getInt("version"))
        assertTrue(json.getJSONArray("observedCandidates").getJSONObject(0).isNull("language"))
        assertFalse(json.toString().contains("productId"))
        assertEquals("NOT_TESTED", json.getJSONObject("dimensions").getJSONObject("physicalPrinting").getString("status"))
    }
}
