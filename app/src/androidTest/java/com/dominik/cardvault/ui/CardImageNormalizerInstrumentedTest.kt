package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardImageNormalizerInstrumentedTest {
    @Test fun appliesAllEightExifTransformsToPixels() {
        val a = Color.rgb(255, 0, 0); val b = Color.rgb(0, 255, 0)
        val c = Color.rgb(0, 0, 255); val d = Color.rgb(255, 255, 0)
        val e = Color.rgb(0, 255, 255); val f = Color.rgb(255, 0, 255)
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to listOf(listOf(a, b), listOf(c, d), listOf(e, f)),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(listOf(b, a), listOf(d, c), listOf(f, e)),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(listOf(f, e), listOf(d, c), listOf(b, a)),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(listOf(e, f), listOf(c, d), listOf(a, b)),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(listOf(a, c, e), listOf(b, d, f)),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(listOf(e, c, a), listOf(f, d, b)),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(listOf(f, d, b), listOf(e, c, a)),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(listOf(b, d, f), listOf(a, c, e)),
        )
        expected.forEach { (orientation, rows) ->
            val source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, a); setPixel(1, 0, b)
                setPixel(0, 1, c); setPixel(1, 1, d)
                setPixel(0, 2, e); setPixel(1, 2, f)
            }
            val transformed = CardImageNormalizer.applyExifDisplayOrientation(source, orientation)
            assertEquals(rows.first().size, transformed.width)
            assertEquals(rows.size, transformed.height)
            rows.forEachIndexed { y, row -> row.forEachIndexed { x, color ->
                assertEquals("EXIF $orientation at $x,$y", color, transformed.getPixel(x, y))
            } }
            transformed.recycle()
        }
    }

    @Test fun normalizesPlausibleCardFrameOnDevice() {
        val source = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(20, 22, 28))
        val card = Path().apply {
            moveTo(165f, 135f); lineTo(748f, 160f); lineTo(705f, 975f); lineTo(125f, 945f); close()
        }
        canvas.drawPath(card, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(91, 156, 71) })
        canvas.drawPath(card, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 16f })
        repeat(36) { row -> canvas.drawLine(190f, 235f + row * 18f, 680f, 235f + row * 18f, Paint().apply { color = Color.rgb(25, 70, 28); strokeWidth = 6f }) }
        repeat(22) { column -> canvas.drawLine(205f + column * 21f, 225f, 205f + column * 21f, 865f, Paint().apply { color = Color.rgb(130, 190, 90); strokeWidth = 4f }) }

        withNormalizedCapture(source) { result ->
            assertNotNull("normalization failed: ${result.quality}", result.bitmap)
            assertTrue(result.quality.cardDetected)
            assertTrue(result.quality.frameConfidence >= CardScanConfiguration.MIN_FRAME_SCORE)
            assertTrue(result.bitmap!!.width == CardScanConfiguration.NORMALIZED_WIDTH)
            assertTrue(result.bitmap!!.height == CardScanConfiguration.NORMALIZED_HEIGHT)
        }
    }

    @Test fun rejectsImageWithoutPlausibleCardFrameOnDevice() {
        val background = Bitmap.createBitmap(720, 1_000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(21, 22, 25)) }
        withNormalizedCapture(background) { result ->
            assertNull(result.bitmap)
            assertTrue(!result.quality.cardDetected || result.quality.reason.isNotBlank())
        }
    }

    @Test fun rejectsTexturedFabricWithoutPhysicalCard() {
        val background = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(background)
        canvas.drawColor(Color.rgb(30, 48, 82))
        repeat(150) { column ->
            canvas.drawRect(
                column * 6f,
                0f,
                column * 6f + 2f,
                1_200f,
                Paint().apply { color = if (column % 3 == 0) Color.rgb(47, 66, 105) else Color.rgb(25, 43, 75) },
            )
        }
        repeat(7) { fold ->
            canvas.drawLine(0f, 130f + fold * 155f, 900f, 165f + fold * 155f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(60, 77, 112); strokeWidth = 13f
            })
        }

        withNormalizedCapture(background) { result ->
            assertNull("fabric texture became a false card: ${result.quality}", result.bitmap)
        }
    }

    @Test fun holoHighlightDoesNotInvalidateAnOtherwisePlausibleSpecialCard() {
        val source = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(16, 17, 23))
        val card = Path().apply {
            moveTo(150f, 125f); lineTo(755f, 150f); lineTo(720f, 1_020f); lineTo(118f, 980f); close()
        }
        canvas.drawPath(card, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 30, 188) })
        canvas.drawPath(card, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 15f })
        repeat(18) { index ->
            canvas.drawCircle(250f + (index % 6) * 80f, 260f + (index / 6) * 190f, 55f, Paint().apply { color = Color.rgb(30 + index * 8, 190 - index * 5, 210) })
        }
        // Local foil reflection: significant but far from covering the card.
        canvas.drawOval(430f, 300f, 690f, 610f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        withNormalizedCapture(source) { result ->
            assertNotNull("holo normalization failed: ${result.quality}", result.bitmap)
            assertTrue(result.quality.cardDetected)
            assertTrue(result.quality.glare > 0f)
            assertEquals(2, result.analysisVariants.size)
        }
    }

    @Test fun landscapeCardProducesOnlyAspectCorrectCanonicalCandidates() {
        val source = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(18, 20, 27))
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(72, 106, 152) }
        canvas.drawRect(215f, 175f, 985f, 725f, cardPaint)
        // DomiDex's scanner guidance keeps the printed top on the source's left edge.
        canvas.drawRect(215f, 175f, 305f, 725f, Paint().apply { color = Color.rgb(220, 35, 48) })
        canvas.drawRect(895f, 175f, 985f, 725f, Paint().apply { color = Color.rgb(25, 55, 215) })
        canvas.drawRect(215f, 175f, 985f, 725f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 14f
        })
        repeat(28) { index ->
            canvas.drawLine(340f + index * 18f, 215f, 340f + index * 18f, 685f, Paint().apply {
                color = if (index % 2 == 0) Color.rgb(125, 170, 205) else Color.rgb(42, 72, 114)
                strokeWidth = 5f
            })
        }
        repeat(24) { row -> repeat(28) { column ->
            canvas.drawRect(
                345f + column * 18f,
                220f + row * 18f,
                353f + column * 18f,
                228f + row * 18f,
                Paint().apply { color = if ((row + column) % 2 == 0) Color.WHITE else Color.BLACK },
            )
        } }

        withNormalizedCapture(source) { result ->
            assertNotNull("landscape card was rejected: ${result.quality}", result.bitmap)
            assertEquals(
                listOf(AnalysisOrientation.DEG_90, AnalysisOrientation.DEG_270),
                result.analysisVariants.map(CanonicalCardAnalysisVariant::orientation),
            )
            assertTrue(result.analysisVariants.all {
                it.bitmap.width == CardScanConfiguration.RECOGNITION_WIDTH &&
                    it.bitmap.height == CardScanConfiguration.RECOGNITION_HEIGHT
            })
        }
    }

    @Test fun completesSmallInterruptionsInLowContrastOuterBorder() {
        val source = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val background = Color.rgb(31, 43, 58)
        canvas.drawColor(background)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(92, 112, 137); strokeWidth = 8f }
        val left = 155f; val top = 110f; val right = 745f; val bottom = 1_020f; val gap = 7f
        canvas.drawLine(left + gap, top, right - gap, top, border)
        canvas.drawLine(right, top + gap, right, bottom - gap, border)
        canvas.drawLine(right - gap, bottom, left + gap, bottom, border)
        canvas.drawLine(left, bottom - gap, left, top + gap, border)
        repeat(30) { row -> canvas.drawLine(205f, 205f + row * 22f, 695f, 205f + row * 22f, Paint().apply {
            color = if (row % 2 == 0) Color.rgb(55, 78, 103) else Color.rgb(39, 57, 77); strokeWidth = 4f
        }) }
        repeat(42) { row -> repeat(25) { column ->
            canvas.drawRect(
                210f + column * 19f,
                145f + row * 19f,
                218f + column * 19f,
                153f + row * 19f,
                Paint().apply { color = if ((row + column) % 2 == 0) Color.rgb(105, 125, 148) else Color.rgb(22, 31, 43) },
            )
        } }

        withNormalizedCapture(source) { result ->
            assertNotNull("interrupted foil-like border was not completed: ${result.quality}", result.bitmap)
            assertTrue(result.quality.cardDetected)
        }
    }

    private inline fun withNormalizedCapture(source: Bitmap, verify: (NormalizedCardCapture) -> Unit) {
        var result: NormalizedCardCapture? = null
        try {
            val capture = CardImageNormalizer.normalize(source)
            result = capture
            verify(capture)
        } finally {
            result?.let { capture ->
                (listOfNotNull(capture.bitmap, capture.recognitionBitmap, capture.originalCrop) +
                    capture.analysisVariants.map(CanonicalCardAnalysisVariant::bitmap))
                    .distinct()
                    .forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            }
            if (!source.isRecycled) source.recycle()
        }
    }
}
