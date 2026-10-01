package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

internal const val MAX_REFERENCE_ARTWORK_BYTES = 8 * 1024 * 1024
internal const val MAX_REFERENCE_ARTWORK_EDGE = 1_400
internal const val ARTWORK_BATCH_TIMEOUT_MILLIS = 7_000L

/** Deduplicate variants sharing artwork, bound network concurrency, preserve every candidate key. */
internal suspend fun verifyArtworkCandidates(
    captured: List<ArtworkDescriptor>,
    references: Map<String, String?>,
    loadReference: suspend (String) -> ArtworkDescriptor?,
): Map<String, ArtworkVerification> {
    val limiter = Semaphore(2)
    val results = java.util.concurrent.ConcurrentHashMap<String, ArtworkVerification>()
    withTimeoutOrNull(ARTWORK_BATCH_TIMEOUT_MILLIS) {
        coroutineScope {
            references.values.filterNotNull().filter(String::isNotBlank).distinct().map { url ->
                async {
                    limiter.withPermit {
                        val reference = loadReference(url)
                        results[url] = if (reference == null) ArtworkVerification(ArtworkMatch.UNAVAILABLE)
                        else CardArtworkVerifier.combineTemporal(captured.map { CardArtworkVerifier.compare(it, reference) })
                    }
                }
            }.awaitAll()
        }
    }
    return references.mapValues { (_, url) -> url?.let(results::get) ?: ArtworkVerification(ArtworkMatch.UNAVAILABLE) }
}

internal fun referenceArtworkDecodeSample(width: Int, height: Int): Int =
    decodeSampleForLargestEdge(maxOf(width, height).coerceAtLeast(1), MAX_REFERENCE_ARTWORK_EDGE)

