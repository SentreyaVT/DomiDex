package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * Actual CardTextAnalyzer, real Handler deadlines, parsers, bitmap preparation and GMS Tasks.
 * Only its one session's still-uninitialized recognizer delegate is replaced by a controlled
 * TextRecognizer proxy. No actual native OCR, camera, network, catalogue or collection writes.
 *
 * Four outstanding submitted tasks are characterized, not asserted to be four native threads.
 * These tests neither create a global-two-task policy nor claim a speed/accuracy improvement.
 */
@RunWith(AndroidJUnit4::class)
class ScannerBatchTransitionInstrumentedTest {
    @Test fun healthyControlledTasksPreserveActualAnalyzerIdentityAndCleanup() =
        withHarness(Mode.HEALTHY) { harness ->
            harness.awaitOpenTasks(2)
            harness.completeToResult()
            val analysis = harness.assertSuccessfulIdentity()
            assertEquals(2, harness.transport.maximumOutstanding.get())
            assertTrue("Both actual OCR stages must have run", harness.transport.records().size > PROBE_INPUT_COUNT)
            assertEquals(0, harness.transport.outstanding().size)
            assertEquals(1, harness.callbacks.get())
            assertTrue(harness.callbackOnMain.get())
            assertFalse(requireNotNull(analysis.normalizedCard).isRecycled)
            val metrics = checkNotNull(harness.measurement.get())
            assertTrue(metrics.stageDurationsNanos.containsKey("probe_ocr_wall"))
            assertTrue(metrics.stageDurationsNanos.containsKey("full_ocr_wall"))
        }

    @Test fun probeDeadlineCanOverlapFourControlledTasksWithoutLateIdentityContamination() =
        withHarness(Mode.HOLD_LAST_TWO_PROBES) { harness ->
            val overlap = harness.awaitActualBatchOverlap()
            val beforeLateCache = harness.readingsSnapshot()
            val firstFull = overlap.drop(2)
            assertTrue("Hold the full batch while old probes finish", firstFull.all { !it.completion.task.isComplete })

            // Real GMS completion callbacks, on the real analyzer's DIRECT_EXECUTOR. These
            // results belong to the already-delivered probe batch, not the active full batch.
            overlap.take(2).forEach { it.complete(LATE_IDENTITY) }
            harness.workerBarrier()
            assertEquals("Late probe responses cannot warm the session cache", beforeLateCache, harness.readingsSnapshot())
            assertEquals("Only the two held full-batch tasks remain", firstFull, harness.transport.outstanding())
            assertTrue(overlap.take(2).all { it.bitmap.isRecycled })
            assertTrue(firstFull.all { !it.bitmap.isRecycled })
            assertEquals(0, harness.callbacks.get())

            harness.completeToResult()
            harness.assertSuccessfulIdentity()
            assertEquals("Characterization, NOT a new normative per-session cap", 4, harness.transport.maximumOutstanding.get())
            assertEquals(0, harness.transport.outstanding().size)
            val metrics = checkNotNull(harness.measurement.get())
            assertTrue("The original five-second probe deadline actually elapsed",
                checkNotNull(metrics.stageDurationsNanos["probe_ocr_wall"]) >= TimeUnit.MILLISECONDS.toNanos(4_900))
            assertTrue(metrics.stageDurationsNanos.containsKey("full_ocr_wall"))
            assertEquals(1, harness.callbacks.get())
        }

    @Test fun closeDuringActualBatchOverlapRetainsLeasesUntilControlledTasksReallyFinish() =
        withHarness(Mode.HOLD_LAST_TWO_PROBES) { harness ->
            val held = harness.awaitActualBatchOverlap()
            val callsBeforeClose = harness.transport.records().size
            harness.closeActualAnalyzer()
            harness.awaitWorkerTermination()
            harness.awaitCallback()
            assertTrue(harness.result.get()?.exceptionOrNull() is CancellationException)
            assertEquals(1, harness.callbacks.get())
            assertEquals(1, harness.transport.closeCalls.get())
            assertEquals(callsBeforeClose, harness.transport.records().size)
            val owned = harness.ownedSnapshot()
            assertEquals(held.size, owned.size)
            held.forEach { task ->
                assertEquals(1, owned[task.bitmap])
                assertFalse("Close must retain a still-leased input", task.bitmap.isRecycled)
                assertFalse(task.completion.task.isComplete)
            }
            assertEquals(4, harness.transport.outstanding().size)

            held.forEachIndexed { index, task ->
                task.complete(LATE_IDENTITY)
                assertTrue("Actual task completion releases its closed-session bitmap", task.bitmap.isRecycled)
                assertEquals(held.size - index - 1, harness.ownedSnapshot().size)
            }
            harness.drainMainQueue()
            assertTrue(harness.ownedSnapshot().isEmpty())
            assertEquals(0, harness.transport.outstanding().size)
            assertEquals(0, harness.transport.submissionsAfterClose.get())
            assertEquals(callsBeforeClose, harness.transport.records().size)
            assertEquals("Late callbacks must not redeliver the session", 1, harness.callbacks.get())
        }

