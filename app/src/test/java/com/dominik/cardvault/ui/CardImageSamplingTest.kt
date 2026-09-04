package com.dominik.cardvault.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `remote artwork decode and encoded payload are both bounded`() {
        assertEquals(4, referenceArtworkDecodeSample(5_600, 4_200))
        assertEquals(8, readBoundedReferenceArtwork(ByteArrayInputStream(ByteArray(8)), maxBytes = 8)?.size)
        assertNull(readBoundedReferenceArtwork(ByteArrayInputStream(ByteArray(9)), maxBytes = 8))
    }
}
