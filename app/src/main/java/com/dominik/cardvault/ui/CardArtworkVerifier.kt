package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import kotlin.math.sqrt

internal enum class ArtworkMatch { UNAVAILABLE, INCONCLUSIVE, CORROBORATES, CONTRADICTS }
internal data class ArtworkVerification(val match: ArtworkMatch, val confidence: Float = 0f)

/**
 * Module 4. It compares only the normalized card artwork with the already narrowed reference
 * candidate. The captured image never leaves the device; only the public reference image is read.
 * The score is deliberately corroborative: a weak visual comparison cannot invent a match.
 */
internal object CardArtworkVerifier {
    suspend fun verify(captured: Bitmap, referenceUrl: String?): ArtworkVerification = withContext(Dispatchers.IO) {
        if (referenceUrl.isNullOrBlank()) return@withContext ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        val reference = download(referenceUrl) ?: return@withContext ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        compare(captured, reference)
    }

    internal fun compare(captured: Bitmap, reference: Bitmap): ArtworkVerification {
        // Exclude printed title/attacks and compare the dominant artwork area after Module 1 has
        // normalized the physical card. This remains tolerant of lighting but catches a clearly
        // unrelated illustration without using the camera background.
        val left = .08f; val top = .225f; val right = .92f; val bottom = .615f
        val a = signature(captured, left, top, right, bottom)
        val b = signature(reference, left, top, right, bottom)
        if (a.isEmpty() || b.isEmpty()) return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        var squaredDistance = 0.0
        a.indices.forEach { index ->
            val difference = a[index] - b[index]
            squaredDistance += difference * difference
        }
        val distance = sqrt(squaredDistance / a.size)
        val similarity = (1f - (distance / 175.0).toFloat()).coerceIn(0f, 1f)
        return when {
            similarity >= .74f -> ArtworkVerification(ArtworkMatch.CORROBORATES, similarity)
            similarity <= .42f -> ArtworkVerification(ArtworkMatch.CONTRADICTS, 1f - similarity)
            else -> ArtworkVerification(ArtworkMatch.INCONCLUSIVE, similarity)
        }
    }

    private fun signature(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (bitmap.width * right).toInt().coerceAtMost(bitmap.width)
        val y1 = (bitmap.height * bottom).toInt().coerceAtMost(bitmap.height)
        if (x1 <= x0 || y1 <= y0) return FloatArray(0)
        val columns = 12; val rows = 9
        return FloatArray(columns * rows * 3).also { values ->
            var index = 0
            for (row in 0 until rows) for (column in 0 until columns) {
                val x = x0 + ((column + .5f) * (x1 - x0) / columns).toInt().coerceIn(0, x1 - x0 - 1)
                val y = y0 + ((row + .5f) * (y1 - y0) / rows).toInt().coerceIn(0, y1 - y0 - 1)
                val pixel = bitmap.getPixel(x, y)
                values[index++] = ((pixel shr 16) and 0xff).toFloat()
                values[index++] = ((pixel shr 8) and 0xff).toFloat()
                values[index++] = (pixel and 0xff).toFloat()
            }
        }
    }

    private fun download(url: String): Bitmap? = runCatching {
        val connection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
            setRequestProperty("User-Agent", "DomiDex/1.0 scanner artwork verification")
        }
        try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.use(BitmapFactory::decodeStream)
        } finally { connection.disconnect() }
    }.getOrNull()
}
