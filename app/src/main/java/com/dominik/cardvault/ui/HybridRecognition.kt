package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.collectorIdentityForSourceLookup
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
        artwork: Map<String, ArtworkVerification>,
    ): List<ScanCandidate> = candidates.map { candidate ->
        candidate.copy(evidence = score(analysis, candidate.card, artwork[candidate.card.scanIdentityKey()]))
    }.sortedByDescending { it.evidence?.finalConfidence ?: it.score }

    fun score(
        analysis: CardTextAnalysis,
        candidate: CatalogSearchResult,
        artwork: ArtworkVerification?,
    ): CandidateSignalScores {
        // A market expansion id or printed set code cannot authorize stripping an arbitrary prefix.
        val numberSourceSetId = candidate.sourceSetId?.takeIf(String::isNotBlank) ?: candidate.expansionId
        val readNumber = collectorIdentityForSourceLookup(analysis.collectorNumber.orEmpty(), numberSourceSetId)
        val candidateNumber = collectorIdentityForSourceLookup(candidate.collectorNumber.orEmpty(), numberSourceSetId)
        val number = if (readNumber.isNotBlank() && readNumber == candidateNumber) {
            (60f + analysis.numberConfidence.coerceIn(0f, 1f) * 40f).roundToInt()
        } else 0

        // A manual source id (for example `sv08.5`) is a catalogue key, not a printed set code.
        // Keep those namespaces separate so the UI selection can never masquerade as OCR.
        val manualSourceSet = canonicalMetadata(analysis.manualSetSourceId.orEmpty())
        val candidateSourceSet = candidate.expansionId
            .takeIf { value -> value.any(Char::isLetter) }
            ?.let(::canonicalMetadata)
            .orEmpty()
        val readSet = canonicalMetadata(analysis.setIdentifier.orEmpty())
        val candidateSet = canonicalMetadata(candidate.setCode.orEmpty())
        val readPrintedTotal = analysis.collectorNumber?.let(::printedCollectorDenominator)
        val printedTotalMatches = readPrintedTotal != null && candidate.mainSetSize == readPrintedTotal
        val printedTotalContradicts = readPrintedTotal != null &&
            candidate.mainSetSize != null &&
            candidate.mainSetSize != readPrintedTotal
        val manualSourceMatches = manualSourceSet.isNotBlank() && candidateSourceSet == manualSourceSet
        val manualSourceContradicts = manualSourceSet.isNotBlank() &&
            candidateSourceSet.isNotBlank() &&
            candidateSourceSet != manualSourceSet
        val set = when {
            printedTotalContradicts || manualSourceContradicts -> 0
            manualSourceMatches -> 100
            readSet.isBlank() && printedTotalMatches -> 65
            readSet.isBlank() -> 0
            candidateSet.isBlank() -> 65 // candidate came from exact source lookup, but has no display code
            readSet == candidateSet -> 100
            manualSourceSet.isNotBlank() && candidateSourceSet.isBlank() -> 65 // exact reducer, market id has no source namespace
            else -> 0
        }

        val candidateName = canonicalName(candidate.name)
        val localizedNameComparable = candidate.language != SOURCE_FALLBACK_LANGUAGE
        val nameSimilarity = if (localizedNameComparable) {
            analysis.searchQueries.maxOfOrNull { similarity(canonicalName(it), candidateName) } ?: 0f
        } else 0f
        val readableName = localizedNameComparable && analysis.hasNameRoiEvidence && analysis.searchQueries.any { canonicalName(it).length >= 3 }
        val name = (nameSimilarity * 100).roundToInt()

        val language = when (candidate.language?.uppercase(Locale.ROOT)) {
            "GERMAN", "DE", "DEUTSCH" -> 100
            SOURCE_FALLBACK_LANGUAGE -> 65
            null, "", "UNKNOWN" -> if (analysis.likelyScript == CardScript.LATIN) 65 else 35
            "ENGLISH" -> if (analysis.likelyScript == CardScript.LATIN) 55 else 20
            else -> 20
        }

        val visualAvailable = artwork != null && artwork.match != ArtworkMatch.UNAVAILABLE
        val visual = if (visualAvailable) (artwork!!.similarity * 100).roundToInt().coerceIn(0, 100) else 0
        val numberAvailable = readNumber.isNotBlank()
        val setAvailable = manualSourceSet.isNotBlank() || readSet.isNotBlank() || printedTotalMatches || printedTotalContradicts
        var weighted = language * LANGUAGE_WEIGHT
        var availableWeight = LANGUAGE_WEIGHT
        if (numberAvailable) { weighted += number * NUMBER_WEIGHT; availableWeight += NUMBER_WEIGHT }
        if (setAvailable) { weighted += set * SET_WEIGHT; availableWeight += SET_WEIGHT }
        if (readableName) { weighted += name * NAME_WEIGHT; availableWeight += NAME_WEIGHT }
        if (visualAvailable) {
            // An inconclusive glare-affected visual result remains evidence, but may not drown out
            // exact printed identity. A clear corroboration/contradiction retains full weight.
            val visualWeight = if (artwork?.match == ArtworkMatch.INCONCLUSIVE) 22f else VISUAL_WEIGHT
            weighted += visual * visualWeight
            availableWeight += visualWeight
        }
        var finalScore = (weighted / availableWeight).roundToInt().coerceIn(0, 100)
        val contradictions = buildSet {
            if (readNumber.isNotBlank() && number == 0) add("collector_number")
            if (readSet.isNotBlank() && candidateSet.isNotBlank() && set == 0) add("set")
            if (manualSourceContradicts) add("manual_set_source")
            if (printedTotalContradicts) add("printed_total")
            if (readableName && nameSimilarity < .25f) add("name")
            if (artwork?.match == ArtworkMatch.CONTRADICTS) add("artwork")
        }
        if (
            "collector_number" in contradictions ||
            "set" in contradictions ||
            "manual_set_source" in contradictions ||
            "printed_total" in contradictions
        ) finalScore = minOf(finalScore, 45)
        if ("name" in contradictions || "artwork" in contradictions) finalScore = minOf(finalScore, 58)

        val hardIdentity = number >= 86 && set >= 65
        val confidence = when {
            contradictions.isNotEmpty() -> RecognitionConfidence.LOW
            finalScore >= 91 && artwork?.match == ArtworkMatch.CORROBORATES && hardIdentity -> RecognitionConfidence.VERY_HIGH
            finalScore >= 85 && hardIdentity -> RecognitionConfidence.HIGH
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
        val relativeMargin = second?.let { margin.toFloat() / best.finalConfidence.coerceAtLeast(1) } ?: 1f
        val clearMargin = margin >= ScannerDecisionPolicy.MIN_ACCEPTED_MARGIN && relativeMargin >= ScannerDecisionPolicy.MIN_ACCEPTED_RELATIVE_MARGIN
        fun result(decision: ScannerDecision, reason: String) = ScannerDecisionResult(
            decision = decision,
            bestScore = best.finalConfidence,
            secondScore = second,
            reason = reason,
            absoluteMargin = margin,
            relativeMargin = relativeMargin,
            contradictionCount = best.contradictions.size,
        )
        return when {
            best.contradictions.isNotEmpty() -> result(ScannerDecision.REJECTED, "independent_signal_contradiction")
            best.confidence in setOf(RecognitionConfidence.VERY_HIGH, RecognitionConfidence.HIGH) && clearMargin ->
                result(ScannerDecision.ACCEPTED, "hybrid_identity_with_clear_absolute_and_relative_margin")
            best.confidence in setOf(RecognitionConfidence.VERY_HIGH, RecognitionConfidence.HIGH, RecognitionConfidence.MEDIUM) ->
                result(ScannerDecision.AMBIGUOUS, if (!clearMargin) "reprint_or_candidate_margin_too_close" else "hybrid_review_required")
            else -> result(ScannerDecision.REJECTED, "hybrid_evidence_insufficient")
        }
    }

    private fun canonicalMetadata(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun canonicalName(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .lowercase(Locale.ROOT).replace("ß", "ss").filter(Char::isLetterOrDigit)

    private const val SOURCE_FALLBACK_LANGUAGE = "SOURCE_FALLBACK"

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
