package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.collectorIdentityForSourceLookup
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Failure boundary for the complete scanner worker. Cancellation retains structured-concurrency
 * semantics; every other failure is converted to the caller's terminal FAILED transition.
 */
internal suspend inline fun runScanWorkerAnalysis(
    crossinline onFailure: (Throwable) -> Unit,
    crossinline analysis: suspend () -> Unit,
) {
    try {
        analysis()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onFailure(failure)
    }
}

internal object ScanQueuePolicy {
    // OCR, perspective correction and artwork verification are intentionally serialized.
    // CameraX keeps the preview independent and the queue remains responsive without doubling
    // native bitmap/OpenCV pressure when two captures arrive close together.
    const val MAX_PARALLEL_WORKERS = 1
    const val MAX_VISUAL_CANDIDATES = 4
    /** Backpressure bounds active work and full-resolution files without discarding pending review. */
    const val MAX_AUTOMATIC_IN_FLIGHT = 4
    const val MAX_TOTAL_BACKLOG = 6

    fun allowsAutomaticCapture(
        scans: List<QueuedCardScan>,
        capturePending: Boolean,
        protectedScanIds: Set<String> = emptySet(),
    ): Boolean =
        !capturePending &&
            scans.count { !it.stage.isTerminal } < MAX_AUTOMATIC_IN_FLIGHT &&
            (scans.size < MAX_TOTAL_BACKLOG || automaticEvictionCandidate(scans, protectedScanIds) != null)

    /** Automatic intake may reclaim only a resolved SAVED item, never pending review or active work. */
    fun automaticEvictionCandidate(
        scans: List<QueuedCardScan>,
        protectedScanIds: Set<String> = emptySet(),
    ): QueuedCardScan? = scans
        .filter { it.id !in protectedScanIds && it.stage == ScanPipelineStage.SAVED }
        .minByOrNull(QueuedCardScan::capturedAtMillis)

    /** Manual intake may reclaim a resolved item or pending automatic capture, never pending review. */
    fun evictionCandidateForManualCapture(
        scans: List<QueuedCardScan>,
        protectedScanId: String? = null,
    ): QueuedCardScan? = scans
        .filter { it.id != protectedScanId && it.stage == ScanPipelineStage.SAVED }
        .minByOrNull(QueuedCardScan::capturedAtMillis)
        ?: scans
            .filter {
                it.id != protectedScanId && it.stage == ScanPipelineStage.CAPTURED &&
                    it.origin == ScanCaptureOrigin.AUTOMATIC
            }
            .minByOrNull(QueuedCardScan::capturedAtMillis)
}

internal enum class ScanCaptureOrigin { AUTOMATIC, MANUAL, GALLERY }

/**
 * One immutable, locally scoped work item for the rapid scanner.  The image stays in the app
 * cache and every later result is carried with this id, so callbacks from different cards cannot
 * be assigned to the currently visible card by accident.
 */
internal data class QueuedCardScan(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val evidenceFiles: List<File> = emptyList(),
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val qualityEligible: Boolean,
    val origin: ScanCaptureOrigin = ScanCaptureOrigin.AUTOMATIC,
    val stage: ScanPipelineStage = ScanPipelineStage.CAPTURED,
    val analysis: CardTextAnalysis? = null,
    val candidates: List<ScanCandidate> = emptyList(),
    val artwork: Map<String, ArtworkVerification> = emptyMap(),
    val decision: ScannerDecisionResult? = null,
    val stageDurationsMillis: Map<ScanPipelineStage, Long> = emptyMap(),
    val totalProcessingMillis: Long? = null,
    val message: String = ScanPipelineStage.CAPTURED.label,
)

