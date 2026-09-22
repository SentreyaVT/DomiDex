package com.dominik.cardvault.ui

import org.junit.Assert.*
import org.junit.Test

class ScannerCameraPreferenceTest {
    private fun lens(focal: Float, pixels: Long = 12_000_000, af: Boolean = true, rear: Boolean = true) =
        ScannerCameraCapabilities(rear, af, focal, 8f, 6f, pixels)

    @Test fun `autofocus main camera beats higher resolution fixed focus and ultrawide`() {
        val main = lens(5.6f)
        val wide = lens(3f, 50_000_000)
        val fixed = lens(5.6f, 108_000_000, af = false)
        assertSame(main, listOf(wide, fixed, main).sortedWith(scannerCameraPreference).first())
    }

    @Test fun `rear camera remains before selfie and telephoto does not beat normal lens`() {
        val main = lens(5.6f)
        val tele = lens(12f, 50_000_000)
        val selfie = lens(5.6f, 108_000_000, rear = false)
        assertEquals(listOf(main, tele, selfie), listOf(selfie, tele, main).sortedWith(scannerCameraPreference))
    }

    @Test fun `missing lens metadata remains a safe fallback`() {
        val unknown = ScannerCameraCapabilities(true, true, null, null, null, 12_000_000)
        assertNull(unknown.equivalentFocalLengthMm)
        assertEquals(unknown, listOf(lens(3f), unknown).sortedWith(scannerCameraPreference).first())
        assertEquals(24.23f, lens(5.6f).equivalentFocalLengthMm!!, .02f)
    }
}
