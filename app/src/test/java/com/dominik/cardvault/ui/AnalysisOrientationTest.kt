package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisOrientationTest {
    @Test fun `aspect correct candidates cover both directions without stretching`() {
        assertEquals(listOf(AnalysisOrientation.DEG_0, AnalysisOrientation.DEG_180), aspectCorrectAnalysisOrientations(true))
        assertEquals(listOf(AnalysisOrientation.DEG_90, AnalysisOrientation.DEG_270), aspectCorrectAnalysisOrientations(false))
        assertEquals(AnalysisOrientation.entries.toSet(), (
            aspectCorrectAnalysisOrientations(true) + aspectCorrectAnalysisOrientations(false)
            ).toSet())
    }

    @Test fun `corner permutations remain deterministic for every analysis direction`() {
        assertEquals(listOf(0, 1, 2, 3), cornerIndicesForAnalysisOrientation(AnalysisOrientation.DEG_0))
        assertEquals(listOf(1, 2, 3, 0), cornerIndicesForAnalysisOrientation(AnalysisOrientation.DEG_90))
        assertEquals(listOf(2, 3, 0, 1), cornerIndicesForAnalysisOrientation(AnalysisOrientation.DEG_180))
        assertEquals(listOf(3, 0, 1, 2), cornerIndicesForAnalysisOrientation(AnalysisOrientation.DEG_270))
    }

    @Test fun `coherent identity wins only with configured runner up margin`() {
        val strong = evidence(AnalysisOrientation.DEG_0, number = "117/084", set = "PBL", name = "Morpeko ex", confidence = .96f)
        val weak = evidence(AnalysisOrientation.DEG_180, number = "117/084", confidence = .70f)

        val selection = AnalysisOrientationSelector.select(listOf(weak, strong))

        assertTrue(selection.accepted)
        assertEquals(AnalysisOrientation.DEG_0, selection.best?.orientation)
        assertTrue(selection.margin >= AnalysisOrientationSelector.MIN_MARGIN)
    }

    @Test fun `near equal orientation evidence remains ambiguous`() {
        val first = evidence(AnalysisOrientation.DEG_90, number = "117/084", set = "PBL", name = "Morpeko ex", confidence = .96f)
        val second = evidence(AnalysisOrientation.DEG_270, number = "117/084", set = "PBL", name = "Morpeko", confidence = .94f)

        val selection = AnalysisOrientationSelector.select(listOf(first, second))

        assertFalse(selection.accepted)
        assertEquals("orientation_evidence_ambiguous", selection.reason)
    }

    @Test fun `all eight exif orientations map display points exactly`() {
        val point = .2f to .7f
        val expected = listOf(
            .2f to .7f,
            .8f to .7f,
            .8f to .3f,
            .2f to .3f,
            .7f to .2f,
            .3f to .2f,
            .3f to .8f,
            .7f to .8f,
        )
        (1..8).forEach { orientation ->
            val actual = exifDisplayPoint(orientation, point.first, point.second)
            assertEquals(expected[orientation - 1].first, actual.first, .0001f)
            assertEquals(expected[orientation - 1].second, actual.second, .0001f)
        }
    }

    private fun evidence(
        orientation: AnalysisOrientation,
        number: String? = null,
        set: String? = null,
        name: String? = null,
        confidence: Float = 0f,
    ) = AnalysisOrientationEvidence(
        orientation,
        CardTextAnalysis(
            lines = listOfNotNull(name, set, number),
            collectorNumber = number,
            setIdentifier = set,
            likelyScript = CardScript.LATIN,
            searchQueries = listOfNotNull(name),
            numberConfidence = confidence,
            hasNameRoiEvidence = name != null,
        ),
    )
}
