package com.dominik.cardvault.data

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.dominik.cardvault.data.catalog.classic30thLocalIdForPrintedCollector
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.sync.PRIVATE_SYNC_BINDER
import com.dominik.cardvault.data.sync.PRIVATE_SYNC_DELETE
import com.dominik.cardvault.data.sync.PRIVATE_SYNC_LAYOUT
import com.dominik.cardvault.data.sync.PRIVATE_SYNC_PLACEMENT
import com.dominik.cardvault.data.sync.PRIVATE_SYNC_PRICE_SELECTION
import com.dominik.cardvault.data.sync.PRIVATE_SYNC_UPSERT
import com.dominik.cardvault.data.sync.deterministicPrivatePlacementId
import com.dominik.cardvault.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToLong

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomCollectionRepository(
    private val database: CardVaultDatabase,
    private val activeOwnerUid: StateFlow<String> = MutableStateFlow(GUEST_OWNER_UID),
    private val onSyncNeeded: () -> Unit = {},
    private val onKeepCloudConflicts: suspend () -> Int = { 0 },
    override val cloudState: Flow<com.dominik.cardvault.data.sync.CollectionCloudState> = kotlinx.coroutines.flow.flowOf(com.dominik.cardvault.data.sync.CollectionCloudState(com.dominik.cardvault.data.sync.CollectionCloudStatus.LOCAL_ONLY)),
    private val verifyGuestClaimOwner: suspend () -> String = { com.dominik.cardvault.auth.requireVerifiedCollectionOwnerForClaim() },
) : CollectionRepository {
    private val dao = database.cardVaultDao()
    private val catalogQueries = CatalogQueryCache<CatalogSearchResult>()
    private val catalogObserverMutex = Mutex()
    private var catalogObserverRegistered = false

    override suspend fun exportUserBackup(settingsJson: String): String = withContext(Dispatchers.IO) {
        com.dominik.cardvault.data.backup.UserBackupStore(database, activeOwnerUid.value).exportJson(org.json.JSONObject(settingsJson))
    }

    override suspend fun restoreUserBackup(raw: String, journalKey: String?): com.dominik.cardvault.data.backup.UserBackupStore.Result = withContext(Dispatchers.IO) {
        val owner = activeOwnerUid.value
        com.dominik.cardvault.data.backup.UserBackupStore(database, owner).restore(raw, journalKey) { activeOwnerUid.value == owner }.also {
            if (!it.alreadyImported && owner != GUEST_OWNER_UID) onSyncNeeded()
        }
    }

    override suspend fun restoreLegacyBackup(raw: String, entries: List<ManualCardInput>, journalKey: String?): Int = withContext(Dispatchers.IO) {
        require(journalKey == null || journalKey.matches(Regex("backup-file-commit:[a-f0-9-]{36}")))
        val owner = activeOwnerUid.value
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        database.withTransaction {
            if (journalKey != null) dao.putMetadata(AppMetadataEntity(journalKey, "done"))
            val key = "backup-v1:$owner:$hash"
            if (dao.metadata(key) != null) return@withTransaction 0
            entries.forEach {
                check(activeOwnerUid.value == owner) { "Konto während des Imports gewechselt. Bitte erneut versuchen." }
                addManualCard(it)
            }
            check(activeOwnerUid.value == owner) { "Konto während des Imports gewechselt. Bitte erneut versuchen." }
            dao.putMetadata(AppMetadataEntity(key, "done"))
            entries.size
        }
    }

    private suspend fun ensureCatalogObserver() = catalogObserverMutex.withLock {
        if (!catalogObserverRegistered) {
            // Registration synchronizes Room's triggers, so the first search calls this on IO.
            database.invalidationTracker.addObserver(CatalogQueryObserver(database.invalidationTracker, catalogQueries))
            catalogObserverRegistered = true
        }
    }

    override val catalogSyncStatus: Flow<CatalogSyncStatus> = dao.observeSyncState(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE).map { state ->
        catalogQueries.observeRevision(state?.lastSuccessAtEpochMillis)
        state?.let { CatalogSyncStatus(it.status, it.lastSuccessAtEpochMillis, it.dataCreatedAt, it.catalogProductCount, it.priceRecordCount, it.errorMessage) }
            ?: CatalogSyncStatus("IDLE", null, null, 0, 0, null)
    }
    override val guestEntryCount: Flow<Int> = dao.observeGuestEntryCount()
    override val syncConflictCount: Flow<Int> = activeOwnerUid.flatMapLatest { ownerUid ->
        if (ownerUid == GUEST_OWNER_UID) kotlinx.coroutines.flow.flowOf(0)
        else dao.observeSyncConflictCount(ownerUid)
    }
    override val syncErrorCount: Flow<Int> = activeOwnerUid.flatMapLatest { ownerUid ->
        if (ownerUid == GUEST_OWNER_UID) kotlinx.coroutines.flow.flowOf(0)
        else dao.observeSyncErrorCount(ownerUid)
    }
    override val binders: Flow<List<BinderSummary>> = activeOwnerUid.flatMapLatest { ownerUid ->
        combine(dao.observeBinders(ownerUid), currentCardsFor(ownerUid)) { rows, currentCards ->
            val cardsByBinder = currentCards.groupBy(CollectionCard::binderId)
            rows.map { row ->
                val cards = cardsByBinder[row.id].orEmpty()
                BinderSummary(row.id, row.name, row.heroAsset, row.uniqueCards, row.ownedCards,
                    cards.sumOf { (it.effectiveValueEur ?: 0.0) * it.quantity },
                    cards.mapNotNull { it.priceUpdatedAtEpochMillis ?: it.activePriceSelection?.sourceAt }.maxOrNull(),
                    cards.filter { it.usesPriceSelection }.sumOf { it.quantity },
                    cards.filter { it.trendValueEur != null }.sumOf { it.quantity })
            }
        }
    }

    override val setBinderVisibility: Flow<Map<Long, Boolean>> = activeOwnerUid.flatMapLatest { ownerUid ->
        dao.observeCollectionLayouts(ownerUid).map { rows ->
            rows.associate { it.collectionEntryId to it.visibleInSetBinder }
        }
    }

    override val portfolio: Flow<PortfolioSummary> = activeOwnerUid.flatMapLatest { portfolioFor(it) }

    /** Summaries only need today's values; do not load every historical price to render a binder. */
    private fun currentCardsFor(ownerUid: String): Flow<List<CollectionCard>> = combine(
        dao.observeCollection(ownerUid), dao.observePriceSelections(ownerUid),
    ) { rows, selections -> collectionCards(rows, selections) }.flowOn(Dispatchers.Default)

    private fun portfolioFor(ownerUid: String, includePortfolioHistory: Boolean = true): Flow<PortfolioSummary> = combine(
            dao.observeCollection(ownerUid),
            dao.observeOwnedRawPriceHistory(ownerUid, com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE),
            dao.observePriceSelections(ownerUid),
        ) { rows, prices, selections ->
        // One indexed view replaces repeated full price-table scans for every card and every
        // history timestamp. Room already emits immutable snapshots, so this cache is local to
        // the current calculation and can never become stale.
        // Source filtering and ownership/identity checks are enforced by the DAO query.
        val cardmarketPrices = prices.groupBy(OwnedPriceHistoryProjection::cardId)
            .mapValues { (_, values) -> values.sortedBy(OwnedPriceHistoryProjection::effectiveAtEpochMillis) }
        PortfolioSummary(collectionCards(rows, selections, cardmarketPrices),
            if (includePortfolioHistory) buildHistory(rows, prices, cardmarketPrices) else emptyList())
    }.flowOn(Dispatchers.Default)

    private fun collectionCards(
        rows: List<CollectionProjection>,
        selections: List<CollectionPriceSelectionEntity>,
        cardmarketPrices: Map<String, List<OwnedPriceHistoryProjection>> = emptyMap(),
    ): List<CollectionCard> {
        val selectionsByEntry = selections.associateBy(CollectionPriceSelectionEntity::collectionEntryId)
        return rows.map { row ->
            val cardPrices = cardmarketPrices[row.priceCardId].orEmpty()
            val first = cardPrices.asSequence().mapNotNull { it.trendValueFor(row.finish) }.firstOrNull()
            val latest = row.marketValueMinor?.takeIf { it > 0L }
            val change = if (first != null && latest != null && first != 0L) (latest - first) * 100.0 / first else 0.0
            CollectionCard(row.entryId, row.cardId, row.name, row.setName, row.collectorNumber,
                CardLanguage.fromStorage(row.language), row.finish, row.variant, row.condition, row.quantity,
                (latest ?: 0L) / 100.0, change, row.priceUpdatedAtEpochMillis.takeIf { latest != null }, row.notes,
                row.acquisitionPriceMinor?.div(100.0), row.acquisitionDateEpochDay, accentFor(row.name), imageUrl = row.imageUrl)
                .copy(
                    binderId = row.binderId,
                    externalProductId = row.externalProductId,
                    rarity = row.rarity,
                    effectOverride = row.effectOverride,
                    sourceSetId = row.sourceSetId,
                    sourceCardId = row.sourceCardId,
                    priceSelection = selectionsByEntry[row.entryId]?.selectionOrNull()
                        ?.takeIf { row.externalProductSource == com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE },
                )
        }
    }

    override val privateBinders: Flow<List<PrivateBinderSummary>> = activeOwnerUid.flatMapLatest { ownerUid ->
        combine(
            dao.observePrivateBinders(ownerUid),
            dao.observeAllPrivateBinderPlacements(ownerUid),
            currentCardsFor(ownerUid),
        ) { binderRows, placementRows, currentCards ->
            val cardsByEntry = currentCards.associateBy(CollectionCard::entryId)
            val placementsByBinder = placementRows.groupBy(PrivateBinderPlacementEntity::binderId)
            binderRows.map { binder ->
                val cards = placementsByBinder[binder.id].orEmpty().mapNotNull { cardsByEntry[it.collectionEntryId] }
                PrivateBinderSummary(
                    id = binder.id,
                    name = binder.name,
                    description = binder.description,
                    coverStyle = binder.coverStyle,
                    minimumPageCount = binder.minimumPageCount,
                    uniqueCards = cards.map(CollectionCard::entryId).distinct().size,
                    ownedCards = cards.sumOf(CollectionCard::quantity),
                    marketValueEur = cards.sumOf { (it.effectiveValueEur ?: 0.0) * it.quantity },
                    createdAtEpochMillis = binder.createdAtEpochMillis,
                    updatedAtEpochMillis = binder.updatedAtEpochMillis,
                    lastOpenedAtEpochMillis = binder.lastOpenedAtEpochMillis,
                    ownerUid = binder.ownerUid,
                    alternativePricedCards = cards.filter { it.usesPriceSelection }.sumOf { it.quantity },
                    trendPricedCards = cards.filter { it.trendValueEur != null }.sumOf { it.quantity },
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun seedIfNeeded() = database.withTransaction {
        // Historical builds identified demo rows only by real card ids. Deleting those ids can
        // destroy legitimate collections, so the cleanup is now permanently data-preserving.
        if (dao.metadata(DEMO_CLEANUP_KEY) == null) {
            dao.putMetadata(AppMetadataEntity(DEMO_CLEANUP_KEY, "disabled-data-preserving-v2"))
        }
        // A catalog/reference set must never be silently treated as owned cards.
        // Binder contents are created only through an explicit manual or scan add.
    }

    override suspend fun addManualCard(input: ManualCardInput): AddCardOutcome {
        val result = addManualCard(input, BinderTarget.MatchingSetBinder)
        return if (result.quantityIncremented) AddCardOutcome.QUANTITY_INCREMENTED else AddCardOutcome.CREATED
    }

    override suspend fun addManualCard(input: ManualCardInput, target: BinderTarget): TargetedAddCardResult {
        val ownerUid = activeOwnerUid.value
        validateManualInputForSync(input)
        val sourceIdentity = sourceIdentity(input.sourceSetId, input.sourceCardId)
        var scheduleSync = false
        val result = database.withTransaction {
            val now = System.currentTimeMillis()
            val normalizedCondition = input.condition.trim()
            val normalizedFinish = input.finish.trim().ifBlank { "Normal" }
            val normalizedVariant = input.variant.trim().ifBlank { "Standard" }
            val amount = input.quantity.coerceAtLeast(1)
            val privateTarget = (target as? BinderTarget.PrivateBinder)?.let { destination ->
                dao.privateBinder(destination.binderId, ownerUid)
                    ?.takeIf { it.deletedAtEpochMillis == null }
                    ?: throw IllegalArgumentException("Der private Binder ist nicht verfügbar.")
            }
            // TCGdex identity is authoritative. Cardmarket remains an independent price link and
            // may appear only after the first scan has already been saved.
            val existing = sourceIdentity?.let { source ->
                dao.findSourceEntry(ownerUid, source.setId, source.cardId, normalizedCondition, normalizedFinish, normalizedVariant)
            } ?: input.externalProductId?.let { productId ->
                dao.findLinkedEntry(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, productId, normalizedCondition, normalizedFinish, normalizedVariant, ownerUid)
            }
            if (existing != null) {
                val syncState = syncStateFor(ownerUid)
                if (existing.externalProductId == null && input.externalProductId != null) {
                    dao.linkExternalProduct(existing.id, com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, input.externalProductId, now, ownerUid, syncState)
                    dao.getCollectionEntry(existing.id, ownerUid)?.let { changed ->
                        scheduleSync = queueMutation(changed, "UPSERT") || scheduleSync
                    }
                }
                val currentEntry = dao.getCollectionEntry(existing.id, ownerUid) ?: existing
                input.priceSelection?.let { selection ->
                    scheduleSync = savePriceSelectionInTransaction(currentEntry, selection, now) || scheduleSync
                }

                if (privateTarget != null) {
                    val assignment = assignToPrivateBinderInTransaction(
                        binder = privateTarget,
                        entry = currentEntry,
                        requestedPosition = null,
                        now = now,
                    )
                    scheduleSync = assignment.syncQueued || scheduleSync
                    scheduleSync = setVisibleInSetBinderInTransaction(currentEntry, visible = false, now) || scheduleSync
                    return@withTransaction TargetedAddCardResult(
                        entryId = existing.id,
                        ownershipCreated = false,
                        quantityIncremented = false,
                        privatePlacementId = assignment.placementId,
                        privateAssignmentCreated = assignment.created,
                    )
                }

                scheduleSync = setVisibleInSetBinderInTransaction(currentEntry, visible = true, now) || scheduleSync
                val newQuantity = existing.quantity.toLong() + amount.toLong()
                require(newQuantity in 1..com.dominik.cardvault.data.sync.CollectionSyncLimits.QUANTITY.toLong()) {
                    "Die Gesamtanzahl darf ${com.dominik.cardvault.data.sync.CollectionSyncLimits.QUANTITY} nicht überschreiten."
                }
                dao.incrementQuantity(existing.id, amount, now, ownerUid, syncState)
                dao.getCollectionEntry(existing.id, ownerUid)?.let { changed ->
                    scheduleSync = queueMutation(changed, "UPSERT") || scheduleSync
                }
                return@withTransaction TargetedAddCardResult(
                    entryId = existing.id,
                    ownershipCreated = false,
                    quantityIncremented = true,
                )
            }

            val displaySetName = input.setName.trim().ifBlank { "Ohne Setzuordnung" }
            val setKey = binderSetKey(input.cardmarketExpansionId, displaySetName, sourceIdentity?.setId)
            val binderId = dao.binderIdForSet(setKey) ?: binderIdForSetKey(setKey)
            com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Set-ID", setKey, com.dominik.cardvault.data.sync.CollectionSyncLimits.SET_ID, required = true)
            com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Binder-ID", binderId, com.dominik.cardvault.data.sync.CollectionSyncLimits.BINDER_ID, required = true)
            dao.insertBinder(BinderEntity(binderId, setKey, displaySetName, createdAtEpochMillis = now, updatedAtEpochMillis = now))

            val cardId = sourceIdentity?.let(::sourceCatalogCardId) ?: "manual-${UUID.randomUUID()}"
            val catalogCard = CatalogCardEntity(
                id = cardId,
                tcg = "POKEMON",
                setId = sourceIdentity?.setId ?: setKey,
                setName = displaySetName,
                name = input.name.trim(),
                collectorNumber = input.collectorNumber.trim(),
                language = input.language.name,
                finish = normalizedFinish,
                variant = normalizedVariant,
                createdAtEpochMillis = now,
                externalSource = sourceIdentity?.let { "TCGDEX_DE" },
                imageUrl = input.imageUrl,
                rarity = input.rarity?.trim()?.ifBlank { "UNKNOWN" } ?: "UNKNOWN",
                effectOverride = input.effectOverride,
            )
            if (sourceIdentity != null) dao.insertCatalogCards(listOf(catalogCard)) else dao.insertCatalogCard(catalogCard)

            val deterministicSyncId = if (ownerUid == GUEST_OWNER_UID) null else when {
                sourceIdentity != null -> deterministicSourceSyncId(ownerUid, sourceIdentity, normalizedCondition, normalizedFinish, normalizedVariant)
                input.externalProductId != null -> deterministicCardmarketSyncId(ownerUid, input.externalProductId, normalizedCondition, normalizedFinish, normalizedVariant)
                else -> null
            }
            val syncId = deterministicSyncId?.let { nextAvailableSyncId(ownerUid, it) }
                ?: UUID.randomUUID().toString()
            val entry = CollectionEntryEntity(
                cardId = cardId,
                quantity = amount,
                condition = normalizedCondition,
                acquisitionPriceMinor = input.acquisitionPriceEur?.times(100)?.roundToLong(),
                acquisitionCurrency = input.acquisitionPriceEur?.let { "EUR" },
                acquisitionDateEpochDay = input.acquisitionDateEpochDay,
                notes = input.notes.trim(),
                frontPhotoUri = null,
                backPhotoUri = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                externalProductSource = input.externalProductId?.let { com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE },
                externalProductId = input.externalProductId,
                binderId = binderId,
                ownerUid = ownerUid,
                syncId = syncId,
                syncState = syncStateFor(ownerUid),
                finishOverride = normalizedFinish,
                variantOverride = normalizedVariant,
                effectOverride = input.effectOverride,
                sourceSetId = sourceIdentity?.setId,
                sourceCardId = sourceIdentity?.cardId,
            )
            // A Firestore tombstone is immutable by rule. A re-add therefore always receives a
            // fresh successor id; the deleted row remains intact and can never be resurrected.
            val stored = entry.copy(id = dao.insertCollectionEntry(entry))
            scheduleSync = queueMutation(stored, "UPSERT") || scheduleSync
            input.priceSelection?.let { selection ->
                scheduleSync = savePriceSelectionInTransaction(stored, selection, now) || scheduleSync
            }
            if (privateTarget == null) {
                TargetedAddCardResult(
                    entryId = stored.id,
                    ownershipCreated = true,
                    quantityIncremented = false,
                )
            } else {
                val layout = CollectionLayoutEntity(
                    collectionEntryId = stored.id,
                    ownerUid = ownerUid,
                    visibleInSetBinder = false,
                    updatedAtEpochMillis = now,
                    syncState = syncStateFor(ownerUid),
                )
                dao.upsertCollectionLayout(layout)
                scheduleSync = queueCollectionLayoutMutation(layout, stored.syncId) || scheduleSync
                val assignment = assignToPrivateBinderInTransaction(privateTarget, stored, null, now)
                scheduleSync = assignment.syncQueued || scheduleSync
                TargetedAddCardResult(
                    entryId = stored.id,
                    ownershipCreated = true,
                    quantityIncremented = false,
                    privatePlacementId = assignment.placementId,
                    privateAssignmentCreated = assignment.created,
                )
            }
        }
        if (scheduleSync) onSyncNeeded()
        return result
    }

    override suspend fun updateEntry(entryId: Long, update: CollectionEntryUpdate) {
        val ownerUid = activeOwnerUid.value
        validateEntryUpdateForSync(update)
        val queued = database.withTransaction {
            dao.updateEntry(entryId, ownerUid, syncStateFor(ownerUid), update.quantity.coerceAtLeast(1), update.condition.trim(), update.notes.trim(), update.acquisitionPriceEur?.times(100)?.roundToLong(), update.acquisitionPriceEur?.let { "EUR" }, update.acquisitionDateEpochDay, update.finish.trim().ifBlank { "Normal" }, update.variant.trim().ifBlank { "Standard" }, update.effectOverride)
            dao.getCollectionEntry(entryId, ownerUid)?.let { queueMutation(it, "UPSERT") } ?: false
        }
        if (queued) onSyncNeeded()
    }
    override suspend fun deleteEntry(entryId: Long) {
        val ownerUid = activeOwnerUid.value
        var queued = false
        database.withTransaction {
            val existing = dao.getCollectionEntry(entryId, ownerUid) ?: return@withTransaction
            if (ownerUid == GUEST_OWNER_UID) {
                dao.deleteCollectionEntry(entryId)
            } else {
                val now = System.currentTimeMillis()
                dao.activePrivateBinderPlacementsForEntry(entryId, ownerUid).forEach { placement ->
                    val tombstone = placement.copy(
                        updatedAtEpochMillis = now,
                        syncState = SYNC_PENDING,
                        deletedAtEpochMillis = now,
                    )
                    dao.updatePrivateBinderPlacement(tombstone)
                    queued = queuePrivateBinderPlacementMutation(tombstone, PRIVATE_SYNC_DELETE) || queued
                }
                // A layout is presentation-only and its cloud id is tied to this immutable
                // collection generation. Once the entry is tombstoned it can never become
                // visible again, so an unsent layout mutation is obsolete rather than retryable.
                dao.deleteCollectionLayout(entryId, ownerUid)
                dao.deletePrivateBinderSyncOutbox("$ownerUid:$PRIVATE_SYNC_LAYOUT:${existing.syncId}")
                dao.tombstoneCollectionEntry(entryId, ownerUid, now, SYNC_PENDING)
                queued = queueMutation(existing.copy(deletedAtEpochMillis = now, updatedAtEpochMillis = now, syncState = SYNC_PENDING), "DELETE") || queued
            }
        }
        if (queued) onSyncNeeded()
    }
    override suspend fun deleteBinder(binderId: String) {
        val ownerUid = activeOwnerUid.value
        var queued = false
        database.withTransaction {
            val entries = dao.activeEntriesForBinder(binderId, ownerUid)
            if (ownerUid == GUEST_OWNER_UID) {
                entries.forEach { dao.deleteCollectionEntry(it.id) }
            } else {
                val now = System.currentTimeMillis()
                dao.tombstoneEntriesForBinder(binderId, ownerUid, now, SYNC_PENDING)
                entries.forEach { entry ->
                    queued = queueMutation(entry.copy(deletedAtEpochMillis = now, updatedAtEpochMillis = now, syncState = SYNC_PENDING), "DELETE") || queued
                }
            }
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun createPrivateBinder(name: String, description: String?, coverStyle: String): String {
        val ownerUid = activeOwnerUid.value
        val normalizedName = validatePrivateBinderName(name)
        val normalizedDescription = validatePrivateBinderDescription(description)
        val normalizedCover = validatePrivateBinderCover(coverStyle)
        val binderId = UUID.randomUUID().toString()
        val queued = database.withTransaction {
            val now = System.currentTimeMillis()
            val binder = PrivateBinderEntity(
                id = binderId,
                ownerUid = ownerUid,
                name = normalizedName,
                description = normalizedDescription,
                coverStyle = normalizedCover,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                syncState = syncStateFor(ownerUid),
            )
            dao.insertPrivateBinder(binder)
            queuePrivateBinderMutation(binder, PRIVATE_SYNC_UPSERT)
        }
        if (queued) onSyncNeeded()
        return binderId
    }

    override suspend fun updatePrivateBinder(binderId: String, name: String, description: String?, coverStyle: String) {
        val ownerUid = activeOwnerUid.value
        val normalizedName = validatePrivateBinderName(name)
        val normalizedDescription = validatePrivateBinderDescription(description)
        val normalizedCover = validatePrivateBinderCover(coverStyle)
        val queued = database.withTransaction {
            val existing = requireActivePrivateBinder(binderId, ownerUid)
            val changed = existing.copy(
                name = normalizedName,
                description = normalizedDescription,
                coverStyle = normalizedCover,
                updatedAtEpochMillis = System.currentTimeMillis(),
                syncState = syncStateFor(ownerUid),
            )
            dao.updatePrivateBinder(changed)
            queuePrivateBinderMutation(changed, PRIVATE_SYNC_UPSERT)
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun setPrivateBinderCoverTheme(binderId: String, coverThemeId: String) {
        val ownerUid = activeOwnerUid.value
        val normalizedThemeId = validatePrivateBinderAppearanceId(coverThemeId, "Der Binderstil")
        val queued = database.withTransaction {
            val existing = requireActivePrivateBinder(binderId, ownerUid)
            if (existing.coverStyle == normalizedThemeId) return@withTransaction false
            val changed = existing.copy(
                coverStyle = normalizedThemeId,
                updatedAtEpochMillis = System.currentTimeMillis(),
                syncState = syncStateFor(ownerUid),
            )
            dao.updatePrivateBinder(changed)
            queuePrivateBinderMutation(changed, PRIVATE_SYNC_UPSERT)
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun setPrivateBinderMinimumPageCount(binderId: String, minimumPageCount: Int) {
        require(minimumPageCount >= 1) { "Ein privater Binder muss mindestens eine Seite haben." }
        val ownerUid = activeOwnerUid.value
        val queued = database.withTransaction {
            val existing = requireActivePrivateBinder(binderId, ownerUid)
            if (existing.minimumPageCount == minimumPageCount) return@withTransaction false
            val changed = existing.copy(
                minimumPageCount = minimumPageCount,
                updatedAtEpochMillis = System.currentTimeMillis(),
                syncState = syncStateFor(ownerUid),
            )
            dao.updatePrivateBinder(changed)
            queuePrivateBinderMutation(changed, PRIVATE_SYNC_UPSERT)
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun markPrivateBinderOpened(binderId: String) {
        val ownerUid = activeOwnerUid.value
        val queued = database.withTransaction {
            val existing = requireActivePrivateBinder(binderId, ownerUid)
            val now = System.currentTimeMillis()
            val changed = existing.copy(
                updatedAtEpochMillis = now,
                lastOpenedAtEpochMillis = now,
                syncState = syncStateFor(ownerUid),
            )
            dao.updatePrivateBinder(changed)
            queuePrivateBinderMutation(changed, PRIVATE_SYNC_UPSERT)
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun deletePrivateBinder(binderId: String) {
        val ownerUid = activeOwnerUid.value
        var queued = false
        database.withTransaction {
            val binder = requireActivePrivateBinder(binderId, ownerUid)
            val now = System.currentTimeMillis()
            dao.activePrivateBinderPlacements(binderId, ownerUid).forEach { placement ->
                val tombstone = placement.copy(
                    updatedAtEpochMillis = now,
                    syncState = syncStateFor(ownerUid),
                    deletedAtEpochMillis = now,
                )
                dao.updatePrivateBinderPlacement(tombstone)
                queued = queuePrivateBinderPlacementMutation(tombstone, PRIVATE_SYNC_DELETE) || queued
            }
            val tombstone = binder.copy(
                updatedAtEpochMillis = now,
                syncState = syncStateFor(ownerUid),
                deletedAtEpochMillis = now,
            )
            dao.updatePrivateBinder(tombstone)
            queued = queuePrivateBinderMutation(tombstone, PRIVATE_SYNC_DELETE) || queued
        }
        if (queued) onSyncNeeded()
    }

    override fun privateBinderContents(binderId: String): Flow<List<PrivateBinderCard>> =
        activeOwnerUid.flatMapLatest { ownerUid ->
            // Keep each card's real history-derived change, but skip the unused whole-portfolio chart.
            combine(dao.observePrivateBinderPlacements(binderId, ownerUid), portfolioFor(ownerUid, includePortfolioHistory = false)) { placements, currentPortfolio ->
                val cardsByEntry = currentPortfolio.cards.associateBy(CollectionCard::entryId)
                placements.mapNotNull { placement ->
                    cardsByEntry[placement.collectionEntryId]?.let { card ->
                        PrivateBinderCard(
                            placementId = placement.id,
                            binderId = placement.binderId,
                            position = placement.slotPosition,
                            card = card,
                            holoEffectId = placement.holoEffectId,
                            holoIntensity = placement.holoIntensity,
                        )
                    }
                }
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun assignToPrivateBinder(binderId: String, entryId: Long, position: Long?): String {
        val ownerUid = activeOwnerUid.value
        val assignment = database.withTransaction {
            val binder = requireActivePrivateBinder(binderId, ownerUid)
            val entry = dao.getCollectionEntry(entryId, ownerUid)
                ?.takeIf { it.deletedAtEpochMillis == null }
                ?: throw IllegalArgumentException("Die Karte ist nicht in dieser Sammlung verfügbar.")
            assignToPrivateBinderInTransaction(binder, entry, position, System.currentTimeMillis())
        }
        if (assignment.syncQueued) onSyncNeeded()
        return assignment.placementId
    }

    override suspend fun unassignFromPrivateBinder(binderId: String, entryId: Long): Boolean {
        val ownerUid = activeOwnerUid.value
        var queued = false
        val removed = database.withTransaction {
            requireActivePrivateBinder(binderId, ownerUid)
            val placement = dao.privateBinderPlacementForEntry(binderId, entryId, ownerUid)
                ?.takeIf { it.deletedAtEpochMillis == null }
                ?: return@withTransaction false
            val now = System.currentTimeMillis()
            val tombstone = placement.copy(
                updatedAtEpochMillis = now,
                syncState = syncStateFor(ownerUid),
                deletedAtEpochMillis = now,
            )
            dao.updatePrivateBinderPlacement(tombstone)
            queued = queuePrivateBinderPlacementMutation(tombstone, PRIVATE_SYNC_DELETE)
            true
        }
        if (queued) onSyncNeeded()
        return removed
    }

    override suspend fun movePrivateBinderCard(binderId: String, placementId: String, targetPosition: Long) {
        require(targetPosition >= 0L) { "Die Zielposition muss innerhalb des Binders liegen." }
        val ownerUid = activeOwnerUid.value
        var queued = false
        database.withTransaction {
            requireActivePrivateBinder(binderId, ownerUid)
            val moving = dao.privateBinderPlacement(placementId, ownerUid)
                ?.takeIf { it.binderId == binderId && it.deletedAtEpochMillis == null }
                ?: throw IllegalArgumentException("Die Binderposition ist nicht verfügbar.")
            if (moving.slotPosition == targetPosition) return@withTransaction
            val now = System.currentTimeMillis()
            val occupant = dao.privateBinderPlacementAt(binderId, targetPosition, ownerUid)
                ?.takeIf { it.id != moving.id }
            if (occupant != null) {
                val swapped = occupant.copy(
                    slotPosition = moving.slotPosition,
                    updatedAtEpochMillis = now,
                    syncState = syncStateFor(ownerUid),
                )
                dao.updatePrivateBinderPlacement(swapped)
                queued = queuePrivateBinderPlacementMutation(swapped, PRIVATE_SYNC_UPSERT) || queued
            }
            val moved = moving.copy(
                slotPosition = targetPosition,
                updatedAtEpochMillis = now,
                syncState = syncStateFor(ownerUid),
            )
            dao.updatePrivateBinderPlacement(moved)
            queued = queuePrivateBinderPlacementMutation(moved, PRIVATE_SYNC_UPSERT) || queued
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun setPrivateBinderPlacementHolo(
        binderId: String,
        placementId: String,
        holoEffectId: String?,
        holoIntensity: Float,
    ) {
        val normalizedEffectId = holoEffectId?.trim()?.ifBlank { null }?.let {
            validatePrivateBinderAppearanceId(it, "Der Holo-Effekt")
        }
        require(holoIntensity.isFinite() && holoIntensity in 0f..1f) {
            "Die Holo-Intensität muss zwischen 0 und 1 liegen."
        }
        val normalizedIntensity = if (normalizedEffectId == null) 0f else holoIntensity
        val ownerUid = activeOwnerUid.value
        val queued = database.withTransaction {
            requireActivePrivateBinder(binderId, ownerUid)
            val existing = dao.privateBinderPlacement(placementId, ownerUid)
                ?.takeIf { it.binderId == binderId && it.deletedAtEpochMillis == null }
                ?: throw IllegalArgumentException("Die Binderposition ist nicht verfügbar.")
            if (existing.holoEffectId == normalizedEffectId && existing.holoIntensity == normalizedIntensity) {
                return@withTransaction false
            }
            val changed = existing.copy(
                holoEffectId = normalizedEffectId,
                holoIntensity = normalizedIntensity,
                updatedAtEpochMillis = System.currentTimeMillis(),
                syncState = syncStateFor(ownerUid),
            )
            dao.updatePrivateBinderPlacement(changed)
            queuePrivateBinderPlacementMutation(changed, PRIVATE_SYNC_UPSERT)
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun setVisibleInSetBinder(entryId: Long, visible: Boolean) {
        val ownerUid = activeOwnerUid.value
        val queued = database.withTransaction {
            val entry = dao.getCollectionEntry(entryId, ownerUid)
                ?.takeIf { it.deletedAtEpochMillis == null }
                ?: throw IllegalArgumentException("Die Karte ist nicht in dieser Sammlung verfügbar.")
            setVisibleInSetBinderInTransaction(entry, visible, System.currentTimeMillis())
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun applyBinderTargetSelection(entryId: Long, target: BinderTarget) {
        val ownerUid = activeOwnerUid.value
        var queued = false
        database.withTransaction {
            val entry = dao.getCollectionEntry(entryId, ownerUid)
                ?.takeIf { it.deletedAtEpochMillis == null }
                ?: throw IllegalArgumentException("Die Karte ist nicht in dieser Sammlung verfügbar.")
            val now = System.currentTimeMillis()
            when (target) {
                BinderTarget.MatchingSetBinder -> {
                    queued = setVisibleInSetBinderInTransaction(entry, visible = true, now) || queued
                }
                is BinderTarget.PrivateBinder -> {
                    val binder = requireActivePrivateBinder(target.binderId, ownerUid)
                    val assignment = assignToPrivateBinderInTransaction(binder, entry, null, now)
                    queued = assignment.syncQueued || queued
                    queued = setVisibleInSetBinderInTransaction(entry, visible = false, now) || queued
                }
            }
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun claimGuestCollectionForCurrentUser(): Int {
        val ownerUid = activeOwnerUid.value
        require(ownerUid != GUEST_OWNER_UID) { "Für die Übernahme ist eine Anmeldung erforderlich." }
        require(verifyGuestClaimOwner() == ownerUid && activeOwnerUid.value == ownerUid) { "Die aktuelle Kontofreigabe konnte nicht bestätigt werden." }
        var claimed = 0
        var privateStateClaimed = false
        database.withTransaction {
            check(activeOwnerUid.value == ownerUid) { "Konto während der Übernahme gewechselt." }
            val now = System.currentTimeMillis()
            dao.activeGuestEntries().forEach { guest ->
                val projection = dao.collectionSyncProjection(GUEST_OWNER_UID, guest.syncId)
                    ?: error("Ein lokaler Sammlungseintrag ist unvollständig und wurde nicht verändert.")
                val guestSource = sourceIdentity(guest.sourceSetId, guest.sourceCardId)
                val deterministicSyncId = when {
                    guestSource != null -> deterministicSourceSyncId(
                        ownerUid,
                        guestSource,
                        guest.condition,
                        projection.finish,
                        projection.variant,
                    )
                    guest.externalProductSource == com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE &&
                        guest.externalProductId != null && guest.externalProductId > 0L -> deterministicCardmarketSyncId(
                            ownerUid,
                            guest.externalProductId,
                            guest.condition,
                            projection.finish,
                            projection.variant,
                        )
                    else -> null
                }
                val syncId = deterministicSyncId?.let { nextAvailableSyncId(ownerUid, it) }
                    ?: UUID.randomUUID().toString()
                val candidate = projection.copy(ownerUid = ownerUid, syncId = syncId)
                val candidateMutation = CollectionSyncOutboxEntity(
                    entryKey = "$ownerUid:$syncId",
                    mutationId = UUID.randomUUID().toString(),
                    ownerUid = ownerUid,
                    syncId = syncId,
                    operation = "UPSERT",
                    expectedRemoteRevision = 0L,
                    createdAtEpochMillis = now,
                )
                com.dominik.cardvault.data.sync.CollectionSyncLimits.requireValid(candidate, candidateMutation)
                if (dao.claimGuestEntry(guest.id, ownerUid, syncId, now) == 1) {
                    val claimedEntry = guest.copy(
                        ownerUid = ownerUid,
                        syncId = syncId,
                        syncState = SYNC_PENDING,
                        remoteRevision = 0L,
                        updatedAtEpochMillis = now,
                    )
                    queueMutation(claimedEntry, "UPSERT")
                    claimed++
                }
            }

            // Private layouts reference the stable local collection primary key, so ownership can
            // be transferred only after every guest collection row received its authenticated
            // sync id. No quantity or set-binder identity is changed here.
            dao.activeGuestPrivateBinders().forEach { guestBinder ->
                val claimedBinder = guestBinder.copy(
                    ownerUid = ownerUid,
                    updatedAtEpochMillis = now,
                    syncState = SYNC_PENDING,
                    remoteRevision = 0L,
                )
                dao.upsertPrivateBinder(claimedBinder)
                privateStateClaimed = queuePrivateBinderMutation(claimedBinder, PRIVATE_SYNC_UPSERT) || privateStateClaimed
            }
            dao.activeGuestPrivateBinderPlacements().forEach { guestPlacement ->
                val entry = dao.getCollectionEntry(guestPlacement.collectionEntryId, ownerUid)
                    ?: return@forEach
                val claimedPlacement = guestPlacement.copy(
                    ownerUid = ownerUid,
                    updatedAtEpochMillis = now,
                    syncState = SYNC_PENDING,
                    remoteRevision = 0L,
                )
                dao.upsertPrivateBinderPlacement(claimedPlacement)
                privateStateClaimed = queuePrivateBinderPlacementMutation(claimedPlacement, PRIVATE_SYNC_UPSERT) || privateStateClaimed
                check(entry.ownerUid == ownerUid)
            }
            dao.guestCollectionLayouts().forEach { guestLayout ->
                val entry = dao.getCollectionEntry(guestLayout.collectionEntryId, ownerUid)
                    ?: return@forEach
                val claimedLayout = guestLayout.copy(
                    ownerUid = ownerUid,
                    updatedAtEpochMillis = now,
                    syncState = SYNC_PENDING,
                    remoteRevision = 0L,
                )
                dao.updateCollectionLayout(claimedLayout)
                privateStateClaimed = queueCollectionLayoutMutation(claimedLayout, entry.syncId) || privateStateClaimed
            }
            dao.priceSelections(GUEST_OWNER_UID).forEach { guestSelection ->
                val entry = dao.getCollectionEntry(guestSelection.collectionEntryId, ownerUid) ?: return@forEach
                val claimedSelection = guestSelection.copy(ownerUid = ownerUid, entrySyncId = entry.syncId,
                    updatedAtEpochMillis = now, syncState = SYNC_PENDING, remoteRevision = 0L)
                dao.upsertPriceSelection(claimedSelection)
                privateStateClaimed = queuePriceSelectionMutation(claimedSelection) || privateStateClaimed
            }
            dao.deleteGuestPrivateBinderPlacements()
            dao.deleteGuestPrivateBinders()
            check(activeOwnerUid.value == ownerUid) { "Konto während der Übernahme gewechselt." }
        }
        if (claimed > 0 || privateStateClaimed) onSyncNeeded()
        return claimed
    }

    override suspend fun retryConflictsKeepingLocal(): Int {
        val ownerUid = activeOwnerUid.value
        if (ownerUid == GUEST_OWNER_UID) return 0
        var retried = 0
        database.withTransaction {
            val now = System.currentTimeMillis()
            dao.conflictedSyncOutbox(ownerUid).forEach { conflict ->
                val remoteRevision = com.dominik.cardvault.data.sync.parseRemoteConflictRevision(conflict.lastError)
                    ?: return@forEach
                // A delete-versus-update conflict is irreversible. This explicit local-wins
                // shortcut intentionally handles edits only; tombstones stay blocked for a
                // future dedicated decision that can show both sides.
                val localEntry = dao.getCollectionEntryBySyncId(ownerUid, conflict.syncId)
                    ?: return@forEach
                if (localEntry.deletedAtEpochMillis != null) return@forEach
                val rebased = dao.rebaseSyncConflict(
                    entryKey = conflict.entryKey,
                    ownerUid = ownerUid,
                    previousMutationId = conflict.mutationId,
                    newMutationId = UUID.randomUUID().toString(),
                    remoteRevision = remoteRevision,
                    createdAt = now,
                )
                if (rebased == 1) {
                    dao.markEntrySynced(ownerUid, conflict.syncId, remoteRevision, SYNC_PENDING)
                    retried++
                }
            }
        }
        if (retried > 0) onSyncNeeded()
        return retried
    }

    override suspend fun keepCloudVersionsForConflicts(): Int = onKeepCloudConflicts()

    override suspend fun retrySyncErrors(): Int {
        val ownerUid = activeOwnerUid.value
        if (ownerUid == GUEST_OWNER_UID) return 0
        var retried = 0
        database.withTransaction {
            val now = System.currentTimeMillis()
            dao.erroredSyncOutbox(ownerUid).forEach { failure ->
                val localEntry = dao.getCollectionEntryBySyncId(ownerUid, failure.syncId)
                    ?: return@forEach
                val changed = dao.retrySyncError(
                    entryKey = failure.entryKey,
                    ownerUid = ownerUid,
                    previousMutationId = failure.mutationId,
                    newMutationId = UUID.randomUUID().toString(),
                    createdAt = now,
                )
                if (changed == 1) {
                    dao.markEntrySynced(ownerUid, failure.syncId, localEntry.remoteRevision, SYNC_PENDING)
                    retried++
                }
            }
        }
        if (retried > 0) onSyncNeeded()
        return retried
    }
    override suspend fun binderReferences(setName: String): List<CollectionCard> =
        (dao.germanMasterSetReferences(setName).ifEmpty {
            dao.catalogReferencesForSet(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, setName)
        }).map { row ->
            // Cardmarket's compact download sometimes omits a structured finish.
            // Recognise only an explicitly labelled Holo/Reverse product name;
            // everything else remains a normal source slot rather than guessed foil.
            val sourceBacked = row.cardId.startsWith("tcgdex-${row.setId}-", ignoreCase = true)
            val sourceFinish = if (sourceBacked) row.finish else when {
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
                sourceSetId = row.setId.takeIf { sourceBacked },
                sourceCardId = sourceCardIdFromCatalogReference(row.cardId, row.setId, row.collectorNumber),
            )
        }
    override suspend fun binderReferenceCount(setName: String): Int =
        // Market products are price references, not German finish/variant slots.
        dao.germanMasterSetReferenceCount(setName)

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
        val input = query.trim()
        if (input.length < 2) return emptyList()
        // Printed German names such as "Mega-Camerupt-ex" often use dashes,
        // while Cardmarket stores the same product as "Mega Camerupt ex".
        // The DAO performs a LIKE query, so preserve the meaningful name parts
        // but let punctuation bridge formatting differences.
        val lookup = input.replace(Regex("[^\\p{L}\\p{N}]+"), "%").trim('%')
        return withContext(Dispatchers.IO) {
            ensureCatalogObserver()
            catalogQueries.getOrLoad(lookup, limit) {
                dao.searchCatalog(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, lookup, limit).map {
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
                        holoTrendPriceEur = it.holoMarketValueMinor?.div(100.0),
                        priceAlternatives = it.priceAlternatives(),
                    )
                }
            }
        }
    }
    override suspend fun catalogProducts(productIds: List<Long>): List<CatalogSearchResult> = withContext(Dispatchers.IO) {
        productIds.filter { it > 0L }.distinct().chunked(900).flatMap { batch ->
            dao.catalogProducts(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, batch).map {
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
                    holoTrendPriceEur = it.holoMarketValueMinor?.div(100.0),
                    priceAlternatives = it.priceAlternatives(),
                )
            }
        }
    }

    override suspend fun linkCatalogProduct(entryId: Long, productId: Long?) {
        val ownerUid = activeOwnerUid.value
        val queued = database.withTransaction {
            dao.linkExternalProduct(entryId, productId?.let { com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }, productId, System.currentTimeMillis(), ownerUid, syncStateFor(ownerUid))
            dao.getCollectionEntry(entryId, ownerUid)?.let { queueMutation(it, "UPSERT") } ?: false
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun setPriceSelection(entryId: Long, selection: CardPriceSelection) {
        val owner = activeOwnerUid.value
        val queued = database.withTransaction {
            val entry = dao.getCollectionEntry(entryId, owner)?.takeIf { it.deletedAtEpochMillis == null }
                ?: error("Die Karte ist nicht in dieser Sammlung verfügbar.")
            val result = savePriceSelectionInTransaction(entry, selection, System.currentTimeMillis())
            check(activeOwnerUid.value == owner) { "Konto während der Preiswahl gewechselt." }
            result
        }
        if (queued) onSyncNeeded()
    }

    override suspend fun clearPriceSelection(entryId: Long) {
        val owner = activeOwnerUid.value
        val queued = database.withTransaction {
            val entry = dao.getCollectionEntry(entryId, owner)?.takeIf { it.deletedAtEpochMillis == null }
                ?: error("Die Karte ist nicht in dieser Sammlung verfügbar.")
            val previous = dao.priceSelection(owner, entry.syncId) ?: return@withTransaction false
            val cleared = CollectionPriceSelectionEntity(entry.id, owner, entry.syncId, true,
                updatedAtEpochMillis = System.currentTimeMillis(), syncState = syncStateFor(owner),
                remoteRevision = previous.remoteRevision)
            dao.upsertPriceSelection(cleared)
            check(activeOwnerUid.value == owner) { "Konto während der Preiswahl gewechselt." }
            queuePriceSelectionMutation(cleared)
        }
        if (queued) onSyncNeeded()
    }

    private suspend fun savePriceSelectionInTransaction(entry: CollectionEntryEntity, selection: CardPriceSelection, now: Long): Boolean {
        require(entry.externalProductSource == com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE) {
            "Der Vergleichswert benötigt eine eindeutige Cardmarket-Zuordnung."
        }
        val identity = dao.collectionSyncProjection(entry.ownerUid, entry.syncId)?.priceIdentityCard()
            ?: error("Die Kartenidentität ist nicht verfügbar.")
        require(selection.matches(identity)) { "Der Vergleichswert gehört nicht zu dieser Karte und Variante." }
        val product = dao.catalogProducts(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE,
            listOf(selection.productId)).singleOrNull() ?: error("Das zugeordnete Marktprodukt fehlt.")
        val trend = if (selection.metric.reverse) product.holoMarketValueMinor else product.marketValueMinor
        require(trend == null || trend <= 0L) { "Ein gültiger Trendpreis ist verfügbar; er hat Vorrang." }
        require(product.priceAlternatives().any {
            it.metric == selection.metric && it.amountMinor == selection.amountMinor &&
                it.currency == selection.currency && it.sourceAt == selection.sourceAt
        }) { "Die Preisdaten haben sich geändert. Bitte den Vergleichswert erneut auswählen." }
        val previous = dao.priceSelection(entry.ownerUid, entry.syncId)
        val stored = selection.asEntity(entry, previous?.remoteRevision ?: 0L, now)
        dao.upsertPriceSelection(stored)
        return queuePriceSelectionMutation(stored)
    }

    private suspend fun queuePriceSelectionMutation(selection: CollectionPriceSelectionEntity): Boolean =
        queuePrivateMutation(selection.ownerUid, PRIVATE_SYNC_PRICE_SELECTION, selection.entrySyncId,
            PRIVATE_SYNC_UPSERT, selection.remoteRevision, selection.updatedAtEpochMillis)

    override fun priceHistory(card: CollectionCard): Flow<List<CardPricePoint>> {
        if (com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity(card.sourceCardId ?: card.id, card.externalProductId)) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val usesReverseHoloPrice = card.finish.contains("reverse", ignoreCase = true)
        return activeOwnerUid.flatMapLatest { ownerUid ->
            dao.observeCardPriceHistory(
                entryId = card.entryId,
                fallbackCardId = card.id,
                source = com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE,
                useHoloPrice = usesReverseHoloPrice,
                ownerUid = ownerUid,
            ).map { rows ->
                rows.filter { it.valueMinor > 0L }.map { row ->
                    CardPricePoint(
                        timestampEpochMillis = row.effectiveAtEpochMillis,
                        valueEur = row.valueMinor / 100.0,
                        currency = row.currency,
                        source = row.source,
                    )
                }
            }
        }
    }

    private fun buildHistory(
        rows: List<CollectionProjection>,
        prices: List<OwnedPriceHistoryProjection>,
        cardmarketPrices: Map<String, List<OwnedPriceHistoryProjection>>,
    ): List<PortfolioPoint> =
        prices.map { it.effectiveAtEpochMillis }.distinct().sorted().map { timestamp ->
            val total = rows.sumOf { row ->
                if (com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity(row.sourceCardId ?: row.cardId, row.externalProductId)) return@sumOf 0L
                latestPriceAtOrBefore(cardmarketPrices[row.priceCardId].orEmpty(), timestamp)
                    ?.trendValueFor(row.finish)
                    ?.times(row.quantity)
                    ?: 0L
            }
            PortfolioPoint(java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("MMM")), total / 100.0)
        }

    /** Binary search over the already sorted per-product history. */
    private fun latestPriceAtOrBefore(prices: List<OwnedPriceHistoryProjection>, timestamp: Long): OwnedPriceHistoryProjection? {
        var low = 0
        var high = prices.lastIndex
        var match: OwnedPriceHistoryProjection? = null
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val candidate = prices[middle]
            if (candidate.effectiveAtEpochMillis <= timestamp) {
                match = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return match
    }

    /** Normal/Holo use the product trend; Reverse Holo requires its own source observation. */
    private fun OwnedPriceHistoryProjection.trendValueFor(finish: String): Long? =
        (if (finish.contains("reverse", ignoreCase = true)) holoTrendValueMinor else trendValueMinor)?.takeIf { it > 0L }

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

    private suspend fun requireActivePrivateBinder(binderId: String, ownerUid: String): PrivateBinderEntity =
        dao.privateBinder(binderId, ownerUid)
            ?.takeIf { it.deletedAtEpochMillis == null }
            ?: throw IllegalArgumentException("Der private Binder ist nicht verfügbar.")

    private data class PrivatePlacementAssignment(
        val placementId: String,
        val created: Boolean,
        val syncQueued: Boolean,
    )

    private suspend fun assignToPrivateBinderInTransaction(
        binder: PrivateBinderEntity,
        entry: CollectionEntryEntity,
        requestedPosition: Long?,
        now: Long,
    ): PrivatePlacementAssignment {
        require(requestedPosition == null || requestedPosition >= 0L) {
            "Die Zielposition muss innerhalb des Binders liegen."
        }
        require(binder.ownerUid == entry.ownerUid && binder.deletedAtEpochMillis == null && entry.deletedAtEpochMillis == null) {
            "Binder und Karte gehören nicht zur gleichen aktiven Sammlung."
        }
        val existing = dao.privateBinderPlacementForEntry(binder.id, entry.id, entry.ownerUid)
        if (existing?.deletedAtEpochMillis == null && existing != null && requestedPosition == null) {
            return PrivatePlacementAssignment(existing.id, created = false, syncQueued = false)
        }
        val active = dao.activePrivateBinderPlacements(binder.id, entry.ownerUid)
        val requestedIsFree = requestedPosition != null && active.none {
            it.id != existing?.id && it.slotPosition == requestedPosition
        }
        val position = when {
            requestedIsFree -> requestedPosition!!
            existing != null && existing.slotPosition >= 0L && active.none {
                it.id != existing.id && it.slotPosition == existing.slotPosition
            } -> existing.slotPosition
            else -> firstFreePrivateBinderPosition(active, existing?.id)
        }
        // Firestore tombstones are immutable. Reassigning a card after removal therefore creates
        // a new placement generation instead of trying to resurrect the prior document.
        val createsSuccessor = existing?.deletedAtEpochMillis != null
        val placementId = when {
            createsSuccessor -> UUID.randomUUID().toString()
            existing != null -> existing.id
            entry.ownerUid == GUEST_OWNER_UID -> UUID.randomUUID().toString()
            else -> deterministicPrivatePlacementId(entry.ownerUid, binder.id, entry.syncId)
        }
        val placement = PrivateBinderPlacementEntity(
            id = placementId,
            ownerUid = entry.ownerUid,
            binderId = binder.id,
            collectionEntryId = entry.id,
            slotPosition = position,
            createdAtEpochMillis = existing?.createdAtEpochMillis?.takeUnless { createsSuccessor } ?: now,
            updatedAtEpochMillis = now,
            syncState = syncStateFor(entry.ownerUid),
            deletedAtEpochMillis = null,
        )
        if (existing == null || createsSuccessor) dao.insertPrivateBinderPlacement(placement)
        else dao.updatePrivateBinderPlacement(placement)
        return PrivatePlacementAssignment(
            placementId = placementId,
            created = existing?.deletedAtEpochMillis != null || existing == null,
            syncQueued = queuePrivateBinderPlacementMutation(placement, PRIVATE_SYNC_UPSERT),
        )
    }

    private fun firstFreePrivateBinderPosition(
        placements: List<PrivateBinderPlacementEntity>,
        excludingPlacementId: String? = null,
    ): Long {
        val used = placements.asSequence()
            .filter { it.id != excludingPlacementId && it.deletedAtEpochMillis == null && it.slotPosition >= 0L }
            .map(PrivateBinderPlacementEntity::slotPosition)
            .toHashSet()
        var candidate = 0L
        while (candidate in used) candidate++
        return candidate
    }

    private suspend fun queuePrivateBinderMutation(binder: PrivateBinderEntity, operation: String): Boolean =
        queuePrivateMutation(
            ownerUid = binder.ownerUid,
            recordType = PRIVATE_SYNC_BINDER,
            recordId = binder.id,
            operation = operation,
            expectedRemoteRevision = binder.remoteRevision,
            updatedAtEpochMillis = binder.updatedAtEpochMillis,
        )

    private suspend fun queuePrivateBinderPlacementMutation(
        placement: PrivateBinderPlacementEntity,
        operation: String,
    ): Boolean = queuePrivateMutation(
        ownerUid = placement.ownerUid,
        recordType = PRIVATE_SYNC_PLACEMENT,
        recordId = placement.id,
        operation = operation,
        expectedRemoteRevision = placement.remoteRevision,
        updatedAtEpochMillis = placement.updatedAtEpochMillis,
    )

    private suspend fun queueCollectionLayoutMutation(
        layout: CollectionLayoutEntity,
        collectionEntrySyncId: String,
    ): Boolean = queuePrivateMutation(
        ownerUid = layout.ownerUid,
        recordType = PRIVATE_SYNC_LAYOUT,
        recordId = collectionEntrySyncId,
        operation = PRIVATE_SYNC_UPSERT,
        expectedRemoteRevision = layout.remoteRevision,
        updatedAtEpochMillis = layout.updatedAtEpochMillis,
    )

    /** Keeps the last acknowledged revision so the next outbox item uses the correct CAS base. */
    private suspend fun setVisibleInSetBinderInTransaction(
        entry: CollectionEntryEntity,
        visible: Boolean,
        now: Long,
    ): Boolean {
        val existing = dao.collectionLayout(entry.id, entry.ownerUid)
        if (existing?.visibleInSetBinder == visible || (existing == null && visible)) return false
        val layout = existing?.copy(
            visibleInSetBinder = visible,
            updatedAtEpochMillis = now,
            syncState = syncStateFor(entry.ownerUid),
        ) ?: CollectionLayoutEntity(
            collectionEntryId = entry.id,
            ownerUid = entry.ownerUid,
            visibleInSetBinder = visible,
            updatedAtEpochMillis = now,
            syncState = syncStateFor(entry.ownerUid),
        )
        dao.upsertCollectionLayout(layout)
        return queueCollectionLayoutMutation(layout, entry.syncId)
    }

    private suspend fun queuePrivateMutation(
        ownerUid: String,
        recordType: String,
        recordId: String,
        operation: String,
        expectedRemoteRevision: Long,
        updatedAtEpochMillis: Long,
    ): Boolean {
        if (ownerUid == GUEST_OWNER_UID) return false
        dao.upsertPrivateBinderSyncOutbox(
            PrivateBinderSyncOutboxEntity(
                entryKey = "$ownerUid:$recordType:$recordId",
                mutationId = UUID.randomUUID().toString(),
                ownerUid = ownerUid,
                recordType = recordType,
                recordId = recordId,
                operation = operation,
                expectedRemoteRevision = expectedRemoteRevision,
                updatedAtEpochMillis = updatedAtEpochMillis,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        return true
    }

    private fun validatePrivateBinderName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 120) { "Der Bindername muss 1 bis 120 Zeichen lang sein." }
    }

    private fun validatePrivateBinderDescription(value: String?): String? = value?.trim()?.ifBlank { null }.also {
        require(it == null || it.length <= 1_000) { "Die Binderbeschreibung darf höchstens 1000 Zeichen lang sein." }
    }

    private fun validatePrivateBinderCover(value: String): String = value.trim().ifBlank { "DOMIDEX" }.also {
        require(it.length <= 80) { "Der Binderstil darf höchstens 80 Zeichen lang sein." }
    }

    private fun validatePrivateBinderAppearanceId(value: String, label: String): String = value.trim().also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) {
            "$label muss eine stabile ID aus Buchstaben, Zahlen, Punkt, Unterstrich oder Bindestrich sein."
        }
    }

    private fun validateManualInputForSync(input: ManualCardInput) {
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Kartenname", input.name.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.NAME, required = true)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Setname", input.setName.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.SET_NAME, required = true)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Kartennummer", input.collectorNumber.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.COLLECTOR_NUMBER)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Finish", input.finish.trim().ifBlank { "Normal" }, com.dominik.cardvault.data.sync.CollectionSyncLimits.FINISH)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Variante", input.variant.trim().ifBlank { "Standard" }, com.dominik.cardvault.data.sync.CollectionSyncLimits.VARIANT)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Zustand", input.condition.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.CONDITION)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Notiz", input.notes.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.NOTES)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Bild-URL", input.imageUrl, com.dominik.cardvault.data.sync.CollectionSyncLimits.URL)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Seltenheit", input.rarity, com.dominik.cardvault.data.sync.CollectionSyncLimits.RARITY)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Effekt", input.effectOverride, com.dominik.cardvault.data.sync.CollectionSyncLimits.EFFECT)
        sourceIdentity(input.sourceSetId, input.sourceCardId)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireQuantity(input.quantity)
    }

    private fun validateEntryUpdateForSync(update: CollectionEntryUpdate) {
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireQuantity(update.quantity)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Zustand", update.condition.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.CONDITION)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Notiz", update.notes.trim(), com.dominik.cardvault.data.sync.CollectionSyncLimits.NOTES)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Finish", update.finish.trim().ifBlank { "Normal" }, com.dominik.cardvault.data.sync.CollectionSyncLimits.FINISH)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Variante", update.variant.trim().ifBlank { "Standard" }, com.dominik.cardvault.data.sync.CollectionSyncLimits.VARIANT)
        com.dominik.cardvault.data.sync.CollectionSyncLimits.requireText("Effekt", update.effectOverride, com.dominik.cardvault.data.sync.CollectionSyncLimits.EFFECT)
    }

    private fun syncStateFor(ownerUid: String): String =
        if (ownerUid == GUEST_OWNER_UID) SYNC_LOCAL_ONLY else SYNC_PENDING

    private suspend fun nextAvailableSyncId(ownerUid: String, initial: String): String {
        var candidate = initial
        repeat(64) {
            val occupied = dao.getCollectionEntryBySyncId(ownerUid, candidate) ?: return candidate
            candidate = deterministicSuccessorSyncId(candidate, occupied)
        }
        error("Für diese Kartenidentität konnten zu viele historische Generationen gefunden werden.")
    }

    private suspend fun queueMutation(entry: CollectionEntryEntity, operation: String): Boolean {
        if (entry.ownerUid == GUEST_OWNER_UID) return false
        dao.upsertSyncOutbox(
            CollectionSyncOutboxEntity(
                entryKey = "${entry.ownerUid}:${entry.syncId}",
                mutationId = UUID.randomUUID().toString(),
                ownerUid = entry.ownerUid,
                syncId = entry.syncId,
                operation = operation,
                expectedRemoteRevision = entry.remoteRevision,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        return true
    }
}

/** Room retains this small wrapper, but never the repository or its cached result lists. */
private class CatalogQueryObserver(
    private val tracker: InvalidationTracker,
    cache: CatalogQueryCache<CatalogSearchResult>,
) : InvalidationTracker.Observer("catalog_cards", "raw_price_snapshots") {
    private val cacheReference = WeakReference(cache)

    override fun onInvalidated(tables: Set<String>) {
        val cache = cacheReference.get()
        if (cache == null) tracker.removeObserver(this) else cache.invalidate()
    }
}

/** Keeps Binder identity independent from translated or differently formatted set names. */
internal fun binderSetKey(cardmarketExpansionId: String?, displaySetName: String, sourceSetId: String? = null): String {
    // Cardmarket expansion ids are numeric. Rejecting arbitrary labels here
    // prevents an old display name from accidentally being stored as an ID.
    val expansionId = cardmarketExpansionId?.trim()?.takeIf { it.all(Char::isDigit) }
    val sourceId = sourceSetId?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9._-]{1,120}")) }
    val raw = sourceId?.let { "tcgdex-$it" }
        ?: expansionId?.let { "cardmarket-$it" }
        ?: displaySetName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "inbox" }
    if (raw.length <= com.dominik.cardvault.data.sync.CollectionSyncLimits.SET_ID) return raw
    val suffix = UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8)).toString().take(12)
    val prefixLength = com.dominik.cardvault.data.sync.CollectionSyncLimits.SET_ID - suffix.length - 1
    return "${raw.take(prefixLength).trimEnd('-')}-$suffix"
}

internal fun binderIdForSetKey(setKey: String): String =
    if (setKey.startsWith("cardmarket-") || setKey.startsWith("tcgdex-")) setKey else "set-$setKey"

internal data class SourceCardIdentity(val setId: String, val cardId: String)

/** Both source identifiers are required together; partial or malformed identity is rejected. */
internal fun sourceIdentity(sourceSetId: String?, sourceCardId: String?): SourceCardIdentity? {
    if (sourceSetId == null && sourceCardId == null) return null
    require(!sourceSetId.isNullOrBlank() && !sourceCardId.isNullOrBlank()) {
        "Set- und Karten-ID der Quelle müssen gemeinsam vorhanden sein."
    }
    val setId = sourceSetId.trim().lowercase()
    val cardId = sourceCardId.trim().lowercase()
    val allowed = Regex("[a-z0-9._-]{1,120}")
    require(setId.matches(allowed) && cardId.matches(allowed)) { "Die Quellenidentität ist ungültig." }
    return SourceCardIdentity(setId, cardId)
}

internal fun sourceCatalogCardId(source: SourceCardIdentity): String =
    "tcgdex-${source.setId}-${source.cardId}"

/** Recovers the canonical card id from source-backed cached master-set print slots. */
internal fun sourceCardIdFromCatalogReference(catalogCardId: String, sourceSetId: String, collectorNumber: String? = null): String? {
    val prefix = "tcgdex-${sourceSetId.lowercase()}-"
    if (!catalogCardId.lowercase().startsWith(prefix)) return null
    val encoded = catalogCardId.substring(prefix.length)
    // Modern source printing IDs are not limited to the legacy foil suffixes.
    // Decode only a stem confirmed by this cached row's own set and number;
    // this changes no stored collection entry or source identity.
    val stem = Regex("^(${Regex.escape(sourceSetId)}-([a-z0-9]+))-.+$", RegexOption.IGNORE_CASE).matchEntire(encoded)
    fun numberKey(value: String) = value.substringBefore('/').filter(Char::isLetterOrDigit).trimStart('0').lowercase()
    if (stem != null && !collectorNumber.isNullOrBlank() && stem.groupValues[2].any(Char::isDigit)) {
        val sourceLocalId = numberKey(stem.groupValues[2])
        val collectorConfirmsStem = sourceLocalId == numberKey(collectorNumber)
        val classicAliasConfirmsStem = sourceSetId.equals("30th-c", ignoreCase = true) &&
            classic30thLocalIdForPrintedCollector(collectorNumber) == sourceLocalId
        if (collectorConfirmsStem || classicAliasConfirmsStem) return stem.groupValues[1]
    }
    val printingSuffixes = listOf(
        "-1steditionholofoil", "-unlimitedholofoil", "-reverseholofoil",
        "-1stedition", "-unlimited", "-holofoil",
    )
    return printingSuffixes.firstOrNull { encoded.endsWith(it, ignoreCase = true) }
        ?.let { encoded.dropLast(it.length) }
        ?.ifBlank { null }
        ?: encoded.ifBlank { null }
}

/**
 * Same owner + physical source printing + collection variant maps to one cloud document.
 * Concurrent offline adds therefore conflict visibly instead of creating silent duplicate rows.
 */
internal fun deterministicSourceSyncId(
    ownerUid: String,
    source: SourceCardIdentity,
    condition: String,
    finish: String,
    variant: String,
): String {
    val key = listOf(ownerUid, source.setId, source.cardId, condition, finish, variant)
        .joinToString("|") { it.trim().lowercase() }
    return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}

internal fun deterministicCardmarketSyncId(
    ownerUid: String,
    externalProductId: Long,
    condition: String,
    finish: String,
    variant: String,
): String {
    require(externalProductId > 0L) { "Cardmarket-Produkt-ID ist ungültig." }
    val key = listOf(ownerUid, "cardmarket", externalProductId.toString(), condition, finish, variant)
        .joinToString("|") { it.trim().lowercase() }
    return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}

internal fun deterministicSuccessorSyncId(
    previousSyncId: String,
    occupied: CollectionEntryEntity,
): String {
    val generationKey = listOf(
        previousSyncId,
        occupied.remoteRevision.toString(),
        if (occupied.deletedAtEpochMillis == null) "active-branch" else "readd-after-delete",
    ).joinToString("|")
    return UUID.nameUUIDFromBytes(generationKey.toByteArray(Charsets.UTF_8)).toString()
}