    private enum class Mode { HEALTHY, HOLD_LAST_TWO_PROBES }

    private class ControlledTask(
        val index: Int,
        val image: InputImage,
        val bitmap: Bitmap,
        val completion: TaskCompletionSource<Text> = TaskCompletionSource(),
    ) {
        fun complete(text: String) {
            check(completion.trySetResult(Text(text, emptyList<Text.TextBlock>()))) { "Task completed twice: " + index }
        }
    }

    private class ControlledRecognizer(
        private val mode: Mode,
        private val newlyLeasedBitmap: (List<Bitmap>) -> Bitmap,
    ) {
        private val lock = Any()
        private val submitted = mutableListOf<ControlledTask>()
        private var closed = false
        val maximumOutstanding = AtomicInteger()
        val closeCalls = AtomicInteger()
        val submissionsAfterClose = AtomicInteger()
        val unexpected = AtomicReference<Throwable?>()
        val recognizer: TextRecognizer

        init {
            check(TextRecognizer::class.java.isInterface) { "SDK TextRecognizer is not proxyable" }
            recognizer = Proxy.newProxyInstance(
                TextRecognizer::class.java.classLoader,
                arrayOf(TextRecognizer::class.java),
            ) { proxy, method, args ->
                try {
                    when {
                        method.name == "process" && method.parameterTypes.contentEquals(arrayOf(InputImage::class.java)) -> {
                            val input = checkNotNull(args?.singleOrNull() as? InputImage)
                            // Do not hold the fake monitor while reading the real resource owner.
                            val prior = records().map(ControlledTask::bitmap)
                            val bitmap = newlyLeasedBitmap(prior)
                            val task = synchronized(lock) {
                                if (closed) {
                                    submissionsAfterClose.incrementAndGet()
                                    error("Production submitted a task after recognizer.close")
                                }
                                ControlledTask(submitted.size, input, bitmap).also {
                                    submitted += it
                                    val pending = submitted.count { record -> !record.completion.task.isComplete }
                                    maximumOutstanding.updateAndGet { previous -> maxOf(previous, pending) }
                                }
                            }
                            // Completing outside the fake lock is essential: GMS callbacks may
                            // execute synchronously, release actual leases and enqueue real work.
                            if (mode == Mode.HOLD_LAST_TWO_PROBES && task.index < PROBE_INPUT_COUNT - 2) {
                                task.complete(EXPECTED_IDENTITY)
                            }
                            task.completion.task
                        }
                        method.name == "close" && method.parameterCount == 0 -> {
                            synchronized(lock) { closed = true; closeCalls.incrementAndGet() }
                            // Deliberately does not complete tasks. Close is not evidence of
                            // native work ending, and must not make the lifetime test pass falsely.
                            null
                        }
                        method.name == "toString" && method.parameterCount == 0 -> "ControlledTextRecognizer"
                        method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                        method.name == "equals" && method.parameterCount == 1 -> proxy === args?.get(0)
                        else -> error("Unexpected SDK method: " + method.toGenericString())
                    }
                } catch (failure: Throwable) {
                    unexpected.compareAndSet(null, failure)
                    throw failure
                }
            } as TextRecognizer
        }

        fun records(): List<ControlledTask> = synchronized(lock) { submitted.toList() }
        fun outstanding(): List<ControlledTask> = records().filter { !it.completion.task.isComplete }

        fun abortOutstanding() {
            // No monitor held during GMS callbacks. trySetException is cleanup-only; the actual
            // case uses complete(), whose duplicate-completion failure is not swallowed.
            outstanding().forEach { it.completion.trySetException(CancellationException("Controlled test teardown")) }
        }
    }

