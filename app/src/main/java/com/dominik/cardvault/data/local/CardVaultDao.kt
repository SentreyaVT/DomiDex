package com.dominik.cardvault.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardVaultDao {
    @Query("""
        SELECT e.id AS entryId, c.id AS cardId, c.name, c.setName, c.collectorNumber,
               c.language, c.finish, c.variant, c.rarity, e.quantity, e.condition,
               e.acquisitionPriceMinor, e.acquisitionCurrency, e.acquisitionDateEpochDay,
               e.notes,
               CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                    ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END AS marketValueMinor,
               p.currency AS priceCurrency,
               CASE WHEN CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                              ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END IS NULL
                    THEN NULL ELSE p.effectiveAtEpochMillis END AS priceUpdatedAtEpochMillis,
               e.externalProductSource, e.externalProductId,
               COALESCE(linked.id, c.id) AS priceCardId, c.imageUrl, c.effectOverride
               , e.binderId
        FROM collection_entries e
        JOIN catalog_cards c ON c.id = e.cardId
        LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource
             AND linked.externalProductId = e.externalProductId
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = COALESCE(linked.id, c.id) AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD'
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        ORDER BY e.updatedAtEpochMillis DESC
    """)
    fun observeCollection(): Flow<List<CollectionProjection>>

    @Query("""
        SELECT b.id, b.name, b.heroAsset, COUNT(DISTINCT e.cardId) AS uniqueCards,
               COALESCE(SUM(e.quantity), 0) AS ownedCards,
               SUM(CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END * e.quantity) AS marketValueMinor,
               MAX(CASE WHEN CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END IS NULL THEN NULL ELSE p.effectiveAtEpochMillis END) AS lastPriceUpdatedAtEpochMillis
        FROM binders b LEFT JOIN collection_entries e ON e.binderId = b.id
        LEFT JOIN catalog_cards c ON c.id = e.cardId
        LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource AND linked.externalProductId = e.externalProductId
        LEFT JOIN raw_price_snapshots p ON p.id = (SELECT p2.id FROM raw_price_snapshots p2 WHERE p2.cardId = COALESCE(linked.id, c.id) AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD' ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1)
        GROUP BY b.id
        ORDER BY b.updatedAtEpochMillis DESC, b.name COLLATE NOCASE
    """)
    fun observeBinders(): Flow<List<BinderProjection>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBinder(binder: BinderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBinders(binders: List<BinderEntity>)

    @Upsert
    suspend fun upsertBinders(binders: List<BinderEntity>)

    @Query("SELECT id FROM binders WHERE setKey = :setKey LIMIT 1")
    suspend fun binderIdForSet(setKey: String): String?

    @Query("SELECT * FROM raw_price_snapshots ORDER BY effectiveAtEpochMillis")
    fun observeRawPriceHistory(): Flow<List<RawPriceSnapshotEntity>>

    /**
     * History stays tied to the exact Cardmarket product linked to this entry.
     * A catalog reference has entryId 0, where its catalog id is the safe fallback.
     */
    @Query("""
        SELECT p.effectiveAtEpochMillis,
               CASE WHEN :useHoloPrice THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                    ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END AS valueMinor,
               p.currency, p.source
        FROM raw_price_snapshots p
        WHERE p.cardId = COALESCE(
            (SELECT linked.id
             FROM collection_entries e
             JOIN catalog_cards c ON c.id = e.cardId
             LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource
                  AND linked.externalProductId = e.externalProductId
             WHERE e.id = :entryId
             LIMIT 1),
            :fallbackCardId
        )
          AND p.source = :source
          AND (CASE WHEN :useHoloPrice THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                    ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END) IS NOT NULL
        ORDER BY p.effectiveAtEpochMillis ASC, p.id ASC
    """)
    fun observeCardPriceHistory(
        entryId: Long,
        fallbackCardId: String,
        source: String,
        useHoloPrice: Boolean,
    ): Flow<List<CardPriceHistoryProjection>>

    @Query("""
        SELECT c.externalProductId, c.name, c.setId, c.setName,
               c.collectorNumber, c.language, c.finish, c.variant, c.imageUrl,
               CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                    ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END AS marketValueMinor, p.currency, c.rarity
        FROM catalog_cards c
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = c.id AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD'
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        WHERE c.externalSource = :source AND c.externalProductId IS NOT NULL
          AND c.name LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY CASE WHEN c.name = :query COLLATE NOCASE THEN 0
                      WHEN c.name LIKE :query || '%' COLLATE NOCASE THEN 1 ELSE 2 END,
                 c.name COLLATE NOCASE
        LIMIT :limit
    """)
    suspend fun searchCatalog(source: String, query: String, limit: Int): List<CatalogSearchProjection>

    @Query("""
        SELECT c.id AS cardId, c.name, c.setName, c.collectorNumber, c.language,
               c.finish, c.variant, c.imageUrl, c.externalProductId,
               CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                    ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END AS marketValueMinor,
               CASE WHEN CASE WHEN LOWER(c.finish) LIKE '%holo%' THEN COALESCE(p.holoTrendValueMinor, p.holoAverageValueMinor, p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor)
                              ELSE COALESCE(p.marketValueMinor, p.trendValueMinor, p.average30DayValueMinor, p.average7DayValueMinor, p.averageValueMinor, p.lowValueMinor) END IS NULL
                    THEN NULL ELSE p.effectiveAtEpochMillis END AS priceUpdatedAtEpochMillis
        FROM catalog_cards c
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = c.id AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD'
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        WHERE c.externalSource = :source AND c.setName = :setName
        ORDER BY CASE WHEN c.collectorNumber GLOB '[0-9]*' THEN CAST(c.collectorNumber AS INTEGER) ELSE 999999 END,
                 c.collectorNumber, c.name COLLATE NOCASE, c.finish, c.variant
    """)
    suspend fun catalogReferencesForSet(source: String, setName: String): List<CatalogReferenceProjection>

    @Query("""
        SELECT c.id AS cardId, c.name, c.setName, c.collectorNumber, c.language,
               c.finish, c.variant, c.imageUrl, c.externalProductId,
               NULL AS marketValueMinor, NULL AS priceUpdatedAtEpochMillis
        FROM catalog_cards c
        WHERE c.externalSource = 'TCGDEX_DE' AND c.setName = :setName
        ORDER BY CASE WHEN c.collectorNumber GLOB '[0-9]*' THEN CAST(c.collectorNumber AS INTEGER) ELSE 999999 END,
                 c.collectorNumber, c.name COLLATE NOCASE
    """)
    suspend fun germanMasterSetReferences(setName: String): List<CatalogReferenceProjection>

    @Query("SELECT COUNT(*) FROM catalog_cards WHERE externalSource = :source AND setName = :setName")
    suspend fun catalogReferenceCountForSet(source: String, setName: String): Int

    @Query("SELECT * FROM catalog_sync_state WHERE source = :source")
    fun observeSyncState(source: String): Flow<CatalogSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCatalogCards(cards: List<CatalogCardEntity>)

    @Upsert
    suspend fun upsertCatalogCards(cards: List<CatalogCardEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawPricesIgnoringDuplicates(prices: List<RawPriceSnapshotEntity>)

    /** Replaces a same-timestamp guide snapshot so newly available Cardmarket
     * fallback fields become visible without waiting for the next source date. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawPricesReplacing(prices: List<RawPriceSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncState(state: CatalogSyncStateEntity)

    @Query("SELECT * FROM catalog_sync_state WHERE source = :source")
    suspend fun syncState(source: String): CatalogSyncStateEntity?

    @Insert
    suspend fun insertCollectionEntries(entries: List<CollectionEntryEntity>): List<Long>

    @Insert
    suspend fun insertRawPrices(prices: List<RawPriceSnapshotEntity>)

    @Insert
    suspend fun insertCatalogCard(card: CatalogCardEntity)

    @Insert
    suspend fun insertCollectionEntry(entry: CollectionEntryEntity): Long

    @Update
    suspend fun updateCollectionEntry(entry: CollectionEntryEntity)

    @Query("SELECT * FROM collection_entries WHERE id = :id")
    suspend fun getCollectionEntry(id: Long): CollectionEntryEntity?

    @Query("SELECT * FROM collection_entries WHERE cardId = :cardId AND `condition` = :condition LIMIT 1")
    suspend fun findMatchingEntry(cardId: String, condition: String): CollectionEntryEntity?

    @Query("""SELECT e.* FROM collection_entries e JOIN catalog_cards c ON c.id = e.cardId
        WHERE e.externalProductSource = :source AND e.externalProductId = :productId
          AND e.`condition` = :condition AND c.finish = :finish AND c.variant = :variant LIMIT 1""")
    suspend fun findLinkedEntry(source: String, productId: Long, condition: String, finish: String, variant: String): CollectionEntryEntity?

    @Query("UPDATE collection_entries SET quantity = quantity + :amount, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun incrementQuantity(id: Long, amount: Int, updatedAt: Long)

    @Query("UPDATE collection_entries SET externalProductSource = :source, externalProductId = :productId, updatedAtEpochMillis = :updatedAt WHERE id = :entryId")
    suspend fun linkExternalProduct(entryId: Long, source: String?, productId: Long?, updatedAt: Long)

    @Query("UPDATE catalog_cards SET finish = :finish, variant = :variant, effectOverride = :effectOverride WHERE id = (SELECT cardId FROM collection_entries WHERE id = :entryId)")
    suspend fun updateCardAppearance(entryId: Long, finish: String, variant: String, effectOverride: String?)

    @Query("DELETE FROM collection_entries WHERE id = :id")
    suspend fun deleteCollectionEntry(id: Long)

    @Query("DELETE FROM collection_entries WHERE cardId IN (:cardIds)")
    suspend fun deleteCollectionEntriesForCardIds(cardIds: List<String>)

    @Query("DELETE FROM binders WHERE id IN (:binderIds) AND NOT EXISTS (SELECT 1 FROM collection_entries e WHERE e.binderId = binders.id)")
    suspend fun deleteEmptyBinders(binderIds: List<String>)

    @Query("DELETE FROM collection_entries WHERE binderId = :binderId")
    suspend fun deleteEntriesForBinder(binderId: String)

    @Query("DELETE FROM binders WHERE id = :binderId")
    suspend fun deleteBinderRow(binderId: String)

    /** Removes only generated reference binders; owned/user-created binders survive. */
    @Query("DELETE FROM binders WHERE id LIKE 'tcgdex-%' AND NOT EXISTS (SELECT 1 FROM collection_entries e WHERE e.binderId = binders.id)")
    suspend fun deleteGeneratedGermanReferenceBinders()

    @Query("SELECT value FROM app_metadata WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(value: AppMetadataEntity)

    @Transaction
    suspend fun updateEntry(id: Long, quantity: Int, condition: String, notes: String, acquisitionPriceMinor: Long?, acquisitionCurrency: String?, acquisitionDateEpochDay: Long?, finish: String, variant: String, effectOverride: String?) {
        val existing = getCollectionEntry(id) ?: return
        updateCollectionEntry(existing.copy(quantity = quantity, condition = condition, notes = notes, acquisitionPriceMinor = acquisitionPriceMinor, acquisitionCurrency = acquisitionCurrency, acquisitionDateEpochDay = acquisitionDateEpochDay, updatedAtEpochMillis = System.currentTimeMillis()))
        updateCardAppearance(id, finish, variant, effectOverride)
    }

    @Transaction
    suspend fun deleteBinderWithEntries(binderId: String) {
        deleteEntriesForBinder(binderId)
        deleteBinderRow(binderId)
    }
}
