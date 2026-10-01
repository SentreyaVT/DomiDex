package com.dominik.cardvault.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import com.dominik.cardvault.data.catalog.SAFE_COLLECTION_PRICE_LINK_SQL

@Dao
interface CardVaultDao {
    // Pokémon's regular Holo printing uses the product trend. The download's
    // holo field describes Reverse Holo and must not fall back to that trend.
    @Query("""
        SELECT e.id AS entryId, c.id AS cardId, c.name, c.setName, c.collectorNumber,
               c.language, COALESCE(e.finishOverride, c.finish) AS finish,
               COALESCE(e.variantOverride, c.variant) AS variant, c.rarity, e.quantity, e.condition,
               e.acquisitionPriceMinor, e.acquisitionCurrency, e.acquisitionDateEpochDay,
               e.notes,
               CASE WHEN LOWER(COALESCE(e.finishOverride, c.finish)) LIKE '%reverse%' THEN NULLIF(p.holoTrendValueMinor, 0)
                    ELSE p.trendValueMinor END AS marketValueMinor,
               p.currency AS priceCurrency,
               CASE WHEN CASE WHEN LOWER(COALESCE(e.finishOverride, c.finish)) LIKE '%reverse%' THEN NULLIF(p.holoTrendValueMinor, 0)
                              ELSE p.trendValueMinor END IS NULL
                    THEN NULL ELSE p.effectiveAtEpochMillis END AS priceUpdatedAtEpochMillis,
               e.externalProductSource, e.externalProductId,
               CASE WHEN """ + SAFE_COLLECTION_PRICE_LINK_SQL + """ THEN COALESCE(linked.id, c.id) ELSE '' END AS priceCardId,
               c.imageUrl, COALESCE(e.effectOverride, c.effectOverride) AS effectOverride
               , e.binderId, e.sourceSetId, e.sourceCardId
        FROM collection_entries e
        JOIN catalog_cards c ON c.id = e.cardId
        LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource
             AND linked.externalProductId = e.externalProductId
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = COALESCE(linked.id, c.id) AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD'
            AND """ + SAFE_COLLECTION_PRICE_LINK_SQL + """
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        WHERE e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
        ORDER BY e.updatedAtEpochMillis DESC
    """)
    fun observeCollection(ownerUid: String = GUEST_OWNER_UID): Flow<List<CollectionProjection>>

