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
    /** Source-backed Cardmarket lookup name when the German card name differs. */
    val marketSearchName: String? = null,
    /** Transient source-verified Cardmarket expansion bridge for Binder reference slots. */
    val marketExpansionId: String? = null,
    /** Stable TCGdex identity. Null only for cards that did not originate in that catalogue. */
    val sourceSetId: String? = null,
    val sourceCardId: String? = null,
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
    /** Source-backed Cardmarket lookup name, usually the English TCGdex printing name. */
    val marketSearchName: String? = null,
    /** Real Cardmarket Holo trend signal. It stays null when the source has none. */
    val holoTrendPriceEur: Double? = null,
    /** Stable visual-catalog identity; it must survive scanner, Room and Firebase. */
    val sourceSetId: String? = null,
    val sourceCardId: String? = null,
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
data class PrivateBinderSummary(
    val id: String,
    val name: String,
    val description: String?,
    val coverStyle: String,
    /** Durable floor for the reader, independent of the highest occupied slot. */
    val minimumPageCount: Int,
    val uniqueCards: Int,
    val ownedCards: Int,
    val marketValueEur: Double,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long?,
)

data class PrivateBinderCard(
    val placementId: String,
    val binderId: String,
    /** Global stable slot: page = position / 9, slot = position % 9. */
    val position: Long,
    val card: CollectionCard,
    /** Presentation override scoped to this binder placement, never to collection identity. */
    val holoEffectId: String? = null,
    val holoIntensity: Float = 0f,
)

sealed interface BinderTarget {
    /** Existing behaviour: derive the one canonical binder from the card's real set identity. */
    data object MatchingSetBinder : BinderTarget
    data class PrivateBinder(val binderId: String) : BinderTarget
}

data class TargetedAddCardResult(
    val entryId: Long,
    val ownershipCreated: Boolean,
    val quantityIncremented: Boolean,
    val privatePlacementId: String? = null,
    val privateAssignmentCreated: Boolean = false,
)

enum class BinderSortOrder {
    MOST_CARDS,
    PROGRESS_ASCENDING,
    PROGRESS_DESCENDING,
    NAME,
    LAST_OPENED,
}

enum class BinderFilter { ALL, COMPLETE, INCOMPLETE, SET, PRIVATE }

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

data class ManualCardInput(val name: String, val setName: String, val collectorNumber: String, val language: CardLanguage, val finish: String, val variant: String, val quantity: Int, val condition: String, val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?, val externalProductId: Long? = null, val imageUrl: String? = null, val rarity: String? = null, val effectOverride: String? = null,
    /** Stable Cardmarket expansion id. It is used for Binder identity only; the German display name remains unchanged. */
    val cardmarketExpansionId: String? = null,
    /** Stable source identity. Both values are present together or both are null. */
    val sourceSetId: String? = null,
    val sourceCardId: String? = null,
)
data class CollectionEntryUpdate(val quantity: Int, val condition: String, val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?, val finish: String, val variant: String, val effectOverride: String?)
enum class AddCardOutcome { CREATED, QUANTITY_INCREMENTED }

