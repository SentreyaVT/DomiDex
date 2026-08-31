package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.SystemClock
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
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
    const val MAX_RECOGNITION_GLARE = .58f
    const val FALLBACK_GEOMETRY_TRIGGER = .68f
    // Preserve the tiny lower-border print from a 12 MP CameraX still. The previous
    // 630x880 target made a correctly focused set code only a few pixels high.
    const val NORMALIZED_WIDTH = 900
    const val NORMALIZED_HEIGHT = 1260
    const val RECOGNITION_WIDTH = 1200
    const val RECOGNITION_HEIGHT = 1680
    const val QUALITY_EVALUATION_WIDTH = 630
    const val QUALITY_EVALUATION_HEIGHT = 880
    const val MAX_DECODE_EDGE = 2600
}

internal data class CardImageQuality(
    val cardDetected: Boolean = false,
    val frameConfidence: Float = 0f,
    val sharpness: Float = 0f,
    val exposure: Float = 0f,
    val glare: Float = 0f,
    val perspective: Float = 0f,
    val geometryScore: Float = 0f,
    val frameQualityScore: Float = 0f,
    val usedFallbackGeometry: Boolean = false,
    val cardCoverage: Float = 0f,
    val aspectRatio: Float = 0f,
    val cornerConfidence: Float = 0f,
    val clippingPercentage: Float = 0f,
    val motionScore: Float = 0f,
    val selectedBestFrame: Int = 0,
    val geometryDurationMillis: Long = 0L,
    val perspectiveDurationMillis: Long = 0L,
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

    fun union(other: CardRoi) = CardRoi(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )
}