/** Stages are intentionally explicit for the live bottom queue and debug diagnostics. */
internal enum class ScanPipelineStage(val label: String) {
    CAPTURED("Aufnahme gespeichert"),
    PREPROCESSING("Karte wird ausgerichtet …"),
    OCR("Name, Set und Nummer werden gelesen …"),
    SET_ANALYSIS("Set-Kennung wird geprüft …"),
    CANDIDATE_SEARCH("Katalog wird abgeglichen …"),
    ARTWORK_VERIFICATION("Artwork wird geprüft …"),
    VARIANT_ANALYSIS("Variante wird geprüft …"),
    PRICE_LOOKUP("Trendpreis wird zugeordnet …"),
    MATCHED("Treffer bereit"),
    AMBIGUOUS("Mehrere Treffer – bitte prüfen"),
    MANUAL_REVIEW("Manuelle Prüfung nötig"),
    FAILED("Analyse fehlgeschlagen"),
    SAVED("Zum Binder hinzugefügt"),
}

internal val ScanPipelineStage.isTerminal: Boolean
    get() = this in setOf(
        ScanPipelineStage.MATCHED,
        ScanPipelineStage.AMBIGUOUS,
        ScanPipelineStage.MANUAL_REVIEW,
        ScanPipelineStage.FAILED,
        ScanPipelineStage.SAVED,
    )

internal data class ScanCandidate(
    val card: CatalogSearchResult,
    val score: Int,
    val evidence: CandidateSignalScores? = null,
)

/** Physical print identity for scanner state; one Cardmarket product may expose several foils. */
internal fun CatalogSearchResult.scanIdentityKey(): String {
    val sourceKey = if (!sourceSetId.isNullOrBlank() && !sourceCardId.isNullOrBlank()) {
        "source:${sourceSetId.lowercase()}:${sourceCardId.lowercase()}"
    } else {
        "product:$productId"
    }
    return "$sourceKey|${finish.orEmpty().trim().lowercase()}|${variant.orEmpty().trim().lowercase()}"
}

/** Session duplicate identity: exact set + physical print + finish + variant, never card name. */
internal fun CatalogSearchResult.scanSessionIdentityKey(): String {
    val setKey = sourceSetId?.takeIf(String::isNotBlank) ?: "market-$expansionId"
    val collectorKey = collectorNumber?.takeIf(String::isNotBlank) ?: "unknown"
    val printKey = sourceCardId?.takeIf(String::isNotBlank) ?: "product-$productId"
    fun canonical(value: String?) = value.orEmpty().trim().lowercase().ifBlank { "unknown" }
    return "set:${canonical(setKey)}|collector:${canonical(collectorKey)}|print:${canonical(printKey)}|finish:${canonical(finish)}|variant:${canonical(variant)}"
}

internal data class ScanCandidateResolution(
    val candidates: List<ScanCandidate>,
    val decision: ScannerDecisionResult,
)

/** Manual identity or missing card geometry always leaves a candidate for explicit selection. */
internal fun constrainManualWholeImageDecision(
    analysis: CardTextAnalysis,
    decision: ScannerDecisionResult,
): ScannerDecisionResult = when {
    decision.decision != ScannerDecision.ACCEPTED -> decision
    analysis.manualIdentityConfirmed && !analysis.manualSetSourceId.isNullOrBlank() -> decision.copy(
        decision = ScannerDecision.AMBIGUOUS,
        reason = "manual_set_number_requires_selection",
    )
    !analysis.imageQuality.cardDetected && analysis.imageQuality.reason == MANUAL_WHOLE_IMAGE_FALLBACK_REASON -> decision.copy(
        decision = ScannerDecision.AMBIGUOUS,
        reason = "manual_whole_image_requires_review",
    )
    analysis.imageQuality.isManualRecognitionFallback -> decision.copy(
        decision = ScannerDecision.AMBIGUOUS,
        reason = "soft_card_image_requires_review",
    )
    else -> decision
}

