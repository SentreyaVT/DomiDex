package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CardBoundaryEditorTest {
    @Test fun largeGalleryImageIsSampledBelowCanvasSafeEdge() {
        assertEquals(8, boundaryDecodeSample(16_320, 12_240))
        assertEquals(1, boundaryDecodeSample(1_920, 1_080))
    }
}
