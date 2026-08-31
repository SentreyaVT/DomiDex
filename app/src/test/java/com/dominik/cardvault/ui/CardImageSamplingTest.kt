package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CardImageSamplingTest {
    @Test fun `twelve megapixel still keeps roughly two thousand pixels for analysis`() {
        assertEquals(2, decodeSampleForLargestEdge(4_080))
    }

    @Test fun `already bounded image is not downsampled`() {
        assertEquals(1, decodeSampleForLargestEdge(2_400))
    }

    @Test fun `very large image is downsampled without crossing the configured bound`() {
        assertEquals(4, decodeSampleForLargestEdge(8_160))
    }
}