/** Explicit source selection never expands into another set, even when the exact lookup is empty. */
internal suspend fun lookupScannerVisualMatches(
    analysis: CardTextAnalysis,
    findExact: suspend (String, String) -> List<PokemonVisualCard>,
    findByPrintedTotal: suspend (String) -> List<PokemonVisualCard>,
): List<PokemonVisualCard> {
    val number = analysis.collectorNumber ?: return emptyList()
    val selectedSourceId = analysis.manualSetSourceId
    val exact = (selectedSourceId ?: analysis.setIdentifier)
        ?.let { findExact(it, number) }
        ?.filter { printedDenominatorMatches(number, it.set.printedTotal) }
        .orEmpty()
    if (selectedSourceId != null) {
        if (selectedSourceId.isBlank()) return emptyList()
        val selectedSet = PokemonSetRegistry.normalize(selectedSourceId)
        val selectedNumber = collectorIdentityForSourceLookup(number, selectedSourceId)
        return exact.filter { card ->
            PokemonSetRegistry.normalize(card.set.id) == selectedSet &&
                collectorIdentityForSourceLookup(card.number, card.set.id) == selectedNumber
        }
    }
    // Automatic OCR retains its denominator fallback when glare corrupts the printed set code.
    return exact.ifEmpty { findByPrintedTotal(number) }
}

/**
 * Module 3 candidate reduction.  The visual catalogue supplies the authoritative German card
 * identity. Cardmarket's compact export has no collector number; pricing therefore reuses the
 * same source-verified expansion/name/print-order bridge as manual set selection. An absent
 * verified product stays unpriced and can never turn into a guessed match.
 */
internal object CardScanCandidateResolver {
    suspend fun resolve(
        analysis: CardTextAnalysis,
        repository: CollectionRepository,
        visualCatalog: PokemonVisualCatalog,
    ): ScanCandidateResolution {
        val manualWholeImageFallback = analysis.imageQuality.isManualRecognitionFallback
        if (!analysis.manualIdentityConfirmed && !manualWholeImageFallback && (!analysis.imageQuality.cardDetected || analysis.imageQuality.reason.isNotBlank())) {
            return ScanCandidateResolution(emptyList(), ScannerDecisionResult(ScannerDecision.REJECTED, reason = analysis.imageQuality.reason.ifBlank { "card_frame_missing" }))
        }
        if (analysis.collectorNumber == null) return ScanCandidateResolution(emptyList(), ScannerDecisionResult(ScannerDecision.REJECTED, reason = "collector_number_missing"))
        val visualMatches = lookupScannerVisualMatches(
            analysis, visualCatalog::findExact, visualCatalog::findByPrintedTotalAndCollectorNumber,
        )
        data class PreparedCandidate(
            val visualCard: PokemonVisualCard,
            val printing: PokemonPrintVariant,
            val unpriced: CatalogSearchResult,
            val score: Int,
        )

        // Reduce on authoritative source identity first. Cardmarket lookup is intentionally
        // deferred until only a handful of plausible physical printings remain.
        val prepared = visualMatches
            .flatMap { visualCard -> visualCard.printVariants.map { printing -> visualCard to printing } }
            .distinctBy { (visualCard, printing) ->
                "${visualCard.set.id.lowercase()}|${visualCard.id.lowercase()}|${canonicalMetadata(printing.finish)}|${canonicalMetadata(printing.variant)}"
            }
            .map { (visualCard, printing) ->
                val unpriced = scannerCatalogResult(visualCard, printing, marketProduct = null)
                PreparedCandidate(visualCard, printing, unpriced, CardRecognitionRanker.score(analysis, unpriced))
            }
            .filter { it.score >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE }
            .sortedByDescending(PreparedCandidate::score)
            .take(8)

        val cardsBySet = mutableMapOf<String, List<PokemonVisualCard>>()
        val expansionBySet = mutableMapOf<String, String?>()
        val candidatesBySearch = mutableMapOf<String, List<CatalogSearchResult>>()
        val candidates = prepared.map { preparedCandidate ->
            val visualCard = preparedCandidate.visualCard
            val searchName = visualCard.marketSearchName ?: visualCard.name
            val marketCandidates = candidatesBySearch.getOrPut(searchName) {
                repository.searchCatalog(searchName, 500)
            }
            // The expansion bridge cannot produce a product when this exact card name has
            // no compatible candidate. Keep the existing unpriced result without searching
            // every other set name solely to establish an expansion that cannot be used.
            if (marketCandidates.none { cardmarketNameMatches(it.name, searchName) }) {
                return@map ScanCandidate(preparedCandidate.unpriced, preparedCandidate.score)
            }
            val hasDeclaredCardmarketProductIds = visualCard.printVariants.any { it.cardmarketProductId != null }
            // Source-declared product ids are resolved before expansion/name-order bridging in
            // visualCardmarketProducts. In that branch cardsInSet and resolvedExpansionId are not
            // read, so loading every set name here would be pure work with no effect on evidence.
            val setCards = if (hasDeclaredCardmarketProductIds) {
                listOf(visualCard)
            } else {
                cardsBySet.getOrPut(visualCard.set.id) {
                    runCatching { visualCatalog.cards(visualCard.set) }.getOrDefault(listOf(visualCard))
                }
            }
            val expansionId = if (hasDeclaredCardmarketProductIds) {
                null
            } else if (expansionBySet.containsKey(visualCard.set.id)) {
                expansionBySet[visualCard.set.id]
            } else {
                val resolved = visualCard.set.cardmarketExpansionId ?: run {
                    val names = setCards.map { it.marketSearchName ?: it.name }.distinct()
                    val marketCandidates = names.associateWith { name ->
                        candidatesBySearch.getOrPut(name) { repository.searchCatalog(name, 500) }
                    }
                    resolveCardmarketExpansionId(setCards, marketCandidates)
                }
                expansionBySet[visualCard.set.id] = resolved
                resolved
            }
            val verifiedProducts = visualCardmarketProducts(
                card = visualCard,
                cardsInSet = setCards,
                candidates = marketCandidates,
                resolvedExpansionId = expansionId,
            )
            val verifiedProduct = visualCardmarketProductForPrinting(preparedCandidate.printing, verifiedProducts)
            ScanCandidate(
                card = scannerCatalogResult(visualCard, preparedCandidate.printing, verifiedProduct),
                score = preparedCandidate.score,
            )
        }
        val decision = constrainManualWholeImageDecision(
            analysis,
            ScannerDecisionPolicy.decide(candidates.map(ScanCandidate::score)),
        )
        return ScanCandidateResolution(candidates, decision)
    }

