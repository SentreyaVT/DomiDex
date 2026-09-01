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

    @Test fun automaticModeCapturesOnFirstAnalyzableContourWithoutOverlayState() {
        val gate = AutoCaptureGate()

        assertTrue(gate.onFrame(captureEligible = true))
        assertFalse(gate.onFrame(captureEligible = true))
    }

    @Test fun movingToNextCardRearmsWithoutRequiringAnEmptyFrame() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3)
        assertTrue(gate.onFrame(true))
        repeat(3) { assertFalse(gate.onFrame(false)) }
        assertTrue(gate.onFrame(true))
    }

    @Test fun sameCardCannotBeCapturedTwiceAfterAQualityTransition() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val sameCard = intArrayOf(20, 80, 30, 90, 40, 100)
        assertTrue(gate.onFrame(true, sameCard))
        repeat(3) { assertFalse(gate.onFrame(false, sameCard)) }

        assertFalse(gate.onFrame(true, sameCard))
    }

    @Test fun differentCardCapturesImmediatelyAfterItsGreenFrame() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3, minimumNewCardDifference = 8f)
        val first = intArrayOf(20, 80, 30, 90, 40, 100)
        val second = intArrayOf(100, 30, 90, 40, 80, 20)
        assertTrue(gate.onFrame(true, first))
        repeat(2) { assertFalse(gate.onFrame(true, second)) }
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
