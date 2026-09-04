package com.dominik.cardvault.data

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.dominik.cardvault.data.local.*
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
) : CollectionRepository {
    private val dao = database.cardVaultDao()
    private val catalogQueries = CatalogQueryCache<CatalogSearchResult>()
    private val catalogObserverMutex = Mutex()
    private var catalogObserverRegistered = false

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
        dao.observeBinders(ownerUid).map { rows -> rows.map { row ->
            BinderSummary(row.id, row.name, row.heroAsset, row.uniqueCards, row.ownedCards, (row.marketValueMinor ?: 0L) / 100.0, row.lastPriceUpdatedAtEpochMillis)
        } }
    }

    override val portfolio: Flow<PortfolioSummary> = activeOwnerUid.flatMapLatest { ownerUid ->
        combine(
            dao.observeCollection(ownerUid),
            dao.observeOwnedRawPriceHistory(ownerUid, com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE),
        ) { rows, prices ->
        // One indexed view replaces repeated full price-table scans for every card and every
        // history timestamp. Room already emits immutable snapshots, so this cache is local to
        // the current calculation and can never become stale.
        val cardmarketPrices = prices.asSequence()
            .filter { it.source == com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE }
            .groupBy(RawPriceSnapshotEntity::cardId)
            .mapValues { (_, values) -> values.sortedBy(RawPriceSnapshotEntity::effectiveAtEpochMillis) }
        val cards = rows.map { row ->
            val cardPrices = cardmarketPrices[row.priceCardId].orEmpty()
            val first = cardPrices.asSequence().mapNotNull { it.trendValueFor(row.finish) }.firstOrNull()
            val latest = row.marketValueMinor
            val change = if (first != null && latest != null && first != 0L) (latest - first) * 100.0 / first else 0.0
            CollectionCard(row.entryId, row.cardId, row.name, row.setName, row.collectorNumber,
                CardLanguage.fromStorage(row.language), row.finish, row.variant, row.condition, row.quantity,
                (latest ?: 0L) / 100.0, change, row.priceUpdatedAtEpochMillis, row.notes,
                row.acquisitionPriceMinor?.div(100.0), row.acquisitionDateEpochDay, accentFor(row.name), imageUrl = row.imageUrl)
                .copy(
                    binderId = row.binderId,
                    externalProductId = row.externalProductId,
                    rarity = row.rarity,
                    effectOverride = row.effectOverride,
                    sourceSetId = row.sourceSetId,
                    sourceCardId = row.sourceCardId,
                )
        }
            PortfolioSummary(cards, buildHistory(rows, prices, cardmarketPrices))
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
        val ownerUid = activeOwnerUid.value
        validateManualInputForSync(input)
        val sourceIdentity = sourceIdentity(input.sourceSetId, input.sourceCardId)
        var scheduleSync = false
        val outcome = database.withTransaction {
            val now = System.currentTimeMillis()
            val normalizedCondition = input.condition.trim()
            val normalizedFinish = input.finish.trim().ifBlank { "Normal" }
            val normalizedVariant = input.variant.trim().ifBlank { "Standard" }
            val amount = input.quantity.coerceAtLeast(1)
            // TCGdex identity is authoritative. Cardmarket remains an independent price link and
            // may appear only after the first scan has already been saved.
            val existing = sourceIdentity?.let { source ->
                dao.findSourceEntry(ownerUid, source.setId, source.cardId, normalizedCondition, normalizedFinish, normalizedVariant)
            } ?: input.externalProductId?.let { productId ->
                dao.findLinkedEntry(com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, productId, normalizedCondition, normalizedFinish, normalizedVariant, ownerUid)
            }
            if (existing != null) {
                val newQuantity = existing.quantity.toLong() + amount.toLong()
                require(newQuantity in 1..com.dominik.cardvault.data.sync.CollectionSyncLimits.QUANTITY.toLong()) {
                    "Die Gesamtanzahl darf ${com.dominik.cardvault.data.sync.CollectionSyncLimits.QUANTITY} nicht überschreiten."
                }
                val syncState = syncStateFor(ownerUid)
                if (existing.externalProductId == null && input.externalProductId != null) {
                    dao.linkExternalProduct(existing.id, com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE, input.externalProductId, now, ownerUid, syncState)
                }
                dao.incrementQuantity(existing.id, amount, now, ownerUid, syncState)
                dao.getCollectionEntry(existing.id, ownerUid)?.let { changed ->
                    scheduleSync = queueMutation(changed, "UPSERT") || scheduleSync
                }
                return@withTransaction AddCardOutcome.QUANTITY_INCREMENTED
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
            AddCardOutcome.CREATED
        }
        if (scheduleSync) onSyncNeeded()
        return outcome
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
        val queued = database.withTransaction {
            val existing = dao.getCollectionEntry(entryId, ownerUid) ?: return@withTransaction false
            if (ownerUid == GUEST_OWNER_UID) {
                dao.deleteCollectionEntry(entryId)
                false
            } else {
                val now = System.currentTimeMillis()
                dao.tombstoneCollectionEntry(entryId, ownerUid, now, SYNC_PENDING)
                queueMutation(existing.copy(deletedAtEpochMillis = now, updatedAtEpochMillis = now, syncState = SYNC_PENDING), "DELETE")
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

    override suspend fun claimGuestCollectionForCurrentUser(): Int {
        val ownerUid = activeOwnerUid.value
        require(ownerUid != GUEST_OWNER_UID) { "Für die Übernahme ist eine Anmeldung erforderlich." }
        var claimed = 0
        database.withTransaction {
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
        }
        if (claimed > 0) onSyncNeeded()
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
        dao.germanMasterSetReferenceCount(setName).takeIf { it > 0 }
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
                    )
                }
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

    override fun priceHistory(card: CollectionCard): Flow<List<CardPricePoint>> {
        val usesHoloPrice = card.finish.contains("holo", ignoreCase = true)
        return activeOwnerUid.flatMapLatest { ownerUid ->
            dao.observeCardPriceHistory(
                entryId = card.entryId,
                fallbackCardId = card.id,
                source = com.dominik.cardvault.data.sync.CardmarketSyncContract.SOURCE,
                useHoloPrice = usesHoloPrice,
                ownerUid = ownerUid,
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
    }

    private fun buildHistory(
        rows: List<CollectionProjection>,
        prices: List<RawPriceSnapshotEntity>,
        cardmarketPrices: Map<String, List<RawPriceSnapshotEntity>>,
    ): List<PortfolioPoint> =
        prices.map { it.effectiveAtEpochMillis }.distinct().sorted().map { timestamp ->
            val total = rows.sumOf { row ->
                latestPriceAtOrBefore(cardmarketPrices[row.priceCardId].orEmpty(), timestamp)
                    ?.trendValueFor(row.finish)
                    ?.times(row.quantity)
                    ?: 0L
            }
            PortfolioPoint(java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("MMM")), total / 100.0)
        }

    /** Binary search over the already sorted per-product history. */
    private fun latestPriceAtOrBefore(prices: List<RawPriceSnapshotEntity>, timestamp: Long): RawPriceSnapshotEntity? {
        var low = 0
        var high = prices.lastIndex
        var match: RawPriceSnapshotEntity? = null
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

    /** The whole portfolio follows the same exact Normal/Holo/Reverse trend as each card row. */
    private fun RawPriceSnapshotEntity.trendValueFor(finish: String): Long? =
        if (finish.contains("holo", ignoreCase = true)) holoTrendValueMinor?.takeIf { it > 0 } ?: trendValueMinor else trendValueMinor

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
    if (stem != null && !collectorNumber.isNullOrBlank() &&
        stem.groupValues[2].any(Char::isDigit) && numberKey(stem.groupValues[2]) == numberKey(collectorNumber)) {
        return stem.groupValues[1]
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