    @Query("""
        SELECT e.binderId AS id, MAX(c.setName) AS name, NULL AS heroAsset,
               COUNT(DISTINCT e.cardId) AS uniqueCards,
               COALESCE(SUM(e.quantity), 0) AS ownedCards,
               SUM(CASE WHEN LOWER(COALESCE(e.finishOverride, c.finish)) LIKE '%reverse%' THEN NULLIF(p.holoTrendValueMinor, 0) ELSE p.trendValueMinor END * e.quantity) AS marketValueMinor,
               MAX(CASE WHEN CASE WHEN LOWER(COALESCE(e.finishOverride, c.finish)) LIKE '%reverse%' THEN NULLIF(p.holoTrendValueMinor, 0) ELSE p.trendValueMinor END IS NULL THEN NULL ELSE p.effectiveAtEpochMillis END) AS lastPriceUpdatedAtEpochMillis
        FROM collection_entries e
        JOIN catalog_cards c ON c.id = e.cardId
        LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource AND linked.externalProductId = e.externalProductId
        LEFT JOIN raw_price_snapshots p ON p.id = (SELECT p2.id FROM raw_price_snapshots p2 WHERE p2.cardId = COALESCE(linked.id, c.id) AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD' AND """ + SAFE_COLLECTION_PRICE_LINK_SQL + """ ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1)
        WHERE e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
        GROUP BY e.binderId
        ORDER BY MAX(e.updatedAtEpochMillis) DESC, MAX(c.setName) COLLATE NOCASE
    """)
    fun observeBinders(ownerUid: String = GUEST_OWNER_UID): Flow<List<BinderProjection>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBinder(binder: BinderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBinders(binders: List<BinderEntity>)

    @Upsert
    suspend fun upsertBinders(binders: List<BinderEntity>)

    @Query("SELECT id FROM binders WHERE setKey = :setKey LIMIT 1")
    suspend fun binderIdForSet(setKey: String): String?

    @Query("SELECT * FROM private_binders WHERE ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL ORDER BY COALESCE(lastOpenedAtEpochMillis, 0) DESC, name COLLATE NOCASE, id")
    fun observePrivateBinders(ownerUid: String = GUEST_OWNER_UID): Flow<List<PrivateBinderEntity>>

    @Query("SELECT * FROM private_binders WHERE id = :binderId AND ownerUid = :ownerUid LIMIT 1")
    suspend fun privateBinder(binderId: String, ownerUid: String = GUEST_OWNER_UID): PrivateBinderEntity?

    /** Only this binder and its referenced cards must be acknowledged before publishing a link. */
    @Query("""
        SELECT CASE
          WHEN NOT EXISTS (
            SELECT 1 FROM private_binders b WHERE b.ownerUid = :ownerUid AND b.id = :binderId
              AND b.deletedAtEpochMillis IS NULL
          ) THEN -1
          WHEN EXISTS (
            SELECT 1 FROM private_binders b WHERE b.ownerUid = :ownerUid AND b.id = :binderId
              AND (b.syncState != 'CLEAN' OR b.remoteRevision <= 0)
          ) OR EXISTS (
            SELECT 1 FROM private_binder_sync_outbox o WHERE o.ownerUid = :ownerUid AND (
              (o.recordType = 'BINDER' AND o.recordId = :binderId) OR
              (o.recordType = 'PLACEMENT' AND EXISTS (
                SELECT 1 FROM private_binder_placements p WHERE p.ownerUid = :ownerUid
                  AND p.binderId = :binderId AND p.id = o.recordId
              ))
            )
          ) OR EXISTS (
            SELECT 1 FROM private_binder_placements p
            LEFT JOIN collection_entries e ON e.id = p.collectionEntryId AND e.ownerUid = :ownerUid
            WHERE p.ownerUid = :ownerUid AND p.binderId = :binderId AND (
              p.syncState != 'CLEAN' OR p.remoteRevision <= 0 OR
              (p.deletedAtEpochMillis IS NULL AND (
                e.id IS NULL OR e.deletedAtEpochMillis IS NOT NULL OR
                e.syncState != 'CLEAN' OR e.remoteRevision <= 0 OR EXISTS (
                  SELECT 1 FROM collection_sync_outbox o
                  WHERE o.ownerUid = :ownerUid AND o.syncId = e.syncId
                )
              ))
            )
          ) THEN 0
          ELSE 1
        END
    """)
    fun observePrivateBinderSharingSyncState(ownerUid: String, binderId: String): Flow<Int>

    @Insert
    suspend fun insertPrivateBinder(value: PrivateBinderEntity)

    @Upsert
    suspend fun upsertPrivateBinder(value: PrivateBinderEntity)

    @Update
    suspend fun updatePrivateBinder(value: PrivateBinderEntity)

    @Query("SELECT * FROM private_binders WHERE ownerUid = '__domidex_guest__' AND deletedAtEpochMillis IS NULL ORDER BY id")
    suspend fun activeGuestPrivateBinders(): List<PrivateBinderEntity>

    @Query("DELETE FROM private_binders WHERE ownerUid = '__domidex_guest__'")
    suspend fun deleteGuestPrivateBinders()

    @Query("SELECT * FROM private_binder_placements WHERE ownerUid = :ownerUid AND binderId = :binderId AND deletedAtEpochMillis IS NULL ORDER BY slotPosition, id")
    fun observePrivateBinderPlacements(binderId: String, ownerUid: String = GUEST_OWNER_UID): Flow<List<PrivateBinderPlacementEntity>>

    @Query("SELECT * FROM private_binder_placements WHERE ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL ORDER BY binderId, slotPosition, id")
    fun observeAllPrivateBinderPlacements(ownerUid: String = GUEST_OWNER_UID): Flow<List<PrivateBinderPlacementEntity>>

    @Query("SELECT * FROM private_binder_placements WHERE id = :placementId AND ownerUid = :ownerUid LIMIT 1")
    suspend fun privateBinderPlacement(placementId: String, ownerUid: String = GUEST_OWNER_UID): PrivateBinderPlacementEntity?

    @Query("SELECT * FROM private_binder_placements WHERE binderId = :binderId AND collectionEntryId = :entryId AND ownerUid = :ownerUid ORDER BY CASE WHEN deletedAtEpochMillis IS NULL THEN 0 ELSE 1 END, updatedAtEpochMillis DESC LIMIT 1")
    suspend fun privateBinderPlacementForEntry(binderId: String, entryId: Long, ownerUid: String = GUEST_OWNER_UID): PrivateBinderPlacementEntity?

    @Query("SELECT * FROM private_binder_placements WHERE binderId = :binderId AND slotPosition = :slotPosition AND ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL LIMIT 1")
    suspend fun privateBinderPlacementAt(binderId: String, slotPosition: Long, ownerUid: String = GUEST_OWNER_UID): PrivateBinderPlacementEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM private_binder_placements WHERE ownerUid = :ownerUid AND binderId = :binderId AND slotPosition = :slotPosition AND id != :excludingId AND deletedAtEpochMillis IS NULL)")
    suspend fun hasOtherActivePrivatePlacement(ownerUid: String, binderId: String, slotPosition: Long, excludingId: String): Boolean

    @Query("SELECT * FROM private_binder_placements WHERE binderId = :binderId AND ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL ORDER BY slotPosition, id")
    suspend fun activePrivateBinderPlacements(binderId: String, ownerUid: String = GUEST_OWNER_UID): List<PrivateBinderPlacementEntity>

    @Query("SELECT * FROM private_binder_placements WHERE collectionEntryId = :entryId AND ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL ORDER BY binderId, slotPosition")
    suspend fun activePrivateBinderPlacementsForEntry(entryId: Long, ownerUid: String = GUEST_OWNER_UID): List<PrivateBinderPlacementEntity>

