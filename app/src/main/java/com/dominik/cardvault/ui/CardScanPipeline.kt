package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Central, deliberately conservative scan thresholds. */
internal object CardScanConfiguration {
    const val CARD_RATIO = 63f / 88f
    const val MIN_CARD_COVERAGE = .055f
    const val MAX_CARD_COVERAGE = .92f
    const val MIN_FRAME_SCORE = .52f
    const val MIN_SHARPNESS = .12f
    const val MIN_EXPOSURE = .28f
    const val MAX_GLARE = .27f
    const val NORMALIZED_WIDTH = 630
    const val NORMALIZED_HEIGHT = 880
}

internal data class CardImageQuality(
    val cardDetected: Boolean = false,
    val frameConfidence: Float = 0f,
    val sharpness: Float = 0f,
    val exposure: Float = 0f,
    val glare: Float = 0f,
    val perspective: Float = 0f,
    val reason: String = "",
)

/** ROIs are proportions of a perspective-corrected card, never camera pixels. */
internal data class CardRoi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun crop(source: Bitmap): Bitmap {
        val x = (source.width * left).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * top).toInt().coerceIn(0, source.height - 1)
        val width = (source.width * (right - left)).toInt().coerceAtLeast(1).coerceAtMost(source.width - x)
        val height = (source.height * (bottom - top)).toInt().coerceAtLeast(1).coerceAtMost(source.height - y)
        return Bitmap.createBitmap(source, x, y, width, height)
    }
}

internal data class CardOcrLayoutProfile(
    val id: String,
    val name: CardRoi,
    val set: CardRoi,
    val number: CardRoi,
)

internal object CardOcrRois {
    /**
     * Layout candidates are relative to Module 1's perspective-corrected card, not to the camera
     * photo. They deliberately overlap at the lower identification band: older borders and modern
     * full-art layouts move the printed number slightly, while attack text remains excluded.
     */
    val profiles = listOf(
        CardOcrLayoutProfile("modern", CardRoi(.055f, .055f, .945f, .245f), CardRoi(.035f, .805f, .500f, .985f), CardRoi(.180f, .805f, .735f, .985f)),
        CardOcrLayoutProfile("legacy", CardRoi(.050f, .045f, .950f, .285f), CardRoi(.030f, .755f, .530f, .965f), CardRoi(.130f, .755f, .760f, .965f)),
        CardOcrLayoutProfile("extended_lower_border", CardRoi(.050f, .050f, .950f, .270f), CardRoi(.025f, .830f, .565f, .995f), CardRoi(.105f, .830f, .805f, .995f)),
    )
    val name get() = profiles.first().name
    val set get() = profiles.first().set
    val number get() = profiles.first().number
}

internal enum class CardOcrRegion { NAME, SET, NUMBER }

/** Diagnostic-only raw OCR retained for the current scan session, never uploaded or persisted. */
internal data class CardOcrEvidence(
    val region: CardOcrRegion,
    val rawValues: List<String>,
    val normalizedValue: String? = null,
    val confidence: Float = 0f,
)

internal data class NormalizedCardCapture(
    val bitmap: Bitmap?,
    val quality: CardImageQuality,
    /** Normalized (0..1) corners in TL, TR, BR, BL order for debug diagnostics. */
    val corners: List<Pair<Float, Float>> = emptyList(),
)

/**
 * Module 1. It locates a card-shaped quadrilateral, rejects implausible rectangles, corrects
 * EXIF rotation and perspective, then produces the one normalized image used by all later stages.
 * It never falls back to whole-photo OCR when the physical-card gate fails.
 */
internal object CardImageNormalizer {
    private val openCvAvailable by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    fun normalize(file: File): NormalizedCardCapture {
        val source = decodeUpright(file) ?: return NormalizedCardCapture(null, CardImageQuality(reason = "image_decode_failed"))
        return normalize(source)
    }

