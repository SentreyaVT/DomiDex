package com.dominik.cardvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The selected manual analysis, not a queued worker or an account/session identity. */
internal data class ManualScannerAnalysisContext(
    val analysis: CardTextAnalysis?,
    val queueReviewMode: Boolean,
    val selectedScanId: String?,
    val filePath: String?,
)

internal data class ManualScannerAnalysisCallbacks(
    val inactive: () -> Unit,
    val invalidFrame: (CardTextAnalysis) -> Unit,
    val started: (CardTextAnalysis) -> Unit,
    val resolved: (List<ScanCandidate>) -> Unit,
    val artworkLoading: (Boolean) -> Unit,
    val artwork: (Map<String, ArtworkVerification>, List<ScanCandidate>) -> Unit,
    val decision: (ScanCandidateResolution?, ScannerDecisionResult) -> Unit,
    val accepted: (CardTextAnalysis, ScanCandidateResolution, ScannerDecisionResult) -> Unit,
    val rejected: (CardTextAnalysis) -> Unit,
)

/** Actual ScannerScreen orchestration; dependencies are injectable without camera, photos or network. */
@Composable
internal fun ManualScannerAnalysisEffect(
    context: ManualScannerAnalysisContext,
    currentContext: () -> ManualScannerAnalysisContext,
    resolve: suspend (CardTextAnalysis) -> ScanCandidateResolution,
    verifyArtwork: suspend (CardTextAnalysis, List<ScanCandidate>) -> Map<String, ArtworkVerification>,
    callbacks: ManualScannerAnalysisCallbacks,
) {
    LaunchedEffect(context) {
        val generationJob = checkNotNull(coroutineContext[Job])
        // Read the real UI fields before each publication, not merely the last recomposition.
        // The Job also invalidates an old observed A -> B -> A generation and disposal callbacks.
        fun isCurrent() = generationJob.isActive && currentContext() == context
        if (!isCurrent()) return@LaunchedEffect
        val current = context.analysis
        if (current == null || context.queueReviewMode) {
            // Only the new current context clears stale progress. Old cancelled finally
            // blocks must never clear loading belonging to a subsequent active analysis.
            callbacks.inactive()
            return@LaunchedEffect
        }
        if (!current.manualIdentityConfirmed && (!current.imageQuality.cardDetected || current.imageQuality.reason.isNotBlank())) {
            callbacks.artworkLoading(false)
            callbacks.invalidFrame(current)
            return@LaunchedEffect
        }
        callbacks.started(current)
        val resolution = try { resolve(current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { null }
        if (!isCurrent()) return@LaunchedEffect
        val ranked = resolution?.candidates.orEmpty()
        callbacks.resolved(ranked)
        if (current.normalizedCard != null && ranked.isNotEmpty()) {
            val visualCandidates = ranked.filter { !it.card.imageUrl.isNullOrBlank() }
                .take(ScanQueuePolicy.MAX_VISUAL_CANDIDATES)
            if (visualCandidates.isNotEmpty() && isCurrent()) {
                callbacks.artworkLoading(true)
                // Optional artwork belongs to this manual analysis, never the whole screen.
                // It does not delay applying ACCEPTED below. That application intentionally
                // clears analysis; late artwork from the ended analysis is then discarded.
                launch {
                    if (!isCurrent()) return@launch
                    val verified = try { verifyArtwork(current, visualCandidates) }
                        finally { if (isCurrent()) callbacks.artworkLoading(false) }
                    if (isCurrent()) callbacks.artwork(verified, ranked)
                }
            }
        }
        val decision = constrainManualWholeImageDecision(current,
            resolution?.decision ?: ScannerDecisionResult(ScannerDecision.REJECTED, reason = "catalog_lookup_unavailable"))
        if (isCurrent()) callbacks.decision(resolution, decision)
        if (decision.decision == ScannerDecision.ACCEPTED && ranked.isNotEmpty() && isCurrent()) {
            callbacks.accepted(current, checkNotNull(resolution), decision)
        }
        if (decision.decision == ScannerDecision.REJECTED && isCurrent()) callbacks.rejected(current)
    }
}