    @Query("SELECT * FROM private_binder_placements WHERE ownerUid = '__domidex_guest__' AND deletedAtEpochMillis IS NULL ORDER BY id")
    suspend fun activeGuestPrivateBinderPlacements(): List<PrivateBinderPlacementEntity>

    @Query("DELETE FROM private_binder_placements WHERE ownerUid = '__domidex_guest__'")
    suspend fun deleteGuestPrivateBinderPlacements()

    @Insert
    suspend fun insertPrivateBinderPlacement(value: PrivateBinderPlacementEntity)

    @Upsert
    suspend fun upsertPrivateBinderPlacement(value: PrivateBinderPlacementEntity)

    @Update
    suspend fun updatePrivateBinderPlacement(value: PrivateBinderPlacementEntity)

    @Query("SELECT * FROM collection_layout WHERE ownerUid = :ownerUid ORDER BY collectionEntryId")
    fun observeCollectionLayouts(ownerUid: String = GUEST_OWNER_UID): Flow<List<CollectionLayoutEntity>>

    @Query("SELECT * FROM collection_layout WHERE collectionEntryId = :entryId AND ownerUid = :ownerUid LIMIT 1")
    suspend fun collectionLayout(entryId: Long, ownerUid: String = GUEST_OWNER_UID): CollectionLayoutEntity?

    @Upsert
    suspend fun upsertCollectionLayout(value: CollectionLayoutEntity)

    @Update
    suspend fun updateCollectionLayout(value: CollectionLayoutEntity)

    @Query("SELECT * FROM collection_price_selections WHERE ownerUid = :ownerUid")
    fun observePriceSelections(ownerUid: String): Flow<List<CollectionPriceSelectionEntity>>

    @Query("SELECT * FROM collection_price_selections WHERE ownerUid = :ownerUid AND entrySyncId = :entrySyncId LIMIT 1")
    suspend fun priceSelection(ownerUid: String, entrySyncId: String): CollectionPriceSelectionEntity?

    @Query("SELECT * FROM collection_price_selections WHERE ownerUid = :ownerUid")
    suspend fun priceSelections(ownerUid: String): List<CollectionPriceSelectionEntity>

    @Upsert
    suspend fun upsertPriceSelection(value: CollectionPriceSelectionEntity)

    @Query("UPDATE collection_price_selections SET syncState = 'CLEAN', remoteRevision = :revision WHERE ownerUid = :ownerUid AND entrySyncId = :entrySyncId")
    suspend fun markPriceSelectionSynced(ownerUid: String, entrySyncId: String, revision: Long)

    @Query("UPDATE collection_price_selections SET remoteRevision = :revision WHERE ownerUid = :ownerUid AND entrySyncId = :entrySyncId")
    suspend fun rebasePendingPriceSelection(ownerUid: String, entrySyncId: String, revision: Long)

    @Query("SELECT * FROM collection_layout WHERE ownerUid = '__domidex_guest__' ORDER BY collectionEntryId")
    suspend fun guestCollectionLayouts(): List<CollectionLayoutEntity>

    @Query("""
        SELECT p.id, p.ownerUid, p.binderId, p.collectionEntryId,
               e.syncId AS collectionEntrySyncId, p.slotPosition,
               p.holoEffectId, p.holoIntensity,
               p.createdAtEpochMillis, p.updatedAtEpochMillis, p.syncState,
               p.remoteRevision, p.deletedAtEpochMillis
        FROM private_binder_placements p
        JOIN collection_entries e ON e.id = p.collectionEntryId
        WHERE p.ownerUid = :ownerUid AND p.id = :placementId
        LIMIT 1
    """)
    suspend fun privateBinderPlacementSyncProjection(ownerUid: String, placementId: String): PrivateBinderPlacementSyncProjection?

    @Query("""
        SELECT l.collectionEntryId, e.syncId AS collectionEntrySyncId, l.ownerUid,
               l.visibleInSetBinder, l.updatedAtEpochMillis, l.syncState,
               l.remoteRevision
        FROM collection_layout l
        JOIN collection_entries e ON e.id = l.collectionEntryId
        WHERE l.ownerUid = :ownerUid AND e.syncId = :entrySyncId
          AND e.deletedAtEpochMillis IS NULL
        LIMIT 1
    """)
    suspend fun collectionLayoutSyncProjection(ownerUid: String, entrySyncId: String): CollectionLayoutSyncProjection?

    @Upsert
    suspend fun upsertPrivateBinderSyncOutbox(value: PrivateBinderSyncOutboxEntity)

    @Query("SELECT * FROM private_binder_sync_outbox WHERE ownerUid = :ownerUid ORDER BY createdAtEpochMillis LIMIT :limit")
    suspend fun pendingPrivateBinderSyncOutbox(ownerUid: String, limit: Int = 50): List<PrivateBinderSyncOutboxEntity>

