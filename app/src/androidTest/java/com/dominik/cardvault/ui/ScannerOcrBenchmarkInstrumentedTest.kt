package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.catalog.collectorIdentityForSourceLookup
import com.dominik.cardvault.model.CatalogSearchResult
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in, local-only replay of public catalogue artwork. Never uses user photos, network,
 * Room, Firebase, or collection writes. Timings are emulator development evidence, not camera
 * latency or real foil accuracy. The finite fixture catalogue is deliberately labelled as such.
 */
@RunWith(AndroidJUnit4::class)
class ScannerOcrBenchmarkInstrumentedTest {
    @Test fun optionalMetricsCannotChangeResultDeliveryEvenWhenObserverThrows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val done = CountDownLatch(1)
        val observations = AtomicInteger()
        val outcome = AtomicReference<Result<CardTextAnalysis>>()
        val measurement = AtomicReference<CardTextAnalysisMetrics>()
        CardTextAnalyzer(
            context,
            normalize = { _, _ -> NormalizedCardCapture(null, CardImageQuality(reason = "fixture_geometry_rejected")) },
            metricsObserver = {
                measurement.set(it); observations.incrementAndGet()
                error("Deliberate observer failure must not affect scanner")
            },
        ).use { analyzer ->
            analyzer.analyze(File(context.cacheDir, "no-photo-needed")) {
                outcome.set(it); done.countDown()
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
        }
        assertEquals(1, observations.get())
        assertEquals("fixture_geometry_rejected", outcome.get().getOrThrow().imageQuality.reason)
        assertEquals(0L, measurement.get().ocrCalls)
        assertTrue(measurement.get().stageDurationsNanos.containsKey("normalize"))
    }