    private class Harness(private val mode: Mode) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val normalizeEntered = CountDownLatch(1)
        private val releaseNormalize = CountDownLatch(1)
        private val callbackArrived = CountDownLatch(1)
        private val createdPlanes = AtomicReference<List<Bitmap>>(emptyList())
        private val resources = AtomicReference<ScanTaskResources<Bitmap>?>()
        private var handle: AutoCloseable? = null
        private var transportOrNull: ControlledRecognizer? = null
        val transport get() = checkNotNull(transportOrNull)
        val callbacks = AtomicInteger()
        val callbackOnMain = AtomicBoolean()
        val result = AtomicReference<Result<CardTextAnalysis>?>(null)
        val measurement = AtomicReference<CardTextAnalysisMetrics?>()
        private val analyzer = CardTextAnalyzer(
            context,
            normalize = { _, _ ->
                normalizeEntered.countDown()
                check(releaseNormalize.await(10, TimeUnit.SECONDS)) { "Recognizer setup was not released" }
                normalizedFixture().also { createdPlanes.set(listOfNotNull(it.bitmap) + it.analysisVariants.map { variant -> variant.bitmap }) }
            },
            metricsObserver = measurement::set,
        )
        private val executor = CardTextAnalyzer::class.java.getDeclaredField("preprocessingExecutor").run {
            isAccessible = true
            get(analyzer) as ExecutorService
        }

        fun start() {
            check(handle == null)
            handle = analyzer.analyze(File(context.cacheDir, "controlled-batch-transition-no-photo")) {
                callbacks.incrementAndGet()
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                result.set(it)
                callbackArrived.countDown()
            }
            assertTrue("Actual normalize must be blocked before injection", normalizeEntered.await(3, TimeUnit.SECONDS))
            val session = checkNotNull(handle)
            val sessionField = CardTextAnalyzer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            assertTrue("Only the returned active session may be instrumented", (sessionField.get(analyzer) as Set<*>).any { it === session })
            val resourcesField = session.javaClass.getDeclaredField("resources").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val actualResources = resourcesField.get(session) as ScanTaskResources<Bitmap>
            resources.set(actualResources)
            assertTrue(ownedSnapshot().isEmpty())
            val fake = ControlledRecognizer(mode) { alreadySubmitted ->
                val newLeases = ownedSnapshot().filter { (bitmap, count) ->
                    count > 0 && alreadySubmitted.none { it === bitmap }
                }.keys
                check(newLeases.size == 1) { "Each real process call must have exactly one newly leased input" }
                newLeases.single().also { check(!it.isRecycled) }
            }
            transportOrNull = fake
            val delegateField = session.javaClass.getDeclaredField("recognizer" + '$' + "delegate").apply { isAccessible = true }
            val original = delegateField.get(session) as? Lazy<*>
                ?: error("Unexpected recognizer delegate representation")
            assertFalse("Never replace an already active native recognizer", original.isInitialized())
            val replacement: Lazy<TextRecognizer> = lazyOf(fake.recognizer)
            delegateField.set(session, replacement)
            assertSame("ART must actually accept the per-session delegate replacement", replacement, delegateField.get(session))
            assertSame(fake.recognizer, (delegateField.get(session) as Lazy<*>).value)
            assertFalse("The production factory must remain uninitialized", original.isInitialized())
            releaseNormalize.countDown()
        }

        fun awaitOpenTasks(count: Int) {
            awaitState("Expected exact outstanding task count " + count, 5_000) {
                transport.outstanding().size == count
            }
            assertTrue("Actual production process calls must reach the installed proxy", transport.records().isNotEmpty())
        }

        fun awaitActualBatchOverlap(): List<ControlledTask> {
            awaitState("Original probe deadline must reach two full-batch submissions", 12_000) {
                transport.records().size >= PROBE_INPUT_COUNT + 2
            }
            workerBarrier()
            val records = transport.records()
            assertEquals(PROBE_INPUT_COUNT + 2, records.size)
            assertEquals(0, callbacks.get())
            assertTrue(records.take(PROBE_INPUT_COUNT - 2).all { it.completion.task.isSuccessful })
            val held = records.takeLast(4)
            assertEquals(listOf(12, 13, 14, 15), held.map(ControlledTask::index))
            assertEquals(held, transport.outstanding())
            assertEquals(4, transport.maximumOutstanding.get())
            val leases = ownedSnapshot().filterValues { it > 0 }
            assertEquals(4, leases.size)
            held.forEach {
                assertEquals(1, leases[it.bitmap])
                assertFalse(it.bitmap.isRecycled)
                assertFalse(it.completion.task.isComplete)
            }
            held.forEachIndexed { index, task ->
                assertTrue("Each pending task must own a distinct bitmap object",
                    held.take(index).none { it.bitmap === task.bitmap })
            }
            return held
        }

