package com.dominik.cardvault.data.knowledge

import java.security.MessageDigest
import java.util.Locale

internal const val SCAN_KNOWLEDGE_VERSION = 1

internal enum class ScanKnowledgeStatus { CANDIDATE, STABLE, DISPUTED }

/** Image-free, account-free evidence extracted from the existing recognition pipeline. */
internal data class ScanKnowledgeEvidence(
    val fingerprint: String,
    val confirmedCardId: String,
    val sourceSetId: String?,
    val cardNumber: String,
    val setCode: String?,
    val ocrTokens: List<String>,
    val visualFingerprint: String?,
    val candidateIds: List<String>,
    val originalConfidence: Int,
    val corrected: Boolean,
    val scannerVersion: String,
    val knowledgeVersion: Int = SCAN_KNOWLEDGE_VERSION,
    val observedAtClient: Long = System.currentTimeMillis(),
)

internal data class ScanKnowledgeRecord(
    val fingerprint: String,
    val cardId: String,
    val sourceSetId: String?,
    val cardNumber: String,
    val status: ScanKnowledgeStatus,
    val confidence: Int,
    val confirmations: Int,
    val corrections: Int,
    val knowledgeVersion: Int,
    val updatedAtEpochMillis: Long,
)

/** Stable across devices; raw OCR text and visual samples never become part of the key. */
internal object ScanKnowledgeFingerprint {
    fun create(
        sourceSetId: String?,
        cardNumber: String,
        setCode: String?,
        ocrTokens: List<String>,
        visualFingerprint: String?,
        scannerVersion: String,
        knowledgeVersion: Int = SCAN_KNOWLEDGE_VERSION,
    ): String {
        val canonical = listOf(
            canonical(sourceSetId.orEmpty()),
            canonical(cardNumber),
            canonical(setCode.orEmpty()),
            ocrTokens.asSequence().map(::canonical).filter(String::isNotBlank).distinct().sorted().take(24).joinToString(","),
            canonical(visualFingerprint.orEmpty()),
            canonical(scannerVersion),
            knowledgeVersion.toString(),
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun canonicalToken(value: String): String = canonical(value).take(80)

    private fun canonical(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}

internal fun shouldUploadScanEvidence(
    evidence: ScanKnowledgeEvidence,
    known: ScanKnowledgeRecord?,
    previouslyUploadedCardId: String?,
): Boolean {
    if (known?.status == ScanKnowledgeStatus.STABLE && known.cardId == evidence.confirmedCardId && !evidence.corrected) return false
    if (previouslyUploadedCardId == evidence.confirmedCardId && !evidence.corrected) return false
    return true
}
