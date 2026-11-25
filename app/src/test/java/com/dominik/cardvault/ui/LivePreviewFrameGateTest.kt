package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewFrameGateTest {
    private fun gate() = LivePreviewFrameGate(
        readyFramesToProcess = 3,
        missingFramesToRearm = 3,
        minimumIntervalMillis = 900L,
        minimumNewCardDifference = 8f,
    )

    private fun frame(value: Int) = IntArray(48) { value }

    @Test fun invalidOrIncompleteFramesNeverReachPreviewRecognition() {
        val gate = gate()
        assertFalse(gate.shouldProcess(false, null, 1, 0))
        assertFalse(gate.shouldProcess(false, frame(40), 2, 150))
        assertFalse(gate.shouldProcess(true, null, 3, 300))
    }

    @Test fun threeStableFramesProduceOneBoundedPreviewRequest() {
        val gate = gate()
        assertFalse(gate.shouldProcess(true, frame(40), 1, 0))
        assertFalse(gate.shouldProcess(true, frame(40), 2, 150))
        assertTrue(gate.shouldProcess(true, frame(40), 3, 900))
        gate.complete(success = true)
        assertFalse(gate.shouldProcess(true, frame(40), 4, 2_000))
    }

    @Test fun sameFrameSequenceCannotBeProcessedTwice() {
        val gate = gate()
        assertFalse(gate.shouldProcess(true, frame(40), 1, 0))
        assertFalse(gate.shouldProcess(true, frame(40), 1, 200))
        assertFalse(gate.shouldProcess(true, frame(40), 2, 400))
        assertTrue(gate.shouldProcess(true, frame(40), 3, 900))
    }

    @Test fun gateAllowsDifferentCardAfterStableEvidenceWithoutWaitingForEmptyPreview() {
        val gate = gate()
        repeat(2) { index -> assertFalse(gate.shouldProcess(true, frame(40), index + 1L, index * 300L)) }
        assertTrue(gate.shouldProcess(true, frame(40), 3, 900)); gate.complete(true)
        assertFalse(gate.shouldProcess(true, frame(70), 4, 1_100))
        assertFalse(gate.shouldProcess(true, frame(70), 5, 1_400))
        assertTrue(gate.shouldProcess(true, frame(70), 6, 1_800))
    }

    @Test fun heldCardRequiresObservedRemovalBeforeItCanBeProcessedAgain() {
        val gate = gate()
        repeat(2) { index -> gate.shouldProcess(true, frame(40), index + 1L, index * 300L) }
        assertTrue(gate.shouldProcess(true, frame(40), 3, 900)); gate.complete(true)
        repeat(3) { index -> assertFalse(gate.shouldProcess(false, null, index + 4L, 1_100L + index * 150L)) }
        assertFalse(gate.shouldProcess(true, frame(40), 7, 1_600))
        assertFalse(gate.shouldProcess(true, frame(40), 8, 1_800))
        assertTrue(gate.shouldProcess(true, frame(40), 9, 2_000))
    }

    @Test fun gateNeverAllowsTwoPreviewExtractionsAtOnce() {
        val gate = gate()
        repeat(2) { index -> gate.shouldProcess(true, frame(40), index + 1L, index * 300L) }
        assertTrue(gate.shouldProcess(true, frame(40), 3, 900))
        assertFalse(gate.shouldProcess(true, frame(70), 4, 1_300))
        gate.complete(success = false)
        assertFalse(gate.shouldProcess(true, frame(70), 5, 1_600))
        assertFalse(gate.shouldProcess(true, frame(70), 6, 1_900))
        assertTrue(gate.shouldProcess(true, frame(70), 7, 2_200))
    }

    @Test fun uniformBrightnessShiftDoesNotCountAsNewCardButRealDifferenceDoes() {
        assertFalse(isDistinctCardPresentation(frame(40), frame(45), 8f))
        assertTrue(isDistinctCardPresentation(frame(40), frame(60), 8f))
    }

    @Test fun interruptionClearsPartialReadinessAndRequiresFreshStableFramesAfterResume() {
        val gate = gate()
        assertFalse(gate.shouldProcess(true, frame(40), 1, 0))
        assertFalse(gate.shouldProcess(true, frame(40), 2, 300))
        gate.pause()
        assertFalse(gate.shouldProcess(true, frame(40), 3, 1_000))
        assertFalse(gate.shouldProcess(true, frame(40), 4, 1_300))
        assertTrue(gate.shouldProcess(true, frame(40), 5, 1_900))
    }
}
