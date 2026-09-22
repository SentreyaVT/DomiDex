package com.dominik.cardvault.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dominik.cardvault.data.backup.LocalDataStartupState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalDataStartupContentInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun failureShowsBlockingExplanationAndOnlyRetry() {
        var retries = 0
        compose.setContent { LocalDataStartupContent(LocalDataStartupState.Failed("IOException")) { retries++ } }
        compose.onNodeWithText("Lokale Daten konnten nicht vorbereitet werden").assertIsDisplayed()
        compose.onNodeWithTag("startup-retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Überspringen").assertDoesNotExist()
    }
    @Test fun preparingHasNoButtonToBypassRecovery() {
        compose.setContent { LocalDataStartupContent(LocalDataStartupState.Loading) {} }
        compose.onNodeWithText("Lokale Daten werden vorbereitet …").assertIsDisplayed()
        compose.onNodeWithTag("startup-retry").assertDoesNotExist()
    }
}
