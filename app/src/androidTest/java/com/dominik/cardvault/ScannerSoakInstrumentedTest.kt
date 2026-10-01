package com.dominik.cardvault

import android.Manifest
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Opt-in emulator-only camera-screen soak. Window frame metrics measure the app UI, not every
 * camera SurfaceView frame. CPU/memory/thread observations are local diagnostics, never uploads.
 */
class ScannerSoakInstrumentedTest {
    private val permissionRule = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(InstrumentationRegistry.getArguments().getString("scannerSoak") == "true")
                assertTrue("Scanner soak must only run on an emulator, never the physical phone",
                    Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk", ignoreCase = true))
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("pm grant com.dominik.cardvault ${Manifest.permission.CAMERA}")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> input.readBytes() }
                base.evaluate()
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissionRule).around(composeRule)

    @Test
    fun scannerPreviewStaysAliveDuringRequestedSoak() {
        val arguments = InstrumentationRegistry.getArguments()
        val label = arguments.getString("scannerSoakLabel") ?: "baseline"
        require(label in setOf("baseline", "optimized")) { "scannerSoakLabel must be baseline or optimized" }
        val durationMillis = arguments.getString("scannerSoakMillis")?.toLongOrNull() ?: 45_000L
        require(durationMillis in 1_000L..600_000L) { "scannerSoakMillis must be 1000..600000" }
        composeRule.openScannerLocallyForTest()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val metricsThread = HandlerThread("DomiDexScannerSoakMetrics")
        val frameLock = Any()
        val frames = ArrayList<FrameSample>()
        val samples = JSONArray()
        var listenerDroppedReports = 0L
        var unretainedReports = 0L
        var firstDrawCount = 0
        var registeredWindow: Window? = null
        var listenerRemoved = false
        var uiAssertionsPassed = false
        val acceptingFrames = AtomicBoolean(false)
        var started = 0L
        var finished = 0L
        var cpuStarted = 0L
        var cpuFinished = 0L
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            // FrameMetrics is reused by Android: copy scalar values only, inside this callback.
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
            val firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
            synchronized(frameLock) {
                if (acceptingFrames.get()) {
                    listenerDroppedReports += dropped.coerceAtLeast(0)
                    if (firstDraw) firstDrawCount++
                    if (frames.size < MAX_RETAINED_FRAMES) frames += FrameSample(total, deadline, firstDraw)
                    else unretainedReports++
                }
            }
        }
        try {
            // Keep binding/startup outside the timed observation, consistently for both APKs.
            SystemClock.sleep(2_500L)
            metricsThread.start()
            instrumentation.runOnMainSync {
                val window = composeRule.activity.window
                window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
                registeredWindow = window
            }
            started = SystemClock.elapsedRealtime()
            cpuStarted = Process.getElapsedCpuTime()
            acceptingFrames.set(true)
            samples.put(resourceSample(started))
            val stopAt = started + durationMillis
            while (SystemClock.elapsedRealtime() < stopAt) {
                SystemClock.sleep(minOf(5_000L, (stopAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
                samples.put(resourceSample(started))
            }
            finished = SystemClock.elapsedRealtime()
            cpuFinished = Process.getElapsedCpuTime()
            acceptingFrames.set(false)
            composeRule.onNodeWithText("SCANNER").assertIsDisplayed()
            composeRule.onNodeWithText("Auto aus").assertIsDisplayed()
            composeRule.onNodeWithText("Auto an").assertDoesNotExist()
            assertFalse(composeRule.activity.isFinishing)
            uiAssertionsPassed = true
        } finally {
            acceptingFrames.set(false)
            if (started > 0L && finished == 0L) {
                finished = SystemClock.elapsedRealtime()
                cpuFinished = Process.getElapsedCpuTime()
            }
            try {
                instrumentation.runOnMainSync {
                    registeredWindow?.let { window ->
                        window.removeOnFrameMetricsAvailableListener(listener)
                        listenerRemoved = true
                    }
                }
            } finally {
                if (metricsThread.isAlive) {
                    metricsThread.quitSafely()
                    metricsThread.join(5_000L)
                }
                val snapshot = synchronized(frameLock) { frames.toList() }
                val report = JSONObject().put("schemaVersion", 1).put("label", label)
                    .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                    .put("requestedDurationMillis", durationMillis)
                    .put("observedDurationMillis", (finished - started).coerceAtLeast(0L))
                    .put("processCpuMillis", (cpuFinished - cpuStarted).coerceAtLeast(0L))
                    .put("uiAssertionsPassed", uiAssertionsPassed)
                    .put("listenerRemoved", listenerRemoved)
                    .put("metricsThreadStopped", !metricsThread.isAlive)
                    .put("complete", uiAssertionsPassed && listenerRemoved && !metricsThread.isAlive)
                    .put("frameReportCount", snapshot.size)
                    .put("listenerDroppedReports", listenerDroppedReports)
                    .put("reportsNotRetainedAfterSafetyCap", unretainedReports)
                    .put("firstDrawFrameCount", firstDrawCount)
                    .put("windowFrameDurationMillis", timingSummary(snapshot.filter { it.totalNanos >= 0 }.map { it.totalNanos / 1_000_000.0 }))
                    .put("steadyWindowFrameDurationMillis", timingSummary(snapshot.filter { it.totalNanos >= 0 && !it.firstDraw }.map { it.totalNanos / 1_000_000.0 }))
                    .put("framesWithPlatformDeadline", snapshot.count { it.deadlineNanos > 0 && !it.firstDraw })
                    .put("framesOverPlatformDeadline", snapshot.count { it.deadlineNanos > 0 && !it.firstDraw && it.totalNanos > it.deadlineNanos })
                    .put("resourceSamples", samples)
                    .put("resourcesAfterProbeStopped", if (started > 0L) resourceSample(started) else JSONObject.NULL)
                    .put("windowFrames", JSONArray(snapshot.map { frame ->
                        JSONObject().put("durationNanos", frame.totalNanos)
                            .put("platformDeadlineNanos", if (frame.deadlineNanos > 0) frame.deadlineNanos else JSONObject.NULL)
                            .put("firstDraw", frame.firstDraw)
                    }))
                    .put("limits", "Window FrameMetrics are UI/compositor timings, not camera-sensor or SurfaceView preview FPS. Dropped reports mean missing measurement callbacks, not proved dropped camera frames. Platform deadlines are used only where available; no invented FPS pass threshold. Process CPU/PSS/heaps/thread count include the app and this probe. Five-second snapshots are not peak RAM or leak proof.")
                val output = File(context.cacheDir, "scanner-soak-$label.json")
                output.writeText(report.toString(2))
                Log.i("DomiDexScannerSoak", "report=${output.name} durationMs=${finished - started} windowFrames=${snapshot.size} cpuMs=${cpuFinished - cpuStarted} uiPassed=$uiAssertionsPassed")
                assertFalse("Frame-metrics test thread did not stop", metricsThread.isAlive)
            }
        }
    }

    private fun resourceSample(started: Long): JSONObject {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val processThreads = File("/proc/self/task").list()?.size
        return JSONObject().put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            .put("processCpuTotalMillis", Process.getElapsedCpuTime())
            .put("totalPssKiB", memory.totalPss).put("nativePssKiB", memory.nativePss)
            .put("dalvikPssKiB", memory.dalvikPss)
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("processThreadCount", processThreads ?: JSONObject.NULL)
            .put("runtimeAllocatedBytes", Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: JSONObject.NULL)
            .put("gcCount", Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: JSONObject.NULL)
    }

    private fun timingSummary(values: List<Double>): Any {
        if (values.isEmpty()) return JSONObject.NULL
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
        return JSONObject().put("samples", sorted.size).put("median", median)
            .put("p95", sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)])
            .put("worst", sorted.last())
    }

    private data class FrameSample(val totalNanos: Long, val deadlineNanos: Long, val firstDraw: Boolean)

    private companion object {
        const val MAX_RETAINED_FRAMES = 50_000
    }
}
