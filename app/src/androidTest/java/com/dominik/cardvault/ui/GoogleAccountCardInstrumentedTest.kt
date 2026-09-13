package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.GoogleAccountState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI state/callback tests only: no external accounts are signed in or changed. */
@RunWith(AndroidJUnit4::class)
class GoogleAccountCardInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun logoutClearsIdentityAndLoginDisplaysOnlyTheNewAccount() {
        val state = mutableStateOf(GoogleAccountState(true, "Testkonto Eins", "one@example.test"))
        var logins = 0
        var logouts = 0
        var switches = 0
        compose.setContent {
            MaterialTheme {
                GoogleAccountCardContent(state.value, false, null,
                    onSignIn = { logins++; state.value = GoogleAccountState(true, null, "two@example.test") },
                    onSignOut = { logouts++; state.value = GoogleAccountState(false, null) },
                    onSwitchAccount = { switches++; state.value = GoogleAccountState(true, null, "switch@example.test") },
                )
            }
        }
        compose.onNodeWithText("Testkonto Eins").assertIsDisplayed()
        compose.onNodeWithText("one@example.test").assertIsDisplayed()
        compose.onNodeWithText("Konto wechseln").performClick()
        compose.onNodeWithText("switch@example.test").assertIsDisplayed()
        compose.onNodeWithText("Abmelden").performClick()
        compose.onNodeWithText("Testkonto Eins").assertDoesNotExist()
        compose.onNodeWithText("one@example.test").assertDoesNotExist()
        compose.onNodeWithText("Mit Google anmelden").performClick()
        compose.onNodeWithText("two@example.test").assertIsDisplayed()
        compose.onNodeWithText("one@example.test").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, switches); assertEquals(1, logins); assertEquals(1, logouts) }
    }

    @Test fun signedOutDoesNotRenderStaleFieldsAndBusyLoginIsDisabled() {
        compose.setContent {
            MaterialTheme {
                GoogleAccountCardContent(GoogleAccountState(false, "Stale", "stale@example.test"), true, null,
                    onSignIn = { error("Busy login must not run") },
                    onSignOut = { error("Signed out") },
                    onSwitchAccount = { error("Switched") },
                )
            }
        }
        compose.onNodeWithText("Stale").assertDoesNotExist()
        compose.onNodeWithText("stale@example.test").assertDoesNotExist()
        compose.onNodeWithText("Abmelden").assertDoesNotExist()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.account_login_running))
            .assertIsNotEnabled()
    }
}
