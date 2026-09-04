package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dominik.cardvault.auth.FirebaseEntitlements
import com.dominik.cardvault.auth.currentFirebaseEntitlementAccount
import com.dominik.cardvault.data.feedback.FirebaseScannerReportUploader
import com.dominik.cardvault.data.feedback.ScannerReportDraft
import com.dominik.cardvault.data.feedback.ScannerReportConsentStore
import com.dominik.cardvault.data.feedback.ScannerReportReceipt
import com.dominik.cardvault.data.feedback.ScannerReportUploadException
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class ScannerFeedbackAccess { CHECKING, LOCAL, WEB_REPORT, UNAVAILABLE }

// Keep old preference readers compatible, but a saved standing consent must never send a photo.
private const val AUTOMATIC_SCANNER_REPORTS_ENABLED = false

internal fun scannerFeedbackAccess(uid: String?, claims: FirebaseEntitlements?): ScannerFeedbackAccess = when {
    uid == null -> ScannerFeedbackAccess.LOCAL
    claims == null -> ScannerFeedbackAccess.UNAVAILABLE
    claims.canUsePretesterBackend -> ScannerFeedbackAccess.WEB_REPORT
    else -> ScannerFeedbackAccess.LOCAL
}

internal val ScanPipelineStage.needsScannerReview: Boolean
    get() = this in setOf(ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)

/** Send only actual identity-ROI evidence, not device logs, file paths or account information. */
internal fun QueuedCardScan.scannerReportDraft(): ScannerReportDraft {
    val evidence = analysis
    return ScannerReportDraft(
        scanId = id,
        image = file,
        // Never mislabel an automatic frame/gallery import to bypass the server contract.
        captureType = if (origin == ScanCaptureOrigin.MANUAL) "MANUAL_CAPTURE" else origin.name,
        guessedName = evidence?.roiEvidence?.firstOrNull { it.region == CardOcrRegion.NAME }?.normalizedValue,
        guessedSet = evidence?.setIdentifier,
        guessedNumber = evidence?.collectorNumber,
        confidence = decision?.bestScore?.div(100.0),
        ocrTokens = evidence?.roiEvidence.orEmpty().flatMap { it.rawValues }.distinct().take(100),
        zoneConfidence = evidence?.roiEvidence.orEmpty().associate { it.region.name to it.confidence.toDouble() },
        failureReason = decision?.reason?.takeIf(String::isNotBlank)
            ?: evidence?.imageQuality?.reason?.takeIf(String::isNotBlank)
            ?: stage.name,
    )
}

internal data class ScannerReportOutcome(
    val reportId: String? = null,
    val error: String? = null,
    val outcomeUnknown: Boolean = false,
)

