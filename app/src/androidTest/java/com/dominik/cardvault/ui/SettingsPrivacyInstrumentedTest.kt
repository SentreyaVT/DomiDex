package com.dominik.cardvault.ui

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.MailTo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** External launches are either inspected or faked. These tests send no mail and delete no data. */
@RunWith(AndroidJUnit4::class)
class SettingsPrivacyInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun privacyAndSupportUseThePublicDestinations() {
        val privacy = privacyPolicyIntent()
        assertEquals(Intent.ACTION_VIEW, privacy.action)
        assertEquals("https://domidex.web.app/datenschutz", privacy.dataString)

        val support = supportMailIntent()
        val mail = MailTo.parse(support.dataString!!)
        assertEquals(Intent.ACTION_SENDTO, support.action)
        assertEquals("Dominik.muxifeld@gmail.com", mail.to)
        assertEquals("DomiDex Support", mail.subject)
    }

    @Test fun deletionIsAMailDraftWithoutPrivateAccountFields() {
        val request = accountDeletionMailIntent()
        val mail = MailTo.parse(request.dataString!!)
        assertEquals(Intent.ACTION_SENDTO, request.action)
        assertEquals("Dominik.muxifeld@gmail.com", mail.to)
        assertEquals("DomiDex Kontolöschung", mail.subject)
        assertTrue(mail.body.orEmpty().contains("ergänze ich selbst"))
        assertEquals(setOf(Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT), request.extras!!.keySet())
    }

    @Test fun unconfirmedDeletionCannotOpenMailAndCancelDoesNothing() {
        var opened = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                AccountDeletionRequestDialog(onDismiss = { dismissed = true }, openRequest = { opened++; true })
            }
        }
        compose.onNodeWithText("E-Mail-Anfrage vorbereiten").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("account_deletion_confirmation").performScrollTo().assertIsOff()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertTrue(dismissed)
        }
    }

    @Test fun explicitConfirmationOnlyOpensADraftAndNeverClaimsDeletion() {
        var opened = 0
        compose.setContent {
            MaterialTheme { AccountDeletionRequestDialog(onDismiss = {}, openRequest = { opened++; true }) }
        }
        compose.onNodeWithTag("account_deletion_confirmation").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("E-Mail-Anfrage vorbereiten").assertIsEnabled().performClick()
        compose.onNodeWithText("E-Mail-App geöffnet. Bitte prüfe den Entwurf und sende ihn selbst. Es wurde noch nichts gelöscht.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun missingMailAppReportsFailureAndKeepsTheAddressAvailable() {
        compose.setContent {
            MaterialTheme { AccountDeletionRequestDialog(onDismiss = {}, openRequest = { false }) }
        }
        compose.onNodeWithTag("account_deletion_confirmation").performScrollTo().performClick()
        compose.onNodeWithText("E-Mail-Anfrage vorbereiten").performClick()
        compose.onNodeWithText("Die E-Mail-App konnte nicht geöffnet werden. Schreibe selbst an Dominik.muxifeld@gmail.com. Es wurde nichts gelöscht.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun unavailableOrBlockedExternalActivitiesReturnFailure() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val noMailApp = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("test") }
        }
        val blockedMailApp = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { throw SecurityException("test") }
        }
        assertFalse(launchSettingsIntent(noMailApp, supportMailIntent()))
        assertFalse(launchSettingsIntent(blockedMailApp, accountDeletionMailIntent()))
    }
}
