package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CatalogSearchResult
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

internal enum class RecognitionConfidence(val userLabel: String) {
    VERY_HIGH("Sicher erkannt"),
    HIGH("Sehr wahrscheinlich erkannt"),
    MEDIUM("Bitte prüfen"),
    LOW("Nicht sicher erkannt"),
    UNRESOLVED("Nicht erkannt"),
}

/** Explainable 0..100 evidence values retained per candidate for debug and feedback. */
internal data class CandidateSignalScores(
    val visualScore: Int,
    val numberScore: Int,
    val setScore: Int,
    val nameScore: Int,
    val languageScore: Int,
    val finalConfidence: Int,
    val confidence: RecognitionConfidence,
    val contradictions: Set<String> = emptySet(),
)

/**
 * Fuses independent evidence after Module 3 has reduced the catalogue candidates. Visual evidence
 * is strongest when available, but a network/cache miss does not make the offline OCR path
 * unusable. A hard contradiction can only lower confidence; it can never be averaged away.
 */
internal object HybridCandidateFusion {
    private const val VISUAL_WEIGHT = 50f
    private const val NUMBER_WEIGHT = 25f
    private const val SET_WEIGHT = 10f
    private const val NAME_WEIGHT = 10f
    private const val LANGUAGE_WEIGHT = 5f

    fun rank(
        analysis: CardTextAnalysis,
        candidates: List<ScanCandidate>,
        artwork: Map<Long, ArtworkVerification>,
    ): List<ScanCandidate> = candidates.map { candidate ->
        candidate.copy(evidence = score(analysis, candidate.card, artwork[candidate.card.productId]))
    }.sortedByDescending { it.evidence?.finalConfidence ?: it.score }

    fun score(
        analysis: CardTextAnalysis,
        candidate: CatalogSearchResult,
        artwork: ArtworkVerification?,
    ): CandidateSignalScores {
        val readNumber = canonicalNumber(analysis.collectorNumber.orEmpty())
        val candidateNumber = canonicalNumber(candidate.collectorNumber.orEmpty())
        val number = if (readNumber.isNotBlank() && readNumber == candidateNumber) 100 else 0

        val readSet = canonicalMetadata(analysis.setIdentifier.orEmpty())
        val candidateSet = canonicalMetadata(candidate.setCode.orEmpty())
        val set = when {
            readSet.isBlank() -> 0
            candidateSet.isBlank() -> 65 // candidate came from exact source lookup, but has no display code
            readSet == candidateSet -> 100
            else -> 0
        }

        val candidateName = canonicalName(candidate.name)
        val nameSimilarity = analysis.searchQueries.maxOfOrNull { similarity(canonicalName(it), candidateName) } ?: 0f
        val readableName = analysis.hasNameRoiEvidence && analysis.searchQueries.any { canonicalName(it).length >= 3 }
        val name = (nameSimilarity * 100).roundToInt()

        val language = when (candidate.language?.uppercase(Locale.ROOT)) {
            "GERMAN", "DE", "DEUTSCH" -> 100
            null, "", "UNKNOWN" -> if (analysis.likelyScript == CardScript.LATIN) 65 else 35
            "ENGLISH" -> if (analysis.likelyScript == CardScript.LATIN) 55 else 20
            else -> 20
        }

        val visualAvailable = artwork != null && artwork.match != ArtworkMatch.UNAVAILABLE
        val visual = if (visualAvailable) (artwork!!.similarity * 100).roundToInt().coerceIn(0, 100) else 0
        var weighted = number * NUMBER_WEIGHT + set * SET_WEIGHT + name * NAME_WEIGHT + language * LANGUAGE_WEIGHT
        var availableWeight = NUMBER_WEIGHT + SET_WEIGHT + NAME_WEIGHT + LANGUAGE_WEIGHT
        if (visualAvailable) {
            weighted += visual * VISUAL_WEIGHT
            availableWeight += VISUAL_WEIGHT
        }
        var finalScore = (weighted / availableWeight).roundToInt().coerceIn(0, 100)
        val contradictions = buildSet {
            if (readNumber.isNotBlank() && number == 0) add("collector_number")
            if (readSet.isNotBlank() && candidateSet.isNotBlank() && set == 0) add("set")
            if (readableName && nameSimilarity < .38f) add("name")
            if (artwork?.match == ArtworkMatch.CONTRADICTS) add("artwork")
        }
        if ("collector_number" in contradictions || "set" in contradictions) finalScore = minOf(finalScore, 45)
        if ("name" in contradictions || "artwork" in contradictions) finalScore = minOf(finalScore, 58)

        val hardIdentity = number == 100 && set >= 65 && "name" !in contradictions
        val confidence = when {
            contradictions.isNotEmpty() -> RecognitionConfidence.LOW
            finalScore >= 92 && artwork?.match == ArtworkMatch.CORROBORATES && hardIdentity -> RecognitionConfidence.VERY_HIGH
            finalScore >= 86 && hardIdentity -> RecognitionConfidence.HIGH
            finalScore >= 72 -> RecognitionConfidence.MEDIUM
            finalScore >= 60 -> RecognitionConfidence.LOW
            else -> RecognitionConfidence.UNRESOLVED
        }
        return CandidateSignalScores(visual, number, set, name, language, finalScore, confidence, contradictions)
    }

