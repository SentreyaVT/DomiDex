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
               c.language, c.finish, c.variant, e.quantity, e.condition,
               e.acquisitionPriceMinor, e.acquisitionCurrency, e.acquisitionDateEpochDay,
               e.notes,
               CASE WHEN LOWER(c.finish) = 'holo' THEN COALESCE(p.holoTrendValueMinor, p.marketValueMinor)
                    ELSE p.marketValueMinor END AS marketValueMinor,
               p.currency AS priceCurrency,
               p.effectiveAtEpochMillis AS priceUpdatedAtEpochMillis,
               e.externalProductSource, e.externalProductId,
               COALESCE(linked.id, c.id) AS priceCardId
        FROM collection_entries e
        JOIN catalog_cards c ON c.id = e.cardId
        LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource
             AND linked.externalProductId = e.externalProductId
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = COALESCE(linked.id, c.id)
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        ORDER BY e.updatedAtEpochMillis DESC
    """)
    fun observeCollection(): Flow<List<CollectionProjection>>

    @Query("SELECT * FROM raw_price_snapshots ORDER BY effectiveAtEpochMillis")
    fun observeRawPriceHistory(): Flow<List<RawPriceSnapshotEntity>>

    @Query("""
        SELECT c.externalProductId, c.name, c.setId,
               p.marketValueMinor, p.currency
        FROM catalog_cards c
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = c.id
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

    @Query("SELECT * FROM catalog_sync_state WHERE source = :source")
    fun observeSyncState(source: String): Flow<CatalogSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCatalogCards(cards: List<CatalogCardEntity>)

    @Upsert
    suspend fun upsertCatalogCards(cards: List<CatalogCardEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawPricesIgnoringDuplicates(prices: List<RawPriceSnapshotEntity>)

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

    @Query("DELETE FROM collection_entries WHERE id = :id")
    suspend fun deleteCollectionEntry(id: Long)

    @Query("SELECT value FROM app_metadata WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(value: AppMetadataEntity)

    @Transaction
    suspend fun updateEntry(id: Long, quantity: Int, condition: String, notes: String, acquisitionPriceMinor: Long?, acquisitionCurrency: String?, acquisitionDateEpochDay: Long?) {
        val existing = getCollectionEntry(id) ?: return
        updateCollectionEntry(existing.copy(quantity = quantity, condition = condition, notes = notes, acquisitionPriceMinor = acquisitionPriceMinor, acquisitionCurrency = acquisitionCurrency, acquisitionDateEpochDay = acquisitionDateEpochDay, updatedAtEpochMillis = System.currentTimeMillis()))
    }
}
