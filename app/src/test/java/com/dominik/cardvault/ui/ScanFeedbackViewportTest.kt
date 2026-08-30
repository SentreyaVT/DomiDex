package com.dominik.cardvault.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanFeedbackViewportTest {
    private val viewport = Size(720f, 1_000f)

    @Test fun normalizedPointStaysAtTheSameCardPositionAcrossZoomLevels() {
        val normal = scanFeedbackPointInViewport(Offset(.18f, .82f), viewport, 1f, Offset.Zero)
        val zoomed = scanFeedbackPointInViewport(Offset(.18f, .82f), viewport, 4f, Offset.Zero)

        // Both points are measured from the card centre. Uniform scaling must
        // multiply that vector equally on both axes and never distort a mark.
        assertEquals((normal.x - viewport.width / 2f) * 4f, zoomed.x - viewport.width / 2f, .001f)
        assertEquals((normal.y - viewport.height / 2f) * 4f, zoomed.y - viewport.height / 2f, .001f)
    }

    @Test fun panIsBoundedToTheActuallyVisibleScaledCard() {
        val pan = clampScanFeedbackPan(viewport, 3f, Offset(9_999f, -9_999f))

        assertEquals(720f, pan.x, .001f)
        assertEquals(-1_000f, pan.y, .001f)
    }

    @Test fun fitToCardHasNoPanAndKeepsNormalisedPosition() {
        val clamped = clampScanFeedbackPan(viewport, 1f, Offset(20f, -20f))
        val point = scanFeedbackPointInViewport(Offset(.5f, .5f), viewport, 1f, clamped)

        assertEquals(Offset.Zero, clamped)
        assertEquals(viewport.width / 2f, point.x, .001f)
        assertEquals(viewport.height / 2f, point.y, .001f)
    }
}
