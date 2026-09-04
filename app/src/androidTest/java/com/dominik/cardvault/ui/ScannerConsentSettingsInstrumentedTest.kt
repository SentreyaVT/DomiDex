package com.dominik.cardvault.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.feedback.ScannerReportConsentStore
import com.dominik.cardvault.data.feedback.ScannerReportReceipt
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Legacy consent storage must never enable uploads; fake accounts and transport only. */
@RunWith(AndroidJUnit4::class)
class ScannerConsentSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consent = ScannerReportConsentStore(context)
    private val stops = mutableListOf<() -> Unit>()
    private val account = "scanner-consent-settings-test"
    private val otherAccount = "scanner-consent-settings-other-test"
    private val toggle = "Nicht erkannte Karten zur Verbesserung senden"
    private val manualInfo = "Fehlscans werden nur über „An ScanLab schicken“ gesendet."
    private var uploads = 0

    @After fun close() {
        stops.forEach { it() }
        consent.clearForAccount(account)
        consent.clearForAccount(otherAccount)
        scope.cancel()
    }

    private fun flow(store: ScannerReportConsentStore = consent) = ScannerReportFlow(
        scope, readAutoConsent = store::isEnabled, writeAutoConsent = store::setEnabled,
    ) { _, _ -> uploads++; ScannerReportReceipt("test-report") }.also { flow ->
        stops += store.observe(flow::refreshAutomaticConsent)
    }

    @Test fun existingStoredTrueCannotEnableScannerSettingsOrReopenedFlow() {
        // Seed the exact historical key rather than a new preference used only by this test.
        context.getSharedPreferences("scanner_report_consent", Context.MODE_PRIVATE)
            .edit().putBoolean("automatic_v1_$account", true).commit()
        assertTrue(consent.isEnabled(account))
        val scanner = flow()
        val settings = flow(ScannerReportConsentStore(context))
        scanner.updateAccess(account, ScannerFeedbackAccess.WEB_REPORT)
        settings.updateAccess(account, ScannerFeedbackAccess.WEB_REPORT)
        compose.setContent { MaterialTheme { Column { ScannerAutomaticReportControl(settings, showAllAccess = true) } } }
        assertManualOnlyControl()
        compose.runOnIdle {
            assertFalse(scanner.automaticEnabled)
            assertFalse(settings.automaticEnabled)
            scanner.registerCapture("legacy-failure")
            scanner.processAutomatic(listOf(failedScan()))
            val reopened = flow(ScannerReportConsentStore(context))
            reopened.updateAccess(account, ScannerFeedbackAccess.WEB_REPORT)
            assertFalse(reopened.automaticEnabled)
            reopened.registerCapture("legacy-failure")
            reopened.processAutomatic(listOf(failedScan()))
            reopened.updateAccess(otherAccount, ScannerFeedbackAccess.WEB_REPORT)
            assertFalse(reopened.automaticEnabled)
            reopened.processAutomatic(listOf(failedScan()))
        }
        compose.runOnIdle { assertEquals(0, uploads) }
    }

    @Test fun liveLegacyPreferenceChangesNeverRestoreAToggleOrUpload() {
        consent.setEnabled(account, true)
        val settings = flow()
        settings.updateAccess(account, ScannerFeedbackAccess.UNAVAILABLE)
        compose.setContent { MaterialTheme { Column { ScannerAutomaticReportControl(settings, showAllAccess = true) } } }
        assertManualOnlyControl()
        for (legacyValue in listOf(false, true)) {
            compose.runOnIdle { consent.setEnabled(account, legacyValue) }
            compose.runOnIdle {
                assertFalse(settings.automaticEnabled)
                settings.updateAccess(account, ScannerFeedbackAccess.WEB_REPORT)
                settings.registerCapture("legacy-failure")
                settings.processAutomatic(listOf(failedScan()))
            }
            assertManualOnlyControl()
        }
        compose.runOnIdle { assertEquals(0, uploads) }
    }

    @Test fun obsoleteActivationMethodsCannotOpenConsentOrEnableReports() {
        consent.clearForAccount(account)
        val settings = flow()
        settings.updateAccess(account, ScannerFeedbackAccess.WEB_REPORT)
        compose.setContent { MaterialTheme { Column { ScannerAutomaticReportControl(settings, showAllAccess = true) } } }
        compose.runOnIdle {
            settings.requestAutomatic(true)
            settings.setAutomatic(true)
            settings.refreshAutomaticConsent()
            settings.registerCapture("legacy-failure")
            settings.processAutomatic(listOf(failedScan()))
            assertFalse(settings.automaticEnabled)
            assertFalse(settings.consentRequested)
            assertFalse(consent.isEnabled(account))
        }
        assertManualOnlyControl()
        compose.runOnIdle { assertEquals(0, uploads) }
    }

    private fun assertManualOnlyControl() {
        compose.onNodeWithText(manualInfo).assertIsDisplayed()
        compose.onNodeWithText(toggle).assertDoesNotExist()
        compose.onNodeWithText("Automatischen Fehlscan-Upload erlauben?").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
    }

    private fun failedScan() = QueuedCardScan(
        id = "legacy-failure", file = File(context.cacheDir, "scanner-consent-test-unused.jpg"),
        qualityEligible = false, origin = ScanCaptureOrigin.MANUAL, stage = ScanPipelineStage.FAILED,
    )
}
