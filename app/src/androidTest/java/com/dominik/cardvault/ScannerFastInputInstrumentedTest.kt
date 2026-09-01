package com.dominik.cardvault

import android.Manifest
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.ui.LatestCardCapture
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.Test

/** Real-device guard for the scanner's lightweight input path. */
class ScannerFastInputInstrumentedTest {
    private val permissionRule = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
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
    fun manualCaptureEntersQueueWithoutBlockingTheScannerUi() {
        val cameraManager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(CameraManager::class.java)
        assumeTrue("A hardware camera is required for the shutter latency test", cameraManager.cameraIdList.isNotEmpty())
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Jetzt entdecken").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Scannen").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("SCANNER").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText("Jetzt entdecken").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Jetzt entdecken").performClick()
            composeRule.waitUntil(timeoutMillis = 8_000L) {
                composeRule.onAllNodesWithText("Jetzt entdecken").fetchSemanticsNodes().isEmpty() &&
                    (composeRule.onAllNodesWithText("Nein, nicht mehr fragen").fetchSemanticsNodes().isNotEmpty() ||
                        composeRule.onAllNodesWithText("Scannen").fetchSemanticsNodes().isNotEmpty())
            }
        }
        if (composeRule.onAllNodesWithText("Nein, nicht mehr fragen").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Nein, nicht mehr fragen").performClick()
            composeRule.waitUntil(timeoutMillis = 8_000L) {
                composeRule.onAllNodesWithText("Nein, nicht mehr fragen").fetchSemanticsNodes().isEmpty()
            }
        }
        if (composeRule.onAllNodesWithText("SCANNER").fetchSemanticsNodes().isEmpty()) {
            composeRule.waitUntil(timeoutMillis = 8_000L) {
                composeRule.onAllNodesWithText("Scanner").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Scanner").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("SCANNER").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Galerie").assertExists()
        composeRule.onNodeWithText("Auto an").performClick()
        composeRule.onNodeWithText("Auto aus").assertExists()

        // Let CameraX finish binding before measuring only the user-visible queue hand-off.
        SystemClock.sleep(2_500L)
        val previousCapture = LatestCardCapture.path
        val startedAt = SystemClock.elapsedRealtime()
        composeRule.onNodeWithContentDescription("Foto aufnehmen").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000L) {
            composeRule.onAllNodesWithText("Scan-Warteschlange").fetchSemanticsNodes().isNotEmpty()
        }
        val queueLatency = SystemClock.elapsedRealtime() - startedAt
        assertTrue("Queue hand-off took ${queueLatency}ms", queueLatency < 2_000L)
        composeRule.waitUntil(timeoutMillis = 4_000L) {
            LatestCardCapture.path?.takeIf { it != previousCapture }?.let(::File)?.let { it.exists() && it.length() > 0L } == true
        }
        val savedLatency = SystemClock.elapsedRealtime() - startedAt
        assertTrue("Full-resolution capture took ${savedLatency}ms", savedLatency < 4_000L)
        assertTrue("Scanner activity closed during capture", !composeRule.activity.isFinishing)
    }
}