    /** Sharing priority reuses the same outbox, limited to one binder and its placements. */
    @Query("""
        SELECT o.* FROM private_binder_sync_outbox o
        WHERE o.ownerUid = :ownerUid AND (
          (o.recordType = 'BINDER' AND o.recordId = :binderId) OR
          (o.recordType = 'PLACEMENT' AND EXISTS (
            SELECT 1 FROM private_binder_placements p WHERE p.ownerUid = :ownerUid
              AND p.binderId = :binderId AND p.id = o.recordId
          ))
        )
        ORDER BY CASE WHEN o.recordType = 'BINDER' THEN 0 ELSE 1 END,
                 o.createdAtEpochMillis, o.entryKey LIMIT :limit
    """)
    suspend fun pendingSharingPrivateBinderOutbox(ownerUid: String, binderId: String, limit: Int = 50): List<PrivateBinderSyncOutboxEntity>

    @Query("SELECT * FROM private_binder_sync_outbox WHERE entryKey = :entryKey LIMIT 1")
    suspend fun privateBinderSyncOutbox(entryKey: String): PrivateBinderSyncOutboxEntity?

    @Query("DELETE FROM private_binder_sync_outbox WHERE entryKey = :entryKey AND mutationId = :mutationId")
    suspend fun deletePrivateBinderSyncOutboxIfCurrent(entryKey: String, mutationId: String)

    @Query("UPDATE private_binder_sync_outbox SET expectedRemoteRevision = :revision WHERE entryKey = :entryKey AND expectedRemoteRevision < :revision")
    suspend fun advancePrivateBinderSyncOutboxBaseRevision(entryKey: String, revision: Long)

    @Query("DELETE FROM private_binder_sync_outbox WHERE entryKey = :entryKey")
    suspend fun deletePrivateBinderSyncOutbox(entryKey: String)

    @Query("DELETE FROM collection_layout WHERE collectionEntryId = :entryId AND ownerUid = :ownerUid")
    suspend fun deleteCollectionLayout(entryId: Long, ownerUid: String)

    @Query("UPDATE private_binders SET syncState = 'CLEAN', remoteRevision = :revision WHERE id = :binderId AND ownerUid = :ownerUid")
    suspend fun markPrivateBinderSynced(ownerUid: String, binderId: String, revision: Long)

    @Query("UPDATE private_binders SET syncState = 'PENDING', remoteRevision = :revision WHERE id = :binderId AND ownerUid = :ownerUid")
    suspend fun rebasePendingPrivateBinder(ownerUid: String, binderId: String, revision: Long)

    @Query("UPDATE private_binder_placements SET syncState = 'CLEAN', remoteRevision = :revision WHERE id = :placementId AND ownerUid = :ownerUid")
    suspend fun markPrivateBinderPlacementSynced(ownerUid: String, placementId: String, revision: Long)

    @Query("UPDATE private_binder_placements SET syncState = 'PENDING', remoteRevision = :revision WHERE id = :placementId AND ownerUid = :ownerUid")
    suspend fun rebasePendingPrivateBinderPlacement(ownerUid: String, placementId: String, revision: Long)

    @Query("""
        UPDATE collection_layout SET syncState = 'CLEAN', remoteRevision = :revision
        WHERE ownerUid = :ownerUid AND collectionEntryId = (
            SELECT id FROM collection_entries WHERE ownerUid = :ownerUid AND syncId = :entrySyncId LIMIT 1
        )
    """)
    suspend fun markCollectionLayoutSynced(ownerUid: String, entrySyncId: String, revision: Long)

    @Query("""
        UPDATE collection_layout SET syncState = 'PENDING', remoteRevision = :revision
        WHERE ownerUid = :ownerUid AND collectionEntryId = (
            SELECT id FROM collection_entries WHERE ownerUid = :ownerUid AND syncId = :entrySyncId LIMIT 1
        )
    """)
    suspend fun rebasePendingCollectionLayout(ownerUid: String, entrySyncId: String, revision: Long)

    /** Only price rows reachable from the active owner's collection enter portfolio memory. */
    @Query("""
        SELECT p.* FROM raw_price_snapshots p
        WHERE p.source = :source AND p.cardId IN (
            SELECT COALESCE(linked.id, c.id)
            FROM collection_entries e
            JOIN catalog_cards c ON c.id = e.cardId
            LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource
                 AND linked.externalProductId = e.externalProductId
            WHERE e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
              AND """ + SAFE_COLLECTION_PRICE_LINK_SQL + """
        )
        ORDER BY p.effectiveAtEpochMillis
    """)
    fun observeOwnedRawPriceHistory(ownerUid: String, source: String): Flow<List<RawPriceSnapshotEntity>>