    fun normalize(source: Bitmap): NormalizedCardCapture {
        if (!openCvAvailable) return NormalizedCardCapture(null, CardImageQuality(reason = "opencv_unavailable"))
        val sourceMat = Mat()
        val gray = Mat()
        val normalized = Mat()
        val blurred = Mat()
        val edges = Mat()
        val adaptive = Mat()
        val hierarchy = Mat()
        try {
            Utils.bitmapToMat(source, sourceMat)
            Imgproc.cvtColor(sourceMat, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, normalized)
            clahe.collectGarbage()
            Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 28.0, 94.0)
            Imgproc.adaptiveThreshold(blurred, adaptive, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 7.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(edges, edges, kernel)
            Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            val candidate = listOf(edges, adaptive)
                .mapNotNull { map -> findBestQuad(map, hierarchy, source.width, source.height) }
                .maxByOrNull { it.score }
                ?: return NormalizedCardCapture(null, CardImageQuality(reason = "no_plausible_card_frame"))
            if (candidate.score < CardScanConfiguration.MIN_FRAME_SCORE || candidate.coverage !in CardScanConfiguration.MIN_CARD_COVERAGE..CardScanConfiguration.MAX_CARD_COVERAGE) {
                return NormalizedCardCapture(null, CardImageQuality(cardDetected = true, frameConfidence = candidate.score, perspective = candidate.perspective, reason = "implausible_card_frame"), candidate.corners.map { it.x.toFloat() / source.width to it.y.toFloat() / source.height })
            }
            val output = warp(sourceMat, candidate.corners)
            val quality = evaluate(output, candidate)
            return if (quality.reason.isBlank()) NormalizedCardCapture(output, quality, candidate.corners.map { it.x.toFloat() / source.width to it.y.toFloat() / source.height })
            else NormalizedCardCapture(null, quality, candidate.corners.map { it.x.toFloat() / source.width to it.y.toFloat() / source.height })
        } finally {
            sourceMat.release(); gray.release(); normalized.release(); blurred.release(); edges.release(); adaptive.release(); hierarchy.release()
        }
    }

    private data class Quad(val corners: List<Point>, val score: Float, val coverage: Float, val perspective: Float)