interface CollectionRepository {
    val cloudState: Flow<com.dominik.cardvault.data.sync.CollectionCloudState>
        get() = kotlinx.coroutines.flow.flowOf(com.dominik.cardvault.data.sync.CollectionCloudState(com.dominik.cardvault.data.sync.CollectionCloudStatus.LOCAL_ONLY))
    suspend fun exportUserBackup(settingsJson: String): String = error("Backup wird nicht unterstützt.")
    suspend fun restoreUserBackup(raw: String, journalKey: String? = null): com.dominik.cardvault.data.backup.UserBackupStore.Result = error("Backup wird nicht unterstützt.")
    suspend fun restoreLegacyBackup(raw: String, entries: List<ManualCardInput>, journalKey: String? = null): Int = error("Backup wird nicht unterstützt.")
    val portfolio: Flow<PortfolioSummary>
    val binders: Flow<List<BinderSummary>>
    /** User-created display binders. Their values never feed [portfolio]. */
    val privateBinders: Flow<List<PrivateBinderSummary>>
    /** Missing entries are intentionally interpreted as true for pre-schema-11 collections. */
    val setBinderVisibility: Flow<Map<Long, Boolean>>
    /** Legacy/local rows waiting for an explicit, user-confirmed account transfer. */
    val guestEntryCount: Flow<Int>
    /** True optimistic-lock conflicts for the currently authenticated owner only. */
    val syncConflictCount: Flow<Int>
    /** Terminal configuration/payload failures for the current owner; never data conflicts. */
    val syncErrorCount: Flow<Int>
    val catalogSyncStatus: Flow<CatalogSyncStatus>
    suspend fun seedIfNeeded()
    suspend fun addManualCard(input: ManualCardInput): AddCardOutcome
    /**
     * Atomic destination-aware add. Assigning an already-owned exact printing to a private binder
     * does not increase its quantity; it creates only a display membership.
     */
    suspend fun addManualCard(input: ManualCardInput, target: BinderTarget): TargetedAddCardResult
    suspend fun createPrivateBinder(name: String, description: String? = null, coverStyle: String = "DOMIDEX"): String
    suspend fun updatePrivateBinder(binderId: String, name: String, description: String? = null, coverStyle: String = "DOMIDEX")
    suspend fun setPrivateBinderCoverTheme(binderId: String, coverThemeId: String)
    /** Persists manually added empty pages; values below one are rejected. */
    suspend fun setPrivateBinderMinimumPageCount(binderId: String, minimumPageCount: Int)
    suspend fun markPrivateBinderOpened(binderId: String)
    suspend fun deletePrivateBinder(binderId: String)
    fun privateBinderContents(binderId: String): Flow<List<PrivateBinderCard>>
    suspend fun assignToPrivateBinder(binderId: String, entryId: Long, position: Long? = null): String
    suspend fun unassignFromPrivateBinder(binderId: String, entryId: Long): Boolean
    /** Occupied targets swap with the moving card; page boundaries need no special handling. */
    suspend fun movePrivateBinderCard(binderId: String, placementId: String, targetPosition: Long)
    suspend fun setPrivateBinderPlacementHolo(
        binderId: String,
        placementId: String,
        holoEffectId: String?,
        holoIntensity: Float,
    )
    /**
     * Applies an explicit display destination to an already-owned entry without changing its
     * ownership or quantity. A private target also creates its binder membership when needed;
     * neither target removes existing private-binder memberships.
     */
    suspend fun applyBinderTargetSelection(entryId: Long, target: BinderTarget)
    suspend fun setVisibleInSetBinder(entryId: Long, visible: Boolean)
    suspend fun updateEntry(entryId: Long, update: CollectionEntryUpdate)
    suspend fun deleteEntry(entryId: Long)
    suspend fun deleteBinder(binderId: String)
    /** Explicit only: never invoked automatically by authentication changes. */
    suspend fun claimGuestCollectionForCurrentUser(): Int
    /**
     * Explicit local-wins retry. Only conflicts carrying a validated remote revision are
     * rebased; malformed/auth failures remain untouched and can never be mistaken for data
     * conflicts.
     */
    suspend fun retryConflictsKeepingLocal(): Int
    /** Explicit Cloud-wins resolution; failed server reads leave conflicts untouched. */
    suspend fun keepCloudVersionsForConflicts(): Int
    /** Explicit retry after a configuration/payload error has been corrected. */
    suspend fun retrySyncErrors(): Int
    /** Source-backed, read-only master-set slots for an existing Binder. */
    suspend fun binderReferences(setName: String): List<CollectionCard>
    suspend fun binderReferenceCount(setName: String): Int
    /** Adds read-only German master-set metadata. It never creates collection entries. */
    suspend fun ensureGermanMasterSetBinders(sets: List<GermanMasterSetDefinition>)
    suspend fun cacheGermanMasterSetCards(set: GermanMasterSetDefinition, cards: List<GermanMasterSetCardDefinition>)
    suspend fun clearGeneratedGermanMasterSetBinders()
    suspend fun searchCatalog(query: String, limit: Int = 30): List<CatalogSearchResult>
    /** Exact source-declared Cardmarket ids, independent of names or inferred expansions. */
    suspend fun catalogProducts(productIds: List<Long>): List<CatalogSearchResult> = emptyList()
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
