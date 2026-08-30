package com.dominik.cardvault.ui

/** Final outcome of Modules 2–4. This is intentionally stricter than the UI candidate threshold. */
internal enum class ScannerDecision { ACCEPTED, AMBIGUOUS, REJECTED }

internal data class ScannerDecisionResult(
    val decision: ScannerDecision,
    val bestScore: Int = 0,
    val secondScore: Int? = null,
    val reason: String,
)

/**
 * Keeps false-positive protection in one testable place. The candidate is still shown for a
 * deliberate collector confirmation; ACCEPTED never means "silently add to Binder".
 */
internal object ScannerDecisionPolicy {
    const val MIN_CANDIDATE_SCORE = 65
    const val ACCEPTED_SCORE = 88
    const val MIN_ACCEPTED_MARGIN = 12

    fun decide(scoresDescending: List<Int>): ScannerDecisionResult {
        val best = scoresDescending.firstOrNull() ?: return ScannerDecisionResult(ScannerDecision.REJECTED, reason = "no_database_candidate")
        val second = scoresDescending.drop(1).firstOrNull()
        if (best < MIN_CANDIDATE_SCORE) return ScannerDecisionResult(ScannerDecision.REJECTED, best, second, "evidence_below_candidate_threshold")
        val margin = second?.let { best - it } ?: Int.MAX_VALUE
        return when {
            best >= ACCEPTED_SCORE && margin >= MIN_ACCEPTED_MARGIN -> ScannerDecisionResult(ScannerDecision.ACCEPTED, best, second, "strong_identity_and_candidate_margin")
            else -> ScannerDecisionResult(ScannerDecision.AMBIGUOUS, best, second, if (margin < MIN_ACCEPTED_MARGIN) "best_candidate_too_close" else "identity_not_strong_enough")
        }
    }
}
