package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardImageNormalizerInstrumentedTest {
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

        val result = CardImageNormalizer.normalize(source)

        assertNotNull("normalization failed: ${result.quality}", result.bitmap)
        assertTrue(result.quality.cardDetected)
        assertTrue(result.quality.frameConfidence >= CardScanConfiguration.MIN_FRAME_SCORE)
        assertTrue(result.bitmap!!.width == CardScanConfiguration.NORMALIZED_WIDTH)
        assertTrue(result.bitmap!!.height == CardScanConfiguration.NORMALIZED_HEIGHT)
    }

    @Test fun rejectsImageWithoutPlausibleCardFrameOnDevice() {
        val background = Bitmap.createBitmap(720, 1_000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(21, 22, 25)) }
        val result = CardImageNormalizer.normalize(background)

        assertNull(result.bitmap)
        assertTrue(!result.quality.cardDetected || result.quality.reason.isNotBlank())
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

        val result = CardImageNormalizer.normalize(source)

        assertNotNull("holo normalization failed: ${result.quality}", result.bitmap)
        assertTrue(result.quality.cardDetected)
        assertTrue(result.quality.glare > 0f)
        assertTrue(result.recognitionBitmap != null)
    }
}
