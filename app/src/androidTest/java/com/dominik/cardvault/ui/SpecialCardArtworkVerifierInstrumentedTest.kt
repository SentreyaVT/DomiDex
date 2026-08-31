package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpecialCardArtworkVerifierInstrumentedTest {
    @Test fun localHoloReflectionIsDownWeightedAcrossMultipleCrops() {
        val reference = specialArtBitmap(withGlare = false)
        val captured = specialArtBitmap(withGlare = true)

        val verification = CardArtworkVerifier.compare(captured, reference)

        assertEquals(ArtworkMatch.CORROBORATES, verification.match)
        assertTrue(verification.similarity >= .78f)
        assertTrue(verification.glarePercentage > 0f)
    }

    private fun specialArtBitmap(withGlare: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(630, 880, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(56, 13, 91))
        repeat(12) { index ->
            canvas.drawCircle(90f + (index % 4) * 150f, 130f + (index / 4) * 230f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(25 + index * 15, 210 - index * 9, 180 + index * 5)
            })
        }
        canvas.drawRect(35f, 760f, 595f, 840f, Paint().apply { color = Color.rgb(25, 20, 42) })
        if (withGlare) canvas.drawOval(355f, 180f, 590f, 465f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bitmap
    }
}
