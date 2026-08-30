package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureGateTest {
    @Test fun capturesOnceAndRequiresCardRemovalBeforeNextCapture() {
        val gate = AutoCaptureGate(missingFramesToRearm = 3)
        assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.HOLD_STILL))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        repeat(2) { assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.FIND_CARD)) }
        assertFalse(gate.onFrame(QualityLevel.YELLOW, QualityGuidance.FIND_CARD))
        assertFalse(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
        assertTrue(gate.onFrame(QualityLevel.GREEN, QualityGuidance.READY))
    }
}
