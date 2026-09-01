package com.dominik.cardvault.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanKnowledgeTest {
    @Test fun fingerprintIsDeterministicAndOrderIndependentForOcrTokens() {
        val first = ScanKnowledgeFingerprint.create("sv1", "001/198", "SV1", listOf("Pikachu", "001/198"), "visual", "v2")
        val second = ScanKnowledgeFingerprint.create("SV1", "001 / 198", "sv-1", listOf("001/198", "PIKACHU"), "VISUAL", "V2")
        assertEquals(first, second)
        assertNotEquals(first, ScanKnowledgeFingerprint.create("sv1", "002/198", "sv1", listOf("Pikachu"), "visual", "v2"))
    }

    @Test fun stableKnownPatternAndSameUploadedVoteAreDeduplicated() {
        val evidence = evidence("card-a")
        val stable = record("card-a", ScanKnowledgeStatus.STABLE)
        assertFalse(shouldUploadScanEvidence(evidence, stable, null))
        assertFalse(shouldUploadScanEvidence(evidence, null, "card-a"))
        assertTrue(shouldUploadScanEvidence(evidence.copy(corrected = true), stable, "card-a"))
        assertTrue(shouldUploadScanEvidence(evidence.copy(confirmedCardId = "card-b"), stable, "card-a"))
    }

    private fun evidence(cardId: String) = ScanKnowledgeEvidence(
        fingerprint = "a".repeat(64), confirmedCardId = cardId, sourceSetId = "sv1", cardNumber = "001/198",
        setCode = "SV1", ocrTokens = listOf("pikachu"), visualFingerprint = "visual", candidateIds = listOf("card-a", "card-b"),
        originalConfidence = 82, corrected = false, scannerVersion = "v2", observedAtClient = 123L,
    )

    private fun record(cardId: String, status: ScanKnowledgeStatus) = ScanKnowledgeRecord(
        fingerprint = "a".repeat(64), cardId = cardId, sourceSetId = "sv1", cardNumber = "001/198", status = status,
        confidence = 90, confirmations = 3, corrections = 0, knowledgeVersion = 1, updatedAtEpochMillis = 123L,
    )
}
