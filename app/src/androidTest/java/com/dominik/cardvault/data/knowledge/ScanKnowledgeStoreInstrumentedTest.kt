package com.dominik.cardvault.data.knowledge

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ScanKnowledgeStoreInstrumentedTest {
    @Test fun localOutboxSurvivesRestartAndIsRemovedOnlyAfterUpload() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-knowledge-test-${System.nanoTime()}")
        try {
            val first = ScanKnowledgeStore(root, true)
            val evidence = ScanKnowledgeEvidence(
                fingerprint = "a".repeat(64), confirmedCardId = "card-a", sourceSetId = "sv1", cardNumber = "001/198",
                setCode = "SV1", ocrTokens = listOf("pikachu"), visualFingerprint = "visual", candidateIds = listOf("card-a", "card-b"),
                originalConfidence = 82, corrected = false, scannerVersion = "v2", observedAtClient = 123L,
            )
            assertTrue(first.enqueue("owner-a", evidence))
            assertEquals(evidence, ScanKnowledgeStore(root, true).pending("owner-a").single())
            assertTrue(first.pending("owner-b").isEmpty())
            first.markUploaded("owner-a", evidence)
            assertTrue(ScanKnowledgeStore(root, true).pending("owner-a").isEmpty())
            assertEquals("card-a", ScanKnowledgeStore(root, true).lastUploadedCardId("owner-a", evidence.fingerprint))
            assertNull(first.lastUploadedCardId("owner-b", evidence.fingerprint))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun consentIsOffByDefaultPerOwnerAndOptOutClearsOnlyOwnersPending() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-consent-test-${System.nanoTime()}")
        val prefsName = "scan-consent-test-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
        try {
            val store = ScanKnowledgeStore(root, true)
            val consent = ScanKnowledgeConsent(prefs, store)
            assertFalse(consent.isEnabled("owner-a"))
            assertNull(consent.ifEnabled("owner-a") { error("Must not enqueue without consent") })
            consent.setEnabled("owner-a", true)
            assertTrue(ScanKnowledgeConsent(prefs, store).isEnabled("owner-a"))
            assertFalse(consent.isEnabled("owner-b"))
            store.enqueue("owner-a", evidence())
            store.enqueue("owner-b", evidence())
            consent.setEnabled("owner-a", false)
            assertFalse(consent.isEnabled("owner-a"))
            assertTrue(store.pending("owner-a").isEmpty())
            assertEquals(1, store.pending("owner-b").size)
        } finally { root.deleteRecursively(); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun acknowledgementCannotRemoveLaterCorrection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-ack-test-${System.nanoTime()}")
        try {
            val store = ScanKnowledgeStore(root, true)
            val original = evidence()
            val corrected = original.copy(confirmedCardId = "card-b", corrected = true)
            store.enqueue("owner-a", original)
            store.enqueue("owner-a", corrected)
            store.markUploaded("owner-a", original)
            assertEquals(corrected, store.pending("owner-a").single())
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyUnownedEvidenceIsNeverAdopted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-legacy-test-${System.nanoTime()}")
        try {
            val legacy = File(root, "outbox/${"a".repeat(64)}.json")
            legacy.parentFile!!.mkdirs(); legacy.writeText("{}")
            val store = ScanKnowledgeStore(root, true)
            assertFalse(legacy.exists())
            assertTrue(store.pending("owner-a").isEmpty())
        } finally { root.deleteRecursively() }
    }

    private fun evidence() = ScanKnowledgeEvidence(
        fingerprint = "a".repeat(64), confirmedCardId = "card-a", sourceSetId = "sv1", cardNumber = "001/198",
        setCode = "SV1", ocrTokens = listOf("pikachu"), visualFingerprint = "visual", candidateIds = listOf("card-a"),
        originalConfidence = 82, corrected = false, scannerVersion = "v2", observedAtClient = 123L,
    )
}