    private fun findBestQuad(edgeMap: Mat, hierarchy: Mat, width: Int, height: Int): Quad? {
        val contours = ArrayList<MatOfPoint>()
        return try {
            Imgproc.findContours(edgeMap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val imageArea = width.toDouble() * height
            contours.mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < imageArea * .018 || area > imageArea * .94) return@mapNotNull null
                val curve = MatOfPoint2f(*contour.toArray())
                val approximation = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, approximation, Imgproc.arcLength(curve, true) * .028, true)
                    val points = approximation.toArray()
                    if (points.size != 4 || !Imgproc.isContourConvex(MatOfPoint(*points))) return@mapNotNull null
                    val ordered = orderCorners(points.toList())
                    val lengths = ordered.indices.map { index -> hypot(ordered[(index + 1) % 4].x - ordered[index].x, ordered[(index + 1) % 4].y - ordered[index].y) }
                    val opposite = min(min(lengths[0], lengths[2]) / max(lengths[0], lengths[2]), min(lengths[1], lengths[3]) / max(lengths[1], lengths[3])).toFloat()
                    val short = min((lengths[0] + lengths[2]) / 2.0, (lengths[1] + lengths[3]) / 2.0)
                    val long = max((lengths[0] + lengths[2]) / 2.0, (lengths[1] + lengths[3]) / 2.0)
                    val ratio = if (long > 0) short / long else 0.0
                    val aspectScore = (1.0 - abs(ratio - CardScanConfiguration.CARD_RATIO) / .20).coerceIn(0.0, 1.0).toFloat()
                    val margin = ordered.all { it.x > width * .008 && it.x < width * .992 && it.y > height * .008 && it.y < height * .992 }
                    val perspective = min(opposite, aspectScore) * if (margin) 1f else .55f
                    val coverage = (area / imageArea).toFloat()
                    val coverageScore = (1f - abs(coverage - .42f) / .48f).coerceIn(0f, 1f)
                    Quad(ordered, perspective * .75f + coverageScore * .25f, coverage, perspective)
                } finally { curve.release(); approximation.release() }
            }.maxByOrNull { it.score }
        } finally { contours.forEach(Mat::release) }
    }

    private fun orderCorners(points: List<Point>): List<Point> {
        val sorted = points.sortedBy { it.y }
        val top = sorted.take(2).sortedBy { it.x }
        val bottom = sorted.takeLast(2).sortedByDescending { it.x }
        return listOf(top[0], top[1], bottom[0], bottom[1])
    }

    private fun warp(source: Mat, corners: List<Point>): Bitmap {
        val sourcePoints = MatOfPoint2f(*corners.toTypedArray())
        val targetPoints = MatOfPoint2f(
            Point(0.0, 0.0), Point(CardScanConfiguration.NORMALIZED_WIDTH.toDouble() - 1, 0.0),
            Point(CardScanConfiguration.NORMALIZED_WIDTH.toDouble() - 1, CardScanConfiguration.NORMALIZED_HEIGHT.toDouble() - 1),
            Point(0.0, CardScanConfiguration.NORMALIZED_HEIGHT.toDouble() - 1),
        )
        val transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
        val output = Mat(CardScanConfiguration.NORMALIZED_HEIGHT, CardScanConfiguration.NORMALIZED_WIDTH, source.type())
        try {
            Imgproc.warpPerspective(source, output, transform, output.size(), Imgproc.INTER_CUBIC)
            return Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally { sourcePoints.release(); targetPoints.release(); transform.release(); output.release() }
    }

    private fun evaluate(bitmap: Bitmap, quad: Quad): CardImageQuality {
        val mat = Mat(); val gray = Mat(); val laplacian = Mat()
        return try {
            Utils.bitmapToMat(bitmap, mat); Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            // Calculate a small, robust pixel sample for exposure/glare and variance.
            var sum = 0.0; var glare = 0; var laplacianSum = 0.0; var laplacianSquared = 0.0; var samples = 0
            val step = 8
            for (y in 0 until gray.rows() step step) for (x in 0 until gray.cols() step step) {
                val value = gray.get(y, x)[0]
                val edge = laplacian.get(y, x)[0]
                sum += value; laplacianSum += edge; laplacianSquared += edge * edge
                if (value >= 244) glare++; samples++
            }
            val mean = sum / samples.coerceAtLeast(1)
            val laplacianMean = laplacianSum / samples.coerceAtLeast(1)
            val laplacianVariance = (laplacianSquared / samples.coerceAtLeast(1) - laplacianMean * laplacianMean).coerceAtLeast(0.0)
            val sharpnessScore = (laplacianVariance / 1_200.0).toFloat().coerceIn(0f, 1f)
            val exposure = (1f - abs(mean - 128.0).toFloat() / 128f).coerceIn(0f, 1f)
            val glareScore = glare.toFloat() / samples.coerceAtLeast(1)
            val reason = when {
                sharpnessScore < CardScanConfiguration.MIN_SHARPNESS -> "image_too_soft"
                exposure < CardScanConfiguration.MIN_EXPOSURE -> "exposure_out_of_range"
                glareScore > CardScanConfiguration.MAX_GLARE -> "strong_reflections"
                else -> ""
            }
            CardImageQuality(true, quad.score, sharpnessScore, exposure, glareScore, quad.perspective, reason)
        } finally { mat.release(); gray.release(); laplacian.release() }
    }

    private fun decodeUpright(file: File): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val largest = max(options.outWidth, options.outHeight).coerceAtLeast(1)
        var sample = 1
        while (largest / sample > 1_900) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return@runCatching null
        val rotation = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
    }.getOrNull()
}
