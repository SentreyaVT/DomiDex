package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in emulator geometry benchmark. All pixels are generated here: no user photos, network,
 * OCR, collection changes, account access or upload. This is geometry regression evidence, not
 * a claim about real-card recognition accuracy. Before/after runs use exactly the same fixtures.
 */
@RunWith(AndroidJUnit4::class)
class ScannerGeometryBenchmarkInstrumentedTest {
    @Test fun benchmarkDeterministicGeometryMatrix() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with scannerBenchmark=true", args.getString("scannerBenchmark") == "true")
        assertTrue("Benchmark must never use a physical phone", Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone"))
        val label = args.getString("benchmarkLabel") ?: "baseline"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,60}")))
        val repetitions = args.getString("benchmarkRepeat")?.toIntOrNull()?.coerceIn(2, 30) ?: 12
        val warmups = 2
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "scanner-benchmark-$label-geometry.json")
        val rows = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("label", label)
            .put("fixtureVersion", "generated-geometry-v2").put("repetitions", repetitions)
            .put("warmups", warmups).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("scope", "synthetic geometry only; not real-card OCR or acceptance accuracy")
            .put("cases", rows)
        val base = makeBase()
        try {
            fixtureNames.forEach { name ->
                val source = makeFixture(name, base)
                try {
                    val pixelStride = if (name == "pixel_stride_2") 2 else 1
                    val rowStride = source.width * pixelStride + if (name == "padded_rows" || pixelStride == 2) 37 else 0
                    val luma = makeLuma(source, rowStride, pixelStride)
                    val geometry = if (name == "cropped_viewport") CameraFrameGeometry(source.width, source.height,
                        cropLeft = 85, cropTop = 20, cropRight = 555, cropBottom = 455)
                    else CameraFrameGeometry(source.width, source.height)
                    val input = JSONObject().put("width", source.width).put("height", source.height)
                        .put("rowStride", rowStride).put("pixelStride", pixelStride)
                        .put("crop", JSONArray(listOf(geometry.cropLeftPx, geometry.cropTopPx, geometry.cropRightPx, geometry.cropBottomPx)))
                        .put("pixelsSha256", bitmapDigest(source)).put("lumaSha256", digest(luma))
                    val detector = CardFrameDetector()
                    val detectorMeasurement = try {
                        measure(warmups, repetitions, { detector.detect(ByteBuffer.wrap(luma), source.width,
                            source.height, rowStride, pixelStride, geometry) }, ::frameJson) { }
                    } finally { detector.close() }
                    val normalizationMeasurement = measure(warmups, repetitions,
                        { CardImageNormalizer.normalize(source) }, ::normalizationJson, ::releaseCapture)
                    assertEquals("Input bitmap changed during geometry analysis", input.getString("pixelsSha256"), bitmapDigest(source))
                    if (name == "blank") {
                        assertFalse(detectorMeasurement.getJSONObject("output").getBoolean("found"))
                        assertFalse(normalizationMeasurement.getJSONObject("output")
                            .getJSONObject("quality").getBoolean("cardDetected"))
                    }
                    if (name == "sharp_texture" || name == "sharp_texture_90") {
                        val normalizedOutput = normalizationMeasurement.getJSONObject("output")
                        assertTrue("Sharp fixture did not detect the physical rectangle: $name",
                            normalizedOutput.getJSONObject("quality").getBoolean("cardDetected"))
                        assertFalse("Sharp fixture must pass existing quality gates, not bypass them: $name, ${normalizedOutput.getJSONObject("quality")}",
                            normalizedOutput.isNull("bitmap"))
                        val variants = normalizedOutput.getJSONArray("analysisVariants")
                        assertEquals("Sharp fixture must exercise both aspect-correct planes: $name", 2, variants.length())
                        repeat(variants.length()) { index ->
                            assertFalse("Missing normalized analysis bitmap: $name, plane $index",
                                variants.getJSONObject(index).isNull("bitmap"))
                        }
                    }
                    val row = JSONObject().put("id", name).put("input", input)
                        .put("detector", detectorMeasurement).put("normalizer", normalizationMeasurement)
                    rows.put(row)
                    // Persist completed cases incrementally so an interrupted opt-in run is diagnosable.
                    output.writeText(report.toString(2))
                    Log.i(TAG, JSONObject().put("case", name)
                        .put("detectorMedianMs", detectorMeasurement.getJSONObject("wallMs").getDouble("median"))
                        .put("normalizerMedianMs", normalizationMeasurement.getJSONObject("wallMs").getDouble("median"))
                        .put("found", detectorMeasurement.getJSONObject("output").getBoolean("found"))
                        .put("normalizerReason", normalizationMeasurement.getJSONObject("output")
                            .getJSONObject("quality").getString("reason")).toString())
                } finally { source.recycle() }
            }
            report.put("reusedDetectorSequence", verifyReusedDetector(base))
        } finally { base.recycle() }
        report.put("complete", true)
        output.writeText(report.toString(2))
        assertEquals(fixtureNames.size, rows.length())
        Log.i(TAG, "report=${output.name} cases=${rows.length()} complete=true")
    }

    /** A camera session switches frames without resetting CLAHE/Mats between lighting changes. */
    private fun verifyReusedDetector(base: Bitmap): JSONArray {
        val results = JSONArray()
        val reused = CardFrameDetector()
        try {
            listOf("good", "dark", "low_contrast", "blank", "good").forEachIndexed { index, name ->
                val source = makeFixture(name, base)
                try {
                    val luma = makeLuma(source, source.width, 1)
                    val actual = reused.detect(ByteBuffer.wrap(luma), source.width, source.height, source.width, 1)
                    val fresh = CardFrameDetector()
                    val expected = try {
                        fresh.detect(ByteBuffer.wrap(luma), source.width, source.height, source.width, 1)
                    } finally { fresh.close() }
                    // Data-class equality compares every stable frame field, including all corners.
                    assertEquals("Retained detector buffers changed result after transition $index ($name)", expected, actual)
                    results.put(JSONObject().put("index", index).put("id", name)
                        .put("matchesFreshDetector", true).put("output", frameJson(actual, false)))
                } finally { source.recycle() }
            }
        } finally { reused.close() }
        return results
    }

    private fun <T> measure(
        warmups: Int,
        repetitions: Int,
        operation: () -> T,
        describe: (T, Boolean) -> JSONObject,
        release: (T) -> Unit,
    ): JSONObject {
        repeat(warmups) { val value = operation(); try { describe(value, false) } finally { release(value) } }
        val wall = ArrayList<Double>()
        val processCpu = ArrayList<Double>()
        val threadCpu = ArrayList<Double>()
        val allocated = ArrayList<Double>()
        val nativeHeap = ArrayList<Double>()
        val stageGeometry = ArrayList<Double>()
        val stagePerspective = ArrayList<Double>()
        val memoryBefore = memoryJson()
        var fullOutput: JSONObject? = null
        var stableStructure: String? = null
        repeat(repetitions) { iteration ->
            val allocatedBefore = runtimeAllocatedBytes()
            val cpuBefore = Process.getElapsedCpuTime()
            val threadBefore = Debug.threadCpuTimeNanos()
            val started = SystemClock.elapsedRealtimeNanos()
            val value = operation()
            val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
            val threadNanos = Debug.threadCpuTimeNanos() - threadBefore
            val cpuMillis = Process.getElapsedCpuTime() - cpuBefore
            val allocatedAfter = runtimeAllocatedBytes()
            // Description/hashing and explicit recycling are deliberately outside measured work.
            try {
                wall += elapsedNanos / 1_000_000.0
                processCpu += cpuMillis.toDouble()
                threadCpu += threadNanos / 1_000_000.0
                if (allocatedBefore != null && allocatedAfter != null) allocated +=
                    (allocatedAfter - allocatedBefore).coerceAtLeast(0L).toDouble()
                nativeHeap += Debug.getNativeHeapAllocatedSize().toDouble()
                val structure = describe(value, false).toString()
                if (stableStructure == null) stableStructure = structure
                else assertEquals("Non-deterministic geometry at iteration $iteration", stableStructure, structure)
                if (fullOutput == null) fullOutput = describe(value, true)
                if (value is NormalizedCardCapture) {
                    stageGeometry += value.quality.geometryDurationMillis.toDouble()
                    stagePerspective += value.quality.perspectiveDurationMillis.toDouble()
                }
            } finally { release(value) }
        }
        return JSONObject().put("wallMs", stats(wall)).put("processCpuMs", stats(processCpu))
            .put("callingThreadCpuMs", stats(threadCpu)).put("javaAllocatedBytes", stats(allocated))
            .put("nativeHeapAfterOperationBytes", stats(nativeHeap)).put("memoryBefore", memoryBefore)
            .put("memoryAfter", memoryJson()).put("geometryStageMs", stats(stageGeometry))
            .put("perspectiveStageMs", stats(stagePerspective)).put("output", fullOutput)
            .put("outputSha256", digest(fullOutput!!.toString().toByteArray(Charsets.UTF_8)))
            .put("memoryNote", "PSS/heap snapshots are observations, not per-operation peak allocations; Java ART counter may be unavailable")
    }

    private fun stats(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("available", false)
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            else sorted[sorted.size / 2]
        return JSONObject().put("available", true).put("samples", JSONArray(values))
            .put("median", median).put("p95", sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)])
            .put("worst", sorted.last())
    }

    private fun runtimeAllocatedBytes(): Long? = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()

    private fun memoryJson(): JSONObject {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        return JSONObject().put("totalPssKiB", memory.totalPss).put("nativePssKiB", memory.nativePss)
            .put("dalvikPssKiB", memory.dalvikPss).put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun frameJson(frame: CardFrame, includePixels: Boolean): JSONObject {
        assertTrue(frame.coverage.isFinite() && frame.perspective.isFinite() && frame.geometryScore.isFinite())
        return JSONObject().put("found", frame.found).put("coverage", frame.coverage.toDouble())
            .put("perspective", frame.perspective.toDouble()).put("corners", cornersJson(frame.corners))
            .put("geometryScore", frame.geometryScore.toDouble()).put("usedFallbackGeometry", frame.usedFallbackGeometry)
            .put("aspectRatio", frame.aspectRatio.toDouble()).put("portraitAligned", frame.portraitAligned)
    }

    private fun normalizationJson(capture: NormalizedCardCapture, includePixels: Boolean): JSONObject {
        val quality = capture.quality
        assertTrue(quality.geometryScore.isFinite() && quality.frameQualityScore.isFinite())
        assertTrue(capture.bitmap == null || !capture.bitmap.isRecycled)
        val qualityJson = JSONObject().put("cardDetected", quality.cardDetected)
            .put("frameConfidence", quality.frameConfidence.toDouble()).put("sharpness", quality.sharpness.toDouble())
            .put("exposure", quality.exposure.toDouble()).put("glare", quality.glare.toDouble())
            .put("perspective", quality.perspective.toDouble()).put("geometryScore", quality.geometryScore.toDouble())
            .put("frameQualityScore", quality.frameQualityScore.toDouble()).put("usedFallbackGeometry", quality.usedFallbackGeometry)
            .put("cardCoverage", quality.cardCoverage.toDouble()).put("aspectRatio", quality.aspectRatio.toDouble())
            .put("cornerConfidence", quality.cornerConfidence.toDouble()).put("clippingPercentage", quality.clippingPercentage.toDouble())
            .put("motionScore", quality.motionScore.toDouble()).put("selectedBestFrame", quality.selectedBestFrame)
            .put("reason", quality.reason)
        return JSONObject().put("quality", qualityJson).put("corners", cornersJson(capture.corners))
            .put("bitmap", bitmapJson(capture.bitmap, includePixels))
            .put("recognitionBitmap", bitmapJson(capture.recognitionBitmap, includePixels))
            .put("originalCrop", bitmapJson(capture.originalCrop, includePixels))
            .put("analysisVariants", JSONArray(capture.analysisVariants.map { variant ->
                JSONObject().put("orientation", variant.orientation.degrees)
                    .put("bitmap", bitmapJson(variant.bitmap, includePixels))
            }))
    }

    private fun cornersJson(corners: List<Pair<Float, Float>>): JSONArray =
        JSONArray(corners.map { (x, y) -> JSONArray(listOf(x.toDouble(), y.toDouble())) })

    private fun bitmapJson(bitmap: Bitmap?, includePixels: Boolean): Any {
        if (bitmap == null) return JSONObject.NULL
        return JSONObject().put("width", bitmap.width).put("height", bitmap.height)
            .put("config", bitmap.config?.name ?: "null").also {
                if (includePixels) it.put("pixelsSha256", bitmapDigest(bitmap))
            }
    }

    private fun bitmapDigest(bitmap: Bitmap): String {
        // Canonical ARGB bytes, independent of row padding or platform Bitmap storage layout.
        val hash = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width)
        val bytes = ByteArray(bitmap.width * 4)
        repeat(bitmap.height) { y ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            pixels.forEachIndexed { x, pixel ->
                bytes[x * 4] = (pixel ushr 24).toByte()
                bytes[x * 4 + 1] = (pixel ushr 16).toByte()
                bytes[x * 4 + 2] = (pixel ushr 8).toByte()
                bytes[x * 4 + 3] = pixel.toByte()
            }
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun releaseCapture(capture: NormalizedCardCapture) {
        (listOfNotNull(capture.bitmap, capture.recognitionBitmap, capture.originalCrop) +
            capture.analysisVariants.map(CanonicalCardAnalysisVariant::bitmap)).distinct()
            .forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun makeBase(): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(28, 32, 38))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(218, 205, 151)
        canvas.drawRect(190f, 55f, 450f, 419f, paint)
        paint.color = Color.rgb(89, 143, 98)
        canvas.drawRect(198f, 63f, 442f, 411f, paint)
        repeat(30) { row -> repeat(20) { col ->
            paint.color = if ((row + col) % 2 == 0) Color.rgb(37, 66, 72) else Color.rgb(157, 190, 160)
            val left = 207f + col * 11f
            val top = 76f + row * 10f
            canvas.drawRect(left, top, left + 5f, top + 4f, paint)
        } }
        // Asymmetry makes rotation fingerprints sensitive to accidentally changed orientation.
        paint.color = Color.rgb(170, 46, 63)
        canvas.drawRect(210f, 79f, 298f, 95f, paint)
        paint.color = Color.rgb(36, 64, 182)
        canvas.drawRect(350f, 385f, 425f, 397f, paint)
        return bitmap
    }

    private fun makeFixture(name: String, base: Bitmap): Bitmap {
        if (name == "blank") return Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(80, 80, 80)) }
        if (name == "sharp_texture" || name == "sharp_texture_90") {
            val output = base.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(228 * 300) { index ->
                val x = index % 228
                val y = index / 228
                // Dense, high-contrast two-pixel detail survives warp/upscale and the existing
                // 630x880 Laplacian gate. Values deliberately avoid white-clipping/glare.
                val luminance = if ((x / 2 + y / 2) % 2 == 0) 24 else 230
                Color.rgb(luminance, luminance, luminance)
            }
            output.setPixels(pixels, 0, 228, 206, 100, 228, 300)
            if (name == "sharp_texture") return output
            return try {
                Bitmap.createBitmap(output, 0, 0, output.width, output.height,
                    Matrix().apply { postRotate(90f) }, false)
            } finally { output.recycle() }
        }
        if (name.startsWith("rotate_")) {
            val rotation = name.substringAfter("rotate_").toFloat()
            return Bitmap.createBitmap(base, 0, 0, base.width, base.height, Matrix().apply { postRotate(rotation) }, false)
        }
        if (name == "perspective") {
            val output = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.rgb(28, 32, 38))
            val transform = Matrix().apply {
                setPolyToPoly(floatArrayOf(190f, 55f, 450f, 55f, 450f, 419f, 190f, 419f), 0,
                    floatArrayOf(197f, 60f, 437f, 83f, 468f, 405f, 174f, 423f), 0, 4)
            }
            canvas.drawBitmap(base, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }
        if (name == "small_card" || name == "large_card") {
            val output = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.rgb(28, 32, 38))
            val scale = if (name == "small_card") .56f else 1.20f
            canvas.scale(scale, scale, 320f, 240f)
            canvas.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }
        val output = base.copy(Bitmap.Config.ARGB_8888, true)
        if (name in setOf("good", "padded_rows", "pixel_stride_2", "cropped_viewport")) return output
        val pixels = IntArray(base.width * base.height)
        base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        val transformed = IntArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            val x = index % base.width
            val y = index / base.width
            var red = Color.red(color).toDouble()
            var green = Color.green(color).toDouble()
            var blue = Color.blue(color).toDouble()
            when (name) {
                "dark" -> { red *= .32; green *= .32; blue *= .32 }
                "low_contrast" -> { red = 90 + red * .15; green = 90 + green * .15; blue = 90 + blue * .15 }
                "shadow" -> {
                    val gain = .25 + .75 * x / (base.width - 1)
                    red *= gain; green *= gain; blue *= gain
                }
                "overexposed" -> { red = red * 1.15 + 55; green = green * 1.15 + 55; blue = blue * 1.15 + 55 }
                "warm" -> { red *= 1.18; blue *= .72 }
                "cold" -> { red *= .72; blue *= 1.18 }
                "noise" -> {
                    val noise = (((index.toLong() * 1_103_515_245L + 12_345L) ushr 16) and 0x7fff_ffffL).toInt() % 17 - 8
                    red += noise; green += noise; blue += noise
                }
                "blur" -> {
                    red = 0.0; green = 0.0; blue = 0.0
                    for (offset in -3..3) {
                        val sampled = pixels[y * base.width + (x + offset).coerceIn(0, base.width - 1)]
                        red += Color.red(sampled) / 7.0; green += Color.green(sampled) / 7.0; blue += Color.blue(sampled) / 7.0
                    }
                }
            }
            transformed[index] = Color.rgb(red.roundToInt().coerceIn(0, 255), green.roundToInt().coerceIn(0, 255), blue.roundToInt().coerceIn(0, 255))
        }
        output.setPixels(transformed, 0, base.width, 0, 0, base.width, base.height)
        return output
    }

    private fun makeLuma(bitmap: Bitmap, rowStride: Int, pixelStride: Int): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteArray(rowStride * bitmap.height) { 19 }.also { bytes ->
            pixels.forEachIndexed { index, color ->
                bytes[index / bitmap.width * rowStride + index % bitmap.width * pixelStride] =
                    ((77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color) + 128) shr 8).toByte()
            }
        }
    }

    companion object {
        private const val TAG = "DomiDexGeometryBench"
        private val fixtureNames = listOf("good", "dark", "low_contrast", "shadow", "blank", "blur",
            "noise", "overexposed", "warm", "cold", "perspective", "small_card", "large_card",
            "rotate_90", "rotate_180", "rotate_270", "padded_rows", "pixel_stride_2",
            "sharp_texture", "sharp_texture_90", "cropped_viewport")
    }
}
