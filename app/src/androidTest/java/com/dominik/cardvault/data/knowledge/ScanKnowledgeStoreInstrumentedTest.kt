package com.dominik.cardvault.data.knowledge

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ScanKnowledgeStoreInstrumentedTest {
    // SDK exception constructors use Android TextUtils/SparseArray; exercise the real classes here,
    // not mocked JVM android.jar stubs, so access-error classification remains integration-tested.
    @Test fun explicitFirestoreAccessFailuresNeverUseOfflineFallback() {
        listOf(com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
            com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED).forEach {
            assertTrue(isScanKnowledgeAccessFailure(com.google.firebase.firestore.FirebaseFirestoreException("synthetic", it)))
        }
    }

    @Test fun authAndSecurityFailuresNeverUseOfflineFallback() {
        assertTrue(isScanKnowledgeAccessFailure(com.google.firebase.auth.FirebaseAuthException("ERROR_USER_DISABLED", "synthetic")))
        assertTrue(isScanKnowledgeAccessFailure(SecurityException("synthetic")))
    }

    @Test fun transportErrorsMayUseAgeValidHintsWithoutGrantingBackendAccess() {
        listOf(com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE,
            com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED).forEach {
            assertFalse(isScanKnowledgeAccessFailure(com.google.firebase.firestore.FirebaseFirestoreException("synthetic", it)))
        }
        assertFalse(isScanKnowledgeAccessFailure(java.io.IOException("synthetic")))
    }

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

    @Test fun removingCachedHintPreservesOutboxReceiptOtherHintAndConsentAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-cache-removal-${System.nanoTime()}")
        val prefsName = "scan-cache-consent-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
        try {
            val store = ScanKnowledgeStore(root, true) { NOW }
            val consent = ScanKnowledgeConsent(prefs, store)
            consent.setEnabled("owner-a", true)
            store.enqueue("owner-a", evidence())
            store.markUploaded("owner-a", evidence())
            val correction = evidence().copy(confirmedCardId = "card-b", corrected = true)
            store.enqueue("owner-a", correction)
            store.enqueue("owner-b", evidence())
            store.put(record())
            store.put(record().copy(fingerprint = "b".repeat(64)))
            val beforeConsent = prefs.all.toMap()
            val cacheFile = File(root, "cache/${"a".repeat(64)}.json")
            File("${cacheFile.path}.bak").writeText(cacheFile.readText())
            File("${cacheFile.path}.new").writeText(cacheFile.readText())

            store.removeCached(evidence().fingerprint)

            val restarted = ScanKnowledgeStore(root, true) { NOW }
            assertNull(restarted.get(evidence().fingerprint))
            assertFalse(cacheFile.exists())
            assertFalse(File("${cacheFile.path}.bak").exists())
            assertFalse(File("${cacheFile.path}.new").exists())
            assertEquals(record().copy(fingerprint = "b".repeat(64)), restarted.get("b".repeat(64)))
            assertEquals(listOf(correction), restarted.pending("owner-a"))
            assertEquals(listOf(evidence()), restarted.pending("owner-b"))
            assertEquals("card-a", restarted.lastUploadedCardId("owner-a", evidence().fingerprint))
            assertEquals(beforeConsent, prefs.all)
            assertTrue(ScanKnowledgeConsent(prefs, restarted).isEnabled("owner-a"))
        } finally { root.deleteRecursively(); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun cacheExpiresAtBoundaryWithoutRenewalOnReadOrRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-cache-expiry-${System.nanoTime()}")
        var now = NOW
        try {
            val store = ScanKnowledgeStore(root, true) { now }
            store.put(record())
            now += SCAN_KNOWLEDGE_MAX_AGE_MILLIS - 1
            assertEquals(record(), store.get(evidence().fingerprint))
            now++
            assertNull(ScanKnowledgeStore(root, true) { now }.get(evidence().fingerprint))
            assertFalse(File(root, "cache/${evidence().fingerprint}.json").exists())
            now = NOW // Rewinding the clock must not restore a removed hint.
            assertNull(store.get(evidence().fingerprint))
        } finally { root.deleteRecursively() }
    }

    @Test fun cacheRejectsMalformedStatusVersionsTimestampAndFingerprintInsteadOfRepairingThem() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-cache-invalid-${System.nanoTime()}")
        try {
            val store = ScanKnowledgeStore(root, true) { NOW }
            val target = File(root, "cache/${evidence().fingerprint}.json")
            val mutations: List<(org.json.JSONObject) -> Unit> = listOf(
                { it.put("status", "UNKNOWN") }, { it.put("knowledgeVersion", 0) },
                { it.put("knowledgeVersion", 1.5) }, { it.put("knowledgeVersion", "1") },
                { it.remove("knowledgeVersion") }, { it.put("updatedAtEpochMillis", 0) },
                { it.put("updatedAtEpochMillis", NOW + 1) }, { it.put("fingerprint", "b".repeat(64)) },
            )
            mutations.forEach { mutate ->
                store.put(record())
                val json = org.json.JSONObject(target.readText())
                mutate(json); target.writeText(json.toString())
                assertNull(store.get(evidence().fingerprint))
                assertFalse(target.exists())
            }
            store.put(record())
            target.writeText("broken JSON")
            assertNull(store.get(evidence().fingerprint))
            assertFalse(target.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidCacheRemovalCannotEscapeItsExactFingerprintPath() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "scan-cache-path-${System.nanoTime()}")
        try {
            val store = ScanKnowledgeStore(root, true) { NOW }
            val sentinel = File(root, "sentinel.json").also { it.writeText("keep") }
            store.put(record())
            listOf("../sentinel", "", "A".repeat(64), "a".repeat(65)).forEach(store::removeCached)
            assertEquals("keep", sentinel.readText())
            assertEquals(record(), store.get(evidence().fingerprint))
        } finally { root.deleteRecursively() }
    }

    private fun record() = ScanKnowledgeRecord("a".repeat(64), "card-a", "sv1", "001",
        ScanKnowledgeStatus.STABLE, 90, 3, 0, 1, NOW)

    private companion object { const val NOW = 1_800_000_000_000L }

    private fun evidence() = ScanKnowledgeEvidence(
        fingerprint = "a".repeat(64), confirmedCardId = "card-a", sourceSetId = "sv1", cardNumber = "001/198",
        setCode = "SV1", ocrTokens = listOf("pikachu"), visualFingerprint = "visual", candidateIds = listOf("card-a"),
        originalConfidence = 82, corrected = false, scannerVersion = "v2", observedAtClient = 123L,
    )
}
