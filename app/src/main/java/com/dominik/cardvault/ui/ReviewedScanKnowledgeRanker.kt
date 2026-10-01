package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.collectorIdentityForSourceLookup
import com.dominik.cardvault.data.knowledge.ReviewedScanKnowledgeHint
import com.dominik.cardvault.data.knowledge.SCAN_KNOWLEDGE_VERSION
import java.util.Locale

/** A lab review may reorder close existing candidates, never establish identity or auto-accept. */
internal object ReviewedScanKnowledgeRanker {
    fun apply(candidates: List<ScanCandidate>, hint: ReviewedScanKnowledgeHint?, fingerprint: String?): List<ScanCandidate> {
        if (hint == null || fingerprint == null || hint.fingerprint != fingerprint ||
            hint.knowledgeVersion != SCAN_KNOWLEDGE_VERSION || hint.scannerVersion != DOMIDEX_SCANNER_VERSION ||
            HybridCandidateFusion.decide(candidates).decision == ScannerDecision.ACCEPTED) return candidates
        val matching = candidates.singleOrNull { candidate ->
            val card = candidate.card
            val evidence = candidate.evidence
            evidence != null && evidence.contradictions.isEmpty() && evidence.numberScore >= 86 && evidence.setScore >= 65 &&
                card.sourceSetId.equals(hint.sourceSetId, true) && card.sourceCardId.equals(hint.sourceCardId, true) &&
                collectorIdentityForSourceLookup(card.collectorNumber.orEmpty(), hint.sourceSetId).let { number ->
                    number.isNotBlank() && number == collectorIdentityForSourceLookup(hint.cardNumber, hint.sourceSetId)
                } && matchesOrdinaryVariant(card.finish, card.variant, hint.variant)
        } ?: return candidates
        val ranked = candidates.map { candidate ->
            if (candidate !== matching) candidate else candidate.copy(evidence = candidate.evidence!!.let {
                it.copy(finalConfidence = (it.finalConfidence + 2).coerceAtMost(100))
            })
        }.sortedByDescending { it.evidence?.finalConfidence ?: it.score }
        // Even a two-point bonus must not manufacture a formerly missing accepted margin.
        return if (HybridCandidateFusion.decide(ranked).decision == ScannerDecision.ACCEPTED) candidates else ranked
    }

    private fun matchesOrdinaryVariant(finish: String?, variant: String?, reviewed: String?): Boolean {
        fun canonical(value: String?) = value.orEmpty().trim().lowercase(Locale.ROOT)
        val actualFinish = canonical(finish)
        val actualVariant = canonical(variant)
        return when (reviewed) {
            "normal" -> actualFinish == "normal" && actualVariant in setOf("standard", "normal")
            "holo" -> actualFinish == "holo" && actualVariant in setOf("standard", "holo")
            "reverse" -> actualFinish == "reverse holo" && actualVariant in setOf("standard", "reverse holo")
            else -> false // Unknown/stamped/metal/lenticular printings are never guessed.
        }
    }
}