        fun completeToResult() {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (callbackArrived.count != 0L && SystemClock.elapsedRealtime() < deadline) {
                throwTransportFailure()
                transport.outstanding().forEach { it.complete(EXPECTED_IDENTITY) }
                if (callbackArrived.count != 0L) SystemClock.sleep(5)
            }
            awaitCallback()
            workerBarrier()
            throwTransportFailure()
        }

        fun assertSuccessfulIdentity(): CardTextAnalysis {
            awaitCallback()
            drainMainQueue()
            val analysis = checkNotNull(result.get()).getOrThrow()
            assertEquals("094/084", analysis.collectorNumber)
            assertEquals("PBL", analysis.setIdentifier)
            assertEquals(AnalysisOrientation.DEG_0, analysis.analysisOrientation)
            assertTrue(analysis.orientationScore >= AnalysisOrientationSelector.MIN_SCORE)
            assertTrue(analysis.orientationMargin >= AnalysisOrientationSelector.MIN_MARGIN)
            assertTrue(analysis.hasNameRoiEvidence)
            assertTrue(analysis.searchQueries.any { it.contains("Pikachu", ignoreCase = true) })
            val observedText = analysis.lines + analysis.searchQueries + analysis.roiEvidence.flatMap(CardOcrEvidence::rawValues)
            assertFalse(observedText.any {
                it.contains("Glurak", ignoreCase = true) || it.contains("OBF") || it.contains("098/197")
            })
            assertTrue(analysis.imageQuality.cardDetected)
            assertEquals("", analysis.imageQuality.reason)
            assertNotNull(analysis.normalizedCard)
            assertFalse(requireNotNull(analysis.normalizedCard).isRecycled)
            assertEquals(1, callbacks.get())
            return analysis
        }

        fun ownedSnapshot(): Map<Bitmap, Int> {
            val actual = checkNotNull(resources.get())
            val ownedField = actual.javaClass.getDeclaredField("owned").apply { isAccessible = true }
            return synchronized(actual) {
                @Suppress("UNCHECKED_CAST")
                java.util.IdentityHashMap(ownedField.get(actual) as Map<Bitmap, Int>)
            }
        }

        fun readingsSnapshot(): Map<OcrReadKey, String> {
            // Map belongs to the real serial worker. Reading it on that same worker prevents
            // making a racing test-side copy while a completion updates a different full input.
            return executor.submit<Map<OcrReadKey, String>> {
                val session = checkNotNull(handle)
                val readingsField = session.javaClass.getDeclaredField("readings").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (readingsField.get(session) as Map<OcrReadKey, String>).toMap()
            }.get(3, TimeUnit.SECONDS)
        }

        fun workerBarrier() { executor.submit {}.get(3, TimeUnit.SECONDS) }
        fun drainMainQueue() { instrumentation.waitForIdleSync() }
        fun awaitCallback() { assertTrue("One actual terminal callback must arrive", callbackArrived.await(3, TimeUnit.SECONDS)) }
        fun closeActualAnalyzer() { handle?.close(); analyzer.close() }
        fun awaitWorkerTermination() {
            assertTrue("The real serial worker must drain its ordered cleanup", executor.awaitTermination(5, TimeUnit.SECONDS))
        }

        private fun throwTransportFailure() {
            transportOrNull?.unexpected?.get()?.let { throw AssertionError("Controlled recognizer contract failed", it) }
        }

        private fun awaitState(message: String, timeoutMillis: Long, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (!condition() && SystemClock.elapsedRealtime() < deadline) {
                throwTransportFailure()
                result.get()?.exceptionOrNull()?.let { throw AssertionError("Analyzer ended before " + message, it) }
                SystemClock.sleep(5)
            }
            throwTransportFailure()
            assertTrue(message, condition())
        }

