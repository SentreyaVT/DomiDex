package com.dominik.cardvault.data.knowledge

import org.junit.Assert.*
import org.junit.Test

class ScanKnowledgeConsentTest {
    @Test fun defaultAndOldConsentNeverAllowUpload() {
        assertFalse(scanKnowledgeConsentAllowsUpload(false, SCAN_KNOWLEDGE_CONSENT_VERSION, 123))
        assertFalse(scanKnowledgeConsentAllowsUpload(true, 0, 123))
        assertFalse(scanKnowledgeConsentAllowsUpload(true, SCAN_KNOWLEDGE_CONSENT_VERSION, 0))
        assertTrue(scanKnowledgeConsentAllowsUpload(true, SCAN_KNOWLEDGE_CONSENT_VERSION, 123))
    }
    @Test fun accountNamespacesAreStableDistinctAndPathSafe() {
        assertEquals(scanKnowledgeOwnerKey("owner-a"), scanKnowledgeOwnerKey("owner-a"))
        assertNotEquals(scanKnowledgeOwnerKey("owner-a"), scanKnowledgeOwnerKey("owner-b"))
        assertTrue(scanKnowledgeOwnerKey("../../owner-a").matches(Regex("[a-f0-9]{64}")))
    }
    @Test(expected = IllegalArgumentException::class) fun blankOwnerRejected() { scanKnowledgeOwnerKey(" ") }
}
