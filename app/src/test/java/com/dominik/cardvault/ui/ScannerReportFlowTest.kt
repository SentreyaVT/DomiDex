package com.dominik.cardvault.ui

import com.dominik.cardvault.auth.FirebaseEntitlements
import com.dominik.cardvault.data.feedback.ScannerReportReceipt
import com.dominik.cardvault.data.feedback.ScannerReportUploadException
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ScannerReportFlowTest {
    @Test fun `both privacy surfaces name the explicitly authorized lab reviewers`() {
        val reviewers = "Administratoren und ausdrücklich freigeschaltete Scanner-Lab-Prüfer"
        for (message in listOf(SCANNER_REPORT_PRIVACY_DETAILS, SCANNER_REPORT_UPLOAD_CONSENT)) {
            assertTrue(message.contains(reviewers))
            assertFalse(message.contains("Nur Administratoren können"))
        }
        assertTrue(SCANNER_REPORT_UPLOAD_CONSENT.contains("Mit „Hochladen“ stimmst du zu"))
        assertTrue(SCANNER_REPORT_UPLOAD_CONSENT.contains("Analysekennung"))
    }

    private fun scan(origin: ScanCaptureOrigin = ScanCaptureOrigin.MANUAL) = QueuedCardScan(
        id = "test-scan", file = File("capture.jpg"), qualityEligible = false,
        origin = origin, stage = ScanPipelineStage.MANUAL_REVIEW,
    )

    @Test fun `only verified admin or pretester routes to web review`() {
        assertEquals(ScannerFeedbackAccess.WEB_REPORT, scannerFeedbackAccess("u", FirebaseEntitlements(admin = true)))
        assertEquals(ScannerFeedbackAccess.WEB_REPORT, scannerFeedbackAccess("u", FirebaseEntitlements(pretester = true)))
        assertEquals(ScannerFeedbackAccess.LOCAL, scannerFeedbackAccess("u", FirebaseEntitlements(appAccess = true)))
        assertEquals(ScannerFeedbackAccess.LOCAL, scannerFeedbackAccess(null, null))
        assertEquals(ScannerFeedbackAccess.UNAVAILABLE, scannerFeedbackAccess("u", null))
    }

    @Test fun `failed and ambiguous outcomes are eligible for web review without weakening recognition`() {
        assertTrue(ScanPipelineStage.MANUAL_REVIEW.needsScannerReview)
        assertTrue(ScanPipelineStage.AMBIGUOUS.needsScannerReview)
        assertTrue(ScanPipelineStage.FAILED.needsScannerReview)
        assertFalse(ScanPipelineStage.MATCHED.needsScannerReview)
        assertFalse(ScanPipelineStage.OCR.needsScannerReview)
    }

    @Test fun `opening does not upload and confirmed submission happens only once`() = runBlocking {
        var uploads = 0
        val flow = ScannerReportFlow(this) { _, uid ->
            assertEquals("tester", uid)
            uploads++
            ScannerReportReceipt("report-1")
        }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        assertFalse(flow.allowsLocalEditor)
        flow.open(scan())
        assertEquals(0, uploads)
        assertEquals("test-scan", flow.activeScanId)
        flow.submit()
        flow.submit()
        yield()
        assertEquals(1, uploads)
        assertEquals("report-1", flow.outcome("test-scan")?.reportId)
        flow.dismiss()
        flow.open(scan())
        flow.submit()
        yield()
        assertEquals(1, uploads)
    }

    @Test fun `released queue preview drops its per scan upload bookkeeping`() = runBlocking {
        val flow = ScannerReportFlow(this) { _, _ -> ScannerReportReceipt("report-1") }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        val captured = scan()
        flow.registerCapture(captured.id)
        flow.open(captured)
        flow.submit()
        yield()
        flow.dismiss()

        assertEquals("report-1", flow.outcome(captured.id)?.reportId)
        flow.releaseCapture(captured.id)
        assertNull(flow.outcome(captured.id))
    }

    @Test fun `automatic frames and gallery files never masquerade as manual reports`() = runBlocking {
        var uploads = 0
        val flow = ScannerReportFlow(this) { _, _ -> uploads++; ScannerReportReceipt("unexpected") }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        for (origin in listOf(ScanCaptureOrigin.AUTOMATIC, ScanCaptureOrigin.GALLERY)) {
            flow.open(scan(origin))
            flow.submit()
            yield()
            assertNotEquals("MANUAL_CAPTURE", scan(origin).scannerReportDraft().captureType)
        }
        assertEquals(0, uploads)
    }

    @Test fun `legacy automatic entry points never upload even with saved consent`() = runBlocking {
        val writes = mutableListOf<Boolean>()
        var uploads = 0
        val flow = ScannerReportFlow(this, readAutoConsent = { true }, writeAutoConsent = { _, enabled -> writes += enabled }) { _, _ ->
            uploads++
            ScannerReportReceipt("unexpected")
        }
        val failed = scan()
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        flow.registerCapture(failed.id)
        assertFalse(flow.automaticEnabled)
        flow.processAutomatic(listOf(failed))
        yield()
        assertEquals(0, uploads)
        flow.requestAutomatic(true)
        assertFalse(flow.consentRequested)
        flow.processAutomatic(listOf(failed))
        flow.setAutomatic(true)
        flow.refreshAutomaticConsent()
        assertFalse(flow.automaticEnabled)
        assertFalse(flow.protectedScanIds(listOf(failed)).contains(failed.id))
        flow.processAutomatic(listOf(failed))
        yield()
        assertEquals(0, uploads)
        assertNull(flow.selected)
        assertNull(flow.outcome(failed.id))
        flow.requestAutomatic(false)
        assertFalse(flow.automaticEnabled)
        assertFalse(writes.contains(true))
    }

    @Test fun `account switches neither resend reports nor upload another accounts pending photo`() = runBlocking {
        var uploads = 0
        val flow = ScannerReportFlow(this, readAutoConsent = { true }) { _, _ -> uploads++; ScannerReportReceipt("report") }
        val first = scan()
        val pending = scan().copy(id = "pending")
        flow.updateAccess("tester-a", ScannerFeedbackAccess.WEB_REPORT)
        flow.registerCapture(first.id)
        flow.registerCapture(pending.id)
        flow.processAutomatic(listOf(first))
        yield()
        assertEquals(0, uploads)
        flow.open(first)
        flow.submit()
        yield()
        assertEquals(1, uploads)
        flow.open(pending)
        flow.updateAccess("tester-b", ScannerFeedbackAccess.WEB_REPORT)
        assertNull(flow.selected)
        assertFalse(flow.automaticEnabled)
        flow.submit()
        flow.processAutomatic(listOf(first, pending))
        yield()
        assertEquals(1, uploads)
        assertNull(flow.outcome(first.id))
        flow.updateAccess("tester-a", ScannerFeedbackAccess.WEB_REPORT)
        flow.processAutomatic(listOf(first))
        yield()
        assertEquals(1, uploads)
        assertEquals("report", flow.outcome(first.id)?.reportId)
        flow.open(first)
        flow.submit()
        yield()
        assertEquals(1, uploads)
    }

    @Test fun `account change discards selection and requires a new manual submission`() = runBlocking {
        val flow = ScannerReportFlow(this) { _, _ -> fail("No upload without a new manual submission"); ScannerReportReceipt("unexpected") }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        flow.open(scan())
        flow.updateAccess("other", ScannerFeedbackAccess.WEB_REPORT)
        assertNull(flow.selected)
        flow.submit()
        flow.updateAccess("other", ScannerFeedbackAccess.UNAVAILABLE)
        assertFalse(flow.allowsLocalEditor)
    }

    @Test fun `saved true cannot activate automatic uploads under any access state`() = runBlocking {
        for (access in ScannerFeedbackAccess.entries) {
            var saved = true
            var uploads = 0
            val writes = mutableListOf<Boolean>()
            val flow = ScannerReportFlow(this, readAutoConsent = { saved },
                writeAutoConsent = { _, enabled -> saved = enabled; writes += enabled }) { _, _ ->
                uploads++
                ScannerReportReceipt("unexpected")
            }
            flow.updateAccess("tester", access)
            flow.registerCapture("test-scan")
            assertFalse(flow.automaticEnabled)
            flow.requestAutomatic(true)
            flow.setAutomatic(true)
            flow.refreshAutomaticConsent()
            flow.processAutomatic(listOf(scan()))
            yield()
            assertEquals(0, uploads)
            flow.requestAutomatic(false)
            assertFalse(flow.automaticEnabled)
            assertFalse(flow.consentRequested)
            assertFalse(writes.contains(true))
        }
    }

    @Test fun `obsolete consent methods cannot enable reports or request a dialog`() = runBlocking {
        var saved = false
        val flow = ScannerReportFlow(this, readAutoConsent = { saved },
            writeAutoConsent = { _, enabled -> saved = enabled }) { _, _ -> fail("No automatic upload"); ScannerReportReceipt("unexpected") }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        flow.setAutomatic(true)
        assertFalse(saved)
        flow.requestAutomatic(true)
        assertFalse(flow.consentRequested)
        flow.dismissConsent()
        flow.setAutomatic(true)
        assertFalse(saved)
        flow.requestAutomatic(true)
        flow.updateAccess("tester", ScannerFeedbackAccess.UNAVAILABLE)
        flow.setAutomatic(true)
        assertFalse(saved)
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        flow.requestAutomatic(true)
        flow.setAutomatic(true)
        assertFalse(saved)
        assertFalse(flow.automaticEnabled)
        assertFalse(flow.consentRequested)
    }

    @Test fun `legacy stored true stays disabled after account switches and reopening`() = runBlocking {
        val saved = mutableMapOf("tester-a" to true)
        fun newFlow() = ScannerReportFlow(this, readAutoConsent = { saved[it] ?: false },
            writeAutoConsent = { uid, enabled -> saved[uid] = enabled }) { _, _ -> fail("No automatic upload"); ScannerReportReceipt("unexpected") }
        val scanner = newFlow()
        scanner.updateAccess("tester-a", ScannerFeedbackAccess.WEB_REPORT)
        assertFalse(scanner.automaticEnabled)
        scanner.updateAccess(null, ScannerFeedbackAccess.LOCAL)
        assertFalse(scanner.automaticEnabled)
        scanner.requestAutomatic(false)
        scanner.updateAccess("tester-b", ScannerFeedbackAccess.WEB_REPORT)
        assertFalse(scanner.automaticEnabled)
        val reopened = newFlow()
        saved["tester-a"] = true
        reopened.updateAccess("tester-a", ScannerFeedbackAccess.WEB_REPORT)
        assertFalse(reopened.automaticEnabled)
        reopened.registerCapture("test-scan")
        reopened.processAutomatic(listOf(scan()))
        yield()
        scanner.updateAccess("tester-a", ScannerFeedbackAccess.WEB_REPORT)
        assertFalse(scanner.automaticEnabled)
    }

    @Test fun `changing legacy preferences cannot reactivate existing scanner and settings flows`() = runBlocking {
        var saved = true
        var uploads = 0
        fun newFlow() = ScannerReportFlow(this, readAutoConsent = { saved },
            writeAutoConsent = { _, enabled -> saved = enabled }) { _, _ ->
            uploads++
            ScannerReportReceipt("unexpected")
        }
        val scanner = newFlow()
        val settings = newFlow()
        scanner.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        settings.updateAccess("tester", ScannerFeedbackAccess.UNAVAILABLE)
        scanner.registerCapture("test-scan")
        for (legacyValue in listOf(false, true)) {
            saved = legacyValue
            settings.refreshAutomaticConsent()
            scanner.refreshAutomaticConsent()
            scanner.processAutomatic(listOf(scan()))
            yield()
            assertFalse(settings.automaticEnabled)
        }
        assertFalse(scanner.automaticEnabled)
        assertEquals(0, uploads)
    }

    @Test fun `automatic processing never submits any capture origin`() = runBlocking {
        var uploads = 0
        val flow = ScannerReportFlow(this, readAutoConsent = { true }) { _, _ ->
            uploads++
            ScannerReportReceipt("unexpected")
        }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        for (origin in ScanCaptureOrigin.entries) {
            flow.registerCapture("test-scan")
            flow.processAutomatic(listOf(scan(origin)))
            yield()
        }
        assertEquals(0, uploads)
    }

    @Test fun `uncertain upload outcome does not permit duplicate retry`() = runBlocking {
        var uploads = 0
        val flow = ScannerReportFlow(this) { _, _ ->
            uploads++
            throw ScannerReportUploadException("Bitte im Scanner-Lab prüfen.", outcomeUnknown = true)
        }
        flow.updateAccess("tester", ScannerFeedbackAccess.WEB_REPORT)
        flow.open(scan())
        flow.submit()
        yield()
        assertTrue(flow.outcome("test-scan")?.outcomeUnknown == true)
        flow.dismiss()
        flow.open(scan())
        flow.submit()
        yield()
        assertEquals(1, uploads)
    }

    @Test fun `draft contains real identity ROI evidence not whole frame logs`() {
        val draft = scan().copy(analysis = CardTextAnalysis(
            lines = listOf("full-frame-only"), collectorNumber = "173", setIdentifier = "SVP",
            likelyScript = CardScript.LATIN, searchQueries = emptyList(),
            roiEvidence = listOf(CardOcrEvidence(CardOcrRegion.NUMBER, listOf("173"), "173", .8f)),
        )).scannerReportDraft()
        assertEquals("SVP", draft.guessedSet)
        assertEquals("173", draft.guessedNumber)
        assertEquals(listOf("173"), draft.ocrTokens)
        assertNull(draft.confidence)
        assertEquals("MANUAL_CAPTURE", draft.captureType)
    }
}