        fun finishAndVerify() {
            val failures = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) { try { block() } catch (failure: Throwable) { failures += failure } }
            // Close before releasing setup: after a reflection failure the original Lazy may
            // still be installed. The resumed normalizer must observe an already-ended session,
            // never briefly proceed into the actual native recognizer as a teardown fallback.
            attempt { handle?.close() }
            attempt { analyzer.close() }
            releaseNormalize.countDown()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            do {
                attempt { transportOrNull?.abortOutstanding() }
                if (executor.isTerminated && transportOrNull?.outstanding().orEmpty().isEmpty()) break
                SystemClock.sleep(5)
            } while (SystemClock.elapsedRealtime() < deadline)
            attempt { assertTrue("Cleanup must not leave the analyzer worker active", executor.isTerminated) }
            attempt { assertTrue("Cleanup must complete every controlled task", transportOrNull?.outstanding().orEmpty().isEmpty()) }
            attempt { drainMainQueue() }
            if (handle != null) attempt { awaitCallback(); assertEquals(1, callbacks.get()) }
            attempt { throwTransportFailure() }
            if (executor.isTerminated && transportOrNull?.outstanding().orEmpty().isEmpty()) {
                if (resources.get() != null) attempt { assertTrue("All owned input resources must be released", ownedSnapshot().isEmpty()) }
                val output = result.get()?.getOrNull()?.normalizedCard
                // A successful output was explicitly transferred by production cleanup.
                attempt { output?.let { if (!it.isRecycled) it.recycle() } }
                attempt { assertTrue("All synthetic planes must be released after actual work ended", createdPlanes.get().all(Bitmap::isRecycled)) }
                attempt { assertTrue("Every controlled input is released", transportOrNull?.records().orEmpty().all { it.bitmap.isRecycled }) }
                attempt {
                    val sessionField = CardTextAnalyzer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                    assertTrue("Ordered cleanup must remove every session", (sessionField.get(analyzer) as Set<*>).isEmpty())
                }
            }
            transportOrNull?.let { fake ->
                if (fake.records().isNotEmpty()) attempt { assertEquals(1, fake.closeCalls.get()) }
                attempt { assertEquals(0, fake.submissionsAfterClose.get()) }
            }
            if (failures.isNotEmpty()) throw AssertionError("Controlled OCR fixture cleanup failed").also { combined ->
                failures.forEach(combined::addSuppressed)
            }
        }
    }

    private fun withHarness(mode: Mode, block: (Harness) -> Unit) {
        assertTrue(OpenCVLoader.initLocal())
        val harness = Harness(mode)
        var originalFailure: Throwable? = null
        try {
            harness.start()
            block(harness)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                harness.finishAndVerify()
            } catch (cleanupFailure: Throwable) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            }
        }
    }

    private companion object {
        const val PROBE_INPUT_COUNT = 14 // Independently frozen existing probe-slot contract.
        const val EXPECTED_IDENTITY = "Pikachu ex\nPBL 094/084"
        const val LATE_IDENTITY = "Glurak ex\nOBF 098/197"

        fun normalizedFixture(): NormalizedCardCapture {
            val plane = Bitmap.createBitmap(1200, 1680, Bitmap.Config.ARGB_8888)
            var display: Bitmap? = null
            return try {
                Canvas(plane).apply {
                    drawColor(Color.rgb(235, 238, 220))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                        textSize = 86f
                    }
                    drawText("Pikachu ex", 130f, 255f, paint)
                    paint.textSize = 48f
                    drawText("PBL 094/084", 230f, 1480f, paint)
                    paint.textSize = 42f
                    drawText("PBL 094/084", 35f, 1630f, paint)
                }
                display = Bitmap.createScaledBitmap(plane, 900, 1260, true)
                NormalizedCardCapture(
                    bitmap = display,
                    quality = CardImageQuality(
                        cardDetected = true, frameConfidence = .95f, sharpness = .95f,
                        exposure = .85f, perspective = 1f, geometryScore = .95f, frameQualityScore = .95f,
                    ),
                    analysisVariants = listOf(CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_0, plane)),
                )
            } catch (failure: Throwable) {
                display?.let { if (!it.isRecycled) it.recycle() }
                if (!plane.isRecycled) plane.recycle()
                throw failure
            }
        }
    }
}
