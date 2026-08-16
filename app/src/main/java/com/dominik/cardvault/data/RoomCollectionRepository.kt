package com.dominik.cardvault.data

import androidx.room.withTransaction
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToLong

class RoomCollectionRepository(private val database: CardVaultDatabase) : CollectionRepository {
    private val dao = database.cardVaultDao()
    override val catalogSyncStatus: Flow<CatalogSyncStatus> = dao.observeSyncState(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE).map { state ->
        state?.let { CatalogSyncStatus(it.status, it.lastSuccessAtEpochMillis, it.dataCreatedAt, it.catalogProductCount, it.priceRecordCount, it.errorMessage) }
            ?: CatalogSyncStatus("IDLE", null, null, 0, 0, null)
    }

    override val portfolio: Flow<PortfolioSummary> = combine(dao.observeCollection(), dao.observeRawPriceHistory()) { rows, prices ->
        val cards = rows.map { row ->
            val cardPrices = prices.filter { it.cardId == row.priceCardId && it.marketValueMinor != null }
            val first = cardPrices.firstOrNull()?.marketValueMinor
            val latest = row.marketValueMinor
            val change = if (first != null && latest != null && first != 0L) (latest - first) * 100.0 / first else 0.0
            CollectionCard(row.entryId, row.cardId, row.name, row.setName, row.collectorNumber,
                CardLanguage.fromStorage(row.language), row.finish, row.variant, row.condition, row.quantity,
                (latest ?: 0L) / 100.0, change, row.priceUpdatedAtEpochMillis, row.notes,
                row.acquisitionPriceMinor?.div(100.0), row.acquisitionDateEpochDay, accentFor(row.name))
                .copy(externalProductId = row.externalProductId)
        }
        PortfolioSummary(cards, buildHistory(rows, prices))
    }

    override suspend fun seedIfNeeded() = database.withTransaction {
        if (dao.metadata(SEED_KEY) != null) return@withTransaction
        val now = System.currentTimeMillis()
        val definitions = listOf(
            SeedCard("sv3-198", "Charizard ex", "sv3", "Obsidian Flames", "198/197", CardLanguage.ENGLISH, 1, "Nahezu neuwertig"),
            SeedCard("sv2a-025", "Pikachu", "sv2a", "Pokémon Card 151", "025/165", CardLanguage.JAPANESE, 2, "Nahezu neuwertig"),
            SeedCard("base-004", "Glurak", "base", "Basis", "4/102", CardLanguage.GERMAN, 1, "Sehr gut"),
            SeedCard("s12a-205", "Mew", "s12a", "VSTAR Universe", "205/172", CardLanguage.JAPANESE, 1, "Nahezu neuwertig"),
        )
        dao.insertCatalogCards(definitions.map { it.catalog(now) })
        dao.insertCollectionEntries(definitions.map { it.entry(now) })
        dao.putMetadata(AppMetadataEntity(SEED_KEY, "1"))
    }

    override suspend fun addManualCard(input: ManualCardInput): AddCardOutcome = database.withTransaction {
        val now = System.currentTimeMillis()
        val normalizedCondition = input.condition.trim()
        val normalizedFinish = input.finish.trim().ifBlank { "Normal" }
        val normalizedVariant = input.variant.trim().ifBlank { "Standard" }
        if (input.externalProductId != null) {
            val existing = dao.findLinkedEntry(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, input.externalProductId, normalizedCondition, normalizedFinish, normalizedVariant)
            if (existing != null) {
                dao.incrementQuantity(existing.id, input.quantity.coerceAtLeast(1), now)
                return@withTransaction AddCardOutcome.QUANTITY_INCREMENTED
            }
        }
        val cardId = "manual-${UUID.randomUUID()}"
        dao.insertCatalogCard(CatalogCardEntity(cardId, "POKEMON", "manual", input.setName.trim(), input.name.trim(), input.collectorNumber.trim(), input.language.name, normalizedFinish, normalizedVariant, now))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = cardId, quantity = input.quantity.coerceAtLeast(1), condition = normalizedCondition, acquisitionPriceMinor = input.acquisitionPriceEur?.times(100)?.roundToLong(), acquisitionCurrency = input.acquisitionPriceEur?.let { "EUR" }, acquisitionDateEpochDay = input.acquisitionDateEpochDay, notes = input.notes.trim(), frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, externalProductSource = input.externalProductId?.let { com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }, externalProductId = input.externalProductId))
        AddCardOutcome.CREATED
    }

    override suspend fun updateEntry(entryId: Long, update: CollectionEntryUpdate) = dao.updateEntry(entryId, update.quantity.coerceAtLeast(1), update.condition.trim(), update.notes.trim(), update.acquisitionPriceEur?.times(100)?.roundToLong(), update.acquisitionPriceEur?.let { "EUR" }, update.acquisitionDateEpochDay)
    override suspend fun deleteEntry(entryId: Long) = dao.deleteCollectionEntry(entryId)
    override suspend fun searchCatalog(query: String, limit: Int): List<CatalogSearchResult> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()
        return dao.searchCatalog(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, normalized, limit).map {
            CatalogSearchResult(it.externalProductId, it.name, it.setId, it.marketValueMinor?.div(100.0), it.currency)
        }
    }
    override suspend fun linkCatalogProduct(entryId: Long, productId: Long?) = dao.linkExternalProduct(entryId, productId?.let { com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }, productId, System.currentTimeMillis())

    private fun buildHistory(rows: List<CollectionProjection>, prices: List<RawPriceSnapshotEntity>): List<PortfolioPoint> =
        prices.map { it.effectiveAtEpochMillis }.distinct().sorted().map { timestamp ->
            val total = rows.sumOf { row -> prices.filter { it.cardId == row.priceCardId && it.effectiveAtEpochMillis <= timestamp }.maxByOrNull { it.effectiveAtEpochMillis }?.marketValueMinor?.times(row.quantity) ?: 0L }
            PortfolioPoint(java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("MMM")), total / 100.0)
        }

    private fun accentFor(name: String): Long = when {
        name.contains("Charizard", true) -> 0xFFF06445; name.contains("Pikachu", true) -> 0xFFFFC83D
        name.contains("Mew", true) -> 0xFFCE77B7; else -> 0xFF6C78A8
    }

    private data class SeedCard(val id: String, val name: String, val setId: String, val setName: String, val number: String, val language: CardLanguage, val quantity: Int, val condition: String) {
        fun catalog(now: Long) = CatalogCardEntity(id, "POKEMON", setId, setName, name, number, language.name, "Normal", "Standard", now)
        fun entry(now: Long) = CollectionEntryEntity(cardId = id, quantity = quantity, condition = condition, acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now)
    }

    companion object { private const val SEED_KEY = "initial_demo_seed_v1" }
}
