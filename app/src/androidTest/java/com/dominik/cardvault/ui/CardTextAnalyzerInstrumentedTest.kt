package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardTextAnalyzerInstrumentedTest {
    @Test fun bundledLatinModelReadsCardLikeNameAndNumberLocally() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageFile = File(context.cacheDir, "ocr-fixture.png")
        val bitmap = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(20, 22, 28))
            drawRect(135f, 140f, 765f, 1_020f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 235, 205) })
            drawRect(135f, 140f, 765f, 1_020f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 18f })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
            drawText("Pikachu ex", 185f, 285f, paint)
            drawText("PBL 122/088", 185f, 930f, paint)
        }
        FileOutputStream(imageFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<CardTextAnalysis>>()
        CardTextAnalyzer(context).use { analyzer ->
            analyzer.analyze(imageFile) { result.set(it); latch.countDown() }
            assertTrue("Local OCR did not finish", latch.await(30, TimeUnit.SECONDS))
        }
        imageFile.delete()

        val analysis = result.get().getOrThrow()
        assertTrue("OCR lines: ${analysis.lines}", analysis.lines.any { it.contains("Pikachu", ignoreCase = true) })
        assertTrue("OCR number: ${analysis.collectorNumber}; lines: ${analysis.lines}", analysis.collectorNumber?.replace(" ", "") == "122/088")
    }
}
