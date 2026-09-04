package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureGateTest {
    @Test fun capturesOnceAndRequiresCardRemovalBeforeNextCapture() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, readyFramesToCapture = 2)
        assertFalse(gate.onFrame(false))
        assertFalse(gate.onFrame(true))
        assertTrue(gate.onFrame(true))
        assertFalse(gate.onFrame(true))
        repeat(3) { assertFalse(gate.onFrame(false)) }
        assertFalse(gate.onFrame(true))
        assertTrue(gate.onFrame(true))
    }

    @Test fun automaticModeRequiresTwoConsecutiveReadyFrames() {
        val gate = AutoCaptureGate()

        assertFalse(gate.onFrame(captureEligible = true))
        assertTrue(gate.onFrame(captureEligible = true))
        assertFalse(gate.onFrame(captureEligible = true))
    }

    @Test fun movingToNextCardRearmsWithoutRequiringAnEmptyFrame() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val first = intArrayOf(20, 80, 30, 90, 40, 100)
        val second = intArrayOf(100, 30, 90, 40, 80, 20)
        assertFalse(gate.onFrame(true, first))
        assertTrue(gate.onFrame(true, first))
        repeat(3) { assertFalse(gate.onFrame(true, second)) }
        assertTrue(gate.onFrame(true, second))
    }

    @Test fun samePresentationCannotBeCapturedTwiceAfterABriefQualityTransition() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val sameCard = intArrayOf(20, 80, 30, 90, 40, 100)
        assertFalse(gate.onFrame(true, sameCard))
        assertTrue(gate.onFrame(true, sameCard))
        assertFalse(gate.onFrame(false, sameCard))

        assertFalse(gate.onFrame(true, sameCard))
        assertFalse(gate.onFrame(true, sameCard))
    }

    @Test fun identicalPhysicalPrintingCanBeCapturedAfterThreePublishedMissingFrames() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val samePrinting = intArrayOf(20, 80, 30, 90, 40, 100)
        assertFalse(gate.onFrame(true, samePrinting))
        assertTrue(gate.onFrame(true, samePrinting))
        repeat(3) { assertFalse(gate.onFrame(false, null)) }

        assertFalse(gate.onFrame(true, samePrinting))
        assertTrue(gate.onFrame(true, samePrinting))
    }

    @Test fun identicalPublishedFramesStillReceiveDistinctEffectKeys() {
        var sequence = 0L
        val keys = List(4) {
            sequence = nextPublishedFrameSequence(sequence)
            sequence
        }

        assertTrue(keys.zipWithNext().all { (first, second) -> second > first })
    }

    @Test fun differentCardCapturesImmediatelyAfterItsGreenFrame() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val first = intArrayOf(20, 80, 30, 90, 40, 100)
        val second = intArrayOf(100, 30, 90, 40, 80, 20)
        assertFalse(gate.onFrame(true, first))
        assertTrue(gate.onFrame(true, first))
        repeat(3) { assertFalse(gate.onFrame(true, second)) }
        assertTrue(gate.onFrame(true, second))
    }

    @Test fun fingerprintComparisonIgnoresUniformBrightnessShift() {
        val original = intArrayOf(20, 80, 30, 90, 40, 100)
        val brighter = original.map { it + 15 }.toIntArray()

        assertTrue(cardFingerprintDifference(original, brighter) < .01f)
    }

    @Test fun briefMissingFrameDoesNotRearmSamePresentation() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, readyFramesToCapture = 2)
        assertFalse(gate.onFrame(true))
        assertTrue(gate.onFrame(true))
        assertFalse(gate.onFrame(false))
        assertFalse(gate.onFrame(true))
        assertFalse(gate.onFrame(true))
    }

    @Test fun qualityWindowWaitsForNearBestFrameWithoutLongDelay() {
        val window = FrameQualityWindow(capacity = 4, tolerance = .04f)
        assertFalse(window.add(.72f))
        assertFalse(window.add(.60f))
        assertTrue(window.add(.70f))
        assertTrue(window.hasTemporalEvidence)
    }
}
