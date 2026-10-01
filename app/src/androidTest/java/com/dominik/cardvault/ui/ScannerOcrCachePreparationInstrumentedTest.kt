package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** Exact-pixel/evidence checks; cache preparation must never change the OCR decision inputs. */
@RunWith(AndroidJUnit4::class)
class ScannerOcrCachePreparationInstrumentedTest {
    @Test fun allCachedReadingsRetainEveryEvidenceSlotWithoutPreparingPixels() = withFixture { source ->
        withPrepared(source) { original, _ ->
            val expected = frozenLegacyDescriptors(source.width, source.height)
            assertEquals("Independent pre-change evidence contract", expected, original.map(::descriptor))
            // An empty successful OCR result is a real cache hit too.
            val cache = expected.mapIndexed { index, input -> input.key to if (index % 2 == 0) "" else "PBL 094/084" }.toMap()
            withPrepared(source, cache) { cached, allocations ->
                assertEquals(33, expected.size)
                assertEquals(expected, cached.map(::descriptor))
                assertTrue(allocations.isEmpty())
                assertTrue(cached.all { it.bitmap == null && it.image == null && cache.containsKey(it.key) })
                // Labels/keys preserve the exact reading-to-region and consensus-slot mapping.
                assertEquals(original.map { cache.getValue(it.key) }, cached.map { cache.getValue(it.key) })
            }
        }
    }

    @Test fun partialCacheRetainsFrozenLegacyOrderIndependentlyOfCurrentBuilder() = withFixture { source ->
        val expected = frozenLegacyDescriptors(source.width, source.height)
        val cache = expected.filterIndexed { index, _ -> index % 3 == 0 }.associate { it.key to "" }
        withPrepared(source, cache) { inputs, _ ->
            assertEquals(expected, inputs.map(::descriptor))
            inputs.forEach { input ->
                if (cache.containsKey(input.key)) { assertNull(input.bitmap); assertNull(input.image) }
                else { assertNotNull(input.bitmap); assertNotNull(input.image) }
            }
        }
    }

    @Test fun optionalRecognitionPlaneKeepsSeparateOriginalAndEnhancedSlots() = withFixture { source ->
        val expected = frozenLegacyDescriptors(source.width, source.height)
        // Production currently passes null. Even an explicitly identical recognition plane
        // still produced a distinct crop in the legacy implementation for these proper ROIs.
        withPrepared(source, recognitionBitmap = source) { inputs, _ ->
            assertEquals(expected, inputs.map(::descriptor))
            inputs.filter { it.variantId == "original" }.forEach { original ->
                val enhanced = inputs.single { it.profileId == original.profileId && it.variantId == "enhanced" }
                assertNotSame(original.bitmap, enhanced.bitmap)
                assertTrue(requireNotNull(original.bitmap).sameAs(requireNotNull(enhanced.bitmap)))
            }
        }
        val alternate = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        alternate.eraseColor(Color.MAGENTA)
        try {
            withPrepared(source, recognitionBitmap = alternate) { inputs, _ ->
                assertEquals(expected, inputs.map(::descriptor))
                inputs.filter { it.variantId == "original" }.forEach { original ->
                    val enhanced = inputs.single { it.profileId == original.profileId && it.variantId == "enhanced" }
                    assertNotSame(original.bitmap, enhanced.bitmap)
                    assertEquals(Color.MAGENTA, requireNotNull(enhanced.bitmap).getPixel(0, 0))
                    assertFalse(requireNotNull(original.bitmap).sameAs(requireNotNull(enhanced.bitmap)))
                }
            }
        } finally { alternate.recycle() }
    }

    @Test fun partialCachePreservesUncachedPixelsAndReleasesDerivativeIntermediates() = withFixture { source ->
        withPrepared(source) { original, originalAllocations ->
            val cache = original.filter { input ->
                input.profileId == "modern_name" ||
                    (input.profileId == "full_art_left" && input.variantId in setOf("original", "enhanced"))
            }.associate { it.key to "" }
            withPrepared(source, cache) { cached, allocations ->
                assertEquals(original.map(::descriptor), cached.map(::descriptor))
                assertTrue("The fully cached name crop must not allocate again", allocations.size < originalAllocations.size)
                assertTrue("Cached lower-strip intermediates must be released after deriving uncached pixels", allocations.any(Bitmap::isRecycled))
                original.zip(cached).forEach { (before, after) ->
                    if (cache.containsKey(after.key)) {
                        assertNull(after.bitmap); assertNull(after.image)
                    } else {
                        val pixels = requireNotNull(after.bitmap)
                        assertFalse(pixels.isRecycled)
                        assertTrue("${after.profileId}/${after.variantId} pixels changed", requireNotNull(before.bitmap).sameAs(pixels))
                        assertNotNull(after.image)
                    }
                }
            }
        }
    }

    @Test fun otherOrientationOrDimensionsCannotReuseCachedReadings() = withFixture { source ->
        withPrepared(source) { original, _ ->
            val unrelated = original.flatMap { input -> listOf(
                input.key.copy(orientation = AnalysisOrientation.DEG_180) to "PBL 094/084",
                input.key.copy(sourceWidth = source.width + 1) to "PBL 094/084",
            ) }.toMap()
            withPrepared(source, unrelated) { prepared, _ ->
                assertEquals(original.map(::descriptor), prepared.map(::descriptor))
                assertTrue(prepared.all { it.bitmap != null && it.image != null })
                original.zip(prepared).forEach { (before, after) ->
                    assertTrue(requireNotNull(before.bitmap).sameAs(requireNotNull(after.bitmap)))
                }
            }
        }
    }

