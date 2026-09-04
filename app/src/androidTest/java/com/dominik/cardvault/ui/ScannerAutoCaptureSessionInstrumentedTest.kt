package com.dominik.cardvault.ui

import android.Manifest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only UI regression: no photo, report, account or collection action is performed. */
@RunWith(AndroidJUnit4::class)
class ScannerAutoCaptureSessionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Before fun cameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.CAMERA,
        )
    }

    @Test fun autoCaptureIsOptInAndNeverRestoredIntoANewSession() {
        val session = mutableIntStateOf(0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                key(session.intValue) {
                    // Prevent camera captures while testing the actual mode toggle.
                    LiveCameraScanner(Modifier.fillMaxSize(), autoCaptureAllowed = false)
                }
            }
        }
        compose.onNodeWithText("Auto aus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Foto aufnehmen").assertIsEnabled()
        compose.onNodeWithText("Auto aus").performClick()
        compose.onNodeWithText("Auto an").assertIsDisplayed()
        compose.onNodeWithContentDescription("Foto aufnehmen").assertIsEnabled()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Auto an").assertIsDisplayed()
        compose.onNodeWithText("Auto an").performClick()
        compose.onNodeWithText("Auto aus").assertIsDisplayed().performClick()
        compose.runOnIdle { session.intValue++ }
        compose.onNodeWithText("Auto aus").assertIsDisplayed().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Auto aus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Foto aufnehmen").assertIsEnabled()
    }
}
