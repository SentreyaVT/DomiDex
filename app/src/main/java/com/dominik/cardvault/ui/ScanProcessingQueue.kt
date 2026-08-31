package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import java.io.File
import java.text.Normalizer
import java.util.UUID

internal object ScanQueuePolicy {
    const val MAX_PARALLEL_WORKERS = 2
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
    val artwork: Map<Long, ArtworkVerification> = emptyMap(),
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

internal data class ScanCandidateResolution(
    val candidates: List<ScanCandidate>,
    val decision: ScannerDecisionResult,
)

/**
 * Module 3 candidate reduction.  The visual catalogue supplies the authoritative German card
 * identity; Cardmarket products are used only when their own product metadata confirms the same
 * printed number and finish.  An absent price can therefore never turn into a guessed match.
 */
internal object CardScanCandidateResolver {
    suspend fun resolve(
        analysis: CardTextAnalysis,
        repository: CollectionRepository,
        visualCatalog: PokemonVisualCatalog,
    ): ScanCandidateResolution {
        if (!analysis.imageQuality.cardDetected || analysis.imageQuality.reason.isNotBlank()) {
            return ScanCandidateResolution(emptyList(), ScannerDecisionResult(ScannerDecision.REJECTED, reason = analysis.imageQuality.reason.ifBlank { "card_frame_missing" }))
        }
        val number = analysis.collectorNumber ?: return ScanCandidateResolution(emptyList(), ScannerDecisionResult(ScannerDecision.REJECTED, reason = "collector_number_missing"))
        val found = linkedMapOf<Long, CatalogSearchResult>()
        val exactSetMatches = analysis.setIdentifier
            ?.let { setCode -> visualCatalog.findExact(setCode, number) }
            .orEmpty()
        // A foil reflection can turn one letter of an otherwise valid set code
        // into another source code. An empty exact lookup must not dead-end the
        // card: the printed denominator still provides a safe all-set fallback.
        val visualMatches = exactSetMatches.ifEmpty {
            visualCatalog.findByPrintedTotalAndCollectorNumber(number)
        }
        visualMatches.forEach { visualCard ->
            val products = repository.searchCatalog(visualCard.name, 40)
            visualCard.printVariants.forEach { printing ->
                val exactProducts = products.filter { product ->
                    // Cardmarket's product id is accepted only when the source-verified
                    // expansion bridge agrees. If that bridge is not available, a matching
                    // localised set name is still required; name + number alone is not safe.
                    val verifiedExpansion = visualCard.set.cardmarketExpansionId
                    val sameSet = verifiedExpansion?.let { it == product.expansionId }
                        ?: (product.visualSetName?.let { canonicalMetadata(it) == canonicalMetadata(visualCard.set.name) } == true)
                    product.collectorNumber?.isNotBlank() == true &&
                        sameSet &&
                        canonicalName(product.name) == canonicalName(visualCard.name) &&
                        canonicalNumber(product.collectorNumber) == canonicalNumber(visualCard.number) &&
                        (product.finish.isNullOrBlank() || canonicalMetadata(product.finish) == canonicalMetadata(printing.finish))
                }
                if (exactProducts.isNotEmpty()) {
                    exactProducts.forEach { product ->
                        found.putIfAbsent(product.productId, product.copy(
                            name = visualCard.name,
                            visualSetName = visualCard.set.name,
                            collectorNumber = visualCard.number,
                            imageUrl = visualCard.largeImageUrl,
                            language = if (visualCard.hasGermanLocalizedName) "GERMAN" else "SOURCE_FALLBACK",
                            finish = printing.finish,
                            variant = printing.variant,
                            rarity = visualCard.rarity,
                            mainSetSize = visualCard.set.printedTotal,
                            setCode = PokemonSetRegistry.metadata(visualCard.set).setCode,
                            marketSearchName = visualCard.marketSearchName,
                        ))
                    }
                } else {
                    // This is a real TCGdex identity, not a synthetic market-price record.
                    // It remains explicitly unpriced until a verified Cardmarket product exists.
                    val localId = -(("${visualCard.set.id}|${visualCard.id}|${visualCard.number}|${printing.id}".hashCode().toLong() and 0x7fff_ffffL) + 1L)
                    found.putIfAbsent(localId, CatalogSearchResult(
                        productId = localId,
                        name = visualCard.name,
                        expansionId = visualCard.set.id,
                        trendPriceEur = null,
                        currency = null,
                        visualSetName = visualCard.set.name,
                        collectorNumber = visualCard.number,
                        imageUrl = visualCard.largeImageUrl,
                        language = if (visualCard.hasGermanLocalizedName) "GERMAN" else "SOURCE_FALLBACK",
                        finish = printing.finish,
                        variant = printing.variant,
                        rarity = visualCard.rarity,
                        hasMarketProduct = false,
                        mainSetSize = visualCard.set.printedTotal,
                        setCode = PokemonSetRegistry.metadata(visualCard.set).setCode,
                        marketSearchName = visualCard.marketSearchName,
                    ))
                }
            }
        }
        val candidates = found.values
            .distinctBy { "${canonicalName(it.name)}|${it.visualSetName ?: it.expansionId}|${it.collectorNumber.orEmpty()}|${it.finish.orEmpty()}|${it.variant.orEmpty()}" }
            .map { ScanCandidate(it, CardRecognitionRanker.score(analysis, it)) }
            .filter { it.score >= ScannerDecisionPolicy.MIN_CANDIDATE_SCORE }
            .sortedByDescending { it.score }
            .take(8)
        return ScanCandidateResolution(candidates, ScannerDecisionPolicy.decide(candidates.map(ScanCandidate::score)))
    }

    private fun canonicalName(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .lowercase().replace("ß", "ss").filter(Char::isLetterOrDigit)
    private fun canonicalNumber(value: String) = value.substringBefore('/').filter(Char::isLetterOrDigit).trimStart('0').ifBlank { "0" }
    private fun canonicalMetadata(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
}

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
