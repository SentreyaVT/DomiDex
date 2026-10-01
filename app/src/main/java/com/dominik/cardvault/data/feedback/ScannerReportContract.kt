package com.dominik.cardvault.data.feedback

import com.dominik.cardvault.auth.firebaseEntitlementsFromClaims
import com.squareup.moshi.Moshi
import java.io.File

internal data class ScannerReportDraft(
    val scanId: String,
    val image: File,
    val captureType: String = "MANUAL_CAPTURE",
    val guessedName: String? = null,
    val guessedSet: String? = null,
    val guessedNumber: String? = null,
    val confidence: Double? = null,
    val ocrTokens: List<String> = emptyList(),
    val zoneConfidence: Map<String, Double> = emptyMap(),
    val failureReason: String,
    val knowledgeContext: ScannerReportKnowledgeContext? = null,
)

/** The original analysis key only; never a reconstruction from the reviewed card or JPEG. */
internal data class ScannerReportKnowledgeContext(
    val fingerprint: String,
    val scannerVersion: String,
    val knowledgeVersion: Int,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean = schemaVersion == 1 && knowledgeVersion == 1 &&
        fingerprint.matches(Regex("[a-f0-9]{64}")) && scannerVersion.matches(Regex("[A-Za-z0-9._-]{1,40}"))
}

internal data class ScannerReportReceipt(val reportId: String)

/** Contains only safe user-facing text; remote error bodies and tokens never escape here. */
internal class ScannerReportUploadException(
    message: String,
    val outcomeUnknown: Boolean = false,
) : Exception(message)

/** Existing Web scanner-report contract. Image paths, ownership and review state are server-owned. */
internal object ScannerReportContract {
    const val ENDPOINT = "https://domidex.web.app/api/pretester/scan-reports"
    const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
    const val MAX_RESPONSE_BYTES = 16 * 1024
    private val scanIdPattern = Regex("[A-Za-z0-9._-]{1,128}")
    private val receiptIdPattern = Regex("[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}")
    private val json = Moshi.Builder().build().adapter(Any::class.java)

    fun allowedAccount(expectedUid: String, currentUid: String?, claims: Map<String, *>): Boolean =
        expectedUid.isNotBlank() && expectedUid == currentUid &&
            firebaseEntitlementsFromClaims(claims).canUsePretesterBackend

    fun payload(draft: ScannerReportDraft): String {
        if (draft.captureType != "MANUAL_CAPTURE") {
            throw ScannerReportUploadException("Es können nur bewusst manuell aufgenommene Fehlscans gesendet werden.")
        }
        if (!scanIdPattern.matches(draft.scanId)) {
            throw ScannerReportUploadException("Der Fehlscan hat keine gültige Kennung. Bitte erneut manuell aufnehmen.")
        }
        val values = linkedMapOf<String, Any>(
            "scanId" to draft.scanId,
            "captureType" to "MANUAL_CAPTURE",
            "attempts" to emptyList<Any>(),
            "ocrTokens" to draft.ocrTokens.take(100).map { it.trim().take(120) },
            "zoneConfidence" to draft.zoneConfidence.asSequence()
                .filter { (key, value) -> key.isNotBlank() && validConfidence(value) }
                .take(64)
                .associate { (key, value) -> key.trim().take(80) to value },
            "failureReason" to (draft.failureReason.trim().take(1_000)
                .ifBlank { "Kein Fehlergrund übermittelt." }),
        )
        draft.guessedName.cleanText(200)?.let { values["guessedName"] = it }
        draft.guessedSet.cleanText(160)?.let { values["guessedSet"] = it }
        draft.guessedNumber.cleanText(80)?.let { values["guessedNumber"] = it }
        // The Web normalizer treats explicit null as zero; unknown confidence must be omitted.
        draft.confidence?.takeIf(::validConfidence)?.let { values["confidence"] = it }
        draft.knowledgeContext?.takeIf(ScannerReportKnowledgeContext::isValid)?.let {
            values["knowledgeContext"] = mapOf(
                "schemaVersion" to it.schemaVersion,
                "fingerprint" to it.fingerprint,
                "scannerVersion" to it.scannerVersion,
                "knowledgeVersion" to it.knowledgeVersion,
            )
        }
        return json.toJson(values)
    }

    fun parseReceipt(status: Int, body: String): ScannerReportReceipt {
        if (status != 201) throw responseFailure(status)
        val parsed = try {
            if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) null
            else json.fromJson(body) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        val id = parsed?.get("reportId") as? String
        if (parsed?.get("ok") != true || parsed?.get("reviewStatus") != "NEW" ||
            id == null || !receiptIdPattern.matches(id)
        ) {
            throw unknownOutcome()
        }
        return ScannerReportReceipt(id)
    }

    /** Ceiling division keeps even an odd or extreme source dimension within the decode budget. */
    fun decodeSample(width: Int, height: Int, maxEdge: Int = 2048): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) {
            throw ScannerReportUploadException("Das Fehlscan-Bild kann nicht gelesen werden.")
        }
        val largest = maxOf(width, height).toLong()
        var sample = 1
        while ((largest + sample - 1L) / sample > maxEdge) {
            if (sample > Int.MAX_VALUE / 2) {
                throw ScannerReportUploadException("Das Fehlscan-Bild ist zu groß.")
            }
            sample *= 2
        }
        return sample
    }

    fun unknownOutcome(): ScannerReportUploadException = ScannerReportUploadException(
        "Der Empfang konnte nicht bestätigt werden. Der Fehlscan könnte bereits im Scanner-Lab liegen. Bitte vor erneutem Senden dort prüfen.",
        outcomeUnknown = true,
    )

    private fun responseFailure(status: Int): ScannerReportUploadException = when (status) {
        400 -> ScannerReportUploadException("Der Fehlscan wurde vom Scanner-Lab abgelehnt. Bitte erneut manuell aufnehmen.")
        401 -> ScannerReportUploadException("Die Anmeldung ist abgelaufen. Bitte erneut anmelden.")
        403 -> ScannerReportUploadException("Zum Senden sind Administrator- oder Pretesterrechte erforderlich.")
        413 -> ScannerReportUploadException("Das Fehlscan-Bild überschreitet die Uploadgrenze von 10 MB.")
        415 -> ScannerReportUploadException("Das Bildformat wurde vom Scanner-Lab nicht akzeptiert.")
        else -> unknownOutcome()
    }

    private fun validConfidence(value: Double): Boolean = value.isFinite() && value in 0.0..1.0
    private fun String?.cleanText(maximum: Int): String? = this?.trim()?.take(maximum)?.takeIf(String::isNotBlank)
}
