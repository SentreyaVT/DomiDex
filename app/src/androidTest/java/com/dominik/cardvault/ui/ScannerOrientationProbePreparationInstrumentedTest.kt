package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Frozen pre-reuse probe contract: no OCR slot, key, orientation or pixel may change. */
@RunWith(AndroidJUnit4::class)
class ScannerOrientationProbePreparationInstrumentedTest {
    @Test fun orientationProbePreservesEveryLegacySlotAndPixel() {
        listOf(320 to 448, 1200 to 1680).forEach { (width, height) ->
            withSource(width, height) { source ->
                AnalysisOrientation.entries.forEach { orientation ->
                    withPrepared(source, orientation, legacy = true) { before, _, _ ->
                        withPrepared(source, orientation, legacy = false) { after, _, _ ->
                            assertEquals(14, before.size)
                            assertEquals(before.map(::descriptor), after.map(::descriptor))
                            before.zip(after).forEach { (expected, actual) ->
                                assertTrue("${orientation}/${actual.profileId}/${actual.variantId} pixels changed",
                                    requireNotNull(expected.bitmap).sameAs(requireNotNull(actual.bitmap)))
                                assertNotNull(actual.image)
                                assertFalse(requireNotNull(actual.bitmap).isRecycled)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun orientationProbeAvoidsExactlyThreeTemporaryBitmapsPerPlane() = withSource(1200, 1680) { source ->
        withPrepared(source, AnalysisOrientation.DEG_0, legacy = true) { _, oldAllocations, oldBytes ->
            withPrepared(source, AnalysisOrientation.DEG_0, legacy = false) { _, allocations, bytes ->
                assertEquals("Frozen allocation count before ROI reuse", 18, oldAllocations.size)
                assertEquals(15, allocations.size)
                assertTrue("Removing duplicate crops must also reduce allocated bytes", bytes < oldBytes)
            }
        }
    }

    @Test fun orientationProbeCancellationRetainsOnlyNativeLeasedInputs() = withSource(1200, 1680) { source ->
        val allocations = mutableListOf<Bitmap>()
        val resources = ScanTaskResources<Bitmap> { if (!it.isRecycled) it.recycle() }
        val inputs = buildOrientationProbeInputs(CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_180, source)) {
            allocations += it
            resources.own(it)
        }
        val original = requireNotNull(inputs.single {
            it.profileId == "orientation_identity_strip" && it.variantId == "original"
        }.bitmap)
        val enhanced = requireNotNull(inputs.single {
            it.profileId == "orientation_identity_strip" && it.variantId == "enhanced"
        }.bitmap)
        assertNotSame(original, enhanced)
        assertTrue(resources.lease(original))
        assertTrue(resources.lease(enhanced))
        try {
            resources.close()
            assertFalse(original.isRecycled)
            assertFalse(enhanced.isRecycled)
            assertTrue(allocations.filter { it !== original && it !== enhanced }.all(Bitmap::isRecycled))
            resources.releaseLease(original)
            assertTrue(original.isRecycled)
            assertFalse(enhanced.isRecycled)
            resources.releaseLease(enhanced)
            assertTrue(allocations.all(Bitmap::isRecycled))
        } finally {
            if (!original.isRecycled) resources.releaseLease(original)
            if (!enhanced.isRecycled) resources.releaseLease(enhanced)
            resources.close()
            assertFalse(source.isRecycled)
        }
    }

    private fun descriptor(input: OcrInput) = input.copy(bitmap = null, image = null)

    /** Independent snapshot of the previous implementation, including its duplicate crop work. */
    private fun legacyInputs(source: Bitmap, orientation: AnalysisOrientation, own: (Bitmap) -> Bitmap): List<OcrInput> {
        fun crop(roi: CardRoi): Bitmap {
            // Deliberately independent of current CardRoi.crop/cropAtReadableScale.
            val x = (source.width * roi.left).toInt().coerceIn(0, source.width - 1)
            val y = (source.height * roi.top).toInt().coerceIn(0, source.height - 1)
            val width = (source.width * (roi.right - roi.left)).toInt().coerceAtLeast(1).coerceAtMost(source.width - x)
            val height = (source.height * (roi.bottom - roi.top)).toInt().coerceAtLeast(1).coerceAtMost(source.height - y)
            val cropped = own(Bitmap.createBitmap(source, x, y, width, height))
            if (roi.bottom - roi.top > .12f) return cropped
            val scale = if (roi.bottom - roi.top <= .10f) 3 else 2
            return own(Bitmap.createScaledBitmap(cropped, cropped.width * scale, cropped.height * scale, true))
                .also { cropped.recycle() }
        }
        fun input(regions: Set<CardOcrRegion>, roi: CardRoi, profile: String, variant: String, pixels: Bitmap, tight: Boolean = false) =
            OcrInput(regions, InputImage.fromBitmap(pixels, 0), dynamic = variant != "inverted", bitmap = pixels,
                profileId = "orientation_$profile", variantId = variant, isTightIdentityStrip = tight,
                orientation = orientation, key = OcrReadKey(roi, variant, orientation, source.width, source.height))
        fun rc3(region: CardOcrRegion, roi: CardRoi, profile: String, inverted: Boolean = false): List<OcrInput> {
            val cropped = crop(roi)
            val enhanced = own(legacyEnhance(cropped))
            val regions = setOf(region)
            return buildList {
                add(input(regions, roi, profile, "original", cropped))
                add(input(regions, roi, profile, "enhanced", enhanced))
                if (inverted) add(input(regions, roi, profile, "inverted", own(legacyInvert(enhanced))))
            }
        }
        fun duplicatedPair(roi: CardRoi, profile: String, tight: Boolean = false): List<OcrInput> {
            val regions = setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER)
            val original = input(regions, roi, profile, "original", crop(roi), tight)
            val secondCrop = crop(roi)
            val enhanced = own(legacyEnhance(secondCrop))
            secondCrop.recycle()
            return listOf(original, input(regions, roi, profile, "enhanced", enhanced, tight))
        }
        return buildList {
            addAll(rc3(CardOcrRegion.NAME, CardRoi(.055f, .055f, .945f, .245f), "rc3_name"))
            addAll(rc3(CardOcrRegion.SET, CardRoi(.035f, .805f, .500f, .985f), "rc3_set", inverted = true))
            addAll(rc3(CardOcrRegion.NUMBER, CardRoi(.180f, .805f, .735f, .985f), "rc3_number", inverted = true))
            addAll(duplicatedPair(CardRoi(.010f, .915f, .355f, .998f), "identity_strip", tight = true))
            addAll(duplicatedPair(CardRoi(.025f, .830f, .805f, .995f), "extended_border"))
            val name = CardRoi(0f, 0f, 1f, .34f)
            val identity = CardRoi(0f, .58f, 1f, 1f)
            add(input(setOf(CardOcrRegion.NAME), name, "historical_name", "original", crop(name)))
            add(input(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), identity, "historical_identity", "original", crop(identity)))
        }
    }

    private fun legacyEnhance(source: Bitmap): Bitmap {
        val contrast = 1.32f
        val translate = (-.5f * contrast + .5f) * 255f
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return filtered(source, matrix)
    }

    private fun legacyInvert(source: Bitmap) = filtered(source, ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))

    private fun filtered(source: Bitmap, matrix: ColorMatrix) =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        }

    private fun withPrepared(
        source: Bitmap, orientation: AnalysisOrientation, legacy: Boolean,
        block: (List<OcrInput>, List<Bitmap>, Long) -> Unit,
    ) {
        val allocations = mutableListOf<Bitmap>()
        var bytes = 0L
        val resources = ScanTaskResources<Bitmap> { if (!it.isRecycled) it.recycle() }
        val own: (Bitmap) -> Bitmap = {
            allocations += it
            bytes += it.allocationByteCount
            resources.own(it)
        }
        try {
            val inputs = if (legacy) legacyInputs(source, orientation, own)
                else buildOrientationProbeInputs(CanonicalCardAnalysisVariant(orientation, source), own)
            block(inputs, allocations, bytes)
        } finally {
            resources.close()
            assertTrue(allocations.all(Bitmap::isRecycled))
            assertFalse(source.isRecycled)
        }
    }

    private fun withSource(width: Int, height: Int, block: (Bitmap) -> Unit) {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(213, 240, 194), Color.rgb(39, 58, 127), Shader.TileMode.CLAMP)
            }
            drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.color = Color.BLACK
            paint.textSize = width * .055f
            drawText("Pikachu ex", width * .06f, height * .12f, paint)
            paint.textSize = width * .042f
            drawText("PBL 094/084", width * .015f, height * .965f, paint)
        }
        try { block(source) } finally { source.recycle() }
    }
}
