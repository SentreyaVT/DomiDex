package com.dominik.cardvault.model

import kotlinx.coroutines.flow.Flow


data class CollectionCard(
    val entryId: Long, val id: String, val name: String, val set: String, val number: String,
    val language: CardLanguage, val finish: String, val variant: String, val condition: String,
    val quantity: Int, val currentValueEur: Double, val changePercent: Double,
    val priceUpdatedAtEpochMillis: Long?, val notes: String, val acquisitionPriceEur: Double?,
    val acquisitionDateEpochDay: Long?, val accent: Long,
    val binderId: String = "inbox",
    val externalProductId: Long? = null,
    val imageUrl: String? = null,
    /** Source-declared rarity; UNKNOWN is intentionally never guessed from artwork. */
    val rarity: String = "UNKNOWN",
    /** Explicit user display choice. Null keeps the source-derived visual effect. */
    val effectOverride: String? = null,
)

data class CatalogSearchResult(
    val productId: Long, val name: String, val expansionId: String,
    val trendPriceEur: Double?, val currency: String?,
    val visualSetName: String? = null, val collectorNumber: String? = null,
    val imageUrl: String? = null,
    val language: String? = null, val finish: String? = null, val variant: String? = null,
    val rarity: String? = null,
    /** False for a local visual-catalog match that has no verified Cardmarket product yet. */
    val hasMarketProduct: Boolean = true,
    /** Source-declared printed total; only populated by the visual catalog/scanner path. */
    val mainSetSize: Int? = null,
    /** Canonical source-backed set code when one is known. */
    val setCode: String? = null,
)

/** Metadata cache for a German master-set slot; it never represents possession. */
data class GermanMasterSetDefinition(val sourceId: String, val name: String, val logoUrl: String? = null)
data class GermanMasterSetCardDefinition(
    val sourceId: String,
    val name: String,
    val number: String,
    val imageUrl: String?,
    val finish: String = "Normal",
    val variant: String = "Standard",
    val rarity: String = "UNKNOWN",
)

enum class CardLanguage(val label: String) {
    GERMAN("Deutsch"), ENGLISH("Englisch"), JAPANESE("Japanisch"), CHINESE("Chinesisch");
    companion object { fun fromStorage(value: String) = entries.firstOrNull { it.name == value } ?: GERMAN }
}

data class PortfolioPoint(val label: String, val value: Double)
/** A source-backed price observation for one exact catalog/finish variant. */
data class CardPricePoint(
    val timestampEpochMillis: Long,
    val valueEur: Double,
    val currency: String,
    val source: String,
)
data class BinderSummary(val id: String, val name: String, val heroAsset: String?, val uniqueCards: Int, val ownedCards: Int, val marketValueEur: Double, val lastPriceUpdatedAtEpochMillis: Long?)
data class PortfolioSummary(val cards: List<CollectionCard>, val history: List<PortfolioPoint>) {
    val totalValue get() = cards.sumOf { it.currentValueEur * it.quantity }
    val totalCards get() = cards.sumOf { it.quantity }
    /** Unknown values are never treated as a zero-Euro market price. */
    val pricedCards get() = cards.filter { it.priceUpdatedAtEpochMillis != null }.sumOf { it.quantity }
    val unpricedCards get() = totalCards - pricedCards
    val hasPricedCards get() = pricedCards > 0
    val changePercent get() = if (history.size > 1 && history.first().value != 0.0) ((history.last().value - history.first().value) / history.first().value) * 100 else 0.0
    companion object { val Empty = PortfolioSummary(emptyList(), emptyList()) }
}

data class ManualCardInput(val name: String, val setName: String, val collectorNumber: String, val language: CardLanguage, val finish: String, val variant: String, val quantity: Int, val condition: String, val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?, val externalProductId: Long? = null, val imageUrl: String? = null, val rarity: String? = null, val effectOverride: String? = null)
data class CollectionEntryUpdate(val quantity: Int, val condition: String, val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?, val finish: String, val variant: String, val effectOverride: String?)
enum class AddCardOutcome { CREATED, QUANTITY_INCREMENTED }

interface CollectionRepository {
    val portfolio: Flow<PortfolioSummary>
    val binders: Flow<List<BinderSummary>>
    val catalogSyncStatus: Flow<CatalogSyncStatus>
    suspend fun seedIfNeeded()
    suspend fun addManualCard(input: ManualCardInput): AddCardOutcome
    suspend fun updateEntry(entryId: Long, update: CollectionEntryUpdate)
    suspend fun deleteEntry(entryId: Long)
    suspend fun deleteBinder(binderId: String)
    /** Source-backed, read-only master-set slots for an existing Binder. */
    suspend fun binderReferences(setName: String): List<CollectionCard>
    suspend fun binderReferenceCount(setName: String): Int
    /** Adds read-only German master-set metadata. It never creates collection entries. */
    suspend fun ensureGermanMasterSetBinders(sets: List<GermanMasterSetDefinition>)
    suspend fun cacheGermanMasterSetCards(set: GermanMasterSetDefinition, cards: List<GermanMasterSetCardDefinition>)
    suspend fun clearGeneratedGermanMasterSetBinders()
    suspend fun searchCatalog(query: String, limit: Int = 30): List<CatalogSearchResult>
    suspend fun linkCatalogProduct(entryId: Long, productId: Long?)
    /** Read-only Cardmarket snapshots for this exact linked product/finish. */
    fun priceHistory(card: CollectionCard): Flow<List<CardPricePoint>>
}

data class CatalogSyncStatus(val state: String, val lastSuccessAtEpochMillis: Long?, val dataCreatedAt: String?, val catalogProductCount: Int, val priceRecordCount: Int, val errorMessage: String?)

enum class GradingArea { CENTERING, CORNERS, EDGES, SURFACE }
data class GradingFinding(val area: GradingArea, val summary: String, val confidence: Float)
data class GradeRange(val minimum: Int, val maximum: Int, val confidence: Float)
data class GradingCosts(val feeEur: Double, val shippingEur: Double, val insuranceEur: Double) { val totalEur get() = feeEur + shippingEur + insuranceEur }
data class GradingPrecheck(val range: GradeRange, val findings: List<GradingFinding>, val rawValueEur: Double, val plausibleGradedValuesEur: Map<Int, Double>, val costs: GradingCosts)
