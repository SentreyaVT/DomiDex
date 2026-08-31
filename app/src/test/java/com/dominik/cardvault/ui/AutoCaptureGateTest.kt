package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureGateTest {
    @Test fun capturesOnceAndRequiresCardRemovalBeforeNextCapture() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, readyFramesToCapture = 2)
        assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.HOLD_STILL))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        repeat(2) { assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.FIND_CARD)) }
        assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.FIND_CARD))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
    }

    @Test fun automaticModeCapturesOnFirstStableGreenFrame() {
        val gate = AutoCaptureGate()

        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
    }

    @Test fun movingToNextCardRearmsWithoutRequiringAnEmptyFrame() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3)
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        repeat(2) { assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.HOLD_STILL)) }
        assertFalse(gate.onFrame(QualityLevel.RED, QualityGuidance.FOCUS))
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
    }

    @Test fun sameCardCannotBeCapturedTwiceAfterAQualityTransition() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val sameCard = intArrayOf(20, 80, 30, 90, 40, 100)
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY, true, sameCard))
        repeat(3) { assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.HOLD_STILL, true, sameCard)) }

        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY, true, sameCard))
    }

    @Test fun differentCardCapturesImmediatelyAfterItsGreenFrame() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val first = intArrayOf(20, 80, 30, 90, 40, 100)
        val second = intArrayOf(100, 30, 90, 40, 80, 20)
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY, true, first))
        repeat(3) { assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.HOLD_STILL, true, second)) }

        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY, true, second))
    }

    @Test fun fingerprintComparisonIgnoresUniformBrightnessShift() {
        val original = intArrayOf(20, 80, 30, 90, 40, 100)
        val brighter = original.map { it + 15 }.toIntArray()

        assertTrue(cardFingerprintDifference(original, brighter) < .01f)
    }

    @Test fun briefMissingFrameDoesNotRearmSamePresentation() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, readyFramesToCapture = 2)
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.FIND_CARD))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
    }

    @Test fun qualityWindowWaitsForNearBestFrameWithoutLongDelay() {
        val window = FrameQualityWindow(capacity = 4, tolerance = .04f)
        assertFalse(window.add(.72f))
        assertFalse(window.add(.60f))
        assertTrue(window.add(.70f))
        assertTrue(window.hasTemporalEvidence)
    }
}