/** Session-scoped uploads; each photo requires an explicit submission through its preview. */
internal class ScannerReportFlow(
    private val scope: CoroutineScope,
    private val readAutoConsent: (String) -> Boolean = { false },
    private val writeAutoConsent: (String, Boolean) -> Unit = { _, _ -> },
    private val upload: suspend (ScannerReportDraft, String) -> ScannerReportReceipt,
) {
    var access by mutableStateOf(ScannerFeedbackAccess.CHECKING)
        private set
    var accountUid by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<QueuedCardScan?>(null)
        private set
    var uploading by mutableStateOf(false)
        private set
    var uploadingScanId by mutableStateOf<String?>(null)
        private set
    var automaticEnabled by mutableStateOf(false)
        private set
    var consentRequested by mutableStateOf(false)
        private set
    var outcomeRevision by mutableStateOf(0)
        private set
    private val outcomes = mutableStateMapOf<String, ScannerReportOutcome>()
    private val captureAccounts = mutableMapOf<String, String?>()
    private var uploadJob: Job? = null
    private var uploadGeneration = 0L
    var retryAccess: () -> Unit = {}
    val activeScanId: String? get() = selected?.id
    val allowsLocalEditor: Boolean get() = access == ScannerFeedbackAccess.LOCAL
    private fun outcomeKey(uid: String?, scanId: String): String = "$uid/$scanId"
    fun outcome(scanId: String): ScannerReportOutcome? = outcomes[outcomeKey(accountUid, scanId)]
    fun registerCapture(scanId: String) { captureAccounts[scanId] = accountUid }

    fun updateAccess(uid: String?, value: ScannerFeedbackAccess) {
        if (uid != accountUid || value == ScannerFeedbackAccess.LOCAL) {
            uploadingScanId?.let { scanId ->
                outcomes[outcomeKey(accountUid, scanId)] = ScannerReportOutcome(
                    error = "Upload unterbrochen. Bitte vor erneutem Senden im Scanner-Lab prüfen.", outcomeUnknown = true,
                )
            }
            uploadGeneration++
            uploadJob?.cancel()
            selected = null
            uploading = false
            uploadingScanId = null
            consentRequested = false
            // Retain per-account receipts/unknown outcomes across sign-out, role and account changes.
            outcomeRevision++
        }
        accountUid = uid
        access = value
        if (value != ScannerFeedbackAccess.WEB_REPORT) consentRequested = false
        refreshAutomaticConsent()
    }

    /** Legacy stored consent is ignored, including after account/token refresh. */
    fun refreshAutomaticConsent() {
        automaticEnabled = AUTOMATIC_SCANNER_REPORTS_ENABLED && (accountUid?.let(readAutoConsent) ?: false)
    }

    fun requestAutomatic(enabled: Boolean) {
        if (!enabled) setAutomatic(false)
        else if (AUTOMATIC_SCANNER_REPORTS_ENABLED && accountUid != null && access == ScannerFeedbackAccess.WEB_REPORT) consentRequested = true
    }

    fun dismissConsent() { consentRequested = false }

    fun setAutomatic(enabled: Boolean) {
        val uid = accountUid ?: return
        // Revocation is local and must remain possible offline or after a role change.
        if (enabled && (!AUTOMATIC_SCANNER_REPORTS_ENABLED || access != ScannerFeedbackAccess.WEB_REPORT || !consentRequested)) return
        writeAutoConsent(uid, enabled)
        automaticEnabled = enabled
        consentRequested = false
    }

    fun protectedScanIds(scans: List<QueuedCardScan>): Set<String> = buildSet {
        activeScanId?.let(::add)
        uploadingScanId?.let(::add)
        // Preserve the existing pending-image protection during token refresh, not for local-only accounts.
        if (automaticEnabled && access != ScannerFeedbackAccess.LOCAL) scans.filter {
            it.origin == ScanCaptureOrigin.MANUAL && it.stage.needsScannerReview &&
                captureAccounts[it.id] == accountUid && outcome(it.id) == null
        }.forEach { add(it.id) }
    }

    /** Compatibility entry point for old callers: never starts a network request. */
    @Suppress("UNUSED_PARAMETER")
    fun processAutomatic(scans: List<QueuedCardScan>) {
        refreshAutomaticConsent()
    }

    fun open(scan: QueuedCardScan) {
        if (access == ScannerFeedbackAccess.WEB_REPORT) selected = scan
    }

    fun dismiss() {
        selected = null // Uploads continue independently of the preview/manual-add dialog.
    }

    fun submit() {
        val scan = selected ?: return
        val uid = accountUid ?: return
        val previous = outcome(scan.id)
        if (access != ScannerFeedbackAccess.WEB_REPORT || uploading || scan.origin != ScanCaptureOrigin.MANUAL ||
            previous?.reportId != null || previous?.outcomeUnknown == true) return
        send(scan, uid)
    }

    private fun send(scan: QueuedCardScan, uid: String) {
        val generation = ++uploadGeneration
        val key = outcomeKey(uid, scan.id)
        uploading = true
        uploadingScanId = scan.id
        outcomes.remove(key)
        uploadJob = scope.launch {
            try {
                val receipt = upload(scan.scannerReportDraft(), uid)
                if (uploadGeneration == generation) outcomes[key] = ScannerReportOutcome(reportId = receipt.reportId)
            } catch (cancelled: CancellationException) {
                if (uploadGeneration == generation) outcomes[key] = ScannerReportOutcome(
                    error = "Upload abgebrochen. Bitte vor erneutem Senden im Scanner-Lab prüfen.", outcomeUnknown = true,
                )
                throw cancelled
            } catch (failure: ScannerReportUploadException) {
                if (uploadGeneration == generation) outcomes[key] = ScannerReportOutcome(
                    error = failure.message, outcomeUnknown = failure.outcomeUnknown,
                )
            } catch (_: Exception) {
                // An unknown outcome must not encourage a duplicate POST.
                if (uploadGeneration == generation) outcomes[key] = ScannerReportOutcome(
                    error = "Upload nicht bestätigt. Bitte zuerst im Web-Scanner-Lab prüfen.", outcomeUnknown = true,
                )
            } finally {
                if (uploadGeneration == generation) {
                    uploading = false
                    uploadingScanId = null
                    outcomeRevision++
                }
            }
        }
    }
}

