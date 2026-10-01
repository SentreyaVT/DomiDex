package com.dominik.cardvault.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.ui.theme.CardVaultTheme
import com.dominik.cardvault.auth.GoogleAccountState
import com.dominik.cardvault.auth.GoogleLoginResult
import kotlinx.coroutines.launch
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic onboarding only; no Firebase login and only unique disposable presentation preferences. */
class DomiDexWelcomeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortWelcomeExplainsAllFourBenefitsAndDoesNotGrantConsent() {
        var continued = 0
        var privacyOpened = 0
        var googleOpened = 0
        compose.setContent { CardVaultTheme { DomiDexWelcome(false, false, { continued++ }, { privacyOpened++ }, { googleOpened++ }) } }
        compose.saveSyntheticStartupScreenshot("welcome-test-state.png")
        listOf("Binder", "Scanner", "Sammlung", "Preise").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Lokales Fortfahren erteilt keine Einwilligung.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
        compose.onNodeWithText("Datenschutz lesen").performClick()
        compose.runOnIdle { assertEquals(0, continued); assertEquals(1, privacyOpened) }
        compose.onNodeWithTag("welcome-continue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, continued); assertEquals(0, googleOpened) }
    }

    @Test fun savingDisablesDuplicateContinuationAndFailureCanBeRetried() {
        val saving = androidx.compose.runtime.mutableStateOf(true)
        val failed = androidx.compose.runtime.mutableStateOf(false)
        var continues = 0
        compose.setContent { CardVaultTheme { DomiDexWelcome(saving.value, failed.value, { continues++ }, {}, {}) } }
        compose.onNodeWithTag("welcome-continue").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("welcome-google").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, continues); saving.value = false; failed.value = true }
        compose.onNodeWithText("Deine Startauswahl konnte nicht gespeichert werden. Bitte versuche es erneut.").assertIsDisplayed()
        compose.onNodeWithTag("welcome-continue").performClick()
        compose.runOnIdle { assertEquals(1, continues) }
    }

    @Test fun compactLargeTextWelcomeKeepsActionsAccessibleAndBenefitsScrollable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                CardVaultTheme {
                    Box(Modifier.requiredSize(320.dp, 560.dp)) {
                        DomiDexWelcome(false, false, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("welcome-continue").assertIsDisplayed()
        compose.onNodeWithTag("welcome-google").assertIsDisplayed()
        compose.onNodeWithText("Datenschutz lesen").assertIsDisplayed()
        compose.onNodeWithText("Preise").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome-continue").assertIsDisplayed()
    }

    @Test fun firstGoogleActionEntersAppWithoutSecondConfirmationOrLocalAction() {
        val finished = mutableStateOf(false)
        val busy = mutableStateOf(false)
        var signedIn = false
        var logins = 0
        var saves = 0
        compose.setContent { CardVaultTheme {
            val scope = rememberCoroutineScope()
            if (finished.value) Text("Sammlung bereit") else DomiDexWelcome(
                busy.value, false, { throw AssertionError("No local continuation needed") }, {},
                onGoogleSignIn = {
                    busy.value = true
                    scope.launch {
                        val outcome = completeWelcomeAccountChoice(true, { signedIn }, {
                            logins++; signedIn = true
                            GoogleLoginResult.Success(GoogleAccountState(true, "Synthetic"))
                        }, { saves++; true })
                        finished.value = outcome == WelcomeAccountOutcome.COMPLETE
                        busy.value = false
                    }
                },
            )
        } }
        compose.runOnIdle { assertEquals(0, logins); assertEquals(0, saves) }
        compose.onNodeWithText("Mit Google anmelden").performClick()
        compose.onNodeWithText("Sammlung bereit").assertIsDisplayed()
        compose.onNodeWithTag("welcome-continue").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, logins); assertEquals(1, saves) }
    }

    @Test fun loginCancellationRemainsReadableWithRetryAndSeparateLocalChoice() {
        val message = mutableStateOf<String?>(null)
        var attempts = 0
        var local = 0
        compose.setContent { CardVaultTheme {
            DomiDexWelcome(false, false, { local++ }, {},
                onGoogleSignIn = { attempts++; message.value = "Anmeldung abgebrochen. Bitte erneut versuchen." },
                loginMessage = message.value)
        } }
        compose.onNodeWithTag("welcome-google").performClick()
        compose.onNodeWithTag("welcome-login-message").assertIsDisplayed()
        compose.onNodeWithTag("welcome-google").assertIsEnabled().performClick()
        compose.onNodeWithTag("welcome-continue").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals(1, local) }
    }

    @Test fun reopeningKeepsExistingPresentationChoicesAndConsentRemainsUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "domidex-welcome-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            preferences.edit().putBoolean("scanner_camera_intro_accepted_v1", false)
                .putBoolean("scanlab_consent", false).putBoolean("opt_in", false)
                .putString("dashboard_focus", "binder").commit()
            val unchanged = preferences.all.toMap()
            assertTrue(WelcomePreferences(preferences).shouldShow())
            assertTrue(WelcomePreferences(preferences).complete())
            assertFalse(WelcomePreferences(preferences).shouldShow())
            assertEquals(unchanged, preferences.all.filterKeys { it !in setOf("arrival_seen", "relaxed_tutorial_answered_v1") })
            assertTrue(WelcomePreferences(preferences).complete())
            assertFalse(WelcomePreferences(preferences).shouldShow())
        } finally {
            check(context.deleteSharedPreferences(name)) // Only this test's freshly created namespace.
        }
    }
}