internal fun readBoundedReferenceArtwork(
    input: InputStream,
    maxBytes: Int = MAX_REFERENCE_ARTWORK_BYTES,
): ByteArray? {
    if (maxBytes <= 0) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal enum class ArtworkMatch { UNAVAILABLE, INCONCLUSIVE, CORROBORATES, CONTRADICTS }

internal data class ArtworkVerification(
    val match: ArtworkMatch,
    val confidence: Float = 0f,
    val similarity: Float = 0f,
    val fullCardSimilarity: Float = 0f,
    val artworkSimilarity: Float = 0f,
    val glarePercentage: Float = 0f,
)

internal data class ArtworkRegionDescriptor(
    val id: String,
    val normalizedLuma: FloatArray,
    val colors: FloatArray,
    val sampleWeights: FloatArray,
    val differenceHash: Long,
    val differenceHashMask: Long,
)

internal data class ArtworkDescriptor(
    val version: Int,
    val regions: List<ArtworkRegionDescriptor>,
    val glarePercentage: Float,
)

/**
 * Multi-crop visual verification for classic and full-art layouts. Locally clipped holo
 * highlights receive a low sample weight instead of turning one reflection into a contradiction.
 */
internal object CardArtworkVerifier {
    const val DESCRIPTOR_VERSION = 3
    private const val MAX_REFERENCE_CACHE = 96
    private data class Region(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val fusionWeight: Float)
    private val regions = listOf(
        Region("full_card", .035f, .035f, .965f, .965f, .28f),
        Region("upper_center", .070f, .070f, .930f, .490f, .16f),
        Region("artwork", .060f, .200f, .940f, .680f, .28f),
        Region("without_lower_print", .045f, .045f, .955f, .825f, .28f),
    )
    private val referenceCache = object : LinkedHashMap<String, ArtworkDescriptor>(MAX_REFERENCE_CACHE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkDescriptor>?) = size > MAX_REFERENCE_CACHE
    }
    private val downloads = Semaphore(2)
    private val artworkClient = OkHttpClient.Builder()
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(2_000, TimeUnit.MILLISECONDS)
        .callTimeout(3_000, TimeUnit.MILLISECONDS)
        .build()

    suspend fun verify(captured: Bitmap, referenceUrl: String?): ArtworkVerification = verify(describe(captured), referenceUrl)

    suspend fun verify(captured: ArtworkDescriptor, referenceUrl: String?): ArtworkVerification {
        if (referenceUrl.isNullOrBlank()) return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        val reference = referenceDescriptor(referenceUrl) ?: return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        return compare(captured, reference)
    }

    suspend fun verifyCandidates(captured: List<ArtworkDescriptor>, references: Map<String, String?>): Map<String, ArtworkVerification> =
        verifyArtworkCandidates(captured, references, ::referenceDescriptor)

    private suspend fun referenceDescriptor(url: String): ArtworkDescriptor? {
        synchronized(referenceCache) { referenceCache[url] }?.let { return it }
        return downloads.withPermit {
            synchronized(referenceCache) { referenceCache[url] }?.let { return@withPermit it }
            val encoded = download(url) ?: return@withPermit null
            withContext(Dispatchers.Default) {
                coroutineContext.ensureActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply {
                    inSampleSize = referenceArtworkDecodeSample(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: return@withContext null
                try {
                    coroutineContext.ensureActive()
                    describe(bitmap).also { descriptor -> synchronized(referenceCache) { referenceCache[url] = descriptor } }
                } finally { bitmap.recycle() }
            }
        }
    }

    internal fun compare(captured: Bitmap, reference: Bitmap): ArtworkVerification = compare(describe(captured), describe(reference))

    internal fun combineTemporal(results: List<ArtworkVerification>): ArtworkVerification {
        if (results.isEmpty()) return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        val available = results.filter { it.match != ArtworkMatch.UNAVAILABLE }
        if (available.isEmpty()) return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        val corroborating = available.filter { it.match == ArtworkMatch.CORROBORATES }
        val contradicting = available.filter { it.match == ArtworkMatch.CONTRADICTS }
        val best = available.maxByOrNull(ArtworkVerification::similarity)!!
        if (corroborating.isNotEmpty() && contradicting.isNotEmpty()) {
            val average = available.map(ArtworkVerification::similarity).average().toFloat()
            return best.copy(match = ArtworkMatch.INCONCLUSIVE, confidence = average, similarity = average)
        }
        if (corroborating.isNotEmpty()) return corroborating.maxByOrNull(ArtworkVerification::similarity)!!
        if (contradicting.size == available.size) return contradicting.maxByOrNull(ArtworkVerification::confidence)!!
        return best.copy(match = ArtworkMatch.INCONCLUSIVE)
    }

    internal fun compare(captured: ArtworkDescriptor, reference: ArtworkDescriptor): ArtworkVerification {
        if (captured.version != reference.version || captured.regions.isEmpty() || reference.regions.isEmpty()) {
            return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        }
        val referenceById = reference.regions.associateBy(ArtworkRegionDescriptor::id)
        val scores = captured.regions.mapNotNull { left ->
            val right = referenceById[left.id] ?: return@mapNotNull null
            val region = regions.firstOrNull { it.id == left.id } ?: return@mapNotNull null
            compareRegion(left, right)?.let { result -> Triple(region, result.first, result.second) }
        }
        if (scores.isEmpty()) return ArtworkVerification(ArtworkMatch.UNAVAILABLE)
        val reliable = scores.filter { it.third >= .28f }
        if (reliable.isEmpty()) return ArtworkVerification(ArtworkMatch.UNAVAILABLE, glarePercentage = captured.glarePercentage)
        val weightSum = reliable.sumOf { (region, _, reliability) -> (region.fusionWeight * reliability).toDouble() }.toFloat().coerceAtLeast(.001f)
        val weightedMean = reliable.sumOf { (region, score, reliability) -> (score * region.fusionWeight * reliability).toDouble() }.toFloat() / weightSum
        val topTwo = reliable.map { it.second }.sortedDescending().take(2)
        val robustPair = if (topTwo.size == 2) topTwo.average().toFloat() * .94f else weightedMean
        val similarity = maxOf(weightedMean, robustPair).coerceIn(0f, 1f)
        val fullCard = scores.firstOrNull { it.first.id == "full_card" }?.second ?: 0f
        val artwork = scores.firstOrNull { it.first.id == "artwork" }?.second ?: 0f
        val strongRegions = reliable.count { it.second >= .72f }
        val weakRegions = reliable.count { it.second <= .42f }
        val match = when {
            similarity >= .78f && strongRegions >= 2 -> ArtworkMatch.CORROBORATES
            similarity <= .40f && weakRegions >= 2 -> ArtworkMatch.CONTRADICTS
            else -> ArtworkMatch.INCONCLUSIVE
        }
        val conclusionConfidence = if (match == ArtworkMatch.CONTRADICTS) 1f - similarity else similarity
        return ArtworkVerification(match, conclusionConfidence, similarity, fullCard, artwork, captured.glarePercentage)
    }

    internal fun describe(bitmap: Bitmap): ArtworkDescriptor {
        var clipped = 0
        var total = 0
        val descriptors = regions.map { region ->
            val descriptor = describeRegion(bitmap, region)
            clipped += descriptor.sampleWeights.count { it < .25f }
            total += descriptor.sampleWeights.size
            descriptor
        }
        return ArtworkDescriptor(DESCRIPTOR_VERSION, descriptors, clipped.toFloat() / total.coerceAtLeast(1))
    }

    private fun describeRegion(bitmap: Bitmap, region: Region): ArtworkRegionDescriptor {
        val columns = 12; val rows = 9
        val luma = FloatArray(columns * rows)
        val colors = FloatArray(columns * rows * 3)
        val weights = FloatArray(columns * rows)
        var lumaIndex = 0; var colorIndex = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val pixel = sample(bitmap, region, column + .5f, row + .5f, columns, rows)
            val red = ((pixel shr 16) and 0xff) / 255f
            val green = ((pixel shr 8) and 0xff) / 255f
            val blue = (pixel and 0xff) / 255f
            val value = red * .299f + green * .587f + blue * .114f
            luma[lumaIndex] = value
            weights[lumaIndex] = when {
                value >= .965f -> .12f
                value >= .910f -> .48f
                value <= .025f -> .35f
                else -> 1f
            }
            lumaIndex += 1
            colors[colorIndex++] = (red - green + 1f) / 2f
            colors[colorIndex++] = (green - blue + 1f) / 2f
            colors[colorIndex++] = (blue - red + 1f) / 2f
        }
        val weightSum = weights.sum().coerceAtLeast(.001f)
        val mean = luma.indices.sumOf { (luma[it] * weights[it]).toDouble() }.toFloat() / weightSum
        val variance = luma.indices.sumOf { index ->
            val delta = luma[index] - mean
            (delta * delta * weights[index]).toDouble()
        }.toFloat() / weightSum
        val deviation = sqrt(variance.coerceAtLeast(.0001f))
        val normalized = FloatArray(luma.size) { index -> (luma[index] - mean) / deviation }

        var hash = 0L; var hashMask = 0L
        for (row in 0 until 8) for (column in 0 until 8) {
            val first = sampleLuma(bitmap, region, column + .5f, row + .5f, 9, 8)
            val second = sampleLuma(bitmap, region, column + 1.5f, row + .5f, 9, 8)
            val bit = row * 8 + column
            if (first < .94f && second < .94f) hashMask = hashMask or (1L shl bit)
            if (first < second) hash = hash or (1L shl bit)
        }
        return ArtworkRegionDescriptor(region.id, normalized, colors, weights, hash, hashMask)
    }

    /** Pair(score, reliable sample fraction). */
    private fun compareRegion(left: ArtworkRegionDescriptor, right: ArtworkRegionDescriptor): Pair<Float, Float>? {
        if (left.normalizedLuma.size != right.normalizedLuma.size || left.colors.size != right.colors.size) return null
        val weights = FloatArray(left.sampleWeights.size) { index -> minOf(left.sampleWeights[index], right.sampleWeights[index]) }
        val weightSum = weights.sum()
        val reliability = weightSum / weights.size.coerceAtLeast(1)
        if (weightSum <= .001f) return null
        var dot = 0.0; var leftLength = 0.0; var rightLength = 0.0
        left.normalizedLuma.indices.forEach { index ->
            val weight = weights[index]
            dot += left.normalizedLuma[index] * right.normalizedLuma[index] * weight
            leftLength += left.normalizedLuma[index] * left.normalizedLuma[index] * weight
            rightLength += right.normalizedLuma[index] * right.normalizedLuma[index] * weight
        }
        val structure = if (leftLength <= 0.0 || rightLength <= 0.0) .5f
        else (((dot / sqrt(leftLength * rightLength)).toFloat() + 1f) / 2f).coerceIn(0f, 1f)
        val validHashMask = left.differenceHashMask and right.differenceHashMask
        val validHashBits = java.lang.Long.bitCount(validHashMask)
        val hash = if (validHashBits < 12) .5f else {
            val differences = java.lang.Long.bitCount((left.differenceHash xor right.differenceHash) and validHashMask)
            1f - differences.toFloat() / validHashBits
        }
        var colorDistance = 0.0; var colorWeight = 0.0
        left.normalizedLuma.indices.forEach { sample ->
            val weight = weights[sample]
            repeat(3) { component ->
                val index = sample * 3 + component
                val delta = left.colors[index] - right.colors[index]
                colorDistance += delta * delta * weight
                colorWeight += weight
            }
        }
        val color = (1f - sqrt(colorDistance / colorWeight.coerceAtLeast(.001)).toFloat()).coerceIn(0f, 1f)
        return (structure * .50f + hash * .32f + color * .18f).coerceIn(0f, 1f) to reliability
    }

    private fun sampleLuma(bitmap: Bitmap, region: Region, column: Float, row: Float, columns: Int, rows: Int): Float {
        val pixel = sample(bitmap, region, column, row, columns, rows)
        return (((pixel shr 16) and 0xff) * .299f + ((pixel shr 8) and 0xff) * .587f + (pixel and 0xff) * .114f) / 255f
    }

    private fun sample(bitmap: Bitmap, region: Region, column: Float, row: Float, columns: Int, rows: Int): Int {
        val x0 = (bitmap.width * region.left).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (bitmap.height * region.top).toInt().coerceIn(0, bitmap.height - 1)
        val width = (bitmap.width * (region.right - region.left)).toInt().coerceAtLeast(1)
        val height = (bitmap.height * (region.bottom - region.top)).toInt().coerceAtLeast(1)
        val x = (x0 + column * width / columns).toInt().coerceIn(0, bitmap.width - 1)
        val y = (y0 + row * height / rows).toInt().coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(x, y)
    }

    private suspend fun download(url: String): ByteArray? = downloadReferenceArtwork(url, artworkClient)
}