@Composable
internal fun rememberScannerReportFlow(): ScannerReportFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember(context) { FirebaseScannerReportUploader(context.applicationContext) }
    val consent = remember(context) { ScannerReportConsentStore(context) }
    val flow = remember(scope, uploader, consent) { ScannerReportFlow(scope,
        readAutoConsent = consent::isEnabled,
        writeAutoConsent = consent::setEnabled,
        upload = uploader::upload,
    ) }
    DisposableEffect(consent, flow) {
        val stopObserving = consent.observe(flow::refreshAutomaticConsent)
        flow.refreshAutomaticConsent()
        onDispose { stopObserving() }
    }
    val auth = remember { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    DisposableEffect(auth, flow) {
        var tokenJob: Job? = null
        fun refresh(force: Boolean) {
            tokenJob?.cancel()
            val uid = auth?.currentUser?.uid
            flow.updateAccess(uid, ScannerFeedbackAccess.CHECKING)
            tokenJob = scope.launch {
                try {
                    val account = auth?.currentFirebaseEntitlementAccount(forceRefresh = force)
                    if (auth?.currentUser?.uid == uid) {
                        flow.updateAccess(uid, if (auth == null) ScannerFeedbackAccess.UNAVAILABLE
                            else scannerFeedbackAccess(uid, account?.entitlements))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    flow.updateAccess(uid, ScannerFeedbackAccess.UNAVAILABLE)
                }
            }
        }
        val listener = FirebaseAuth.IdTokenListener { refresh(false) }
        auth?.addIdTokenListener(listener)
        flow.retryAccess = { refresh(true) }
        refresh(true)
        onDispose {
            auth?.removeIdTokenListener(listener)
            tokenJob?.cancel()
            flow.retryAccess = {}
        }
    }
    return flow
}

@Composable
internal fun ScannerAutomaticReportControl(flow: ScannerReportFlow, showAllAccess: Boolean = false) {
    if (!showAllAccess && flow.access != ScannerFeedbackAccess.WEB_REPORT) return
    var showDetails by remember { mutableStateOf(false) }
    Text("Fehlscans werden nur über „An ScanLab schicken“ gesendet.")
    Text("Du findest den Button unten bei der nicht erkannten Aufnahme. Erst nach deiner Bestätigung wird dieses Foto gesendet. Es gibt keine automatischen Uploads, auch nicht mit einer früher gespeicherten Einwilligung.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    Text("Der bestehende Upload erlaubt Fotos über den Auslöser; keine Auto-Aufnahmen oder Galerie-Importe.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    when {
        flow.accountUid == null -> Text("Melde dich an, um einen Fehlscan zu senden.")
        flow.access == ScannerFeedbackAccess.CHECKING -> Text("Berechtigung wird geprüft.")
        flow.access == ScannerFeedbackAccess.UNAVAILABLE -> {
            Text("Berechtigung konnte nicht geprüft werden. Neue Uploads sind gesperrt.")
            TextButton(onClick = flow.retryAccess) { Text("Berechtigung erneut prüfen") }
        }
        flow.access == ScannerFeedbackAccess.LOCAL -> Text("Das Senden ist nur für Administratoren und freigeschaltete Tester möglich.")
    }
    TextButton(onClick = { showDetails = true }) { Text("Mehr zum Datenschutz beim Senden") }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Welche Scandaten werden gesendet?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gesendet werden das Kartenfoto, erkannte Kartenmerkmale und technische Analyseergebnisse wie Erkennungssicherheit und Fehlergrund. Standort- und andere Foto-Metadaten werden vor dem Senden entfernt.")
                Text("Dein Klarname, Telefonnummer, Adresse und eine zusätzliche Gerätekennung gehören nicht zu den Berichtsfeldern. Der Upload erfolgt mit deinem angemeldeten Konto und ist diesem zugeordnet; er ist nicht anonym. Fotografiere keine persönlichen Informationen.")
                Text("Nur Administratoren können die Berichte im Scanner Lab ansehen. Ein ausdrücklich gestarteter Upload kann im Hintergrund abgeschlossen werden. Bereits gesendete Berichte werden durch Schließen der Ansicht nicht gelöscht. Für deren Löschung kannst du den Datenschutzkontakt nutzen.")
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Verstanden") } },
    )
}

@Composable
internal fun ScannerReportButton(flow: ScannerReportFlow, scan: QueuedCardScan) {
    if (flow.access != ScannerFeedbackAccess.WEB_REPORT) return
    val outcome = flow.outcome(scan.id)
    if (scan.stage.needsScannerReview) {
        Button(onClick = { flow.open(scan) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (outcome != null) "Upload-Status" else "An ScanLab schicken")
        }
    } else if (scan.stage == ScanPipelineStage.MATCHED) {
        TextButton(onClick = { flow.open(scan) }) {
            Text(if (outcome != null) "Upload-Status" else "Fehlscan melden")
        }
    }
}

@Composable
internal fun ScannerReportDialog(flow: ScannerReportFlow, onManualAdd: (() -> Unit)? = null) {
    val scan = flow.selected ?: return
    val outcome = flow.outcome(scan.id)
    val manualCapture = scan.origin == ScanCaptureOrigin.MANUAL
    val canUpload = manualCapture && flow.access == ScannerFeedbackAccess.WEB_REPORT &&
        !flow.uploading && outcome?.reportId == null && outcome?.outcomeUnknown != true
    AlertDialog(
        onDismissRequest = flow::dismiss,
        title = { Text("Fehlscan an Scanner-Lab") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = scan.file,
                    contentDescription = "Aufnahme für den Fehlscan-Bericht",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                )
                Text("Die Analysefelder werden im Admin-Scanner-Lab der Web-App bearbeitet. Manuelles Hinzufügen mit Set und Nummer bleibt auf dem Handy möglich.")
                when {
                    outcome?.reportId != null -> Text("Hochgeladen. Der Bericht ist im Web-Scanner-Lab zur Bearbeitung bereit.")
                    !manualCapture -> Text("Der aktuelle Firebase-Upload erlaubt nur manuell ausgelöste Fotos. Bitte die Karte erneut mit dem Auslöser aufnehmen. Diese Aufnahme wird nicht hochgeladen.")
                    else -> Text("Mit „Hochladen“ stimmst du zu, dieses Foto und die erkannten Kartenmerkmale samt Analyseergebnis geschützt in Firebase zu speichern. Nur Administratoren können den Bericht ansehen. Prüfe, dass keine persönlichen Informationen im Bild sichtbar sind. Standort-Metadaten werden entfernt.")
                }
                if (flow.uploadingScanId == scan.id) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Bericht wird im Hintergrund hochgeladen …")
                }
                outcome?.error?.let { Text(it) }
                onManualAdd?.let { action -> TextButton(onClick = action) { Text("Manuell hinzufügen: Set + Nummer") } }
                if (flow.access == ScannerFeedbackAccess.UNAVAILABLE) {
                    Text("Berechtigung konnte nicht geprüft werden. Es wurde kein neuer Upload gestartet.")
                    TextButton(onClick = flow.retryAccess) { Text("Berechtigung erneut prüfen") }
                }
            }
        },
        confirmButton = {
            if (manualCapture && outcome?.reportId == null && outcome?.outcomeUnknown != true) {
                Button(onClick = flow::submit, enabled = canUpload) { Text("Hochladen") }
            } else TextButton(onClick = flow::dismiss) { Text("Schließen") }
        },
        dismissButton = {
            if (manualCapture && outcome?.reportId == null && outcome?.outcomeUnknown != true) {
                TextButton(onClick = flow::dismiss) { Text(if (flow.uploadingScanId == scan.id) "Schließen" else "Abbrechen") }
            }
        },
    )
}
