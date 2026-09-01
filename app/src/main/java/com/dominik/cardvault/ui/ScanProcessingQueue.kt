package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import java.io.File
import java.util.UUID

internal object ScanQueuePolicy {
    // OCR, perspective correction and artwork verification are intentionally serialized.
    // CameraX keeps the preview independent and the queue remains responsive without doubling
    // native bitmap/OpenCV pressure when two captures arrive close together.
    const val MAX_PARALLEL_WORKERS = 1
    const val MAX_VISUAL_CANDIDATES = 4
}

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

internal data class ScanCandidateResolution(
    val candidates: List<ScanCandidate>,
    val decision: ScannerDecisionResult,
)

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
        if (!analysis.manualIdentityConfirmed && (!analysis.imageQuality.cardDetected || analysis.imageQuality.reason.isNotBlank())) {
            return ScanCandidateResolution(emptyList(), ScannerDecisionResult(ScannerDecision.REJECTED, reason = analysis.imageQuality.reason.ifBlank { "card_frame_missing" }))
        }
        val number = analysis.collectorNumber ?: return ScanCandidateResolution(emptyList(), ScannerDecisionResult(ScannerDecision.REJECTED, reason = "collector_number_missing"))
        val exactSetMatches = (analysis.manualSetSourceId ?: analysis.setIdentifier)
            ?.let { setCode -> visualCatalog.findExact(setCode, number) }
            ?.filter { visualCard -> printedDenominatorMatches(number, visualCard.set.printedTotal) }
            .orEmpty()
        // A foil reflection can turn one letter of an otherwise valid set code
        // into another source code. An empty exact lookup must not dead-end the
        // card: the printed denominator still provides a safe all-set fallback.
        val visualMatches = exactSetMatches.ifEmpty {
            visualCatalog.findByPrintedTotalAndCollectorNumber(number)
        }
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
            val setCards = cardsBySet.getOrPut(visualCard.set.id) {
                runCatching { visualCatalog.cards(visualCard.set) }.getOrDefault(listOf(visualCard))
            }
            val expansionId = if (expansionBySet.containsKey(visualCard.set.id)) {
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
            val searchName = visualCard.marketSearchName ?: visualCard.name
            val marketCandidates = candidatesBySearch.getOrPut(searchName) {
                repository.searchCatalog(searchName, 500)
            }
            val verifiedProduct = visualCardmarketProducts(
                card = visualCard,
                cardsInSet = setCards,
                candidates = marketCandidates,
                resolvedExpansionId = expansionId,
            ).firstOrNull()
            ScanCandidate(
                card = scannerCatalogResult(visualCard, preparedCandidate.printing, verifiedProduct),
                score = preparedCandidate.score,
            )
        }
        return ScanCandidateResolution(candidates, ScannerDecisionPolicy.decide(candidates.map(ScanCandidate::score)))
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
