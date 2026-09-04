package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in replay of a local owner-provided photo; no upload, collection mutation or bundled photos. */
@RunWith(AndroidJUnit4::class)
class ScannerRealCaptureRegressionInstrumentedTest {
    @Test fun identifiesLocalCaptureUsingExistingPipeline() {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("scanFixture")
        assumeTrue("Pass a private cache fixture with scanFixture", name != null)
        require(name!!.matches(Regex("[A-Za-z0-9_-]+\\.jpg")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, name)
        assertTrue("Copy the owner-provided fixture into the emulator cache first", file.isFile)
        val originalHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        val normalized = CardImageNormalizer.normalize(file, allowManualWholeImageFallback = true)
        Log.i("DomiDexReplay", "geometry=${normalized.quality} corners=${normalized.corners}")
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { ocr ->
                normalized.analysisVariants.forEach { variant ->
                    File(context.cacheDir, "replay-plane-${variant.orientation.degrees}.png").outputStream().use {
                        variant.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val roi = CardRoi(0f, .80f, 1f, 1f).crop(variant.bitmap)
                    try {
                        val text = Tasks.await(ocr.process(InputImage.fromBitmap(roi, 0)), 10, TimeUnit.SECONDS).text
                        Log.i("DomiDexReplay", "lower-${variant.orientation.degrees}=$text")
                    } finally { roi.recycle() }
                }
            }
        } finally {
            (normalized.analysisVariants.map { it.bitmap } + listOfNotNull(normalized.bitmap, normalized.originalCrop, normalized.recognitionBitmap))
                .distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<CardTextAnalysis>>()
        CardTextAnalyzer(context).use { analyzer ->
            analyzer.analyze(file, allowManualWholeImageFallback = true) { result.set(it); latch.countDown() }
            assertTrue("Recognition timed out", latch.await(35, TimeUnit.SECONDS))
        }
        val analysis = result.get().getOrThrow()
        try {
            Log.i("DomiDexReplay", "identity=${analysis.setIdentifier}/${analysis.collectorNumber} names=${analysis.searchQueries} quality=${analysis.imageQuality}")
            assertEquals("The original capture must stay unchanged", originalHash,
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList())
            if (args.getString("expectUnresolved") == "true") {
                assertEquals(null, analysis.collectorNumber)
                assertTrue("Unresolved identity must stay blocked", analysis.imageQuality.reason.startsWith("orientation_evidence_"))
                return
            }
            assertEquals(args.getString("expectedSet"), analysis.setIdentifier)
            assertEquals(args.getString("expectedNumber"), analysis.collectorNumber)
            assertTrue("Must use a verified card plane", analysis.imageQuality.cardDetected)
            val expectedReason = args.getString("expectedReason").orEmpty()
            assertEquals(expectedReason, analysis.imageQuality.reason)
            if (expectedReason == MANUAL_SOFT_CARD_FALLBACK_REASON) {
                val strict = CardImageNormalizer.normalize(file)
                assertEquals("Automatic quality gate remains strict", "image_too_soft", strict.quality.reason)
                assertEquals(null, strict.bitmap)
                val constrained = constrainManualWholeImageDecision(analysis,
                    ScannerDecisionResult(ScannerDecision.ACCEPTED, 99, 20, "test_strong_identity"))
                assertEquals("Soft photos still require confirmation", ScannerDecision.AMBIGUOUS, constrained.decision)
            }
            args.getString("expectedCardId")?.let { expectedCardId ->
                val cards = runBlocking {
                    withTimeout(45_000) {
                        PokemonVisualCatalog().findExact(analysis.setIdentifier!!, analysis.collectorNumber!!)
                    }
                }
                Log.i("DomiDexReplay", "catalog=${cards.map { it.id }}")
                assertTrue("The real source must contain the exact card", cards.any { it.id == expectedCardId })
                val candidates = cards.flatMap { card -> card.printVariants.map { printing ->
                    val source = scannerCatalogResult(card, printing, null)
                    ScanCandidate(source, CardRecognitionRanker.score(analysis, source))
                } }.filter { it.score >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE }
                assertTrue("Exact catalogue card must survive ranking", candidates.any { it.card.sourceCardId == expectedCardId })
                val ranked = HybridCandidateFusion.rank(analysis, candidates, emptyMap())
                val decision = constrainManualWholeImageDecision(analysis, HybridCandidateFusion.decide(ranked))
                Log.i("DomiDexReplay", "decision=${decision.decision} reason=${decision.reason}")
                assertTrue("Source identity must remain a selectable candidate", decision.decision != ScannerDecision.REJECTED)
            }
        } finally { analysis.normalizedCard?.let { if (!it.isRecycled) it.recycle() } }
    }
}
