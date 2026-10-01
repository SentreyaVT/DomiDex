package com.dominik.cardvault.ui

import com.dominik.cardvault.data.feedback.ScannerReportContract
import com.squareup.moshi.Moshi
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ScannerReportKnowledgeContextTest {
    private val original = CardTextAnalysis(emptyList(), "002", "SVI", CardScript.LATIN, listOf("original token"),
        visualDescriptors = listOf(ArtworkDescriptor(3, listOf(ArtworkRegionDescriptor("artwork", floatArrayOf(),
            floatArrayOf(), floatArrayOf(), 0x123L, -1L)), 0f)))

    @Test fun originalFingerprintSurvivesMemoryTrimmingAndManualIdentityChanges() {
        val context = requireNotNull(scannerReportKnowledgeContext(original))
        val edited = original.copy(visualDescriptors = emptyList(), collectorNumber = "999", manualSetSourceId = "other")
        assertNotEquals(scanKnowledgeFingerprint(edited), context.fingerprint)
        val draft = QueuedCardScan(file = File("private.jpg"), qualityEligible = false, origin = ScanCaptureOrigin.MANUAL,
            analysis = edited, knowledgeContext = context).scannerReportDraft()
        assertEquals(context, draft.knowledgeContext)
        val payload = Moshi.Builder().build().adapter(Any::class.java).fromJson(ScannerReportContract.payload(draft)) as Map<*, *>
        val metadata = payload["knowledgeContext"] as Map<*, *>
        assertEquals(setOf("schemaVersion", "fingerprint", "scannerVersion", "knowledgeVersion"), metadata.keys)
        assertEquals(context.fingerprint, metadata["fingerprint"])
        assertEquals("scanner-v2.1", metadata["scannerVersion"])
        assertEquals(1.0, metadata["schemaVersion"])
        assertEquals(1.0, metadata["knowledgeVersion"])
    }

    @Test fun absentOriginalContextAndUnknownNumberAreNeverReconstructed() {
        assertNull(scannerReportKnowledgeContext(original.copy(collectorNumber = null)))
        assertNull(scannerReportKnowledgeContext(original.copy(collectorNumber = " ")))
        val draft = QueuedCardScan(file = File("private.jpg"), qualityEligible = false, origin = ScanCaptureOrigin.MANUAL,
            analysis = original).scannerReportDraft()
        assertNull(draft.knowledgeContext)
        assertFalse(ScannerReportContract.payload(draft).contains("knowledgeContext"))
    }

    @Test fun malformedOptionalContextCannotBecomeLearningEvidence() {
        val context = requireNotNull(scannerReportKnowledgeContext(original))
        val scan = QueuedCardScan(file = File("private.jpg"), qualityEligible = false, origin = ScanCaptureOrigin.MANUAL)
        for (invalid in listOf(context.copy(fingerprint = "reconstructed"), context.copy(fingerprint = "A".repeat(64)),
            context.copy(scannerVersion = " "), context.copy(schemaVersion = 2), context.copy(knowledgeVersion = 2))) {
            assertFalse(ScannerReportContract.payload(scan.copy(knowledgeContext = invalid).scannerReportDraft()).contains("knowledgeContext"))
        }
    }
}