    @Test fun benchmarkPublicFixturesWithUnchangedRecognitionPolicy() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with scannerBenchmark=true", args.getString("scannerBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "scanner-public-fixtures")
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        val entries = manifest.getJSONArray("fixtures")
        val fixtures = (0 until entries.length()).map { index -> Fixture(entries.getJSONObject(index), folder) }
        fixtures.forEach(Fixture::verifyHash)
        val selectedIds = args.getString("scannerFixtureIds")?.split(',')?.toSet()
        val selected = fixtures.filter { selectedIds == null || it.id in selectedIds }
            .take(args.getString("scannerFixtureLimit")?.toIntOrNull()?.coerceIn(1, 20) ?: 3)
        val cases = args.getString("scannerCases")?.split(',') ?: listOf("clean", "dim")
        require(cases.all { it in SUPPORTED_CASES }) { "Unsupported scannerCases: $cases" }
        val repeats = args.getString("scannerRepeats")?.toIntOrNull()?.coerceIn(1, 20) ?: 3
        val recognitionWidth = args.getString("scannerRecognitionWidth")?.toInt() ?: 1200
        require(recognitionWidth in setOf(800, 1000, 1200)) { "scannerRecognitionWidth must be 800, 1000 or 1200" }
        val normalization: (File, Boolean) -> NormalizedCardCapture = if (recognitionWidth == 1200) {
            CardImageNormalizer::normalize
        } else { file, manual ->
            resizeAnalysisPlanesForExperiment(CardImageNormalizer.normalize(file, manual), recognitionWidth)
        }
        val label = args.getString("scannerBenchmarkLabel")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,60}")) } ?: "development"
        val output = File(context.cacheDir, "scanner-benchmark-$label.json")
        val samples = JSONArray()
        val referenceDescriptors = fixtures.associate { fixture ->
            val bitmap = requireNotNull(BitmapFactory.decodeFile(fixture.file.absolutePath))
            try { fixture.id to CardArtworkVerifier.describe(bitmap) } finally { bitmap.recycle() }
        }
        val catalogue = fixtures.flatMap(Fixture::candidates)
        val report = JSONObject().apply {
            put("label", label)
            put("schemaVersion", 1)
            put("scope", "Public flat artwork on synthetic frames; finite fixture catalogue, not live catalogue or physical foil/camera validation")
            put("recognitionWidth", recognitionWidth)
            put("experiment", if (recognitionWidth == 1200) "none" else "test_only_analysis_plane_resize")
            put("ocrPixelsChangedFromDefault", recognitionWidth != 1200)
            put("inputHashScope", "Original synthetic input PNG only, not the resized OCR planes or ROI pixels")
            put("experimentCaveat", if (recognitionWidth == 1200) JSONObject.NULL else
                "Not a pixel-equivalent optimization. Initial display/quality/ROIs/policy/deadlines are unchanged; " +
                    "the existing analyzer may derive a different final display/artwork bitmap if it selects a non-first orientation.")
            put("device", "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            put("appVersionCode", com.dominik.cardvault.BuildConfig.VERSION_CODE)
            put("provenance", manifest.optString("provenance"))
            put("cases", JSONArray(cases))
            put("repeats", repeats)
            put("expectedSamples", selected.size * cases.size * repeats)
            put("complete", false)
            put("samples", samples)
        }
        assertTrue("No public fixtures selected", selected.isNotEmpty())
        try {
            selected.forEach { fixture -> cases.forEach { case ->
                val frame = createFrame(fixture, case)
                val input = File(context.cacheDir, "scanner-benchmark-input.png")
                try { input.outputStream().use { assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { frame.recycle() }
                val inputSha256 = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
                    .joinToString("") { "%02x".format(it) }
                try {
                    repeat(repeats) { iteration ->
                        val capturedMetrics = AtomicReference<CardTextAnalysisMetrics?>()
                        val result = AtomicReference<Result<CardTextAnalysis>>()
                        val done = CountDownLatch(1)
                        val memoryBefore = memory()
                        val cpuBefore = Process.getElapsedCpuTime()
                        val elapsedBefore = SystemClock.elapsedRealtimeNanos()
                        CardTextAnalyzer(context, normalize = normalization, metricsObserver = capturedMetrics::set).use { analyzer ->
                            analyzer.analyze(input, allowManualWholeImageFallback = case == "manual_whole") {
                                result.set(it); done.countDown()
                            }
                            assertTrue("Analyzer callback must remain bounded", done.await(25, TimeUnit.SECONDS))
                        }
                        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - elapsedBefore) / 1_000_000.0
                        val cpuMillis = Process.getElapsedCpuTime() - cpuBefore
                        val memoryAfter = memory()
                        val analysis = result.get().getOrNull()
                        try {
                            val rankingStarted = SystemClock.elapsedRealtimeNanos()
                            val candidates = if (analysis == null || (!analysis.imageQuality.isManualRecognitionFallback &&
                                    (!analysis.imageQuality.cardDetected || analysis.imageQuality.reason.isNotBlank()))) emptyList()
                                else catalogue.filter { candidate ->
                                    val source = candidate.sourceSetId.orEmpty()
                                    val number = analysis.collectorNumber.orEmpty()
                                    number.isNotBlank() &&
                                        collectorIdentityForSourceLookup(number, source) == collectorIdentityForSourceLookup(candidate.collectorNumber.orEmpty(), source) &&
                                        printedDenominatorMatches(number, candidate.mainSetSize ?: 0)
                                }.map { candidate -> ScanCandidate(candidate, CardRecognitionRanker.score(analysis, candidate)) }
                                    .filter { it.score >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE }
                                    .sortedByDescending(ScanCandidate::score).take(8)
                            val artwork = candidates.take(ScanQueuePolicy.MAX_VISUAL_CANDIDATES).associate { candidate ->
                                val reference = candidate.card.sourceCardId?.let(referenceDescriptors::get)
                                candidate.card.scanIdentityKey() to if (reference == null || analysis == null) ArtworkVerification(ArtworkMatch.UNAVAILABLE)
                                else CardArtworkVerifier.combineTemporal(analysis.visualDescriptors.map { CardArtworkVerifier.compare(it, reference) })
                            }
                            val ranked = if (analysis == null) emptyList() else HybridCandidateFusion.rank(analysis, candidates, artwork)
                            val decision = if (analysis == null) ScannerDecisionResult(ScannerDecision.REJECTED, reason = "analyzer_failure")
                                else constrainManualWholeImageDecision(analysis, HybridCandidateFusion.decide(ranked))
                            val rankingMillis = (SystemClock.elapsedRealtimeNanos() - rankingStarted) / 1_000_000.0
                            val metrics = capturedMetrics.get()
                            val expectedNumber = collectorIdentityForSourceLookup(fixture.collectorNumber, fixture.sourceSetId)
                            val readNumber = analysis?.collectorNumber?.let { collectorIdentityForSourceLookup(it, fixture.sourceSetId) }
                            val localIdCorrect = expectedNumber.isNotBlank() && expectedNumber == readNumber
                            val expectedDenominator = fixture.collectorNumber.substringAfter('/', "").toIntOrNull()
                            val readDenominator = analysis?.collectorNumber?.substringAfter('/', "")?.toIntOrNull()
                            val sample = JSONObject().apply {
                                put("fixtureId", fixture.id); put("case", case); put("iteration", iteration)
                                put("inputSha256", inputSha256)
                                put("recognitionWidth", recognitionWidth)
                                put("ocrPixelsChangedFromDefault", recognitionWidth != 1200)
                                put("elapsedMillis", elapsedMillis); put("processCpuMillis", cpuMillis)
                                put("memoryBefore", memoryBefore); put("memoryAfter", memoryAfter)
                                put("failure", result.get().exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                                put("collectorNumber", analysis?.collectorNumber ?: JSONObject.NULL)
                                put("setIdentifier", analysis?.setIdentifier ?: JSONObject.NULL)
                                put("nameQueries", JSONArray(analysis?.searchQueries.orEmpty()))
                                put("expectedCollectorNumber", fixture.collectorNumber)
                                // Historical field measures only the collector localId, not a printed denominator.
                                put("numberCorrect", localIdCorrect)
                                put("numberCorrectScope", "collector_local_id_only")
                                put("fullNumberCorrect", localIdCorrect && expectedDenominator == readDenominator)
                                put("orientation", analysis?.analysisOrientation?.degrees ?: JSONObject.NULL)
                                put("orientationMargin", analysis?.orientationMargin ?: 0)
                                put("qualityReason", analysis?.imageQuality?.reason ?: JSONObject.NULL)
                                put("geometryMillis", analysis?.imageQuality?.geometryDurationMillis ?: 0)
                                put("firstWarpAndScaleMillis", analysis?.imageQuality?.perspectiveDurationMillis ?: 0)
                                put("ocrCalls", metrics?.ocrCalls ?: 0); put("cacheHitInputs", metrics?.cacheHitInputs ?: 0)
                                put("preparedOcrBitmaps", metrics?.preparedOcrBitmaps ?: 0)
                                put("preparedOcrBitmapBytes", metrics?.preparedOcrBitmapBytes ?: 0)
                                put("phaseMillis", JSONObject(metrics?.stageDurationsNanos?.mapValues { it.value / 1_000_000.0 }.orEmpty()))
                                put("fixtureCatalogueDecision", decision.decision.name)
                                put("fixtureCatalogueReason", decision.reason)
                                put("fixtureCatalogueBestId", ranked.firstOrNull()?.card?.sourceCardId ?: JSONObject.NULL)
                                put("fixtureCatalogueCandidates", JSONArray(ranked.map { it.card.scanIdentityKey() }))
                                put("fixtureCatalogueRankingMillis", rankingMillis)
                                put("fixtureCatalogueFalseAccept", decision.decision == ScannerDecision.ACCEPTED &&
                                    (case == "blank" || ranked.firstOrNull()?.card?.sourceCardId != fixture.id))
                            }
                            samples.put(sample)
                            output.writeText(report.toString(2))
                            Log.i("DomiDexOcrBenchmark", "${fixture.id}/$case/$iteration: ${elapsedMillis.toLong()}ms calls=${metrics?.ocrCalls} number=${analysis?.collectorNumber} decision=${decision.decision}")
                        } finally { analysis?.normalizedCard?.let { if (!it.isRecycled) it.recycle() } }
                    }
                } finally { input.delete() }
            } }
            report.put("complete", samples.length() == selected.size * cases.size * repeats)
        } finally {
            report.put("summary", summarize(samples))
            output.writeText(report.toString(2))
            fixtures.forEach(Fixture::verifyHash)
            Log.i("DomiDexOcrBenchmark", "Report: ${output.absolutePath}")
        }
        assertEquals("No false accepts in the finite fixture catalogue", 0, (0 until samples.length()).count {
            samples.getJSONObject(it).getBoolean("fixtureCatalogueFalseAccept")
        })
    }

    /** Test-only experiment; never changes the production normalizer or its geometry/quality gate. */
    private fun resizeAnalysisPlanesForExperiment(capture: NormalizedCardCapture, width: Int): NormalizedCardCapture {
        val originalPlanes = capture.analysisVariants.map { it.bitmap }.distinct()
        val retained = listOfNotNull(capture.bitmap, capture.originalCrop, capture.recognitionBitmap)
        val resized = IdentityHashMap<Bitmap, Bitmap>()
        try {
            val variants = capture.analysisVariants.map { variant ->
                val source = variant.bitmap
                val pixels = resized[source] ?: (if (source.width == width) source else
                    Bitmap.createScaledBitmap(source, width,
                        (source.height.toDouble() * width / source.width).roundToInt().coerceAtLeast(1), true)
                    ).also { resized[source] = it }
                variant.copy(bitmap = pixels)
            }
            originalPlanes.filter { old -> retained.none { it === old } && variants.none { it.bitmap === old } }
                .forEach { if (!it.isRecycled) it.recycle() }
            return capture.copy(analysisVariants = variants)
        } catch (failure: Throwable) {
            // Ownership has not yet reached AnalysisSession when injected normalization fails.
            (originalPlanes + retained + resized.values).distinct().forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        }
    }

    @Test fun recognitionWidthExperimentPreservesDisplayAndBitmapAliases() {
        val display = Bitmap.createBitmap(90, 126, Bitmap.Config.ARGB_8888)
        val originalCrop = Bitmap.createBitmap(120, 168, Bitmap.Config.ARGB_8888)
        val recognition = Bitmap.createBitmap(120, 168, Bitmap.Config.ARGB_8888)
        val disposablePlane = Bitmap.createBitmap(120, 168, Bitmap.Config.ARGB_8888)
        val capture = NormalizedCardCapture(display, CardImageQuality(cardDetected = true),
            originalCrop = originalCrop, recognitionBitmap = recognition,
            analysisVariants = listOf(
                CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_0, display),
                CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_90, originalCrop),
                CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_180, recognition),
                CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_270, disposablePlane),
            ))
        var scaled: NormalizedCardCapture? = null
        try {
            val result = resizeAnalysisPlanesForExperiment(capture, 800)
            scaled = result
            assertTrue(result.bitmap === display && result.originalCrop === originalCrop && result.recognitionBitmap === recognition)
            assertEquals(capture.quality, result.quality)
            assertEquals(capture.analysisVariants.map { it.orientation }, result.analysisVariants.map { it.orientation })
            assertTrue(!display.isRecycled && !originalCrop.isRecycled && !recognition.isRecycled)
            assertTrue(disposablePlane.isRecycled)
            assertTrue(result.analysisVariants.all { it.bitmap.width == 800 && it.bitmap.height == 1120 && !it.bitmap.isRecycled })
        } finally {
            (listOf(display, originalCrop, recognition, disposablePlane) + scaled?.analysisVariants.orEmpty().map { it.bitmap })
                .distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private class Fixture(val json: JSONObject, folder: File) {
        val id = json.getString("id")
        val sourceSetId = json.getString("sourceSetId")
        val collectorNumber = json.getString("collectorNumber")
        val file = File(folder, json.getString("path")).canonicalFile.also {
            require(it.parentFile == folder.canonicalFile && it.isFile)
        }
        fun verifyHash() {
            val expected = json.getString("sha256")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            assertEquals("Public fixture must remain unchanged", expected, digest.digest().joinToString("") { "%02x".format(it) })
        }
        fun candidates(): List<CatalogSearchResult> {
            val variants = json.getJSONObject("variants")
            return listOf("normal" to "Normal", "holo" to "Holo", "reverse" to "Reverse Holo")
                .filter { variants.optBoolean(it.first) }.map { (variant, finish) ->
                    CatalogSearchResult(
                        productId = "$id:$variant".hashCode().toLong(), name = json.getString("name"),
                        expansionId = sourceSetId, trendPriceEur = null, currency = null,
                        collectorNumber = json.getString("localId"), mainSetSize = json.getInt("printedTotal"),
                        setCode = json.optString("set").takeUnless { it.isBlank() || it == "null" },
                        language = if (json.optString("language") == "de") "GERMAN" else "ENGLISH",
                        finish = finish, variant = if (variant == "reverse") "Reverse Holo" else "Standard",
                        rarity = json.optString("rarity"), hasMarketProduct = false,
                        sourceSetId = sourceSetId, sourceCardId = id,
                    )
                }
        }
    }

    private fun createFrame(fixture: Fixture, case: String): Bitmap {
        val source = requireNotNull(BitmapFactory.decodeFile(fixture.file.absolutePath))
        val frame = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.rgb(35, 39, 45))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bounds = when (case) {
            "small" -> RectF(275f, 385f, 725f, 1015f)
            "off_center" -> RectF(45f, 80f, 675f, 960f)
            "manual_whole" -> RectF(0f, 0f, 1000f, 1400f)
            else -> RectF(140f, 197f, 860f, 1203f)
        }
        try {
            if (case != "blank") {
                canvas.save()
                if (case == "tilted") canvas.rotate(7f, 500f, 700f)
                if (case == "perspective") {
                    val matrix = Matrix()
                    matrix.setPolyToPoly(floatArrayOf(0f, 0f, source.width.toFloat(), 0f, source.width.toFloat(), source.height.toFloat(), 0f, source.height.toFloat()), 0,
                        floatArrayOf(180f, 215f, 825f, 150f, 855f, 1190f, 125f, 1160f), 0, 4)
                    canvas.drawBitmap(source, matrix, paint)
                } else canvas.drawBitmap(source, null, bounds, paint)
                canvas.restore()
            }
        } finally { source.recycle() }
        if (case == "shadow" || case == "uneven") {
            paint.shader = LinearGradient(100f, 0f, 900f, 0f, intArrayOf(0xb0000000.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 1000f, 1400f, paint)
            paint.shader = null
        }
        if (case == "reflection") {
            paint.shader = LinearGradient(300f, 0f, 650f, 0f, intArrayOf(Color.TRANSPARENT, 0xafffffff.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
        }
        val correction = when (case) {
            "room_light" -> colorMatrix(.78f, .78f, .78f, 0f)
            "dim" -> colorMatrix(.48f, .48f, .48f, 0f)
            "low_contrast" -> colorMatrix(.50f, .50f, .50f, 64f)
            "overexposed" -> colorMatrix(1.12f, 1.12f, 1.12f, 30f)
            "warm" -> colorMatrix(1.08f, 1f, .82f, 0f)
            "cold" -> colorMatrix(.82f, 1f, 1.08f, 0f)
            else -> null
        }
        var result = frame
        if (correction != null) {
            result = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(frame, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(correction) })
            frame.recycle()
        }
        if (case == "mild_blur" || case == "low_resolution") {
            val small = Bitmap.createScaledBitmap(result, 400, 560, true)
            val processed = if (case == "low_resolution") small else Bitmap.createScaledBitmap(small, 1000, 1400, true).also { small.recycle() }
            result.recycle(); result = processed
        }
        if (case == "noise" || case == "motion_blur") {
            val width = result.width
            val height = result.height
            val pixels = IntArray(width * height)
            result.getPixels(pixels, 0, width, 0, 0, width, height)
            val processed = IntArray(pixels.size) { index ->
                val pixel = pixels[index]
                if (case == "noise") {
                    val offset = (((index.toLong() * 1_103_515_245L + 12_345L) ushr 16) % 17).toInt() - 8
                    Color.rgb((Color.red(pixel) + offset).coerceIn(0, 255),
                        (Color.green(pixel) + offset).coerceIn(0, 255),
                        (Color.blue(pixel) + offset).coerceIn(0, 255))
                } else {
                    val x = index % width
                    val row = index / width * width
                    var red = 0; var green = 0; var blue = 0
                    for (offset in -3..3) {
                        val sample = pixels[row + (x + offset).coerceIn(0, width - 1)]
                        red += Color.red(sample); green += Color.green(sample); blue += Color.blue(sample)
                    }
                    Color.rgb(red / 7, green / 7, blue / 7)
                }
            }
            result.setPixels(processed, 0, width, 0, 0, width, height)
        }
        if (case in setOf("rotated90", "rotated180", "rotated270")) {
            val degrees = case.removePrefix("rotated").toFloat()
            val rotated = Bitmap.createBitmap(result, 0, 0, result.width, result.height, Matrix().apply { setRotate(degrees) }, true)
            result.recycle(); result = rotated
        }
        return result
    }

    private fun colorMatrix(red: Float, green: Float, blue: Float, offset: Float) = ColorMatrix(floatArrayOf(
        red, 0f, 0f, 0f, offset, 0f, green, 0f, 0f, offset,
        0f, 0f, blue, 0f, offset, 0f, 0f, 0f, 1f, 0f,
    ))

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return JSONObject().apply {
            put("totalPssKb", info.totalPss)
            put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            put("runtimeAllocatedBytes", Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: JSONObject.NULL)
            put("gcCount", Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: JSONObject.NULL)
        }
    }

    private fun summarize(samples: JSONArray): JSONObject {
        val items = (0 until samples.length()).map(samples::getJSONObject)
        val durations = items.map { it.getDouble("elapsedMillis") }.sorted()
        fun percentile(fraction: Double) = durations.getOrNull((ceil(durations.size * fraction).toInt() - 1).coerceAtLeast(0)) ?: 0.0
        val median = if (durations.isEmpty()) 0.0 else if (durations.size % 2 == 0)
            (durations[durations.size / 2 - 1] + durations[durations.size / 2]) / 2.0
        else durations[durations.size / 2]
        return JSONObject().apply {
            put("sampleCount", items.size); put("medianMillis", median); put("p95Millis", percentile(.95))
            put("worstMillis", durations.lastOrNull() ?: 0.0)
            put("numberCorrectCount", items.count { it.getBoolean("numberCorrect") })
            put("numberCorrectScope", "collector_local_id_only")
            put("fullNumberCorrectCount", items.count { it.getBoolean("fullNumberCorrect") })
            put("falseAcceptsFiniteCatalogue", items.count { it.getBoolean("fixtureCatalogueFalseAccept") })
            ScannerDecision.entries.forEach { decision -> put(decision.name, items.count { it.getString("fixtureCatalogueDecision") == decision.name }) }
        }
    }

    private companion object {
        val SUPPORTED_CASES = setOf("clean", "room_light", "dim", "low_contrast", "shadow", "uneven", "overexposed", "warm", "cold", "mild_blur", "low_resolution", "noise", "motion_blur", "small", "off_center", "tilted", "perspective", "reflection", "rotated90", "rotated180", "rotated270", "manual_whole", "blank")
    }
}
