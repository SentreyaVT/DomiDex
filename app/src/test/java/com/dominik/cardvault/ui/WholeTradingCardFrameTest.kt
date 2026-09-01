package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeTradingCardFrameTest {
    @Test fun acceptsOnlyLargeOuterPokemonCardGeometry() {
        assertTrue(isWholeTradingCardFrame(frame(coverage = .32f, aspect = .716f)))
        assertFalse(isWholeTradingCardFrame(frame(coverage = .09f, aspect = .716f)))
        assertFalse(isWholeTradingCardFrame(frame(coverage = .32f, aspect = .45f)))
        assertFalse(isWholeTradingCardFrame(frame(coverage = .32f, aspect = .95f)))
        assertFalse(isWholeTradingCardFrame(frame(coverage = .32f, aspect = .716f, geometry = .40f)))
        assertFalse(isWholeTradingCardFrame(frame(coverage = .32f, aspect = .716f, portraitAligned = false)))
    }

    @Test fun captureGateAcceptsOffsetScaledPerspectiveAndRotatedCardPlanes() {
        assertTrue(isCaptureEligibleTradingCardFrame(frame(coverage = .10f, aspect = .55f, geometry = .46f, perspective = .33f, portraitAligned = false)))
        assertTrue(isCaptureEligibleTradingCardFrame(frame(coverage = 1.30f, aspect = .90f, geometry = .50f, perspective = .40f)))
        assertFalse(isCaptureEligibleTradingCardFrame(frame(coverage = .08f, aspect = .716f)))
        assertFalse(isCaptureEligibleTradingCardFrame(frame(coverage = .32f, aspect = .46f)))
        assertFalse(isCaptureEligibleTradingCardFrame(frame(coverage = .32f, aspect = .716f, corners = emptyList())))
    }

    @Test fun cameraCoordinatesRespectEveryExifRotationWithoutChangingThePhotoDirection() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val source = .21f to .73f
            val preview = sourceToPreview(source.first, source.second, rotation)
            val restored = previewToSource(preview.first, preview.second, rotation)
            assertTrue(kotlin.math.abs(restored.first - source.first) < .0001f)
            assertTrue(kotlin.math.abs(restored.second - source.second) < .0001f)
        }
    }

    private fun frame(
        coverage: Float,
        aspect: Float,
        geometry: Float = .82f,
        perspective: Float = .82f,
        portraitAligned: Boolean = true,
        corners: List<Pair<Float, Float>> = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.9f to 0.9f, 0.1f to 0.9f),
    ) = CardFrame(
        found = true,
        coverage = coverage,
        perspective = perspective,
        corners = corners,
        geometryScore = geometry,
        aspectRatio = aspect,
        portraitAligned = portraitAligned,
    )
}
