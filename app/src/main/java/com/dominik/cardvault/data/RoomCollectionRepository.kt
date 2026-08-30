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
    override val binders: Flow<List<BinderSummary>> = dao.observeBinders().map { rows -> rows.map { row ->
        BinderSummary(row.id, row.name, row.heroAsset, row.uniqueCards, row.ownedCards, (row.marketValueMinor ?: 0L) / 100.0, row.lastPriceUpdatedAtEpochMillis)
    } }

    override val portfolio: Flow<PortfolioSummary> = combine(dao.observeCollection(), dao.observeRawPriceHistory()) { rows, prices ->
        val cards = rows.map { row ->
            val cardPrices = prices.filter { it.cardId == row.priceCardId && it.source == com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }
            val first = cardPrices.asSequence().mapNotNull { it.trendValueFor(row.finish) }.firstOrNull()
            val latest = row.marketValueMinor
            val change = if (first != null && latest != null && first != 0L) (latest - first) * 100.0 / first else 0.0
            CollectionCard(row.entryId, row.cardId, row.name, row.setName, row.collectorNumber,
                CardLanguage.fromStorage(row.language), row.finish, row.variant, row.condition, row.quantity,
                (latest ?: 0L) / 100.0, change, row.priceUpdatedAtEpochMillis, row.notes,
                row.acquisitionPriceMinor?.div(100.0), row.acquisitionDateEpochDay, accentFor(row.name), imageUrl = row.imageUrl)
                .copy(binderId = row.binderId, externalProductId = row.externalProductId, rarity = row.rarity, effectOverride = row.effectOverride)
        }
        PortfolioSummary(cards, buildHistory(rows, prices))
    }

    override suspend fun seedIfNeeded() = database.withTransaction {
        removeAccidentalDemoCollectionOnce()
        // A catalog/reference set must never be silently treated as owned cards.
        // Binder contents are created only through an explicit manual or scan add.
    }

    /** Removes only the exact IDs previously inserted by the accidental demo seed. */
    private suspend fun removeAccidentalDemoCollectionOnce() {
        if (dao.metadata(DEMO_CLEANUP_KEY) != null) return
        val baseDemoIds = listOf("sv3-198", "sv2a-025", "base-004", "s12a-205")
        val masterDemoIds = WachsendesChaosMasterSet.cards.map { it.id }
        dao.deleteCollectionEntriesForCardIds(baseDemoIds + masterDemoIds)
        dao.deleteEmptyBinders(listOf("set-sv3", "set-sv2a", "set-base", "set-s12a", "set-me04"))
        dao.putMetadata(AppMetadataEntity(DEMO_CLEANUP_KEY, "completed"))
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
        val setKey = input.setName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "inbox" }
        val binderId = dao.binderIdForSet(setKey) ?: "set-$setKey"
        dao.insertBinder(BinderEntity(binderId, setKey, input.setName.trim().ifBlank { "Ohne Setzuordnung" }, createdAtEpochMillis = now, updatedAtEpochMillis = now))
        dao.insertCatalogCard(CatalogCardEntity(cardId, "POKEMON", setKey, input.setName.trim(), input.name.trim(), input.collectorNumber.trim(), input.language.name, normalizedFinish, normalizedVariant, now, imageUrl = input.imageUrl, rarity = input.rarity?.trim()?.ifBlank { "UNKNOWN" } ?: "UNKNOWN", effectOverride = input.effectOverride))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = cardId, quantity = input.quantity.coerceAtLeast(1), condition = normalizedCondition, acquisitionPriceMinor = input.acquisitionPriceEur?.times(100)?.roundToLong(), acquisitionCurrency = input.acquisitionPriceEur?.let { "EUR" }, acquisitionDateEpochDay = input.acquisitionDateEpochDay, notes = input.notes.trim(), frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, externalProductSource = input.externalProductId?.let { com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }, externalProductId = input.externalProductId, binderId = binderId))
        AddCardOutcome.CREATED
    }

    override suspend fun updateEntry(entryId: Long, update: CollectionEntryUpdate) = database.withTransaction {
        dao.updateEntry(entryId, update.quantity.coerceAtLeast(1), update.condition.trim(), update.notes.trim(), update.acquisitionPriceEur?.times(100)?.roundToLong(), update.acquisitionPriceEur?.let { "EUR" }, update.acquisitionDateEpochDay, update.finish.trim().ifBlank { "Normal" }, update.variant.trim().ifBlank { "Standard" }, update.effectOverride)
    }
    override suspend fun deleteEntry(entryId: Long) = dao.deleteCollectionEntry(entryId)
    override suspend fun deleteBinder(binderId: String) = database.withTransaction { dao.deleteBinderWithEntries(binderId) }
    override suspend fun binderReferences(setName: String): List<CollectionCard> =
        (dao.germanMasterSetReferences(setName).ifEmpty {
            dao.catalogReferencesForSet(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, setName)
        }).map { row ->
            // Cardmarket's compact download sometimes omits a structured finish.
            // Recognise only an explicitly labelled Holo/Reverse product name;
            // everything else remains a normal source slot rather than guessed foil.
            val sourceFinish = when {
                row.name.contains("reverse", ignoreCase = true) -> "Reverse Holo"
                row.name.contains("holo", ignoreCase = true) -> "Holo"
                else -> "Normal"
            }
            CollectionCard(
                entryId = 0L, id = row.cardId, name = row.name, set = row.setName,
                number = row.collectorNumber, language = CardLanguage.fromStorage(row.language),
                finish = sourceFinish, variant = row.variant, condition = "Nicht im Besitz",
                quantity = 0, currentValueEur = row.marketValueMinor?.div(100.0) ?: 0.0, changePercent = 0.0,
                priceUpdatedAtEpochMillis = row.priceUpdatedAtEpochMillis, notes = "", acquisitionPriceEur = null,
                acquisitionDateEpochDay = null, accent = accentFor(row.name),
                externalProductId = row.externalProductId, imageUrl = row.imageUrl,
            )
        }
    override suspend fun binderReferenceCount(setName: String): Int =
        dao.germanMasterSetReferences(setName).size.takeIf { it > 0 }
            ?: dao.catalogReferenceCountForSet(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, setName)

    override suspend fun ensureGermanMasterSetBinders(sets: List<GermanMasterSetDefinition>) = database.withTransaction {
        val now = System.currentTimeMillis()
        dao.upsertBinders(sets.map { set ->
            val safeId = set.sourceId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            BinderEntity("tcgdex-$safeId", "tcgdex-$safeId", set.name, heroAsset = set.logoUrl, createdAtEpochMillis = now, updatedAtEpochMillis = now)
        })
    }

    override suspend fun cacheGermanMasterSetCards(set: GermanMasterSetDefinition, cards: List<GermanMasterSetCardDefinition>) = database.withTransaction {
        val now = System.currentTimeMillis()
        dao.upsertCatalogCards(cards.map { card ->
            CatalogCardEntity(
                id = "tcgdex-${set.sourceId}-${card.sourceId}", tcg = "POKEMON", setId = set.sourceId,
                setName = set.name, name = card.name, collectorNumber = card.number,
                language = "GERMAN", finish = card.finish, variant = card.variant, createdAtEpochMillis = now,
                externalSource = "TCGDEX_DE", imageUrl = card.imageUrl, rarity = card.rarity,
            )
        })
    }

    override suspend fun clearGeneratedGermanMasterSetBinders() = database.withTransaction {
        dao.deleteGeneratedGermanReferenceBinders()
    }
    override suspend fun searchCatalog(query: String, limit: Int): List<CatalogSearchResult> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()
        return dao.searchCatalog(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, normalized, limit).map {
            CatalogSearchResult(
                productId = it.externalProductId,
                name = it.name,
                expansionId = it.setId,
                trendPriceEur = it.marketValueMinor?.div(100.0),
                currency = it.currency,
                visualSetName = it.setName,
                collectorNumber = it.collectorNumber,
                imageUrl = it.imageUrl,
                language = it.language,
                finish = it.finish,
                variant = it.variant,
                rarity = it.rarity,
            )
        }
    }
    override suspend fun linkCatalogProduct(entryId: Long, productId: Long?) = dao.linkExternalProduct(entryId, productId?.let { com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }, productId, System.currentTimeMillis())

    override fun priceHistory(card: CollectionCard): Flow<List<CardPricePoint>> {
        val usesHoloPrice = card.finish.contains("holo", ignoreCase = true)
        return dao.observeCardPriceHistory(
            entryId = card.entryId,
            fallbackCardId = card.id,
            source = com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE,
            useHoloPrice = usesHoloPrice,
        ).map { rows ->
            rows.map { row ->
                CardPricePoint(
                    timestampEpochMillis = row.effectiveAtEpochMillis,
                    valueEur = row.valueMinor / 100.0,
                    currency = row.currency,
                    source = row.source,
                )
            }
        }
    }

    private fun buildHistory(rows: List<CollectionProjection>, prices: List<RawPriceSnapshotEntity>): List<PortfolioPoint> =
        prices.map { it.effectiveAtEpochMillis }.distinct().sorted().map { timestamp ->
            val total = rows.sumOf { row -> prices.filter { it.cardId == row.priceCardId && it.source == com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE && it.effectiveAtEpochMillis <= timestamp }.maxByOrNull { it.effectiveAtEpochMillis }?.trendValueFor(row.finish)?.times(row.quantity) ?: 0L }
            PortfolioPoint(java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("MMM")), total / 100.0)
        }

    /** The whole portfolio follows the same exact Normal/Holo/Reverse trend as each card row. */
    private fun RawPriceSnapshotEntity.trendValueFor(finish: String): Long? =
        if (finish.contains("holo", ignoreCase = true)) holoTrendValueMinor ?: trendValueMinor else trendValueMinor

    private fun accentFor(name: String): Long = when {
        name.contains("Charizard", true) -> 0xFFF06445; name.contains("Pikachu", true) -> 0xFFFFC83D
        name.contains("Mew", true) -> 0xFFCE77B7; else -> 0xFF6C78A8
    }

    private data class SeedCard(val id: String, val name: String, val setId: String, val setName: String, val number: String, val language: CardLanguage, val quantity: Int, val condition: String, val finish: String = "Normal", val variant: String = "Standard", val imageUrl: String? = null) {
        fun catalog(now: Long) = CatalogCardEntity(id, "POKEMON", setId, setName, name, number, language.name, finish, variant, now, imageUrl = imageUrl)
        fun entry(now: Long) = CollectionEntryEntity(cardId = id, quantity = quantity, condition = condition, acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, binderId = "set-" + setId.lowercase().replace(' ', '-'))
    }

    companion object {
        private const val DEMO_CLEANUP_KEY = "remove_accidental_demo_collection_v1"
    }
}
