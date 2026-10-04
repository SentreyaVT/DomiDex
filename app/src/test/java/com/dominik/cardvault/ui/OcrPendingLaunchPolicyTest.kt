package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPendingLaunchPolicyTest {
    @Test fun singleOrientationStartsNumberNameSetBeforeVariantsWithoutDroppingAnySlot() {
        val inputs = plane(AnalysisOrientation.DEG_0)
        val groups = groups(inputs)
        val before = groups.map { it.toList() }
        val ordered = orderPendingOcrGroups(groups, true)
        assertEquals(listOf(5, 0, 2, 1, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13), ordered.map { it.first().index })
        assertEquals(before, groups)
        ordered.forEach { group -> assertSame(groups.single { it.first().index == group.first().index }, group) }
    }

    @Test fun everyOrientationGetsItsNumberBeforeAnyNameAndNameBeforeAnySet() {
        for (orientations in listOf(
            listOf(AnalysisOrientation.DEG_0, AnalysisOrientation.DEG_180),
            listOf(AnalysisOrientation.DEG_270, AnalysisOrientation.DEG_90),
            AnalysisOrientation.entries.toList(),
        )) {
            val ordered = orderPendingOcrGroups(groups(orientations.flatMap(::plane)), true)
            val count = orientations.size
            for ((priority, offset) in listOf(5, 0, 2).withIndex()) {
                val stage = ordered.drop(priority * count).take(count).map { it.first() }
                assertEquals(orientations, stage.map { it.value.orientation })
                assertEquals(orientations.indices.map { it * 14 + offset }, stage.map { it.index })
            }
            assertEquals((0 until count * 14).filter { it % 14 !in setOf(5, 0, 2) },
                ordered.drop(3 * count).map { it.first().index })
        }
    }

    @Test fun fullOcrReturnsTheExactOriginalPendingListInOriginalOrder() {
        val pending = groups(plane(AnalysisOrientation.DEG_0) + plane(AnalysisOrientation.DEG_180))
        assertSame(pending, orderPendingOcrGroups(pending, false))
        assertEquals((0 until 28).toList(), pending.map { it.first().index })
        val empty = emptyList<List<IndexedValue<OcrInput>>>()
        assertSame(empty, orderPendingOcrGroups(empty, false))
        assertTrue(orderPendingOcrGroups(empty, true).isEmpty())
    }

    @Test fun completedReadsKeepOriginalResultIndicesEvenWithOutOfOrderCompletion() {
        val inputs = plane(AnalysisOrientation.DEG_0) + plane(AnalysisOrientation.DEG_180)
        val expected = inputs.indices.associateWith { "reading-at-original-index-$it" }
        val readings = inputs.mapIndexed { index, input -> input.key to expected.getValue(index) }.toMap()
        val results = mutableMapOf<Int, String>()
        // Reverse completion order is independent of both preparation and launch order.
        orderPendingOcrGroups(groups(inputs), true).asReversed().forEach { group ->
            val reading = readings.getValue(group.first().value.key)
            group.forEach { entry ->
                assertSame(inputs[entry.index], entry.value)
                results[entry.index] = reading
            }
        }
        assertEquals(expected, results)
    }

    @Test fun duplicateKeysStayOneGroupAndCachedIncludingBlankReadingsNeverReenterPending() {
        val original = plane(AnalysisOrientation.DEG_0)
        val inputs = original + original[5].copy(profileId = "another_label_for_same_pixels")
        val cache = mapOf(original[0].key to "cached-name", original[2].key to "")
        val beforeCache = cache.toMap()
        val results = mutableMapOf<Int, String>()
        // The analyzer's existing grouping/cache phase is deliberately unchanged.
        val uncached = groups(inputs).filter { group ->
            val reading = cache[group.first().value.key]
            if (reading != null) {
                group.forEach { results[it.index] = reading }
                false
            } else true
        }
        val ordered = orderPendingOcrGroups(uncached, true)
        assertEquals(12, ordered.size)
        assertSame(uncached.single { it.first().index == 5 }, ordered.first())
        assertEquals(listOf(5, 14), ordered.first().map { it.index })
        assertFalse(ordered.any { it.first().value.key in cache })
        assertEquals(mapOf(0 to "cached-name", 2 to ""), results)
        ordered.forEach { group -> group.forEach { results[it.index] = "read-${group.first().index}" } }
        assertEquals(inputs.indices.toSet(), results.keys)
        assertEquals("read-5", results[5]); assertEquals("read-5", results[14])
        assertEquals("cached-name", results[0]); assertEquals("", results[2])
        assertEquals(beforeCache, cache)
    }

    @Test fun timeoutSuffixIsExactlyTheUnstartedGroupsAfterAnyLaunchPrefix() {
        val ordered = orderPendingOcrGroups(groups(plane(AnalysisOrientation.DEG_0) + plane(AnalysisOrientation.DEG_180)), true)
        for (next in 0..ordered.size) {
            val launched = ordered.take(next)
            val releasedByTimeout = ordered.drop(next)
            assertEquals(ordered, launched + releasedByTimeout)
            assertTrue(launched.flatMap { it }.map { it.index }.toSet()
                .intersect(releasedByTimeout.flatMap { it }.map { it.index }.toSet()).isEmpty())
            releasedByTimeout.forEachIndexed { offset, group -> assertSame(ordered[next + offset], group) }
        }
    }

    @Test fun unknownProfilesNonoriginalVariantsAndMissingOrientationKeepTheirRelativeOrder() {
        val base = plane(AnalysisOrientation.DEG_0)
        val inputs = listOf(
            base[5].copy(variantId = "enhanced", key = base[5].key.copy(variant = "enhanced")),
            base[0].copy(profileId = "unknown", key = base[0].key.copy(roi = CardRoi(.01f, .01f, .10f, .10f))),
            base[2].copy(orientation = null, key = base[2].key.copy(orientation = null)),
            base[5], base[0], base[2],
        )
        val pending = inputs.mapIndexed { index, input -> listOf(IndexedValue(index, input)) }
        assertEquals(inputs.size, pending.map { it.first().value.key }.toSet().size)
        val ordered = orderPendingOcrGroups(pending, true)
        assertEquals(listOf(3, 4, 5, 0, 1, 2), ordered.map { it.first().index })
    }

    @Test fun numberFirstDoesNotBypassExistingUnknownSetOrNameEvidenceRequirements() {
        fun parse(numberText: String, name: String = "") = CardTextParser.parse(
            mapOf(CardScript.LATIN to numberText), preferredNameText = name,
            preferredNumberText = numberText, preferredSetText = "",
        )
        fun selection(analysis: CardTextAnalysis) = AnalysisOrientationSelector.select(
            listOf(AnalysisOrientationEvidence(AnalysisOrientation.DEG_0, analysis)),
        )
        val known = parse("PBL 094/084")
        assertEquals("094/084", known.collectorNumber)
        assertEquals("PBL", known.setIdentifier)
        assertEquals(78, AnalysisOrientationSelector.score(known))
        assertTrue(selection(known).accepted)
        val unknown = parse("ZZZ 094/084")
        assertNull(unknown.setIdentifier)
        assertEquals(48, AnalysisOrientationSelector.score(unknown))
        assertFalse(selection(unknown).accepted)
        assertTrue(selection(parse("ZZZ 094/084", "Pikachu ex")).accepted)
    }

    private fun groups(inputs: List<OcrInput>) = inputs.withIndex().groupBy { it.value.key }.values.toList()

    private fun plane(orientation: AnalysisOrientation): List<OcrInput> {
        fun input(profile: String, roi: CardRoi, variant: String, region: CardOcrRegion) = OcrInput(
            regions = setOf(region), image = null, dynamic = true, bitmap = null,
            profileId = "orientation_$profile", variantId = variant, isTightIdentityStrip = profile == "identity_strip",
            orientation = orientation, key = OcrReadKey(roi, variant, orientation, 1200, 1680),
        )
        // Independent descriptor fixture; no Android pixels, ML Kit, clocks or threads.
        return buildList {
            for (variant in listOf("original", "enhanced")) add(input("rc3_name", CardRoi(.055f, .055f, .945f, .245f), variant, CardOcrRegion.NAME))
            for (variant in listOf("original", "enhanced", "inverted")) add(input("rc3_set", CardRoi(.035f, .805f, .500f, .985f), variant, CardOcrRegion.SET))
            for (variant in listOf("original", "enhanced", "inverted")) add(input("rc3_number", CardRoi(.180f, .805f, .735f, .985f), variant, CardOcrRegion.NUMBER))
            for (variant in listOf("original", "enhanced")) add(input("identity_strip", CardRoi(.010f, .915f, .355f, .998f), variant, CardOcrRegion.NUMBER))
            for (variant in listOf("original", "enhanced")) add(input("extended_border", CardRoi(.025f, .830f, .805f, .995f), variant, CardOcrRegion.NUMBER))
            add(input("historical_name", CardRoi(0f, 0f, 1f, .34f), "original", CardOcrRegion.NAME))
            add(input("historical_identity", CardRoi(0f, .58f, 1f, 1f), "original", CardOcrRegion.NUMBER))
        }
    }
}