    /**
     * History stays tied to the exact Cardmarket product linked to this entry.
     * A catalog reference has entryId 0, where its catalog id is the safe fallback.
     * useHoloPrice selects the download's Reverse Holo field, not regular Holo.
     */
    @Query("""
        SELECT p.effectiveAtEpochMillis,
               CASE WHEN :useHoloPrice THEN NULLIF(p.holoTrendValueMinor, 0)
                    ELSE p.trendValueMinor END AS valueMinor,
               p.currency, p.source
        FROM raw_price_snapshots p
        WHERE p.cardId = COALESCE(
            (SELECT linked.id
             FROM collection_entries e
             JOIN catalog_cards c ON c.id = e.cardId
             LEFT JOIN catalog_cards linked ON linked.externalSource = e.externalProductSource
                  AND linked.externalProductId = e.externalProductId
             WHERE e.id = :entryId AND e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
             LIMIT 1),
            :fallbackCardId
        )
          AND p.source = :source
          AND NOT EXISTS (
            SELECT 1 FROM collection_entries e JOIN catalog_cards c ON c.id = e.cardId
            WHERE e.id = :entryId AND e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
              AND NOT (""" + SAFE_COLLECTION_PRICE_LINK_SQL + """)
          )
          AND (CASE WHEN :useHoloPrice THEN NULLIF(p.holoTrendValueMinor, 0)
                    ELSE p.trendValueMinor END) IS NOT NULL
        ORDER BY p.effectiveAtEpochMillis ASC, p.id ASC
    """)
    fun observeCardPriceHistory(
        entryId: Long,
        fallbackCardId: String,
        source: String,
        useHoloPrice: Boolean,
        ownerUid: String = GUEST_OWNER_UID,
    ): Flow<List<CardPriceHistoryProjection>>

    @Query("""
        SELECT c.externalProductId, c.name, c.setId, c.setName,
               c.collectorNumber, c.language, c.finish, c.variant, c.imageUrl,
               p.trendValueMinor AS marketValueMinor,
               NULLIF(p.holoTrendValueMinor, 0) AS holoMarketValueMinor,
               p.currency, c.rarity, p.averageValueMinor, p.lowValueMinor,
               p.average1DayValueMinor, p.average7DayValueMinor, p.average30DayValueMinor,
               p.holoAverageValueMinor, p.holoLowValueMinor, p.effectiveAtEpochMillis AS priceSourceAt
        FROM (
            SELECT c.externalProductId, c.name FROM catalog_cards c
            WHERE c.externalSource = :source AND c.externalProductId IS NOT NULL
              AND c.name LIKE '%' || :query || '%' COLLATE NOCASE
            ORDER BY CASE WHEN c.name = :query COLLATE NOCASE THEN 0
                          WHEN c.name LIKE :query || '%' COLLATE NOCASE THEN 1 ELSE 2 END,
                     c.name COLLATE NOCASE
            LIMIT :limit
        ) matched
        JOIN catalog_cards c ON c.externalSource = :source AND c.externalProductId = matched.externalProductId
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = c.id AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD'
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        ORDER BY CASE WHEN c.name = :query COLLATE NOCASE THEN 0
                      WHEN c.name LIKE :query || '%' COLLATE NOCASE THEN 1 ELSE 2 END,
                 c.name COLLATE NOCASE
    """)
    suspend fun searchCatalog(source: String, query: String, limit: Int): List<CatalogSearchProjection>

    @Query("""
        SELECT c.externalProductId, c.name, c.setId, c.setName,
               c.collectorNumber, c.language, c.finish, c.variant, c.imageUrl,
               p.trendValueMinor AS marketValueMinor,
               NULLIF(p.holoTrendValueMinor, 0) AS holoMarketValueMinor,
               p.currency, c.rarity, p.averageValueMinor, p.lowValueMinor,
               p.average1DayValueMinor, p.average7DayValueMinor, p.average30DayValueMinor,
               p.holoAverageValueMinor, p.holoLowValueMinor, p.effectiveAtEpochMillis AS priceSourceAt
        FROM catalog_cards c
        LEFT JOIN raw_price_snapshots p ON p.id = (
            SELECT p2.id FROM raw_price_snapshots p2
            WHERE p2.cardId = c.id AND p2.source = :source
            ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
        )
        WHERE c.externalSource = :source AND c.externalProductId IN (:productIds)
        ORDER BY c.externalProductId
    """)
    suspend fun catalogProducts(source: String, productIds: List<Long>): List<CatalogSearchProjection>

    @Query("""
        SELECT c.id AS cardId, c.setId, c.name, c.setName, c.collectorNumber, c.language,
               c.finish, c.variant, c.imageUrl, c.externalProductId,
               CASE WHEN LOWER(c.finish) LIKE '%reverse%' THEN NULLIF(p.holoTrendValueMinor, 0)
                    ELSE p.trendValueMinor END AS marketValueMinor,
               CASE WHEN CASE WHEN LOWER(c.finish) LIKE '%reverse%' THEN NULLIF(p.holoTrendValueMinor, 0)
                              ELSE p.trendValueMinor END IS NULL
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
        SELECT c.id AS cardId, c.setId, c.name, c.setName, c.collectorNumber, c.language,
               c.finish, c.variant, c.imageUrl, c.externalProductId,
               NULL AS marketValueMinor, NULL AS priceUpdatedAtEpochMillis
        FROM catalog_cards c
        WHERE c.externalSource = 'TCGDEX_DE' AND c.setName = :setName
        ORDER BY CASE WHEN c.collectorNumber GLOB '[0-9]*' THEN CAST(c.collectorNumber AS INTEGER) ELSE 999999 END,
                 c.collectorNumber, c.name COLLATE NOCASE
    """)
    suspend fun germanMasterSetReferences(setName: String): List<CatalogReferenceProjection>