    private fun canonicalMetadata(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
}

/** A physically printed denominator may reduce candidates, but may never be averaged away. */
internal fun printedDenominatorMatches(collectorNumber: String, candidatePrintedTotal: Int): Boolean {
    val denominator = printedCollectorDenominator(collectorNumber) ?: return true
    return candidatePrintedTotal > 0 && candidatePrintedTotal == denominator
}

internal fun printedCollectorDenominator(collectorNumber: String): Int? =
    Regex("^\\s*\\d{1,4}\\s*/\\s*(\\d{1,4})\\s*$")
        .matchEntire(collectorNumber)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

/** A pure transition guard that keeps a queued item truthful even when a worker fails. */
internal fun ScanPipelineStage.canTransitionTo(next: ScanPipelineStage): Boolean = when (this) {
    ScanPipelineStage.CAPTURED -> next in setOf(ScanPipelineStage.PREPROCESSING, ScanPipelineStage.FAILED)
    ScanPipelineStage.PREPROCESSING -> next in setOf(ScanPipelineStage.OCR, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.OCR -> next in setOf(ScanPipelineStage.SET_ANALYSIS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.SET_ANALYSIS -> next in setOf(ScanPipelineStage.CANDIDATE_SEARCH, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.CANDIDATE_SEARCH -> next in setOf(ScanPipelineStage.ARTWORK_VERIFICATION, ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.ARTWORK_VERIFICATION -> next in setOf(ScanPipelineStage.VARIANT_ANALYSIS, ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.VARIANT_ANALYSIS -> next in setOf(ScanPipelineStage.PRICE_LOOKUP, ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.PRICE_LOOKUP -> next in setOf(ScanPipelineStage.MATCHED, ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED)
    ScanPipelineStage.MATCHED -> next == ScanPipelineStage.SAVED
    ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED, ScanPipelineStage.SAVED -> false
}
