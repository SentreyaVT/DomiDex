package com.dominik.cardvault.ui

import com.dominik.cardvault.data.knowledge.SCAN_KNOWLEDGE_VERSION
import com.dominik.cardvault.data.knowledge.ScanKnowledgeEvidence
import com.dominik.cardvault.data.knowledge.ScanKnowledgeFingerprint
import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.knowledge.ScanKnowledgeStatus
import com.dominik.cardvault.data.feedback.ScannerReportKnowledgeContext
import com.dominik.cardvault.model.CatalogSearchResult
import java.security.MessageDigest

internal const val DOMIDEX_SCANNER_VERSION = "scanner-v2.1"

internal fun scannerReportKnowledgeContext(analysis: CardTextAnalysis): ScannerReportKnowledgeContext? =
    scanKnowledgeFingerprint(analysis)?.let {
        ScannerReportKnowledgeContext(it, DOMIDEX_SCANNER_VERSION, SCAN_KNOWLEDGE_VERSION)
    }

internal fun scanKnowledgeFingerprint(analysis: CardTextAnalysis): String? {
    val cardNumber = analysis.collectorNumber?.takeIf(String::isNotBlank) ?: return null
    val tokens = analysis.roiEvidence.flatMap { evidence ->
        listOf(evidence.normalizedValue) + evidence.rawValues
    }.filterNotNull().ifEmpty { analysis.searchQueries }
    return ScanKnowledgeFingerprint.create(
        sourceSetId = analysis.manualSetSourceId,
        cardNumber = cardNumber,
        setCode = analysis.setIdentifier,
        ocrTokens = tokens,
        visualFingerprint = visualFingerprint(analysis),
        scannerVersion = DOMIDEX_SCANNER_VERSION,
    )
}

internal fun buildScanKnowledgeEvidence(
    analysis: CardTextAnalysis,
    originalCandidates: List<ScanCandidate>,
    selected: CatalogSearchResult,
): ScanKnowledgeEvidence? {
    val fingerprint = scanKnowledgeFingerprint(analysis) ?: return null
    val selectedIdentity = selected.scanIdentityKey()
    val originalBest = originalCandidates.firstOrNull()
    val tokens = analysis.roiEvidence.flatMap { evidence ->
        listOf(evidence.normalizedValue) + evidence.rawValues
    }.filterNotNull().ifEmpty { analysis.searchQueries }
        .map(ScanKnowledgeFingerprint::canonicalToken)
        .filter(String::isNotBlank)
        .distinct()
        .take(24)
    return ScanKnowledgeEvidence(
        fingerprint = fingerprint,
        confirmedCardId = selectedIdentity,
        sourceSetId = selected.sourceSetId ?: analysis.manualSetSourceId,
        cardNumber = analysis.collectorNumber.orEmpty().take(24),
        setCode = analysis.setIdentifier?.take(20),
        ocrTokens = tokens,
        visualFingerprint = visualFingerprint(analysis),
        candidateIds = originalCandidates.map { it.card.scanIdentityKey() }.distinct().take(8),
        originalConfidence = (originalBest?.evidence?.finalConfidence ?: originalBest?.score ?: 0).coerceIn(0, 100),
        corrected = analysis.manualIdentityConfirmed || (originalBest != null && originalBest.card.scanIdentityKey() != selectedIdentity),
        scannerVersion = DOMIDEX_SCANNER_VERSION,
        knowledgeVersion = SCAN_KNOWLEDGE_VERSION,
    )
}

/** Community evidence only reorders already valid official candidates; it never removes contradictions. */
internal object ScanKnowledgeCandidateRanker {
    fun apply(candidates: List<ScanCandidate>, knowledge: ScanKnowledgeRecord?): List<ScanCandidate> {
        if (knowledge == null || knowledge.knowledgeVersion != SCAN_KNOWLEDGE_VERSION || knowledge.status == ScanKnowledgeStatus.DISPUTED) return candidates
        val positive = when (knowledge.status) {
            ScanKnowledgeStatus.STABLE -> 5
            ScanKnowledgeStatus.CANDIDATE -> 2
            ScanKnowledgeStatus.DISPUTED -> 0
        }
        return candidates.map { candidate ->
            val evidence = candidate.evidence ?: return@map candidate
            if (evidence.contradictions.isNotEmpty()) return@map candidate
            val same = candidate.card.scanIdentityKey() == knowledge.cardId
            val delta = if (same) positive else -minOf(positive, 3)
            candidate.copy(evidence = evidence.copy(finalConfidence = (evidence.finalConfidence + delta).coerceIn(0, 100)))
        }.sortedByDescending { it.evidence?.finalConfidence ?: it.score }
    }
}

private fun visualFingerprint(analysis: CardTextAnalysis): String? {
    val compact = analysis.visualDescriptors.flatMap { descriptor ->
        descriptor.regions.map { region -> "${descriptor.version}:${region.id}:${region.differenceHash.toULong().toString(16)}:${region.differenceHashMask.toULong().toString(16)}" }
    }.take(8).joinToString("|")
    if (compact.isBlank()) return null
    return MessageDigest.getInstance("SHA-256").digest(compact.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
