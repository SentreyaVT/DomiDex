package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * Real bundled ML Kit, no network/catalogue or user photos. Geometry is injected so identical
 * ROI keys/dimensions test capture-session isolation without making a real-card accuracy claim.
 */
@RunWith(AndroidJUnit4::class)
class ScannerSessionIsolationInstrumentedTest {
    @Test fun cancellationCleanupSubmissionCannotRaceShutdownWhilePreprocessingOwnsBitmap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        val resourcesBound = CountDownLatch(1)
        val preprocessingOwnsBitmap = CountDownLatch(1)
        val releasePreprocessing = CountDownLatch(1)
        val cleanupSubmissionEntered = CountDownLatch(1)
        val releaseCleanupSubmission = CountDownLatch(1)
        val shutdownRequested = CountDownLatch(1)
        val callbackArrived = CountDownLatch(1)
        val cancellationReturned = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val resourcesReference = AtomicReference<ScanTaskResources<Bitmap>>()
        val cancellationThreadReference = AtomicReference<Thread>()
        val callbacks = AtomicInteger()
        val result = AtomicReference<Result<CardTextAnalysis>>()
        val cancellationFailure = AtomicReference<Throwable>()
        val closeFailure = AtomicReference<Throwable>()
        var cancellationThread: Thread? = null
        var closeThread: Thread? = null
        val analyzer = CardTextAnalyzer(context, normalize = { _, _ ->
            check(resourcesBound.await(5, TimeUnit.SECONDS)) { "Session resources were not bound" }
            // Model an owned, not-yet-leased crop in the preprocessing worker. The real crop
            // builders have the same lifetime before recognizer.process(); no ML Kit is needed
            // to reproduce unsafe shutdown/inline cleanup of such an input deterministically.
            resourcesReference.get().own(fixture)
            preprocessingOwnsBitmap.countDown()
            check(releasePreprocessing.await(45, TimeUnit.SECONDS)) { "Preprocessing fixture was not released" }
            NormalizedCardCapture(fixture, CardImageQuality(reason = "cleanup_submission_race_fixture"))
        })
        val executorField = CardTextAnalyzer::class.java.getDeclaredField("preprocessingExecutor").apply { isAccessible = true }
        val delegate = executorField.get(analyzer) as ExecutorService
        val intercepted = AtomicBoolean()
        val gatedExecutor = object : AbstractExecutorService() {
            override fun execute(command: Runnable) {
                if (Thread.currentThread() === cancellationThreadReference.get() && intercepted.compareAndSet(false, true)) {
                    // Completion has already won, but its cleanup has not entered the worker
                    // queue. Keep that exact window open while analyzer.close() starts.
                    cleanupSubmissionEntered.countDown()
                    check(releaseCleanupSubmission.await(20, TimeUnit.SECONDS)) { "Cleanup submission was not released" }
                }
                delegate.execute(command)
            }

            override fun shutdown() {
                delegate.shutdown()
                shutdownRequested.countDown()
            }

            override fun shutdownNow(): MutableList<Runnable> {
                val discarded = delegate.shutdownNow()
                shutdownRequested.countDown()
                return discarded
            }

            override fun isShutdown(): Boolean = delegate.isShutdown
            override fun isTerminated(): Boolean = delegate.isTerminated
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
        }
        try {
            // Instrumentation-only replacement of an existing private ExecutorService field.
            // Verify the actual installed instance so unsupported reflective writes fail clearly.
            executorField.set(analyzer, gatedExecutor)
            assertTrue("The test executor must replace the existing instance", executorField.get(analyzer) === gatedExecutor)
            val handle = analyzer.analyze(File(context.cacheDir, "cleanup-submission-no-photo")) {
                callbacks.incrementAndGet()
                result.set(it)
                callbackArrived.countDown()
            }
            val resourcesField = handle.javaClass.getDeclaredField("resources").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val resources = resourcesField.get(handle) as ScanTaskResources<Bitmap>
            resourcesReference.set(resources)
            resourcesBound.countDown()
            assertTrue("Preprocessing must own its input before cancellation", preprocessingOwnsBitmap.await(5, TimeUnit.SECONDS))
            assertFalse(fixture.isRecycled)

            val activeCancellationThread = Thread({
                try {
                    handle.close()
                } catch (failure: Throwable) {
                    cancellationFailure.set(failure)
                } finally {
                    cancellationReturned.countDown()
                }
            }, "scanner-cleanup-submission-cancel")
            cancellationThread = activeCancellationThread
            cancellationThreadReference.set(activeCancellationThread)
            activeCancellationThread.start()
            assertTrue("Cancellation must reach its real cleanup submission", cleanupSubmissionEntered.await(5, TimeUnit.SECONDS))

            val activeCloseThread = Thread({
                try {
                    analyzer.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally {
                    closeReturned.countDown()
                }
            }, "scanner-cleanup-submission-close")
            closeThread = activeCloseThread
            activeCloseThread.start()
            // With synchronized finish(), close waits at the session monitor. The old path
            // reaches shutdown instead. Observe either concrete state; do not guess a delay.
            fun closeIsWaitingForCompletion(): Boolean = activeCloseThread.state == Thread.State.BLOCKED &&
                activeCloseThread.stackTrace.any {
                    it.className.endsWith("CardTextAnalyzer\$AnalysisSession") && it.methodName in setOf("finish", "close")
                }
            val closeProgressDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (shutdownRequested.count != 0L && !closeIsWaitingForCompletion() &&
                closeReturned.count != 0L && SystemClock.elapsedRealtime() < closeProgressDeadline) {
                SystemClock.sleep(5)
            }
            assertTrue("Close must reach shutdown or the in-progress session completion",
                shutdownRequested.count == 0L || closeIsWaitingForCompletion())
            releaseCleanupSubmission.countDown()
            assertTrue("Cancellation must return after submission is unblocked", cancellationReturned.await(5, TimeUnit.SECONDS))
            assertTrue("Analyzer close must return", closeReturned.await(5, TimeUnit.SECONDS))
            assertTrue("Cancellation must not throw: ${cancellationFailure.get()}", cancellationFailure.get() == null)
            assertTrue("Analyzer close must not throw: ${closeFailure.get()}", closeFailure.get() == null)
            assertTrue("Cancellation callback must arrive", callbackArrived.await(5, TimeUnit.SECONDS))
            assertTrue(result.get().exceptionOrNull() is CancellationException)
            assertEquals(1, callbacks.get())
            assertEquals("Preprocessing must still be held for the lifetime check", 1L, releasePreprocessing.count)
            assertFalse("Cleanup must not recycle an owned bitmap while preprocessing still uses it", fixture.isRecycled)

            releasePreprocessing.countDown()
            assertTrue("Shutdown must drain queued cleanup", gatedExecutor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue("Bitmap must be recycled once preprocessing has really ended", fixture.isRecycled)
            val sessionsField = CardTextAnalyzer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            assertTrue("Completed cleanup must remove the session", (sessionsField.get(analyzer) as Set<*>).isEmpty())
            instrumentation.waitForIdleSync()
            assertEquals("Late preprocessing must not deliver a second result", 1, callbacks.get())
        } finally {
            // Always unblock both worker and submitting thread, including the expected old-code
            // assertion failure. Never recycle a fixture still owned by a running worker.
            resourcesBound.countDown()
            releaseCleanupSubmission.countDown()
            releasePreprocessing.countDown()
            cancellationThread?.join(5_000L)
            closeThread?.join(5_000L)
            try {
                analyzer.close()
            } finally {
                if (!delegate.isTerminated) {
                    delegate.shutdownNow()
                    delegate.awaitTermination(5, TimeUnit.SECONDS)
                }
                if (delegate.isTerminated && !fixture.isRecycled) fixture.recycle()
            }
        }
    }

    @Test fun repeatedAnalyzerCloseRacingEmptyCaptureCleanupFinishesExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repetitions = 512
        val callbacks = AtomicIntegerArray(repetitions)
        val terminalSuccesses = AtomicInteger()
        val terminalCancellations = AtomicInteger()
        val sessionsField = CardTextAnalyzer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        val executorField = CardTextAnalyzer::class.java.getDeclaredField("preprocessingExecutor").apply { isAccessible = true }
        // This regression needs session cleanup only, not expensive bitmap preparation/ML Kit.
        // One session specifically exercises Kotlin Collection.toList()'s old size == 1 branch:
        // cleanup could remove it between size and iterator.next(), throwing NoSuchElementException.
        val closeOffsetsNanos = longArrayOf(0, 500, 1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000)
        repeat(repetitions) { iteration ->
            val normalizationEntered = CountDownLatch(1)
            val allowNormalization = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val result = AtomicReference<Result<CardTextAnalysis>>()
            val analyzer = CardTextAnalyzer(context, normalize = { _, _ ->
                normalizationEntered.countDown()
                check(allowNormalization.await(5, TimeUnit.SECONDS)) { "Close-race fixture was not released" }
                NormalizedCardCapture(null, CardImageQuality(reason = "close_race_no_card"))
            })
            val executor = executorField.get(analyzer) as ExecutorService
            @Suppress("UNCHECKED_CAST")
            val sessions = sessionsField.get(analyzer) as Set<Any>
            try {
                analyzer.analyze(File(context.cacheDir, "close-race-$iteration-no-photo")) {
                    callbacks.incrementAndGet(iteration)
                    result.set(it)
                    completed.countDown()
                }
                assertTrue("Iteration $iteration: normalization must start", normalizationEntered.await(5, TimeUnit.SECONDS))
                assertEquals("Iteration $iteration: exactly one active session before release", 1, sessions.size)
                when (iteration % 16) {
                    0 -> {
                        // Deterministic cancellation endpoint, with cleanup still behind normalize.
                        analyzer.close()
                        allowNormalization.countDown()
                    }
                    1 -> {
                        // Deterministic successful endpoint, followed by close/cleanup overlap.
                        allowNormalization.countDown()
                        assertTrue("Iteration $iteration: empty capture must finish", completed.await(5, TimeUnit.SECONDS))
                        analyzer.close()
                    }
                    else -> {
                        // Race the real worker's finish/cleanup against close on the test thread.
                        // Vary a small scheduling offset rather than relying on a single sleep.
                        allowNormalization.countDown()
                        val closeAt = SystemClock.elapsedRealtimeNanos() + closeOffsetsNanos[iteration % closeOffsetsNanos.size]
                        while (SystemClock.elapsedRealtimeNanos() < closeAt) { /* bounded sub-millisecond scheduling offset */ }
                        analyzer.close()
                    }
                }
                analyzer.close() // Public close remains idempotent during its own pending cleanup.
                assertTrue("Iteration $iteration: close must shut down the worker", executor.isShutdown)
                assertTrue("Iteration $iteration: worker and cleanup must finish", executor.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue("Iteration $iteration: exactly one terminal callback must arrive", completed.await(5, TimeUnit.SECONDS))
                assertTrue("Iteration $iteration: cleanup must remove the session", sessions.isEmpty())
                assertEquals("Iteration $iteration: terminal callback count", 1, callbacks.get(iteration))
                val terminal = result.get()
                if (terminal.isSuccess) {
                    terminalSuccesses.incrementAndGet()
                    val analysis = terminal.getOrThrow()
                    assertTrue(analysis.lines.isEmpty())
                    assertTrue(analysis.collectorNumber == null)
                    assertTrue(analysis.normalizedCard == null)
                } else {
                    terminalCancellations.incrementAndGet()
                    assertTrue("Iteration $iteration: no failure except intentional cancellation: ${terminal.exceptionOrNull()}",
                        terminal.exceptionOrNull() is CancellationException)
                }
            } finally {
                allowNormalization.countDown()
                try {
                    analyzer.close()
                } finally {
                    // On a regression, do not leak the worker whose close() threw before shutdown.
                    if (!executor.isTerminated) {
                        executor.shutdownNow()
                        executor.awaitTermination(5, TimeUnit.SECONDS)
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        assertTrue("Exercise successful completion as well as cancellation", terminalSuccesses.get() > 0)
        assertTrue("Exercise cancellation as well as successful completion", terminalCancellations.get() > 0)
        repeat(repetitions) { iteration ->
            assertEquals("Iteration $iteration: no delayed duplicate callback after all workers stopped", 1, callbacks.get(iteration))
        }
    }

    @Test fun cancelDuringLeasedNativeOcrReleasesInputsAndAllowsAnotherIdentity() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val first = SyntheticIdentity("native-cancellation-A", "Pikachu ex", "PBL", "094/084")
        val second = SyntheticIdentity("native-cancellation-B", "Glurak ex", "OBF", "098/197")
        val fixtures = listOf(first, second).associateBy(SyntheticIdentity::fileName)
        val firstCallbacks = AtomicInteger()
        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val firstResult = AtomicReference<Result<CardTextAnalysis>>()
        val secondResult = AtomicReference<Result<CardTextAnalysis>>()
        val measurement = AtomicReference<CardTextAnalysisMetrics>()
        try {
            CardTextAnalyzer(
                context,
                normalize = { file, _ -> createNormalizedFixture(requireNotNull(fixtures[file.name])) },
                metricsObserver = measurement::set,
            ).use { analyzer ->
                val handle = analyzer.analyze(File(context.cacheDir, first.fileName)) {
                    firstCallbacks.incrementAndGet()
                    firstResult.set(it)
                    firstFinished.countDown()
                }
                // Test-local observation only: production exposes no diagnostic getters/hooks.
                val sessionsField = CardTextAnalyzer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val sessions = sessionsField.get(analyzer) as Set<Any>
                assertTrue("The cancellable handle must be the active capture session", sessions.any { it === handle })
                val resourcesField = handle.javaClass.getDeclaredField("resources").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val resources = resourcesField.get(handle) as ScanTaskResources<Bitmap>
                val ownedField = resources.javaClass.getDeclaredField("owned").apply { isAccessible = true }
                val executorField = CardTextAnalyzer::class.java.getDeclaredField("preprocessingExecutor").apply { isAccessible = true }
                val executor = executorField.get(analyzer) as ExecutorService
                assertEquals("The launch barrier requires the existing serial OCR worker", 1, (executor as ThreadPoolExecutor).maximumPoolSize)

                fun ownedSnapshot(): Map<Bitmap, Int> = synchronized(resources) {
                    @Suppress("UNCHECKED_CAST")
                    (ownedField.get(resources) as Map<Bitmap, Int>).toMap()
                }

                var ownedAtCancellation: List<Bitmap>? = null
                val launchDeadline = SystemClock.elapsedRealtime() + 12_000L
                while (ownedAtCancellation == null && SystemClock.elapsedRealtime() < launchDeadline) {
                    val previouslyLeased = ownedSnapshot().filterValues { it > 0 }.keys
                    if (previouslyLeased.isNotEmpty()) {
                        // A lease alone precedes process(image) by a few instructions. This FIFO
                        // barrier proves its launch block (including real ML Kit submission) ended.
                        // Never wait for the executor while holding the resource monitor.
                        executor.submit {}.get(3, TimeUnit.SECONDS)
                        synchronized(resources) {
                            val now = ownedSnapshot()
                            val stillLeased = previouslyLeased.filter { (now[it] ?: 0) > 0 }
                            if (stillLeased.isNotEmpty()) {
                                assertEquals("Must cancel before any terminal result", 0, firstCallbacks.get())
                                assertTrue("Native inputs must remain valid while leased", stillLeased.none(Bitmap::isRecycled))
                                ownedAtCancellation = now.keys.toList()
                                // releaseLease() uses this same monitor: completion cannot race
                                // between observing the in-flight input and cancelling its session.
                                handle.close()
                                assertTrue("Cancellation must not recycle leased native inputs", stillLeased.none(Bitmap::isRecycled))
                            }
                        }
                    }
                    if (ownedAtCancellation == null) {
                        assertEquals("OCR finished before a native lease could be verified", 0, firstCallbacks.get())
                        // Poll a proven state, not a guessed fixed delay before cancellation.
                        SystemClock.sleep(10)
                    }
                }
                assertTrue("Must observe a submitted ML Kit input before cancelling", ownedAtCancellation != null)
                assertTrue("Cancellation callback must arrive", firstFinished.await(5, TimeUnit.SECONDS))
                assertTrue(firstResult.get().exceptionOrNull() is CancellationException)
                assertEquals(1, firstCallbacks.get())
                assertTrue("Cancelled capture really invoked ML Kit", measurement.get().ocrCalls > 0)

                val releaseDeadline = SystemClock.elapsedRealtime() + 25_000L
                while ((ownedSnapshot().isNotEmpty() || sessions.any { it === handle }) &&
                    SystemClock.elapsedRealtime() < releaseDeadline) {
                    SystemClock.sleep(10)
                }
                assertTrue("Actual native completion must release every outstanding lease", ownedSnapshot().isEmpty())
                assertFalse("Cancelled session must be removed after cleanup", sessions.any { it === handle })
                assertTrue("All cancelled capture bitmaps must be recycled after native completion", requireNotNull(ownedAtCancellation).all(Bitmap::isRecycled))

                val secondHandle = analyzer.analyze(File(context.cacheDir, second.fileName)) {
                    secondResult.set(it)
                    secondFinished.countDown()
                }
                assertTrue("The same analyzer must accept a subsequent capture", secondFinished.await(25, TimeUnit.SECONDS))
                val analysis = secondResult.get().getOrThrow()
                logCompletedProbeDiagnostics(second.fileName, analyzer, secondHandle, analysis, measurement.get(),
                    previousTimingHandle = handle)
                assertTrue("The subsequent capture must actually invoke ML Kit", measurement.get().ocrCalls > 0)
                assertEquals(second.number, analysis.collectorNumber)
                assertEquals(second.setCode, analysis.setIdentifier)
                assertTrue(analysis.searchQueries.any { it.contains("Glurak", ignoreCase = true) })
                assertFalse(analysis.searchQueries.any { it.contains("Pikachu", ignoreCase = true) })
                assertFalse(analysis.roiEvidence.flatMap(CardOcrEvidence::rawValues).any {
                    it.contains(first.number) || it.contains("Pikachu", ignoreCase = true)
                })
                assertFalse(requireNotNull(analysis.normalizedCard).isRecycled)
                instrumentation.waitForIdleSync()
                assertEquals("Late OCR completion must never deliver a second cancellation result", 1, firstCallbacks.get())
            }
        } finally {
            listOfNotNull(firstResult.get()?.getOrNull(), secondResult.get()?.getOrNull())
                .mapNotNull(CardTextAnalysis::normalizedCard).distinct().forEach {
                    if (!it.isRecycled) it.recycle()
                }
        }
    }

    @Test fun sameAnalyzerReadsNewIdentityFromSecondSameSizedCapture() {
        assertTrue(OpenCVLoader.initLocal())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = SyntheticIdentity("session-isolation-A", "Pikachu ex", "PBL", "094/084")
        val second = SyntheticIdentity("session-isolation-B", "Glurak ex", "OBF", "098/197")
        val fixtures = listOf(first, second).associateBy(SyntheticIdentity::fileName)
        val normalizationCalls = AtomicInteger()
        val measurement = AtomicReference<CardTextAnalysisMetrics>()
        val retainedResults = mutableListOf<CardTextAnalysis>()
        try {
            CardTextAnalyzer(
                context,
                normalize = { file, _ ->
                    normalizationCalls.incrementAndGet()
                    createNormalizedFixture(requireNotNull(fixtures[file.name]))
                },
                metricsObserver = measurement::set,
            ).use { analyzer ->
                fun runFixture(fixture: SyntheticIdentity): CardTextAnalysis {
                    val completed = CountDownLatch(1)
                    val result = AtomicReference<Result<CardTextAnalysis>>()
                    val captureHandle = analyzer.analyze(File(context.cacheDir, fixture.fileName)) {
                        result.set(it)
                        completed.countDown()
                    }
                    assertTrue("Real OCR must complete within its existing deadline", completed.await(25, TimeUnit.SECONDS))
                    val analysis = result.get().getOrThrow()
                    retainedResults += analysis
                    logCompletedProbeDiagnostics(fixture.fileName, analyzer, captureHandle, analysis, measurement.get())
                    assertTrue("Each capture must actually invoke ML Kit", measurement.get().ocrCalls > 0)
                    assertEquals("Fixture ${fixture.fileName}: ${analysis.lines}", fixture.number, analysis.collectorNumber)
                    assertEquals(fixture.setCode, analysis.setIdentifier)
                    assertEquals(AnalysisOrientation.DEG_0, analysis.analysisOrientation)
                    assertEquals("", analysis.imageQuality.reason)
                    assertTrue("Name ROI must read the current fixture: ${analysis.searchQueries}", analysis.searchQueries.any {
                        it.contains(fixture.name.substringBefore(' '), ignoreCase = true)
                    })
                    return analysis
                }

                val analysisA = runFixture(first)
                val analysisB = runFixture(second)
                assertEquals(2, normalizationCalls.get())
                assertTrue(analysisA.collectorNumber != analysisB.collectorNumber)
                assertFalse("No stale name query from A may enter B", analysisB.searchQueries.any {
                    it.contains("Pikachu", ignoreCase = true)
                })
                val secondRawEvidence = analysisB.roiEvidence.flatMap(CardOcrEvidence::rawValues)
                assertFalse("No stale A identity may enter B's raw evidence", secondRawEvidence.any {
                    it.contains(first.number) || it.contains("Pikachu", ignoreCase = true)
                })
                // Returning a second result must not consume or mutate the first result's bitmap.
                assertFalse(requireNotNull(analysisA.normalizedCard).isRecycled)
                assertFalse(requireNotNull(analysisB.normalizedCard).isRecycled)
                assertTrue(analysisA.normalizedCard !== analysisB.normalizedCard)
            }
        } finally {
            retainedResults.mapNotNull(CardTextAnalysis::normalizedCard).distinct().forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    /**
     * Read-only diagnostics after the genuine terminal callback, before unchanged assertions.
     * No extra ML Kit call, new bitmap, modified timeout, callback replacement or warm-up.
     * Absence is deliberately NOT classified as queued, running, failed or timed out.
     */
    private fun logCompletedProbeDiagnostics(
        label: String,
        analyzer: CardTextAnalyzer,
        handle: AutoCloseable,
        analysis: CardTextAnalysis,
        metrics: CardTextAnalysisMetrics?,
        previousTimingHandle: AutoCloseable? = null,
    ) {
        val tag = "DomiDexOcrTest"
        try {
            check(label in setOf("native-cancellation-B", "session-isolation-A", "session-isolation-B"))
            val token = InstrumentationRegistry.getArguments().getString("domidexRegressionRunId")
            check(token != null && token.matches(Regex("[a-f0-9]{32}")))
            val measured = checkNotNull(metrics)
            val executorField = CardTextAnalyzer::class.java.getDeclaredField("preprocessingExecutor").apply { isAccessible = true }
            val executor = executorField.get(analyzer) as ExecutorService
            val readingsField = handle.javaClass.getDeclaredField("readings").apply { isAccessible = true }
            fun timingSnapshot(target: AutoCloseable): OcrTimingSnapshot {
                val field = target.javaClass.getDeclaredField("timing").apply { isAccessible = true }
                return checkNotNull(field.get(target) as OcrTimingTrace?).snapshot()
            }
            check(previousTimingHandle == null || label == "native-cancellation-B")
            // The real map belongs to this exact AnalysisSession. Read it on its existing
            // serial worker, after its completion/cleanup queue; do not race mutableMap.
            // This bounded post-result observation does not extend any OCR deadline.
            val (snapshot, timingSnapshots) = executor.submit(java.util.concurrent.Callable {
                @Suppress("UNCHECKED_CAST")
                val readings = (readingsField.get(handle) as Map<OcrReadKey, String>).toMap()
                val timings = buildList {
                    if (previousTimingHandle != null) add("native-cancellation-A" to timingSnapshot(previousTimingHandle))
                    add(label to timingSnapshot(handle))
                }
                readings to timings
            }).get(5, TimeUnit.SECONDS)
            val modern = CardOcrRois.profiles.single { it.id == "modern" }
            val strip = CardOcrRois.profiles.single { it.id == "full_art_left" }.number
            val border = CardOcrRois.profiles.single { it.id == "extended_lower_border" }
            val expected = mutableListOf<Pair<String, OcrReadKey>>()
            fun slots(profile: String, roi: CardRoi, vararg variants: String) {
                variants.forEach { variant ->
                    expected += "$profile/$variant" to OcrReadKey(
                        roi, variant, AnalysisOrientation.DEG_0,
                        CardScanConfiguration.RECOGNITION_WIDTH, CardScanConfiguration.RECOGNITION_HEIGHT,
                    )
                }
            }
            // Same14 descriptors as the independently frozen probe-preparation test.
            // No buildOrientationProbeInputs call: diagnostics allocate no OCR pixels.
            slots("rc3_name", modern.name, "original", "enhanced")
            slots("rc3_set", modern.set, "original", "enhanced", "inverted")
            slots("rc3_number", modern.number, "original", "enhanced", "inverted")
            slots("identity_strip", strip, "original", "enhanced")
            slots("extended_border", border.set.union(border.number), "original", "enhanced")
            slots("historical_name", historicalNameFallbackRoi, "original")
            slots("historical_identity", historicalIdentityFallbackRoi, "original")
            check(expected.size == 14 && expected.map { it.second }.toSet().size == 14)
            fun base(event: String) = org.json.JSONObject().put("event", event)
                .put("runToken", token).put("fixture", label)
            fun emit(payload: org.json.JSONObject) {
                val encoded = payload.toString()
                check(encoded.toByteArray(Charsets.UTF_8).size <= 3_500)
                android.util.Log.i(tag, encoded)
            }
            emit(base("completed_probe_summary")
                .put("elapsedNanos", measured.elapsedNanos)
                .put("stagesNanos", org.json.JSONObject(measured.stageDurationsNanos))
                .put("ocrCalls", measured.ocrCalls).put("cacheHitInputs", measured.cacheHitInputs)
                .put("preparedOcrBitmaps", measured.preparedOcrBitmaps)
                .put("preparedOcrBitmapBytes", measured.preparedOcrBitmapBytes)
                .put("failureType", measured.failureType ?: org.json.JSONObject.NULL)
                .put("qualityReason", analysis.imageQuality.reason)
                .put("orientation", analysis.analysisOrientation?.degrees ?: org.json.JSONObject.NULL)
                .put("orientationScore", analysis.orientationScore).put("orientationMargin", analysis.orientationMargin)
                .put("collectorNumber", analysis.collectorNumber ?: org.json.JSONObject.NULL)
                .put("setIdentifier", analysis.setIdentifier ?: org.json.JSONObject.NULL)
                .put("ocrDurationMillis", analysis.ocrDurationMillis)
                .put("expectedProbeKeys", 14).put("allCompletedSessionKeys", snapshot.size)
                .put("completedProbeKeys", expected.count { snapshot.containsKey(it.second) })
                .put("scope", "terminal-session-cache; no per-ROI submission/failure/timing classification"))
            expected.forEachIndexed { index, (slot, key) ->
                val present = snapshot.containsKey(key)
                val reading = snapshot[key]
                val bytes = reading?.toByteArray(Charsets.UTF_8)
                val entry = base("completed_probe_slot").put("index", index).put("slot", slot)
                    .put("state", when { !present -> "not_completed"; reading!!.isBlank() -> "completed_blank"; else -> "completed_text" })
                    .put("key", key.toString()).put("utf8Bytes", bytes?.size ?: org.json.JSONObject.NULL)
                    .put("sha256", bytes?.let { value ->
                        java.security.MessageDigest.getInstance("SHA-256").digest(value)
                            .joinToString("") { "%02x".format(it) }
                    } ?: org.json.JSONObject.NULL)
                // Synthetic text only. Preserve state/length/hash, not a fabricated/truncated
                // text value, if an unexpected recognizer result exceeds this log budget.
                if (bytes != null && bytes.size <= 768) entry.put("text", reading)
                else entry.put("text", org.json.JSONObject.NULL).put("textOmittedOversize", bytes != null)
                emit(entry)
            }
            emit(base("completed_probe_end").put("slotRecords", 14))
            // Supplementary test-only metadata on the already-collected Scanner tag. Existing
            // DomiDexOcrTest's exact 16-record grammar is unchanged. Never emit recognized text.
            timingSnapshots.forEach { (timingLabel, trace) ->
                fun timingBase(event: String) = org.json.JSONObject().put("event", event)
                    .put("runToken", token).put("fixture", timingLabel)
                fun emitTiming(payload: org.json.JSONObject) {
                    val encoded = payload.toString()
                    check(encoded.toByteArray(Charsets.UTF_8).size <= 3_500)
                    android.util.Log.i("DomiDexScanner", encoded)
                }
                emitTiming(timingBase("ocr_timing_summary").put("records", trace.entries.size)
                    .put("observed", trace.observed).put("dropped", trace.dropped)
                    .put("clockRegressed", trace.clockRegressed).put("clockFailures", trace.clockFailures)
                    .put("scope", "snapshot-only; events after this read are not observed; close-return is not native-unload completion"))
                trace.entries.forEach { entry ->
                    val row = timingBase("ocr_timing_entry").put("ordinal", entry.ordinal)
                        .put("elapsedNanos", entry.elapsedNanos).put("stage", entry.event.name)
                        .put("probe", entry.probe ?: org.json.JSONObject.NULL)
                        .put("outcome", entry.outcome?.name ?: org.json.JSONObject.NULL)
                        .put("callId", entry.call?.id ?: org.json.JSONObject.NULL)
                    entry.call?.let { call ->
                        row.put("profile", call.profile).put("variant", call.variant)
                            .put("orientation", call.orientation ?: org.json.JSONObject.NULL)
                            .put("regions", org.json.JSONArray(call.regions)).put("roi", org.json.JSONArray(call.roi))
                    }
                    emitTiming(row)
                }
                emitTiming(timingBase("ocr_timing_end").put("records", trace.entries.size)
                    .put("observed", trace.observed).put("dropped", trace.dropped))
            }
        } catch (failure: Exception) {
            // A diagnostic failure never replaces or relaxes the original business assertion.
            // This marker invalidates diagnostic completeness, not the original test contract.
            android.util.Log.e(tag, "diagnostic_unavailable fixture=$label type=" + failure.javaClass.simpleName)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    private data class SyntheticIdentity(val fileName: String, val name: String, val setCode: String, val number: String)

    private fun createNormalizedFixture(identity: SyntheticIdentity): NormalizedCardCapture {
        val plane = Bitmap.createBitmap(
            CardScanConfiguration.RECOGNITION_WIDTH,
            CardScanConfiguration.RECOGNITION_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        try {
            Canvas(plane).apply {
                drawColor(Color.rgb(235, 238, 220))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                    textSize = 86f
                }
                drawText(identity.name, 130f, 255f, paint)
                paint.textSize = 48f
                // Fits inside both independent modern SET and NUMBER probe ROIs.
                drawText("${identity.setCode} ${identity.number}", 230f, 1480f, paint)
                // The tight identity strip repeats the same information, never a different pair.
                paint.textSize = 42f
                drawText("${identity.setCode} ${identity.number}", 35f, 1630f, paint)
            }
            val display = Bitmap.createScaledBitmap(
                plane,
                CardScanConfiguration.NORMALIZED_WIDTH,
                CardScanConfiguration.NORMALIZED_HEIGHT,
                true,
            )
            return NormalizedCardCapture(
                bitmap = display,
                quality = CardImageQuality(
                    cardDetected = true,
                    frameConfidence = .95f,
                    sharpness = .95f,
                    exposure = .85f,
                    perspective = 1f,
                    geometryScore = .95f,
                    frameQualityScore = .95f,
                ),
                analysisVariants = listOf(CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_0, plane)),
            )
        } catch (failure: Throwable) {
            if (!plane.isRecycled) plane.recycle()
            throw failure
        }
    }
}
