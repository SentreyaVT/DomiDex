package com.dominik.cardvault.data.knowledge

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            assertTrue(first.enqueue(evidence))
            assertEquals(evidence, ScanKnowledgeStore(root, true).pending().single())
            first.markUploaded(evidence)
            assertTrue(ScanKnowledgeStore(root, true).pending().isEmpty())
            assertEquals("card-a", ScanKnowledgeStore(root, true).lastUploadedCardId(evidence.fingerprint))
        } finally {
            root.deleteRecursively()
        }
    }
}