internal fun decodeSampleForLargestEdge(largestEdge: Int, maxDecodedEdge: Int = CardScanConfiguration.MAX_DECODE_EDGE): Int {
    var sample = 1
    while (largestEdge / sample > maxDecodedEdge) sample *= 2
    return sample
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
        // ACE SPEC/full-art identity print sits in a very shallow lower-left strip. Keeping this
        // crop tight prevents nearby rule text from dominating ML Kit's line grouping.
        CardOcrLayoutProfile("full_art_left", CardRoi(.030f, .025f, .970f, .230f), CardRoi(.010f, .915f, .355f, .998f), CardRoi(.010f, .915f, .355f, .998f)),
        CardOcrLayoutProfile("full_art_center", CardRoi(.030f, .025f, .970f, .260f), CardRoi(.260f, .790f, .745f, .998f), CardRoi(.230f, .790f, .775f, .998f)),
        CardOcrLayoutProfile("full_art_right", CardRoi(.030f, .025f, .970f, .260f), CardRoi(.545f, .790f, .995f, .998f), CardRoi(.525f, .790f, .995f, .998f)),
        CardOcrLayoutProfile("full_lower_strip", CardRoi(.030f, .025f, .970f, .275f), CardRoi(.010f, .745f, .995f, .998f), CardRoi(.010f, .745f, .995f, .998f)),
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
    /** Recognition-only local contrast image. It is never persisted or uploaded. */
    val recognitionBitmap: Bitmap? = null,
    /** Unwarped bounding crop retained only until OCR input creation. */
    val originalCrop: Bitmap? = null,
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
        return try { normalize(source) } finally { source.recycle() }
    }

    fun normalize(source: Bitmap): NormalizedCardCapture {
        if (!openCvAvailable) return NormalizedCardCapture(null, CardImageQuality(reason = "opencv_unavailable"))
        val geometryStarted = SystemClock.elapsedRealtime()
        val sourceMat = Mat()
        val gray = Mat()
        val normalized = Mat()
        val rawBlurred = Mat()
        val normalizedBlurred = Mat()
        val edges = Mat()
        val contrastEdges = Mat()
        val boundaryEdges = Mat()
        val structureDensity = Mat()
        val foregroundMask = Mat()
        val adaptive = Mat()
        val luminance = Mat()
        val hierarchy = Mat()
        try {
            Utils.bitmapToMat(source, sourceMat)
            Imgproc.cvtColor(sourceMat, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, normalized)
            clahe.collectGarbage()
            Imgproc.GaussianBlur(gray, rawBlurred, Size(5.0, 5.0), 0.0)
            Imgproc.GaussianBlur(normalized, normalizedBlurred, Size(5.0, 5.0), 0.0)
            // Fast normal edge pass. Expensive fallbacks are evaluated only when it is weak.
            Imgproc.Canny(rawBlurred, edges, 32.0, 104.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(edges, edges, kernel)
            val primary = findBestQuad(edges, hierarchy, source.width, source.height, usedFallback = false)
            val candidate = if (primary == null || primary.score < CardScanConfiguration.FALLBACK_GEOMETRY_TRIGGER) {
                Imgproc.Canny(normalizedBlurred, contrastEdges, 22.0, 86.0)
                Imgproc.adaptiveThreshold(normalizedBlurred, adaptive, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 7.0)
                Imgproc.threshold(normalizedBlurred, luminance, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                listOf(contrastEdges, adaptive, luminance).forEach { map ->
                    Imgproc.morphologyEx(map, map, Imgproc.MORPH_CLOSE, kernel)
                }
                // Full-art/foil borders often contain short interruptions even in a sharp frame.
                // Complete only nearby boundary segments; do not lower the physical-card gate or
                // allow OCR to see the surrounding photo.
                Imgproc.Canny(normalizedBlurred, boundaryEdges, 18.0, 72.0)
                val boundaryKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(13.0, 13.0))
                Imgproc.morphologyEx(boundaryEdges, boundaryEdges, Imgproc.MORPH_CLOSE, boundaryKernel)
                boundaryKernel.release()
                // Special cards can visually merge with a similarly coloured surface while their
                // printed artwork and text still form one dense, card-shaped structure. Aggregate
                // that local edge evidence with an adaptive (Otsu) density split. The result still
                // has to pass the same aspect, corner, coverage and margin checks as an outer edge.
                Imgproc.blur(contrastEdges, structureDensity, Size(31.0, 31.0))
                Imgproc.threshold(structureDensity, structureDensity, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                val structureKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
                Imgproc.morphologyEx(structureDensity, structureDensity, Imgproc.MORPH_CLOSE, structureKernel)
                structureKernel.release()
                foregroundFromBorder(sourceMat, foregroundMask)
                (listOfNotNull(primary) + listOf(contrastEdges, adaptive, luminance).mapNotNull { map ->
                    findBestQuad(map, hierarchy, source.width, source.height, usedFallback = true)
                } + listOf(boundaryEdges, structureDensity, foregroundMask).mapNotNull { map ->
                    findBestQuad(
                        map,
                        hierarchy,
                        source.width,
                        source.height,
                        usedFallback = true,
                        allowMinimumAreaRectangle = true,
                    )
                }).maxByOrNull { it.score }
            } else primary
            kernel.release()
            val geometryFinished = SystemClock.elapsedRealtime()
            candidate
                ?: return NormalizedCardCapture(null, CardImageQuality(
                    geometryDurationMillis = geometryFinished - geometryStarted,
                    reason = "no_plausible_card_frame",
                ))
            if (candidate.score < CardScanConfiguration.MIN_FRAME_SCORE || candidate.coverage !in CardScanConfiguration.MIN_CARD_COVERAGE..CardScanConfiguration.MAX_CARD_COVERAGE) {
                return NormalizedCardCapture(
                    null,
                    CardImageQuality(
                        cardDetected = true,
                        frameConfidence = candidate.score,
                        perspective = candidate.perspective,
                        geometryScore = candidate.score,
                        usedFallbackGeometry = candidate.usedFallback,
                        cardCoverage = candidate.coverage,
                        aspectRatio = candidate.aspectRatio,
                        cornerConfidence = candidate.cornerConfidence,
                        geometryDurationMillis = geometryFinished - geometryStarted,
                        reason = "implausible_card_frame",
                    ),
                    candidate.corners.map { it.x.toFloat() / source.width to it.y.toFloat() / source.height },
                )
            }
            val perspectiveStarted = SystemClock.elapsedRealtime()
            val portraitCorners = orientForPortrait(candidate.corners)
            val output = warp(sourceMat, portraitCorners)
            val perspectiveDuration = SystemClock.elapsedRealtime() - perspectiveStarted
            val quality = evaluate(output, candidate).copy(
                geometryDurationMillis = geometryFinished - geometryStarted,
                perspectiveDurationMillis = perspectiveDuration,
            )
            val normalizedCorners = candidate.corners.map { it.x.toFloat() / source.width to it.y.toFloat() / source.height }
            if (quality.reason.isNotBlank()) {
                output.recycle()
                return NormalizedCardCapture(null, quality, normalizedCorners)
            }
            val recognitionSource = warp(
                sourceMat,
                portraitCorners,
                CardScanConfiguration.RECOGNITION_WIDTH,
                CardScanConfiguration.RECOGNITION_HEIGHT,
            )
            val recognitionOutput = try { contrastRecognitionImage(recognitionSource) } finally { recognitionSource.recycle() }
            return NormalizedCardCapture(
                bitmap = output,
                quality = quality,
                corners = normalizedCorners,
                recognitionBitmap = recognitionOutput,
                originalCrop = boundingCrop(source, candidate.corners),
            )
        } finally {
            sourceMat.release(); gray.release(); normalized.release(); rawBlurred.release(); normalizedBlurred.release()
            edges.release(); contrastEdges.release(); boundaryEdges.release(); structureDensity.release(); foregroundMask.release(); adaptive.release(); luminance.release(); hierarchy.release()
        }
    }

    private data class Quad(
        val corners: List<Point>,
        val score: Float,
        val coverage: Float,
        val perspective: Float,
        val usedFallback: Boolean,
        val aspectRatio: Float,
        val cornerConfidence: Float,
    )

    private fun findBestQuad(
        edgeMap: Mat,
        hierarchy: Mat,
        width: Int,
        height: Int,
        usedFallback: Boolean,
        allowMinimumAreaRectangle: Boolean = false,
    ): Quad? {
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
                    val approximatedPoints = approximation.toArray()
                    val exactQuadrilateral = approximatedPoints.size == 4 && Imgproc.isContourConvex(MatOfPoint(*approximatedPoints))
                    val (points, measuredArea, structuralConfidence) = if (exactQuadrilateral) {
                        Triple(approximatedPoints.toList(), area, 1f)
                    } else {
                        if (!allowMinimumAreaRectangle) return@mapNotNull null
                        val rectangle = Imgproc.minAreaRect(curve)
                        val rectangleArea = rectangle.size.width * rectangle.size.height
                        if (rectangleArea <= 0.0) return@mapNotNull null
                        val coverage = rectangleArea / imageArea
                        if (coverage < .12 || coverage > .94) return@mapNotNull null
                        val contourFill = (area / rectangleArea).coerceIn(0.0, 1.0)
                        val rectanglePerimeter = 2.0 * (rectangle.size.width + rectangle.size.height)
                        val perimeterSupport = if (rectanglePerimeter <= 0.0) 0.0 else
                            (Imgproc.arcLength(curve, true) / rectanglePerimeter).coerceIn(0.0, 1.0)
                        val support = (contourFill * .62 + perimeterSupport * .38).toFloat()
                        // A minimum-area rectangle is only a contour-completion mechanism. It
                        // cannot promote a weak interior line or arbitrary background rectangle.
                        if (support < .46f) return@mapNotNull null
                        val rectanglePoints = Array(4) { Point() }
                        rectangle.points(rectanglePoints)
                        Triple(rectanglePoints.toList(), rectangleArea, support)
                    }
                    val ordered = orderCorners(points)
                    val lengths = ordered.indices.map { index -> hypot(ordered[(index + 1) % 4].x - ordered[index].x, ordered[(index + 1) % 4].y - ordered[index].y) }
                    val opposite = min(min(lengths[0], lengths[2]) / max(lengths[0], lengths[2]), min(lengths[1], lengths[3]) / max(lengths[1], lengths[3])).toFloat()
                    val short = min((lengths[0] + lengths[2]) / 2.0, (lengths[1] + lengths[3]) / 2.0)
                    val long = max((lengths[0] + lengths[2]) / 2.0, (lengths[1] + lengths[3]) / 2.0)
                    val ratio = if (long > 0) short / long else 0.0
                    val aspectScore = (1.0 - abs(ratio - CardScanConfiguration.CARD_RATIO) / .20).coerceIn(0.0, 1.0).toFloat()
                    val rightAngles = rightAngleScore(ordered)
                    val margin = ordered.all { it.x > width * .008 && it.x < width * .992 && it.y > height * .008 && it.y < height * .992 }
                    val perspective = min(min(opposite, aspectScore), rightAngles) * if (margin) 1f else .55f
                    val coverage = (measuredArea / imageArea).toFloat()
                    val coverageScore = (1f - abs(coverage - .42f) / .48f).coerceIn(0f, 1f)
                    val geometry = aspectScore * .34f + rightAngles * .30f + opposite * .22f + coverageScore * .14f
                    Quad(
                        ordered,
                        geometry * structuralConfidence * if (margin) 1f else .72f,
                        coverage,
                        perspective,
                        usedFallback,
                        ratio.toFloat(),
                        rightAngles,
                    )
                } finally { curve.release(); approximation.release() }
            }.maxByOrNull { it.score }
        } finally { contours.forEach(Mat::release) }
    }

    private fun rightAngleScore(points: List<Point>): Float = points.indices.map { index ->
        val previous = points[(index + 3) % 4]
        val current = points[index]
        val next = points[(index + 1) % 4]
        val ax = previous.x - current.x; val ay = previous.y - current.y
        val bx = next.x - current.x; val by = next.y - current.y
        val denominator = hypot(ax, ay) * hypot(bx, by)
        if (denominator <= 0.0) 0f else (1.0 - abs((ax * bx + ay * by) / denominator)).coerceIn(0.0, 1.0).toFloat()
    }.average().toFloat()

    /**
     * Builds a colour-independent foreground proposal from the actual photo border. Scanner
     * framing keeps the card away from the outermost strip, so that strip is valid evidence for
     * the local surface colour (cloth, desk, playmat, etc.). Otsu chooses the split per capture;
     * no material- or card-specific RGB threshold is encoded here.
     */
    private fun foregroundFromBorder(source: Mat, output: Mat) {
        val rgb = Mat()
        val lab = Mat()
        val borderMask = Mat.zeros(source.rows(), source.cols(), CvType.CV_8UC1)
        val channels = ArrayList<Mat>()
        val accumulated = Mat.zeros(source.rows(), source.cols(), CvType.CV_8UC1)
        val difference = Mat()
        try {
            Imgproc.cvtColor(source, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            val insetX = (source.cols() * .075).toInt().coerceAtLeast(1)
            val insetY = (source.rows() * .075).toInt().coerceAtLeast(1)
            Imgproc.rectangle(borderMask, Point(0.0, 0.0), Point((source.cols() - 1).toDouble(), (source.rows() - 1).toDouble()), Scalar.all(255.0), -1)
            Imgproc.rectangle(
                borderMask,
                Point(insetX.toDouble(), insetY.toDouble()),
                Point((source.cols() - insetX - 1).toDouble(), (source.rows() - insetY - 1).toDouble()),
                Scalar.all(0.0),
                -1,
            )
            val background = Core.mean(lab, borderMask)
            Core.split(lab, channels)
            channels.forEachIndexed { index, channel ->
                val backgroundChannel = Mat(channel.rows(), channel.cols(), channel.type(), Scalar.all(background.`val`[index]))
                try {
                    Core.absdiff(channel, backgroundChannel, difference)
                    difference.convertTo(difference, CvType.CV_8UC1, if (index == 0) .34 else .33)
                    Core.add(accumulated, difference, accumulated)
                } finally { backgroundChannel.release() }
            }
            Imgproc.GaussianBlur(accumulated, accumulated, Size(7.0, 7.0), 0.0)
            Imgproc.threshold(accumulated, output, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(29.0, 29.0))
            Imgproc.morphologyEx(output, output, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(output, output, Imgproc.MORPH_CLOSE, closeKernel)
            openKernel.release(); closeKernel.release()
        } finally {
            rgb.release(); lab.release(); borderMask.release(); channels.forEach(Mat::release); accumulated.release(); difference.release()
        }
    }

    private fun orderCorners(points: List<Point>): List<Point> {
        val sorted = points.sortedBy { it.y }
        val top = sorted.take(2).sortedBy { it.x }
        val bottom = sorted.takeLast(2).sortedByDescending { it.x }
        return listOf(top[0], top[1], bottom[0], bottom[1])
    }

    /**
     * CameraX may save a still in landscape even though the physical card is upright relative to
     * the user. Map the card's long axis to the normalized vertical axis before any OCR ROI is
     * cropped. The real-device capture direction places the printed top at the source's right
     * edge, hence the one-corner shift (TR, BR, BL, TL).
     */
    private fun orientForPortrait(corners: List<Point>): List<Point> {
        if (corners.size != 4) return corners
        val horizontalEdges = (
            hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y) +
                hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)
            ) / 2.0
        val verticalEdges = (
            hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y) +
                hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y)
            ) / 2.0
        return if (horizontalEdges > verticalEdges) corners.drop(1) + corners.first() else corners
    }

    private fun warp(
        source: Mat,
        corners: List<Point>,
        outputWidth: Int = CardScanConfiguration.NORMALIZED_WIDTH,
        outputHeight: Int = CardScanConfiguration.NORMALIZED_HEIGHT,
    ): Bitmap {
        val sourcePoints = MatOfPoint2f(*corners.toTypedArray())
        val targetPoints = MatOfPoint2f(
            Point(0.0, 0.0), Point(outputWidth.toDouble() - 1, 0.0),
            Point(outputWidth.toDouble() - 1, outputHeight.toDouble() - 1),
            Point(0.0, outputHeight.toDouble() - 1),
        )
        val transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
        val output = Mat(outputHeight, outputWidth, source.type())
        try {
            Imgproc.warpPerspective(source, output, transform, output.size(), Imgproc.INTER_CUBIC)
            return Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally { sourcePoints.release(); targetPoints.release(); transform.release(); output.release() }
    }

    private fun evaluate(bitmap: Bitmap, quad: Quad): CardImageQuality {
        val mat = Mat(); val gray = Mat(); val qualityGray = Mat(); val laplacian = Mat()
        return try {
            Utils.bitmapToMat(bitmap, mat); Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            // Keep the quality gate calibrated while the OCR output uses a larger resolution.
            // Otherwise Laplacian variance changes merely because the same card has more pixels.
            Imgproc.resize(
                gray,
                qualityGray,
                Size(CardScanConfiguration.QUALITY_EVALUATION_WIDTH.toDouble(), CardScanConfiguration.QUALITY_EVALUATION_HEIGHT.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            Imgproc.Laplacian(qualityGray, laplacian, CvType.CV_64F)
            // Calculate a small, robust pixel sample for exposure/glare and variance.
            var sum = 0.0; var glare = 0; var clipped = 0; var laplacianSum = 0.0; var laplacianSquared = 0.0; var samples = 0
            val step = 8
            for (y in 0 until qualityGray.rows() step step) for (x in 0 until qualityGray.cols() step step) {
                val value = qualityGray.get(y, x)[0]
                val edge = laplacian.get(y, x)[0]
                sum += value; laplacianSum += edge; laplacianSquared += edge * edge
                if (value >= 244) glare++
                if (value >= 250 || value <= 5) clipped++
                samples++
            }
            val mean = sum / samples.coerceAtLeast(1)
            val laplacianMean = laplacianSum / samples.coerceAtLeast(1)
            val laplacianVariance = (laplacianSquared / samples.coerceAtLeast(1) - laplacianMean * laplacianMean).coerceAtLeast(0.0)
            val sharpnessScore = (laplacianVariance / 1_200.0).toFloat().coerceIn(0f, 1f)
            val exposure = (1f - abs(mean - 128.0).toFloat() / 128f).coerceIn(0f, 1f)
            val glareScore = glare.toFloat() / samples.coerceAtLeast(1)
            val glareQuality = (1f - glareScore / CardScanConfiguration.MAX_RECOGNITION_GLARE).coerceIn(0f, 1f)
            val frameQuality = (sharpnessScore * .32f + exposure * .18f + glareQuality * .20f + quad.score * .20f + quad.perspective * .10f).coerceIn(0f, 1f)
            val reason = when {
                sharpnessScore < CardScanConfiguration.MIN_SHARPNESS -> "image_too_soft"
                exposure < CardScanConfiguration.MIN_EXPOSURE -> "exposure_out_of_range"
                // Local holo highlights are expected. Only an overwhelmingly clipped card is
                // unusable; ordinary glare is down-weighted later by the visual descriptor.
                glareScore > CardScanConfiguration.MAX_RECOGNITION_GLARE -> "overwhelming_reflection"
                else -> ""
            }
            CardImageQuality(
                cardDetected = true,
                frameConfidence = quad.score,
                sharpness = sharpnessScore,
                exposure = exposure,
                glare = glareScore,
                perspective = quad.perspective,
                geometryScore = quad.score,
                frameQualityScore = frameQuality,
                usedFallbackGeometry = quad.usedFallback,
                cardCoverage = quad.coverage,
                aspectRatio = quad.aspectRatio,
                cornerConfidence = quad.cornerConfidence,
                clippingPercentage = clipped.toFloat() / samples.coerceAtLeast(1),
                reason = reason,
            )
        } finally { mat.release(); gray.release(); qualityGray.release(); laplacian.release() }
    }

    private fun contrastRecognitionImage(source: Bitmap): Bitmap {
        val rgba = Mat(); val gray = Mat(); val enhanced = Mat(); val blurred = Mat(); val sharpened = Mat()
        return try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(2.4, Size(8.0, 8.0))
            clahe.apply(gray, enhanced); clahe.collectGarbage()
            // Bounded unsharp masking improves the already present small-print edges. It is an
            // OCR-only derivative and never replaces the original artwork image or invents data.
            Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 1.05)
            Core.addWeighted(enhanced, 1.48, blurred, -.48, 0.0, sharpened)
            Bitmap.createBitmap(sharpened.cols(), sharpened.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(sharpened, it) }
        } finally { rgba.release(); gray.release(); enhanced.release(); blurred.release(); sharpened.release() }
    }

    private fun boundingCrop(source: Bitmap, corners: List<Point>): Bitmap? = runCatching {
        val left = corners.minOf { it.x }.toInt().coerceIn(0, source.width - 1)
        val top = corners.minOf { it.y }.toInt().coerceIn(0, source.height - 1)
        val right = corners.maxOf { it.x }.toInt().coerceIn(left + 1, source.width)
        val bottom = corners.maxOf { it.y }.toInt().coerceIn(top + 1, source.height)
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }.getOrNull()

    private fun decodeUpright(file: File): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val largest = max(options.outWidth, options.outHeight).coerceAtLeast(1)
        val sample = decodeSampleForLargestEdge(largest)
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