    private fun descriptor(input: OcrInput) = input.copy(bitmap = null, image = null)

    /**
     * Frozen independently from pre-optimization commit 096aa35's buildOcrInputs and
     * CardOcrRois. Do not derive this contract from current profiles, unions or preprocessing
     * conditions: a changed slot/order/region/key must fail even if both new builder paths agree.
     */
    private fun frozenLegacyDescriptors(width: Int, height: Int): List<OcrInput> {
        val name = setOf(CardOcrRegion.NAME)
        val border = setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER)
        val modernName = CardRoi(.055f, .055f, .945f, .245f)
        val legacyName = CardRoi(.050f, .045f, .950f, .285f)
        val extendedName = CardRoi(.050f, .050f, .950f, .270f)
        val modern = CardRoi(.035f, .805f, .735f, .985f)
        val legacy = CardRoi(.030f, .755f, .760f, .965f)
        val extended = CardRoi(.025f, .830f, .805f, .995f)
        val left = CardRoi(.010f, .915f, .355f, .998f)
        val center = CardRoi(.230f, .790f, .775f, .998f)
        val right = CardRoi(.525f, .790f, .995f, .998f)
        val lower = CardRoi(.010f, .745f, .995f, .998f)
        val historicalName = CardRoi(0f, 0f, 1f, .34f)
        val historicalIdentity = CardRoi(0f, .58f, 1f, 1f)
        fun slot(profile: String, variant: String, roi: CardRoi, dynamic: Boolean, tight: Boolean, regions: Set<CardOcrRegion>) = OcrInput(
            regions = regions, image = null, dynamic = dynamic, bitmap = null,
            profileId = profile, variantId = variant, isTightIdentityStrip = tight,
            orientation = AnalysisOrientation.DEG_0,
            key = OcrReadKey(roi, variant, AnalysisOrientation.DEG_0, width, height),
        )
        return listOf(
            slot("modern_name", "original", modernName, false, false, name),
            slot("modern_name", "enhanced", modernName, false, false, name),
            slot("legacy_name", "original", legacyName, false, false, name),
            slot("legacy_name", "enhanced", legacyName, false, false, name),
            slot("extended_lower_border_name", "original", extendedName, false, false, name),
            slot("extended_lower_border_name", "enhanced", extendedName, false, false, name),
            slot("modern", "original", modern, false, false, border),
            slot("modern", "enhanced", modern, false, false, border),
            slot("modern", "inverted", modern, false, false, border),
            slot("legacy", "original", legacy, false, false, border),
            slot("legacy", "enhanced", legacy, false, false, border),
            slot("legacy", "inverted", legacy, false, false, border),
            slot("extended_lower_border", "original", extended, false, false, border),
            slot("extended_lower_border", "enhanced", extended, false, false, border),
            slot("extended_lower_border", "inverted", extended, false, false, border),
            slot("full_art_left", "original", left, true, true, border),
            slot("full_art_left", "enhanced", left, true, true, border),
            slot("full_art_left", "threshold", left, true, true, border),
            slot("full_art_left", "inverted", left, true, true, border),
            slot("full_art_center", "original", center, true, false, border),
            slot("full_art_center", "enhanced", center, true, false, border),
            slot("full_art_center", "inverted", center, true, false, border),
            slot("full_art_right", "original", right, true, false, border),
            slot("full_art_right", "enhanced", right, true, false, border),
            slot("full_art_right", "inverted", right, true, false, border),
            slot("full_lower_strip", "original", lower, true, false, border),
            slot("full_lower_strip", "enhanced", lower, true, false, border),
            slot("full_lower_strip", "inverted", lower, true, false, border),
            slot("historical_name", "original", historicalName, true, false, name),
            slot("historical_name", "enhanced", historicalName, true, false, name),
            slot("historical_identity", "original", historicalIdentity, true, false, border),
            slot("historical_identity", "enhanced", historicalIdentity, true, false, border),
            slot("historical_identity", "inverted", historicalIdentity, true, false, border),
        )
    }

    private fun withPrepared(
        source: Bitmap,
        readings: Map<OcrReadKey, String> = emptyMap(),
        recognitionBitmap: Bitmap? = null,
        block: (List<OcrInput>, List<Bitmap>) -> Unit,
    ) {
        val allocations = mutableListOf<Bitmap>()
        val resources = ScanTaskResources<Bitmap> { if (!it.isRecycled) it.recycle() }
        try {
            val inputs = buildOcrInputs(source, recognitionBitmap, AnalysisOrientation.DEG_0, own = {
                allocations += it
                resources.own(it)
            }, cachedReadings = readings)
            block(inputs, allocations)
        } finally {
            resources.close()
            assertTrue("All temporary bitmaps must be released", allocations.all(Bitmap::isRecycled))
            assertFalse("Preparation must never recycle its caller-owned source", source.isRecycled)
            recognitionBitmap?.let { assertFalse("Recognition source belongs to the caller", it.isRecycled) }
        }
    }

    private fun withFixture(block: (Bitmap) -> Unit) {
        assertTrue(OpenCVLoader.initLocal())
        val source = Bitmap.createBitmap(320, 448, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 320f, 448f, Color.rgb(221, 232, 194), Color.rgb(43, 73, 119), Shader.TileMode.CLAMP)
            }
            drawRect(0f, 0f, 320f, 448f, paint)
            paint.shader = null; paint.color = Color.BLACK; paint.textSize = 22f
            drawText("Pikachu ex", 18f, 48f, paint)
            paint.textSize = 17f
            drawText("PBL 094/084", 10f, 425f, paint)
        }
        try { block(source) } finally { source.recycle() }
    }
}
