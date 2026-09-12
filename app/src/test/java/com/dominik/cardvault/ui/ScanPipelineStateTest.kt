package com.dominik.cardvault.ui

import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPipelineStateTest {
    @Test fun `worker failure boundary converts any non cancellation failure to terminal failure`() = runBlocking {
        val failure = AssertionError("artwork fusion failed")
        var terminalFailure: Throwable? = null

        runScanWorkerAnalysis(onFailure = { terminalFailure = it }) {
            throw failure
        }

        assertEquals(failure, terminalFailure)
    }

    @Test fun `worker failure boundary preserves cancellation`() = runBlocking {
        val cancellation = CancellationException("scanner closed")
        var failureCalls = 0

        try {
            runScanWorkerAnalysis(onFailure = { failureCalls += 1 }) {
                throw cancellation
            }
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }

        assertEquals(0, failureCalls)
    }

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

    @Test fun `automatic intake counts active work instead of completed session history`() {
        val completed = (1..2).map { index -> scan("done-$index", index.toLong(), ScanPipelineStage.MATCHED) }
        val active = (1 until ScanQueuePolicy.MAX_AUTOMATIC_IN_FLIGHT).map { index ->
            scan("active-$index", (10 + index).toLong(), ScanPipelineStage.OCR)
        }

        assertTrue(ScanQueuePolicy.allowsAutomaticCapture(completed + active, capturePending = false))
        assertFalse(
            ScanQueuePolicy.allowsAutomaticCapture(
                completed + active + scan("active-last", 20, ScanPipelineStage.CAPTURED),
                capturePending = false,
            ),
        )
        assertFalse(ScanQueuePolicy.allowsAutomaticCapture(emptyList(), capturePending = true))
    }

    @Test fun `automatic intake evicts only resolved saved item and never pending review`() {
        val pendingReview = scan("pending-review", 1, ScanPipelineStage.MATCHED)
        val saved = scan("saved", 2, ScanPipelineStage.SAVED)
        val active = scan("active", 3, ScanPipelineStage.OCR)

        assertEquals(saved, ScanQueuePolicy.automaticEvictionCandidate(listOf(active, saved, pendingReview)))
        assertEquals(null, ScanQueuePolicy.automaticEvictionCandidate(listOf(active, pendingReview)))
    }

    @Test fun `six unresolved terminal results apply backpressure without evicting review files`() {
        val queue = listOf(
            scan("matched-1", 1, ScanPipelineStage.MATCHED),
            scan("manual", 2, ScanPipelineStage.MANUAL_REVIEW),
            scan("failed", 3, ScanPipelineStage.FAILED).copy(evidenceFiles = listOf(File("failed-evidence.jpg"))),
            scan("ambiguous", 4, ScanPipelineStage.AMBIGUOUS).copy(evidenceFiles = listOf(File("ambiguous-evidence.jpg"))),
            scan("matched-2", 5, ScanPipelineStage.MATCHED),
            scan("matched-3", 6, ScanPipelineStage.MATCHED),
        )
        val retainedFiles = queue.map { it.file to it.evidenceFiles }

        assertEquals(ScanQueuePolicy.MAX_TOTAL_BACKLOG, queue.size)
        assertFalse(ScanQueuePolicy.allowsAutomaticCapture(queue, capturePending = false))
        assertEquals(null, ScanQueuePolicy.automaticEvictionCandidate(queue))
        assertEquals(retainedFiles, queue.map { it.file to it.evidenceFiles })
    }

    @Test fun `more than ten thousand accepted results keep only six unresolved queue entries`() {
        var queue = emptyList<QueuedCardScan>()
        var resolved = 0
        repeat(10_050) { index ->
            if (queue.size == ScanQueuePolicy.MAX_TOTAL_BACKLOG) {
                val handled = queue.minBy(QueuedCardScan::capturedAtMillis)
                queue = queue.map { scan ->
                    if (scan.id == handled.id) scan.copy(stage = ScanPipelineStage.SAVED) else scan
                }
                val eviction = ScanQueuePolicy.automaticEvictionCandidate(queue)
                assertEquals(handled.id, eviction?.id)
                queue = queue.filterNot { it.id == eviction?.id }
                resolved += 1
            }
            assertTrue(ScanQueuePolicy.allowsAutomaticCapture(queue, capturePending = false))
            queue = listOf(scan("accepted-$index", index.toLong(), ScanPipelineStage.MATCHED)) + queue
            assertTrue(queue.size <= ScanQueuePolicy.MAX_TOTAL_BACKLOG)
        }

        assertEquals(10_044, resolved)
        assertEquals(ScanQueuePolicy.MAX_TOTAL_BACKLOG, queue.size)
        assertTrue(queue.all { it.stage == ScanPipelineStage.MATCHED && it.file.name.startsWith("accepted-") })
        assertFalse(ScanQueuePolicy.allowsAutomaticCapture(queue, capturePending = false))
    }

    @Test fun `manual intake evicts saved then pending automatic item but never unresolved terminal`() {
        val active = scan("active", 1, ScanPipelineStage.OCR, ScanCaptureOrigin.AUTOMATIC)
        val unresolvedManual = scan("manual-review", 2, ScanPipelineStage.MANUAL_REVIEW, ScanCaptureOrigin.MANUAL)
        val unresolvedMatched = scan("matched", 3, ScanPipelineStage.MATCHED, ScanCaptureOrigin.AUTOMATIC)
        val saved = scan("saved", 4, ScanPipelineStage.SAVED, ScanCaptureOrigin.MANUAL)
        assertEquals(
            saved,
            ScanQueuePolicy.evictionCandidateForManualCapture(listOf(active, unresolvedMatched, unresolvedManual, saved)),
        )

        val pendingManual = scan("pending-manual", 5, ScanPipelineStage.CAPTURED, ScanCaptureOrigin.MANUAL)
        val pendingAuto = scan("pending-auto", 6, ScanPipelineStage.CAPTURED, ScanCaptureOrigin.AUTOMATIC)
        assertEquals(
            pendingAuto,
            ScanQueuePolicy.evictionCandidateForManualCapture(
                listOf(active, unresolvedMatched, unresolvedManual, pendingManual, pendingAuto),
            ),
        )
        assertEquals(
            null,
            ScanQueuePolicy.evictionCandidateForManualCapture(listOf(pendingManual)),
        )
        assertEquals(
            null,
            ScanQueuePolicy.evictionCandidateForManualCapture(listOf(active, unresolvedMatched, unresolvedManual)),
        )
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
