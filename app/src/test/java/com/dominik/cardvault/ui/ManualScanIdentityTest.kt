package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualScanIdentityTest {
    @Test fun formatsSelectedSetNumberWithOfficialTotal() {
        assertEquals("068/131", manualCollectorNumber("68", 131))
        assertEquals("0173/1025", manualCollectorNumber("173", 1025))
    }

    @Test fun rejectsMissingNumber() {
        assertEquals(null, manualCollectorNumber("", 131))
    }

    @Test fun explicitSelectionKeepsPrintedCodeAndStableSourceId() {
        val result = withManualScanIdentity(null, "sv08.5", "PRE", "068/131")

        assertEquals("sv08.5", result.manualSetSourceId)
        assertEquals("PRE", result.setIdentifier)
        assertEquals("068/131", result.collectorNumber)
        assertEquals(1f, result.numberConfidence)
        assertTrue(result.manualIdentityConfirmed)
        assertFalse(result.imageQuality.cardDetected)
    }
}
