package com.dominik.cardvault.data.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanKnowledgeFreshnessTest {
    @Test fun sourceAgeMustBeStrictlyLessThanSevenDays() {
        assertTrue(isUsableScanKnowledge(record(NOW), NOW, FINGERPRINT))
        assertTrue(isUsableScanKnowledge(record(NOW - SCAN_KNOWLEDGE_MAX_AGE_MILLIS + 1), NOW, FINGERPRINT))
        assertFalse(isUsableScanKnowledge(record(NOW - SCAN_KNOWLEDGE_MAX_AGE_MILLIS), NOW, FINGERPRINT))
        assertFalse(isUsableScanKnowledge(record(NOW - SCAN_KNOWLEDGE_MAX_AGE_MILLIS - 1), NOW, FINGERPRINT))
    }

    @Test fun unknownFutureAndInvalidClocksFailClosed() {
        listOf(0L, -1L, 1L, NOW + 1, Long.MAX_VALUE).forEach {
            assertFalse(isUsableScanKnowledge(record(it), NOW, FINGERPRINT))
        }
        assertFalse(isUsableScanKnowledge(record(NOW), -1L, FINGERPRINT))
    }

    @Test fun exactFingerprintAndKnowledgeVersionAreRequired() {
        val record = record(NOW)
        assertFalse(isUsableScanKnowledge(record, NOW, "b".repeat(64)))
        assertFalse(isUsableScanKnowledge(record.copy(fingerprint = "../cache"), NOW))
        listOf(-1, 0, 2, Int.MAX_VALUE).forEach {
            assertFalse(isUsableScanKnowledge(record.copy(knowledgeVersion = it), NOW))
        }
        assertFalse(isUsableScanKnowledge(record.copy(cardId = " "), NOW))
    }

    @Test fun disputedAndAbsentKnowledgeAreNotUsable() {
        assertFalse(isUsableScanKnowledge(null, NOW))
        assertFalse(isUsableScanKnowledge(record(NOW).copy(status = ScanKnowledgeStatus.DISPUTED), NOW))
        assertTrue(isUsableScanKnowledge(record(NOW).copy(status = ScanKnowledgeStatus.CANDIDATE), NOW))
    }

    private fun record(timestamp: Long) = ScanKnowledgeRecord(FINGERPRINT, "card-a", "sv1", "001",
        ScanKnowledgeStatus.STABLE, 90, 3, 0, 1, timestamp)

    companion object {
        private const val NOW = 1_800_000_000_000L
        private val FINGERPRINT = "a".repeat(64)
    }
}