    fun decide(candidates: List<ScanCandidate>): ScannerDecisionResult {
        val best = candidates.firstOrNull()?.evidence
            ?: return ScannerDecisionResult(ScannerDecision.REJECTED, reason = "no_hybrid_candidate")
        val second = candidates.drop(1).firstOrNull()?.evidence?.finalConfidence
        val margin = second?.let { best.finalConfidence - it } ?: Int.MAX_VALUE
        return when {
            best.contradictions.isNotEmpty() -> ScannerDecisionResult(ScannerDecision.REJECTED, best.finalConfidence, second, "independent_signal_contradiction")
            best.confidence in setOf(RecognitionConfidence.VERY_HIGH, RecognitionConfidence.HIGH) && margin >= ScannerDecisionPolicy.MIN_ACCEPTED_MARGIN ->
                ScannerDecisionResult(ScannerDecision.ACCEPTED, best.finalConfidence, second, "hybrid_identity_with_clear_margin")
            best.confidence in setOf(RecognitionConfidence.VERY_HIGH, RecognitionConfidence.HIGH, RecognitionConfidence.MEDIUM) ->
                ScannerDecisionResult(ScannerDecision.AMBIGUOUS, best.finalConfidence, second, if (margin < ScannerDecisionPolicy.MIN_ACCEPTED_MARGIN) "reprint_or_candidate_margin_too_close" else "hybrid_review_required")
            else -> ScannerDecisionResult(ScannerDecision.REJECTED, best.finalConfidence, second, "hybrid_evidence_insufficient")
        }
    }

    private fun canonicalNumber(value: String) = value.substringBefore('/').filter(Char::isDigit).trimStart('0').ifBlank { if (value.any(Char::isDigit)) "0" else "" }
    private fun canonicalMetadata(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun canonicalName(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .lowercase(Locale.ROOT).replace("ß", "ss").filter(Char::isLetterOrDigit)

    private fun similarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right || left.contains(right) || right.contains(left)) return 1f
        val leftPairs = left.windowed(2).toSet()
        val rightPairs = right.windowed(2).toSet()
        val pairScore = leftPairs.intersect(rightPairs).size.toFloat() / leftPairs.union(rightPairs).size.coerceAtLeast(1)
        val editScore = 1f - levenshtein(left, right).toFloat() / maxOf(left.length, right.length)
        return (pairScore * .42f + editScore.coerceIn(0f, 1f) * .58f).coerceIn(0f, 1f)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { row, leftChar ->
            val current = IntArray(right.length + 1); current[0] = row + 1
            right.forEachIndexed { column, rightChar ->
                current[column + 1] = minOf(current[column] + 1, previous[column + 1] + 1, previous[column] + if (leftChar == rightChar) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }
}
