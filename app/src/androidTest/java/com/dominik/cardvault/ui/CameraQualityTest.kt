package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraQualityTest {
    @Test fun detectsCardShapedQuadrilateralAndRejectsBlankFrame() {
        val width = 640; val height = 480
        val framed = ByteArray(width * height) { 35 }
        for (y in 70 until 410) for (x in 195 until 445) {
            if (x < 203 || x >= 437 || y < 78 || y >= 402) framed[y * width + x] = 230.toByte()
        }
        CardFrameDetector().use { detector ->
            val card = detector.detect(ByteBuffer.wrap(framed), width, height, width, 1)
            assertTrue(card.found)
            assertTrue(card.coverage > .25f)
            assertTrue(card.perspective > .65f)
            assertFalse(detector.detect(ByteBuffer.wrap(ByteArray(width * height) { 80 }), width, height, width, 1).found)
        }
    }
}
