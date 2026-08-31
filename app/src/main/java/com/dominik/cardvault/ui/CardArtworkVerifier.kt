package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.LinkedHashMap
import kotlin.math.sqrt

internal enum class ArtworkMatch { UNAVAILABLE, INCONCLUSIVE, CORROBORATES, CONTRADICTS }
internal data class ArtworkVerification(
    val match: ArtworkMatch,
    /** Strength of the conclusion shown in diagnostics. */
    val confidence: Float = 0f,
    /** Raw visual similarity, always 0..1 even for a contradiction. */
    val similarity: Float = 0f,
)

internal data class ArtworkDescriptor(
    val version: Int,
    val normalizedLuma: FloatArray,
    val colors: FloatArray,
    val differenceHash: Long,
)

/**
 * Module 4. It compares only the normalized artwork area after catalogue reduction. The compact
 * descriptor combines structure, perceptual edge hash and colour. Reference descriptors are kept
 * in a small bounded session LRU, so repeated/reprint scans neither retain bitmaps nor download and
 * recalculate the same reference on every card.
 */
internal object CardArtworkVerifier {
    const val DESCRIPTOR_VERSION = 2
    private const val MAX_REFERENCE_CACHE = 96
    private val referenceCache = object : LinkedHashMap<String, ArtworkDescriptor>(MAX_REFERENCE_CACHE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkDescriptor>?) = size > MAX_REFERENCE_CACHE
    }

    suspend fun verify(captured: Bitmap, referenceUrl: String?): ArtworkVerification =
        verify(describe(captured), referenceUrl)

    suspend fun verify(captured: ArtworkDescriptor, referenceUrl: String?): ArtworkVerification = withContext(Dispatchers.IO) {
        if (referenceUrl.isNullOrBlank()) return@withContext ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        val reference = synchronized(referenceCache) { referenceCache[referenceUrl] } ?: run {
            val downloaded = download(referenceUrl) ?: return@withContext ArtworkVerification(ArtworkMatch.UNAVAILABLE)
            val descriptor = try { describe(downloaded) } finally { downloaded.recycle() }
            synchronized(referenceCache) { referenceCache[referenceUrl] = descriptor }
            descriptor
        }
        compare(captured, reference)
    }

    internal fun compare(captured: Bitmap, reference: Bitmap): ArtworkVerification = compare(describe(captured), describe(reference))

    internal fun compare(captured: ArtworkDescriptor, reference: ArtworkDescriptor): ArtworkVerification {
        if (captured.version != reference.version || captured.normalizedLuma.isEmpty() || reference.normalizedLuma.isEmpty()) {
            return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        }
        val structure = cosineSimilarity(captured.normalizedLuma, reference.normalizedLuma)
        val hash = 1f - java.lang.Long.bitCount(captured.differenceHash xor reference.differenceHash) / 64f
        var colorDistance = 0.0
        captured.colors.indices.forEach { index ->
            val delta = captured.colors[index] - reference.colors[index]
            colorDistance += delta * delta
        }
        val color = (1f - sqrt(colorDistance / captured.colors.size.coerceAtLeast(1)).toFloat()).coerceIn(0f, 1f)
        val similarity = (structure * .48f + hash * .34f + color * .18f).coerceIn(0f, 1f)
        return when {
            similarity >= .78f -> ArtworkVerification(ArtworkMatch.CORROBORATES, similarity, similarity)
            similarity <= .43f -> ArtworkVerification(ArtworkMatch.CONTRADICTS, 1f - similarity, similarity)
            else -> ArtworkVerification(ArtworkMatch.INCONCLUSIVE, similarity, similarity)
        }
    }

    internal fun describe(bitmap: Bitmap): ArtworkDescriptor {
        // Title, attacks and lower metadata are excluded. Module 1 has already removed the camera
        // background and normalised the physical card before this method is called.
        val left = .08f; val top = .225f; val right = .92f; val bottom = .615f
        val columns = 12; val rows = 9
        val luma = FloatArray(columns * rows)
        val colors = FloatArray(columns * rows * 3)
        var lumaIndex = 0; var colorIndex = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val pixel = sample(bitmap, left, top, right, bottom, column + .5f, row + .5f, columns, rows)
            val red = ((pixel shr 16) and 0xff) / 255f
            val green = ((pixel shr 8) and 0xff) / 255f
            val blue = (pixel and 0xff) / 255f
            luma[lumaIndex++] = red * .299f + green * .587f + blue * .114f
            // Chroma is more tolerant of exposure than absolute RGB.
            colors[colorIndex++] = (red - green + 1f) / 2f
            colors[colorIndex++] = (green - blue + 1f) / 2f
            colors[colorIndex++] = (blue - red + 1f) / 2f
        }
        val mean = luma.average().toFloat()
        val variance = luma.sumOf { value -> val delta = value - mean; (delta * delta).toDouble() }.toFloat() / luma.size.coerceAtLeast(1)
        val deviation = sqrt(variance.coerceAtLeast(.0001f))
        val normalized = FloatArray(luma.size) { index -> (luma[index] - mean) / deviation }

        var hash = 0L
        for (row in 0 until 8) for (column in 0 until 8) {
            val first = sampleLuma(bitmap, left, top, right, bottom, column + .5f, row + .5f, 9, 8)
            val second = sampleLuma(bitmap, left, top, right, bottom, column + 1.5f, row + .5f, 9, 8)
            if (first < second) hash = hash or (1L shl (row * 8 + column))
        }
        return ArtworkDescriptor(DESCRIPTOR_VERSION, normalized, colors, hash)
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return 0f
        var dot = 0.0; var leftLength = 0.0; var rightLength = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftLength += left[index] * left[index]
            rightLength += right[index] * right[index]
        }
        if (leftLength <= 0.0 || rightLength <= 0.0) return 0f
        return ((dot / sqrt(leftLength * rightLength)).toFloat() + 1f).div(2f).coerceIn(0f, 1f)
    }

    private fun sampleLuma(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float, column: Float, row: Float, columns: Int, rows: Int): Float {
        val pixel = sample(bitmap, left, top, right, bottom, column, row, columns, rows)
        return (((pixel shr 16) and 0xff) * .299f + ((pixel shr 8) and 0xff) * .587f + (pixel and 0xff) * .114f) / 255f
    }

    private fun sample(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float, column: Float, row: Float, columns: Int, rows: Int): Int {
        val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val width = (bitmap.width * (right - left)).toInt().coerceAtLeast(1)
        val height = (bitmap.height * (bottom - top)).toInt().coerceAtLeast(1)
        val x = (x0 + column * width / columns).toInt().coerceIn(0, bitmap.width - 1)
        val y = (y0 + row * height / rows).toInt().coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(x, y)
    }

    private fun download(url: String): Bitmap? = runCatching {
        val connection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
            useCaches = true
            setRequestProperty("User-Agent", "DomiDex/1.0 scanner artwork verification")
        }
        try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.use(BitmapFactory::decodeStream)
        } finally { connection.disconnect() }
    }.getOrNull()
}