    @Query("SELECT COUNT(*) FROM catalog_cards WHERE externalSource = 'TCGDEX_DE' AND setName = :setName")
    suspend fun germanMasterSetReferenceCount(setName: String): Int

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

    @Query("SELECT * FROM collection_entries WHERE id = :id AND ownerUid = :ownerUid")
    suspend fun getCollectionEntry(id: Long, ownerUid: String = GUEST_OWNER_UID): CollectionEntryEntity?

    @Query("SELECT * FROM collection_entries WHERE ownerUid = :ownerUid AND syncId = :syncId LIMIT 1")
    suspend fun getCollectionEntryBySyncId(ownerUid: String, syncId: String): CollectionEntryEntity?

    @Query("SELECT COUNT(*) FROM collection_entries WHERE ownerUid = '__domidex_guest__' AND deletedAtEpochMillis IS NULL")
    fun observeGuestEntryCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM collection_sync_outbox WHERE ownerUid = :ownerUid AND state = 'CONFLICT'")
    fun observeSyncConflictCount(ownerUid: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM collection_sync_outbox WHERE ownerUid = :ownerUid AND state = 'ERROR'")
    fun observeSyncErrorCount(ownerUid: String): Flow<Int>

    @Query("SELECT * FROM collection_sync_outbox WHERE ownerUid = :ownerUid AND state = 'CONFLICT' ORDER BY createdAtEpochMillis")
    suspend fun conflictedSyncOutbox(ownerUid: String): List<CollectionSyncOutboxEntity>

    @Query("SELECT * FROM collection_sync_outbox WHERE ownerUid = :ownerUid AND state = 'ERROR' ORDER BY createdAtEpochMillis")
    suspend fun erroredSyncOutbox(ownerUid: String): List<CollectionSyncOutboxEntity>

    @Query("SELECT * FROM collection_entries WHERE ownerUid = '__domidex_guest__' AND deletedAtEpochMillis IS NULL ORDER BY id")
    suspend fun activeGuestEntries(): List<CollectionEntryEntity>

    @Query("""UPDATE collection_entries SET ownerUid = :newOwnerUid, syncId = :newSyncId,
        syncState = 'PENDING', remoteRevision = 0, updatedAtEpochMillis = :updatedAt
        WHERE id = :entryId AND ownerUid = '__domidex_guest__' AND deletedAtEpochMillis IS NULL""")
    suspend fun claimGuestEntry(entryId: Long, newOwnerUid: String, newSyncId: String, updatedAt: Long): Int

    @Query("""
        SELECT e.id AS entryId, e.ownerUid, e.syncId, e.syncState, e.remoteRevision,
               e.deletedAtEpochMillis, e.updatedAtEpochMillis,
               c.name, c.setId, c.setName, c.collectorNumber, c.language,
               COALESCE(e.finishOverride, c.finish) AS finish,
               COALESCE(e.variantOverride, c.variant) AS variant,
               c.rarity, e.quantity, e.condition, e.notes,
               e.acquisitionPriceMinor, e.acquisitionCurrency, e.acquisitionDateEpochDay,
               e.externalProductSource, e.externalProductId, c.imageUrl,
               COALESCE(e.effectOverride, c.effectOverride) AS effectOverride,
               e.sourceSetId, e.sourceCardId,
               e.binderId,
               CASE WHEN e.sourceSetId IS NOT NULL THEN 'tcgdex-' || e.sourceSetId ELSE c.setId END AS binderSetKey,
               c.setName AS binderName, NULL AS binderHeroAsset
        FROM collection_entries e
        JOIN catalog_cards c ON c.id = e.cardId
        WHERE e.ownerUid = :ownerUid AND e.syncId = :syncId
        LIMIT 1
    """)
    suspend fun collectionSyncProjection(ownerUid: String, syncId: String): CollectionSyncProjection?

    @Query("""SELECT e.* FROM collection_entries e JOIN catalog_cards c ON c.id = e.cardId
        WHERE e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
          AND e.externalProductSource = :source AND e.externalProductId = :productId
          AND e.`condition` = :condition
          AND COALESCE(e.finishOverride, c.finish) = :finish
          AND COALESCE(e.variantOverride, c.variant) = :variant LIMIT 1""")
    suspend fun findLinkedEntry(source: String, productId: Long, condition: String, finish: String, variant: String, ownerUid: String = GUEST_OWNER_UID): CollectionEntryEntity?

    @Query("""SELECT e.* FROM collection_entries e JOIN catalog_cards c ON c.id = e.cardId
        WHERE e.ownerUid = :ownerUid AND e.deletedAtEpochMillis IS NULL
          AND e.sourceSetId = :sourceSetId AND e.sourceCardId = :sourceCardId
          AND e.`condition` = :condition
          AND COALESCE(e.finishOverride, c.finish) = :finish
          AND COALESCE(e.variantOverride, c.variant) = :variant LIMIT 1""")
    suspend fun findSourceEntry(
        ownerUid: String,
        sourceSetId: String,
        sourceCardId: String,
        condition: String,
        finish: String,
        variant: String,
    ): CollectionEntryEntity?

    @Query("UPDATE collection_entries SET quantity = quantity + :amount, updatedAtEpochMillis = :updatedAt, syncState = :syncState WHERE id = :id AND ownerUid = :ownerUid")
    suspend fun incrementQuantity(id: Long, amount: Int, updatedAt: Long, ownerUid: String = GUEST_OWNER_UID, syncState: String = SYNC_LOCAL_ONLY)

    @Query("UPDATE collection_entries SET externalProductSource = :source, externalProductId = :productId, updatedAtEpochMillis = :updatedAt, syncState = :syncState WHERE id = :entryId AND ownerUid = :ownerUid")
    suspend fun linkExternalProduct(entryId: Long, source: String?, productId: Long?, updatedAt: Long, ownerUid: String = GUEST_OWNER_UID, syncState: String = SYNC_LOCAL_ONLY)

    @Query("UPDATE collection_entries SET finishOverride = :finish, variantOverride = :variant, effectOverride = :effectOverride WHERE id = :entryId AND ownerUid = :ownerUid")
    suspend fun updateCardAppearance(entryId: Long, finish: String, variant: String, effectOverride: String?, ownerUid: String = GUEST_OWNER_UID)

    @Query("UPDATE collection_entries SET deletedAtEpochMillis = :deletedAt, updatedAtEpochMillis = :deletedAt, syncState = :syncState WHERE id = :id AND ownerUid = :ownerUid")
    suspend fun tombstoneCollectionEntry(id: Long, ownerUid: String, deletedAt: Long, syncState: String)

    @Query("DELETE FROM collection_entries WHERE id = :id")
    suspend fun deleteCollectionEntry(id: Long)

    @Query("UPDATE collection_entries SET deletedAtEpochMillis = :deletedAt, updatedAtEpochMillis = :deletedAt, syncState = :syncState WHERE binderId = :binderId AND ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL")
    suspend fun tombstoneEntriesForBinder(binderId: String, ownerUid: String, deletedAt: Long, syncState: String)

    @Query("SELECT * FROM collection_entries WHERE binderId = :binderId AND ownerUid = :ownerUid AND deletedAtEpochMillis IS NULL")
    suspend fun activeEntriesForBinder(binderId: String, ownerUid: String): List<CollectionEntryEntity>

    /** Removes only generated reference binders; owned/user-created binders survive. */
    @Query("DELETE FROM binders WHERE id LIKE 'tcgdex-%' AND NOT EXISTS (SELECT 1 FROM collection_entries e WHERE e.binderId = binders.id)")
    suspend fun deleteGeneratedGermanReferenceBinders()

    @Query("SELECT value FROM app_metadata WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(value: AppMetadataEntity)

    /** Bounded lexicographic range; callers construct a fixed owner-scoped prefix. */
    @Query("SELECT * FROM app_metadata WHERE `key` >= :startInclusive AND `key` < :endExclusive AND `key` > :afterKey ORDER BY `key` LIMIT :limit")
    suspend fun metadataRangePage(startInclusive: String, endExclusive: String, afterKey: String, limit: Int): List<AppMetadataEntity>

    @Query("SELECT COUNT(*) FROM app_metadata WHERE `key` >= :startInclusive AND `key` < :endExclusive")
    suspend fun metadataRangeCount(startInclusive: String, endExclusive: String): Int

    @Query("DELETE FROM app_metadata WHERE `key` = :key")
    suspend fun deleteMetadata(key: String): Int

    @Transaction
    suspend fun updateEntry(id: Long, ownerUid: String, syncState: String, quantity: Int, condition: String, notes: String, acquisitionPriceMinor: Long?, acquisitionCurrency: String?, acquisitionDateEpochDay: Long?, finish: String, variant: String, effectOverride: String?) {
        val existing = getCollectionEntry(id, ownerUid) ?: return
        updateCollectionEntry(existing.copy(quantity = quantity, condition = condition, notes = notes, acquisitionPriceMinor = acquisitionPriceMinor, acquisitionCurrency = acquisitionCurrency, acquisitionDateEpochDay = acquisitionDateEpochDay, updatedAtEpochMillis = System.currentTimeMillis(), syncState = syncState))
        updateCardAppearance(id, finish, variant, effectOverride, ownerUid)
    }

    @Upsert
    suspend fun upsertSyncOutbox(value: CollectionSyncOutboxEntity)

    @Query("SELECT * FROM collection_sync_outbox WHERE ownerUid = :ownerUid AND state = 'PENDING' ORDER BY createdAtEpochMillis LIMIT :limit")
    suspend fun pendingSyncOutbox(ownerUid: String, limit: Int = 50): List<CollectionSyncOutboxEntity>

    /** Do not push an unrelated collection while preparing this specific binder for sharing. */
    @Query("""
        SELECT o.* FROM collection_sync_outbox o
        WHERE o.ownerUid = :ownerUid AND o.state = 'PENDING' AND EXISTS (
          SELECT 1 FROM collection_entries e
          JOIN private_binder_placements p ON p.collectionEntryId = e.id AND p.ownerUid = e.ownerUid
          WHERE e.ownerUid = :ownerUid AND e.syncId = o.syncId
            AND p.binderId = :binderId AND p.deletedAtEpochMillis IS NULL
        )
        ORDER BY o.createdAtEpochMillis, o.entryKey LIMIT :limit
    """)
    suspend fun pendingSharingCollectionOutbox(ownerUid: String, binderId: String, limit: Int = 50): List<CollectionSyncOutboxEntity>

    @Query("SELECT * FROM collection_sync_outbox WHERE entryKey = :entryKey LIMIT 1")
    suspend fun syncOutbox(entryKey: String): CollectionSyncOutboxEntity?

    @Query("DELETE FROM collection_sync_outbox WHERE entryKey = :entryKey AND mutationId = :mutationId")
    suspend fun deleteSyncOutboxIfCurrent(entryKey: String, mutationId: String)

    @Query("UPDATE collection_sync_outbox SET expectedRemoteRevision = :revision WHERE entryKey = :entryKey AND expectedRemoteRevision < :revision")
    suspend fun advanceOutboxBaseRevision(entryKey: String, revision: Long)

    @Query("UPDATE collection_sync_outbox SET state = 'CONFLICT', lastError = :reason WHERE entryKey = :entryKey AND mutationId = :mutationId")
    suspend fun markOutboxConflict(entryKey: String, mutationId: String, reason: String)

    @Query("UPDATE collection_sync_outbox SET state = 'ERROR', lastError = :reason WHERE entryKey = :entryKey AND mutationId = :mutationId")
    suspend fun markOutboxError(entryKey: String, mutationId: String, reason: String)

    @Query("""UPDATE collection_sync_outbox
        SET mutationId = :newMutationId, expectedRemoteRevision = :remoteRevision,
            state = 'PENDING', createdAtEpochMillis = :createdAt, lastError = NULL
        WHERE entryKey = :entryKey AND ownerUid = :ownerUid AND mutationId = :previousMutationId
          AND state = 'CONFLICT'""")
    suspend fun rebaseSyncConflict(
        entryKey: String,
        ownerUid: String,
        previousMutationId: String,
        newMutationId: String,
        remoteRevision: Long,
        createdAt: Long,
    ): Int

    @Query("""UPDATE collection_sync_outbox
        SET mutationId = :newMutationId, state = 'PENDING', createdAtEpochMillis = :createdAt,
            lastError = NULL
        WHERE entryKey = :entryKey AND ownerUid = :ownerUid AND mutationId = :previousMutationId
          AND state = 'ERROR'""")
    suspend fun retrySyncError(
        entryKey: String,
        ownerUid: String,
        previousMutationId: String,
        newMutationId: String,
        createdAt: Long,
    ): Int

    @Query("UPDATE collection_entries SET remoteRevision = :revision, syncState = :state WHERE ownerUid = :ownerUid AND syncId = :syncId")
    suspend fun markEntrySynced(ownerUid: String, syncId: String, revision: Long, state: String)

    @Query("UPDATE collection_entries SET syncState = 'CONFLICT' WHERE ownerUid = :ownerUid AND syncId = :syncId")
    suspend fun markEntryConflict(ownerUid: String, syncId: String)

    @Query("UPDATE collection_entries SET syncState = 'ERROR' WHERE ownerUid = :ownerUid AND syncId = :syncId")
    suspend fun markEntrySyncError(ownerUid: String, syncId: String)

    @Query("""UPDATE collection_entries SET quantity = :quantity, condition = :condition,
        acquisitionPriceMinor = :acquisitionPriceMinor, acquisitionCurrency = :acquisitionCurrency,
        acquisitionDateEpochDay = :acquisitionDateEpochDay, notes = :notes,
        externalProductSource = :externalProductSource, externalProductId = :externalProductId,
        sourceSetId = :sourceSetId, sourceCardId = :sourceCardId,
        binderId = :binderId, updatedAtEpochMillis = :updatedAtEpochMillis,
        remoteRevision = :remoteRevision, syncState = 'CLEAN', deletedAtEpochMillis = :deletedAtEpochMillis,
        finishOverride = :finish, variantOverride = :variant, effectOverride = :effectOverride
        WHERE ownerUid = :ownerUid AND syncId = :syncId AND syncState = 'CLEAN'""")
    suspend fun applyCleanRemoteEntry(
        ownerUid: String,
        syncId: String,
        quantity: Int,
        condition: String,
        acquisitionPriceMinor: Long?,
        acquisitionCurrency: String?,
        acquisitionDateEpochDay: Long?,
        notes: String,
        externalProductSource: String?,
        externalProductId: Long?,
        sourceSetId: String?,
        sourceCardId: String?,
        binderId: String,
        updatedAtEpochMillis: Long,
        remoteRevision: Long,
        deletedAtEpochMillis: Long?,
        finish: String,
        variant: String,
        effectOverride: String?,
    )
}
