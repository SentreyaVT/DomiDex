package com.dominik.cardvault.model

import kotlinx.coroutines.flow.Flow

data class CollectionCard(
    val entryId: Long, val id: String, val name: String, val set: String, val number: String,
    val language: CardLanguage, val finish: String, val variant: String, val condition: String,
    val quantity: Int, val currentValueEur: Double, val changePercent: Double,
    val priceUpdatedAtEpochMillis: Long?, val notes: String, val acquisitionPriceEur: Double?,
    val acquisitionDateEpochDay: Long?, val accent: Long,
)

enum class CardLanguage(val label: String) {
    GERMAN("Deutsch"), ENGLISH("Englisch"), JAPANESE("Japanisch"), CHINESE("Chinesisch");
    companion object { fun fromStorage(value: String) = entries.firstOrNull { it.name == value } ?: GERMAN }
}

data class PortfolioPoint(val label: String, val value: Double)
data class PortfolioSummary(val cards: List<CollectionCard>, val history: List<PortfolioPoint>) {
    val totalValue get() = cards.sumOf { it.currentValueEur * it.quantity }
    val totalCards get() = cards.sumOf { it.quantity }
    val changePercent get() = if (history.size > 1 && history.first().value != 0.0) ((history.last().value - history.first().value) / history.first().value) * 100 else 0.0
    companion object { val Empty = PortfolioSummary(emptyList(), emptyList()) }
}

data class ManualCardInput(val name: String, val setName: String, val collectorNumber: String, val language: CardLanguage, val finish: String, val variant: String, val quantity: Int, val condition: String, val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?)
data class CollectionEntryUpdate(val quantity: Int, val condition: String, val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?)

interface CollectionRepository {
    val portfolio: Flow<PortfolioSummary>
    val catalogSyncStatus: Flow<CatalogSyncStatus>
    suspend fun seedIfNeeded()
    suspend fun addManualCard(input: ManualCardInput)
    suspend fun updateEntry(entryId: Long, update: CollectionEntryUpdate)
    suspend fun deleteEntry(entryId: Long)
}

data class CatalogSyncStatus(val state: String, val lastSuccessAtEpochMillis: Long?, val dataCreatedAt: String?, val catalogProductCount: Int, val priceRecordCount: Int, val errorMessage: String?)

enum class GradingArea { CENTERING, CORNERS, EDGES, SURFACE }
data class GradingFinding(val area: GradingArea, val summary: String, val confidence: Float)
data class GradeRange(val minimum: Int, val maximum: Int, val confidence: Float)
data class GradingCosts(val feeEur: Double, val shippingEur: Double, val insuranceEur: Double) { val totalEur get() = feeEur + shippingEur + insuranceEur }
data class GradingPrecheck(val range: GradeRange, val findings: List<GradingFinding>, val rawValueEur: Double, val plausibleGradedValuesEur: Map<Int, Double>, val costs: GradingCosts)
