package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.feedback.ScannerReportReceipt
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only reports use an injected fake transport; no production data or credentials. */
@RunWith(AndroidJUnit4::class)
class ScannerReportDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }

    @Test fun failedScanButtonRequiresExplicitConfirmationBeforeSending() {
        var uploads = 0
        val flow = ScannerReportFlow(scope, readAutoConsent = { true }) { _, _ ->
            uploads++; ScannerReportReceipt("button-test-report")
        }
        val scan = QueuedCardScan(file = File("unused-test-preview.jpg"), qualityEligible = false,
            origin = ScanCaptureOrigin.MANUAL, stage = ScanPipelineStage.FAILED)
        flow.updateAccess("test-account", ScannerFeedbackAccess.WEB_REPORT)
        flow.registerCapture(scan.id)
        compose.setContent { MaterialTheme { Column {
            ScannerReportButton(flow, scan)
            ScannerReportDialog(flow)
        } } }
        compose.runOnIdle { flow.processAutomatic(listOf(scan)); assertEquals(0, uploads) }
        compose.onNodeWithText("An ScanLab schicken").assertIsDisplayed().performClick()
        compose.onNodeWithText("Fehlscan an Scanner-Lab").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNodeWithText("Hochladen").performClick()
        compose.onNodeWithText("Hochgeladen. Der Bericht ist im Web-Scanner-Lab zur Bearbeitung bereit.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, uploads) }
    }

    @Test fun allUnrecognizedStagesOfferTheButtonButAnalysisDoesNot() {
        val flow = ScannerReportFlow(scope) { _, _ -> error("No upload without confirmation") }
        flow.updateAccess("test-account", ScannerFeedbackAccess.WEB_REPORT)
        compose.setContent { MaterialTheme { Column {
            listOf(ScanPipelineStage.FAILED, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.OCR).forEach {
                ScannerReportButton(flow, QueuedCardScan(file = File("preview.jpg"), qualityEligible = false,
                    origin = ScanCaptureOrigin.MANUAL, stage = it))
            }
        } } }
        compose.onAllNodesWithText("An ScanLab schicken").assertCountEquals(3)
    }

    @Test fun consentBeforeUploadAndNoLocalCorrectionFields() {
        var uploads = 0
        val flow = ScannerReportFlow(scope) { _, _ -> uploads++; ScannerReportReceipt("ui-test-report") }
        flow.updateAccess("test-account", ScannerFeedbackAccess.WEB_REPORT)
        flow.open(QueuedCardScan(file = File("unused-test-preview.jpg"), qualityEligible = false,
            origin = ScanCaptureOrigin.MANUAL, stage = ScanPipelineStage.FAILED))
        compose.setContent { MaterialTheme { ScannerReportDialog(flow) } }
        compose.onNodeWithText("Fehlscan an Scanner-Lab").assertIsDisplayed()
        compose.onNodeWithText("Set-Kennung und Nummer manuell eingeben").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNodeWithText("Hochladen").performClick()
        compose.onNodeWithText("Hochgeladen. Der Bericht ist im Web-Scanner-Lab zur Bearbeitung bereit.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, uploads) }
    }

    @Test fun cancelDoesNotSendAnImage() {
        var uploads = 0
        val flow = ScannerReportFlow(scope) { _, _ -> uploads++; ScannerReportReceipt("unexpected") }
        flow.updateAccess("test-account", ScannerFeedbackAccess.WEB_REPORT)
        flow.open(QueuedCardScan(file = File("unused-test-preview.jpg"), qualityEligible = false,
            origin = ScanCaptureOrigin.MANUAL, stage = ScanPipelineStage.AMBIGUOUS))
        compose.setContent { MaterialTheme { ScannerReportDialog(flow) } }
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithText("Fehlscan an Scanner-Lab").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, uploads) }
    }

    @Test fun automaticImageRequiresFreshManualCapture() {
        val flow = ScannerReportFlow(scope) { _, _ -> error("Must not upload") }
        flow.updateAccess("test-account", ScannerFeedbackAccess.WEB_REPORT)
        flow.open(QueuedCardScan(file = File("unused-test-preview.jpg"), qualityEligible = false,
            origin = ScanCaptureOrigin.AUTOMATIC, stage = ScanPipelineStage.MANUAL_REVIEW))
        compose.setContent { MaterialTheme { ScannerReportDialog(flow) } }
        compose.onNodeWithText("Hochladen").assertDoesNotExist()
        compose.onNodeWithText("Der aktuelle Firebase-Upload erlaubt nur manuell ausgelöste Fotos.", substring = true).assertIsDisplayed()
    }

    @Test fun manualSetAndNumberRemainsAvailableDuringBackgroundUpload() {
        val response = CompletableDeferred<ScannerReportReceipt>()
        var manualAddOpened = false
        val flow = ScannerReportFlow(scope) { _, _ -> response.await() }
        flow.updateAccess("test-account", ScannerFeedbackAccess.WEB_REPORT)
        flow.open(QueuedCardScan(file = File("unused-test-preview.jpg"), qualityEligible = false,
            origin = ScanCaptureOrigin.MANUAL, stage = ScanPipelineStage.FAILED))
        compose.setContent { MaterialTheme { ScannerReportDialog(flow, onManualAdd = { manualAddOpened = true; flow.dismiss() }) } }
        compose.onNodeWithText("Hochladen").performClick()
        compose.onNodeWithText("Manuell hinzufügen: Set + Nummer").performClick()
        compose.runOnIdle {
            assertEquals(true, manualAddOpened)
            assertEquals(true, flow.uploading)
            response.complete(ScannerReportReceipt("background-report"))
        }
    }
}
