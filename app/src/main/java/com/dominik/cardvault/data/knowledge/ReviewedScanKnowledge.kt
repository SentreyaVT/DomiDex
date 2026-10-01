package com.dominik.cardvault.data.knowledge

import com.dominik.cardvault.auth.firebaseEntitlementsFromClaims
import com.dominik.cardvault.data.feedback.ScannerReportKnowledgeContext
import com.squareup.moshi.Moshi

/** Separate from community votes: no invented STABLE state or confirmation count. */
internal data class ReviewedScanKnowledgeHint(
    val fingerprint: String,
    val scannerVersion: String,
    val knowledgeVersion: Int,
    val sourceSetId: String,
    val sourceCardId: String,
    val cardNumber: String,
    val variant: String?,
)

internal object ReviewedScanKnowledgeContract {
    const val ENDPOINT = "https://domidex.web.app/api/scanner/reviewed-knowledge"
    const val TIMEOUT_MILLIS = 1_500L
    const val MAX_RESPONSE_BYTES = 4_096L
    private val json = Moshi.Builder().build().adapter(Any::class.java)
    private val sourceId = Regex("[A-Za-z0-9._-]{1,128}")
    private val cardNumber = Regex("[A-Za-z0-9.-]{1,40}")
    private val variants = setOf("normal", "holo", "reverse", "metal", "lenticular")

    fun allowedAccount(expectedUid: String, currentUid: String?, claims: Map<String, *>): Boolean =
        expectedUid.isNotBlank() && currentUid == expectedUid &&
            firebaseEntitlementsFromClaims(claims).canUsePretesterBackend

    fun payload(context: ScannerReportKnowledgeContext): String? = context.takeIf { it.isValid() }?.let {
        json.toJson(mapOf("fingerprint" to it.fingerprint, "scannerVersion" to it.scannerVersion,
            "knowledgeVersion" to it.knowledgeVersion))
    }

    fun parseResponse(context: ScannerReportKnowledgeContext, body: String): ReviewedScanKnowledgeHint? {
        if (!context.isValid() || body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return null
        val root = runCatching { json.fromJson(body) as? Map<*, *> }.getOrNull() ?: return null
        if (root["ok"] != true) return null
        val hint = root["hint"] as? Map<*, *> ?: return null
        if (hint["fingerprint"] != context.fingerprint || hint["scannerVersion"] != context.scannerVersion ||
            (hint["knowledgeVersion"] as? Number)?.toDouble() != context.knowledgeVersion.toDouble()) return null
        val setId = (hint["sourceSetId"] as? String)?.takeIf(sourceId::matches) ?: return null
        val cardId = (hint["sourceCardId"] as? String)?.takeIf(sourceId::matches) ?: return null
        val number = (hint["cardNumber"] as? String)?.takeIf(cardNumber::matches) ?: return null
        val variant = hint["variant"]
        if (variant != null && variant !in variants) return null
        return ReviewedScanKnowledgeHint(context.fingerprint, context.scannerVersion, context.knowledgeVersion,
            setId, cardId, number, variant as? String)
    }
}
