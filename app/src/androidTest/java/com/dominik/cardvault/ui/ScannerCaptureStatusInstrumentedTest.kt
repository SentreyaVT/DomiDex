package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Status UI only: no camera, capture, permission, account or collection interaction. */
@RunWith(AndroidJUnit4::class)
class ScannerCaptureStatusInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(Status())

    @Before fun controlReadinessClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test fun greenMustSettleBeforeManualOrAutomaticReadinessAppears() {
        showStatus()
        update { copy(quality = QualityLevel.GREEN) }
        advance(160)
        assertNeutral()

        advance(48)
        compose.onNodeWithText(MANUAL_READY).assertIsDisplayed()
        update { copy(autoEnabled = true) }
        compose.onNodeWithText(AUTO_READY).assertIsDisplayed()
        compose.onNodeWithText(MANUAL_READY).assertDoesNotExist()
        update { copy(autoAllowed = false) }
        compose.onNodeWithText("Auto pausiert · manuell möglich").assertIsDisplayed()
        compose.onNodeWithText(AUTO_READY).assertDoesNotExist()
    }

    @Test fun losingGreenClearsReadinessImmediatelyAndRequiresANewStableInterval() {
        showStatus()
        update { copy(quality = QualityLevel.GREEN) }
        advance(208)
        compose.onNodeWithText(MANUAL_READY).assertIsDisplayed()

        update { copy(quality = QualityLevel.YELLOW) }
        assertNeutral()
        update { copy(quality = QualityLevel.GREEN) }
        advance(160)
        assertNeutral()
        advance(48)
        compose.onNodeWithText(MANUAL_READY).assertIsDisplayed()
        update { copy(quality = QualityLevel.RED) }
        assertNeutral()
    }

    @Test fun shortGreenBlipNeverProducesDelayedReadiness() {
        showStatus()
        update { copy(quality = QualityLevel.GREEN) }
        advance(80)
        assertNeutral()
        update { copy(quality = QualityLevel.RED) }
        advance(400)
        assertNeutral()

        update { copy(quality = QualityLevel.GREEN) }
        advance(160)
        assertNeutral()
        advance(48)
        compose.onNodeWithText(MANUAL_READY).assertIsDisplayed()
    }

    @Test fun redAndYellowStayNeutralAndStatusPositionDoesNotMoveWithQuality() {
        showStatus()
        assertNeutral()
        val initialBounds = compose.onNodeWithTag(STATUS_TAG).fetchSemanticsNode().boundsInRoot

        update { copy(quality = QualityLevel.YELLOW) }
        advance(400)
        assertNeutral()
        assertEquals(initialBounds, compose.onNodeWithTag(STATUS_TAG).fetchSemanticsNode().boundsInRoot)
        update { copy(quality = QualityLevel.GREEN) }
        advance(208)
        compose.onNodeWithText(MANUAL_READY).assertIsDisplayed()
        assertEquals(initialBounds, compose.onNodeWithTag(STATUS_TAG).fetchSemanticsNode().boundsInRoot)
        update { copy(quality = QualityLevel.RED) }
        assertNeutral()
        assertEquals(initialBounds, compose.onNodeWithTag(STATUS_TAG).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun savedAndFailedMessagesOverrideReadinessAndCaptureInProgress() {
        showStatus()
        update { copy(quality = QualityLevel.GREEN, autoEnabled = true) }
        advance(208)
        compose.onNodeWithText(AUTO_READY).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = context.getString(R.string.capture_saved)
        val failed = context.getString(R.string.capture_failed)
        val running = context.getString(R.string.capture_in_progress)

        update { copy(message = R.string.capture_saved, inProgress = true) }
        compose.onNodeWithText(saved).assertIsDisplayed()
        compose.onNodeWithText(AUTO_READY).assertDoesNotExist()
        compose.onNodeWithText(running).assertDoesNotExist()
        update { copy(message = R.string.capture_failed, quality = QualityLevel.RED) }
        compose.onNodeWithText(failed).assertIsDisplayed()
        compose.onNodeWithText(saved).assertDoesNotExist()
        compose.onNodeWithText(NEUTRAL).assertDoesNotExist()
        update { copy(message = null) }
        compose.onNodeWithText(running).assertIsDisplayed()
        update { copy(inProgress = false) }
        assertNeutral()
    }

    @Test fun fullQueueExplainsThatAnExistingResultMustBeHandled() {
        showStatus()
        update { copy(manualAllowed = false, autoAllowed = false) }

        compose.onNodeWithText("Warteschlange voll · bitte ein Ergebnis bearbeiten").assertIsDisplayed()
    }

    private fun showStatus() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val current = state.value
                    ScannerCaptureStatus(
                        qualityLevel = current.quality,
                        captureMessage = current.message,
                        captureInProgress = current.inProgress,
                        autoCaptureEnabled = current.autoEnabled,
                        autoCaptureAllowed = current.autoAllowed,
                        manualCaptureAllowed = current.manualAllowed,
                        modifier = Modifier.width(340.dp).padding(top = 76.dp).testTag(STATUS_TAG),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun update(change: Status.() -> Status) {
        compose.runOnIdle { state.value = state.value.change() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun assertNeutral() {
        compose.onNodeWithText(NEUTRAL).assertIsDisplayed()
        compose.onNodeWithText(MANUAL_READY).assertDoesNotExist()
        compose.onNodeWithText(AUTO_READY).assertDoesNotExist()
    }

    private data class Status(
        val quality: QualityLevel = QualityLevel.RED,
        val message: Int? = null,
        val inProgress: Boolean = false,
        val autoEnabled: Boolean = false,
        val autoAllowed: Boolean = true,
        val manualAllowed: Boolean = true,
    )

    private companion object {
        const val STATUS_TAG = "scanner-capture-status"
        const val NEUTRAL = "Karte ruhig halten"
        const val MANUAL_READY = "Bereit – Auslöser drücken"
        const val AUTO_READY = "Bereit – automatische Aufnahme"
    }
}
