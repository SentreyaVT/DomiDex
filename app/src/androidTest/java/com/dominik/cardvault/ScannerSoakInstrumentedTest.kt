package com.dominik.cardvault

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real-device soak harness. Normal connected test runs skip the timed hold. */
class ScannerSoakInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scannerPreviewStaysAliveDuringRequestedSoak() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("scannerSoak") == "true")
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.waitForIdle()
        // Fresh debug installs may still show the one-time tour prompt.
        runCatching { composeRule.onNodeWithText("Nein, nicht mehr fragen").performClick() }
        composeRule.waitForIdle()
        // A test restart can restore the scanner's saveable navigation state. In that
        // case there is no bottom-navigation item to click and the preview is already live.
        runCatching { composeRule.onNodeWithText("Scanner").performClick() }
        composeRule.waitForIdle()
        check(composeRule.onAllNodesWithText("SCANNER").fetchSemanticsNodes().isNotEmpty()) {
            "Scanner screen did not become visible"
        }
        Thread.sleep(arguments.getString("scannerSoakMillis")?.toLongOrNull() ?: 45_000L)
        assertFalse(composeRule.activity.isFinishing)
    }
}
