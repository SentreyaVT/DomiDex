package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerEntryPolicyTest {
    @Test fun `friendly explanation always precedes Android camera permission`() {
        assertEquals(ScannerCameraEntryStep.EXPLAIN, scannerCameraEntryStep(introAccepted = false, cameraGranted = false))
        assertEquals(ScannerCameraEntryStep.REQUEST_SYSTEM_PERMISSION, scannerCameraEntryStep(introAccepted = true, cameraGranted = false))
        assertEquals(ScannerCameraEntryStep.READY, scannerCameraEntryStep(introAccepted = true, cameraGranted = true))
    }
}
