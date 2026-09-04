package com.dominik.cardvault.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPipelineStateTest {
    @Test fun `pipeline follows capture to match in order`() {
        assertTrue(ScanPipelineStage.CAPTURED.canTransitionTo(ScanPipelineStage.PREPROCESSING))
        assertTrue(ScanPipelineStage.PREPROCESSING.canTransitionTo(ScanPipelineStage.OCR))
        assertTrue(ScanPipelineStage.OCR.canTransitionTo(ScanPipelineStage.SET_ANALYSIS))
        assertTrue(ScanPipelineStage.CANDIDATE_SEARCH.canTransitionTo(ScanPipelineStage.ARTWORK_VERIFICATION))
        assertTrue(ScanPipelineStage.PRICE_LOOKUP.canTransitionTo(ScanPipelineStage.MATCHED))
    }

    @Test fun `terminal scans cannot be overwritten by late callbacks`() {
        assertFalse(ScanPipelineStage.MATCHED.canTransitionTo(ScanPipelineStage.OCR))
        assertFalse(ScanPipelineStage.FAILED.canTransitionTo(ScanPipelineStage.MATCHED))
        assertFalse(ScanPipelineStage.SAVED.canTransitionTo(ScanPipelineStage.CANDIDATE_SEARCH))
    }

    @Test fun `automatic intake pauses before slots reserved for manual captures`() {
        val scans = (1..ScanQueuePolicy.MAX_AUTOMATIC_BACKLOG).map { index -> scan("auto-$index", index.toLong()) }

        assertFalse(ScanQueuePolicy.allowsAutomaticCapture(scans, capturePending = false))
        assertTrue(ScanQueuePolicy.allowsAutomaticCapture(scans.dropLast(1), capturePending = false))
        assertFalse(ScanQueuePolicy.allowsAutomaticCapture(emptyList(), capturePending = true))
    }

    @Test fun `manual intake evicts oldest terminal then pending automatic item but not active work`() {
        val active = scan("active", 1, ScanPipelineStage.OCR, ScanCaptureOrigin.AUTOMATIC)
        val oldTerminal = scan("old-terminal", 2, ScanPipelineStage.MANUAL_REVIEW, ScanCaptureOrigin.MANUAL)
        val newerTerminal = scan("new-terminal", 3, ScanPipelineStage.MATCHED, ScanCaptureOrigin.AUTOMATIC)
        assertEquals(oldTerminal, ScanQueuePolicy.evictionCandidateForManualCapture(listOf(active, newerTerminal, oldTerminal)))

        val pendingManual = scan("pending-manual", 4, ScanPipelineStage.CAPTURED, ScanCaptureOrigin.MANUAL)
        val pendingAuto = scan("pending-auto", 5, ScanPipelineStage.CAPTURED, ScanCaptureOrigin.AUTOMATIC)
        assertEquals(pendingAuto, ScanQueuePolicy.evictionCandidateForManualCapture(listOf(active, pendingManual, pendingAuto)))
        assertEquals(null, ScanQueuePolicy.evictionCandidateForManualCapture(listOf(active)))
    }

    @Test fun `whole image fallback can expose candidates but never becomes automatically accepted`() {
        val fallback = CardTextAnalysis(
            lines = listOf("Pikachu", "PBL 122/088"),
            collectorNumber = "122/088",
            setIdentifier = "PBL",
            likelyScript = CardScript.LATIN,
            searchQueries = listOf("Pikachu"),
            imageQuality = CardImageQuality(
                cardDetected = false,
                reason = MANUAL_WHOLE_IMAGE_FALLBACK_REASON,
            ),
        )
        val accepted = ScannerDecisionResult(
            decision = ScannerDecision.ACCEPTED,
            bestScore = 95,
            secondScore = 70,
            reason = "strong_identity_and_candidate_margin",
        )

        val constrained = constrainManualWholeImageDecision(fallback, accepted)

        assertEquals(ScannerDecision.AMBIGUOUS, constrained.decision)
        assertEquals("manual_whole_image_requires_review", constrained.reason)
        assertEquals(accepted.bestScore, constrained.bestScore)
        assertEquals(accepted.secondScore, constrained.secondScore)
        assertEquals(
            accepted,
            constrainManualWholeImageDecision(
                fallback.copy(imageQuality = CardImageQuality(cardDetected = true)),
                accepted,
            ),
        )
    }

    @Test fun `soft normalized manual card can be reviewed but never auto accepted`() {
        val analysis = CardTextAnalysis(
            lines = emptyList(), collectorNumber = "047/088", setIdentifier = "POR",
            likelyScript = CardScript.LATIN, searchQueries = listOf("Mega-Zygarde ex"),
            imageQuality = CardImageQuality(cardDetected = true, reason = MANUAL_SOFT_CARD_FALLBACK_REASON),
        )
        val strong = ScannerDecisionResult(ScannerDecision.ACCEPTED, 99, 10, "strong")
        assertTrue(analysis.imageQuality.isManualRecognitionFallback)
        assertEquals(ScannerDecision.AMBIGUOUS, constrainManualWholeImageDecision(analysis, strong).decision)
        val rejected = ScannerDecisionResult(ScannerDecision.REJECTED, 20, 10, "contradiction")
        assertEquals(rejected, constrainManualWholeImageDecision(analysis, rejected))
        assertFalse(CardImageQuality(cardDetected = true, reason = "image_too_soft").isManualRecognitionFallback)
        assertFalse(CardImageQuality(cardDetected = false, reason = MANUAL_SOFT_CARD_FALLBACK_REASON).isManualRecognitionFallback)
    }

    private fun scan(
        id: String,
        capturedAt: Long,
        stage: ScanPipelineStage = ScanPipelineStage.CAPTURED,
        origin: ScanCaptureOrigin = ScanCaptureOrigin.AUTOMATIC,
    ) = QueuedCardScan(
        id = id,
        file = File("$id.jpg"),
        capturedAtMillis = capturedAt,
        qualityEligible = false,
        origin = origin,
        stage = stage,
    )
}
