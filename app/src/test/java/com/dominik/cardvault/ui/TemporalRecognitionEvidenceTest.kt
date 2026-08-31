package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalRecognitionEvidenceTest {
    @Test fun `best number and best name from separate frames are combined without pixel averaging`() {
        val nameFrame = CardTextAnalysis(
            lines = listOf("Glurak ex"), collectorNumber = null, setIdentifier = null,
            likelyScript = CardScript.LATIN, searchQueries = listOf("Glurak ex"),
            hasNameRoiEvidence = true,
            imageQuality = CardImageQuality(cardDetected = true, frameQualityScore = .82f),
        )
        val numberFrame = CardTextAnalysis(
            lines = listOf("OBF 198/197"), collectorNumber = "198/197", setIdentifier = "OBF",
            likelyScript = CardScript.LATIN, searchQueries = emptyList(), numberConfidence = .96f,
            imageQuality = CardImageQuality(cardDetected = true, frameQualityScore = .74f),
        )

        val merged = TemporalRecognitionEvidence.merge(listOf(nameFrame, numberFrame))!!

        assertEquals("198/197", merged.collectorNumber)
        assertEquals("OBF", merged.setIdentifier)
        assertTrue("Glurak ex" in merged.searchQueries)
        assertTrue(merged.usedMultiFrameEvidence)
    }
}
