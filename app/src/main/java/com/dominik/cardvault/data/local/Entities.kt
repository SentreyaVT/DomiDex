package com.dominik.cardvault.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

const val GUEST_OWNER_UID = "__domidex_guest__"
const val SYNC_LOCAL_ONLY = "LOCAL_ONLY"
const val SYNC_PENDING = "PENDING"
const val SYNC_CLEAN = "CLEAN"
const val SYNC_CONFLICT = "CONFLICT"
const val SYNC_ERROR = "ERROR"

@Entity(tableName = "catalog_cards", indices = [Index(value = ["tcg", "setId", "collectorNumber", "language", "finish", "variant"]), Index(value = ["externalSource", "externalProductId"], unique = true)])
data class CatalogCardEntity(
    @PrimaryKey val id: String,
    val tcg: String,
    val setId: String,
    val setName: String,
    val name: String,
    val collectorNumber: String,
    val language: String,
    val finish: String,
    val variant: String,
    val createdAtEpochMillis: Long,
    val externalSource: String? = null,
    val externalProductId: Long? = null,
    val imageUrl: String? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val rarity: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "NULL") val effectOverride: String? = null,
)

/** Read-only catalog slot for a Binder master-set page; never an owned entry. */
data class CatalogReferenceProjection(
    val cardId: String,
    val setId: String,
    val name: String,
    val setName: String,
    val collectorNumber: String,
    val language: String,
    val finish: String,
    val variant: String,
    val imageUrl: String?,
    val externalProductId: Long?,
    val marketValueMinor: Long?,
    val priceUpdatedAtEpochMillis: Long?,
)

/** A Binder is the only collection grouping. Cards stay in collection_entries; no parallel state. */
@Entity(tableName = "binders", indices = [Index(value = ["setKey"], unique = true)])
data class BinderEntity(
    @PrimaryKey val id: String,
    val setKey: String,
    val name: String,
    val heroAsset: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "collection_entries",
    foreignKeys = [ForeignKey(entity = CatalogCardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index("cardId"),
        Index(value = ["externalProductSource", "externalProductId"]),
        Index("binderId"),
        Index(value = ["ownerUid", "syncId"], unique = true),
        Index(value = ["ownerUid", "deletedAtEpochMillis"]),
        Index(value = ["ownerUid", "sourceSetId", "sourceCardId"]),
    ],
)
data class CollectionEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val quantity: Int,
    val condition: String,
    val acquisitionPriceMinor: Long?,
    val acquisitionCurrency: String?,
    val acquisitionDateEpochDay: Long?,
    val notes: String,
    val frontPhotoUri: String?,
    val backPhotoUri: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val externalProductSource: String? = null,
    val externalProductId: Long? = null,
    @ColumnInfo(defaultValue = "'inbox'") val binderId: String = "inbox",
    @ColumnInfo(defaultValue = "'__domidex_guest__'") val ownerUid: String = GUEST_OWNER_UID,
    @ColumnInfo(defaultValue = "'pending'") val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "'LOCAL_ONLY'") val syncState: String = SYNC_LOCAL_ONLY,
    @ColumnInfo(defaultValue = "0") val remoteRevision: Long = 0L,
    val deletedAtEpochMillis: Long? = null,
    val finishOverride: String? = null,
    val variantOverride: String? = null,
    val effectOverride: String? = null,
    /** Stable TCGdex printing identity. It is independent from Cardmarket price linkage. */
    val sourceSetId: String? = null,
    val sourceCardId: String? = null,
)

/** One coalesced, idempotent cloud mutation per user-owned collection entry. */
@Entity(
    tableName = "collection_sync_outbox",
    indices = [Index(value = ["ownerUid", "state", "createdAtEpochMillis"])],
)
data class CollectionSyncOutboxEntity(
    @PrimaryKey val entryKey: String,
    val mutationId: String,
    val ownerUid: String,
    val syncId: String,
    val operation: String,
    val expectedRemoteRevision: Long,
    val state: String = SYNC_PENDING,
    val createdAtEpochMillis: Long,
    val lastError: String? = null,
)

/** Complete user-owned snapshot used only at the Room/Firestore replication boundary. */
data class CollectionSyncProjection(
    val entryId: Long,
    val ownerUid: String,
    val syncId: String,
    val syncState: String,
    val remoteRevision: Long,
    val deletedAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long,
    val name: String,
    val setId: String,
    val setName: String,
    val collectorNumber: String,
    val language: String,
    val finish: String,
    val variant: String,
    val rarity: String,
    val quantity: Int,
    val condition: String,
    val notes: String,
    val acquisitionPriceMinor: Long?,
    val acquisitionCurrency: String?,
    val acquisitionDateEpochDay: Long?,
    val externalProductSource: String?,
    val externalProductId: Long?,
    val imageUrl: String?,
    val effectOverride: String?,
    val sourceSetId: String?,
    val sourceCardId: String?,
    val binderId: String,
    val binderSetKey: String,
    val binderName: String,
    val binderHeroAsset: String?,
)

@Entity(
    tableName = "raw_price_snapshots",
    foreignKeys = [ForeignKey(entity = CatalogCardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index(value = ["cardId", "source", "effectiveAtEpochMillis"], unique = true)],
)
data class RawPriceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val source: String,
    val currency: String,
    val lowValueMinor: Long?,
    val averageValueMinor: Long?,
    val trendValueMinor: Long?,
    val marketValueMinor: Long?,
    val average1DayValueMinor: Long? = null,
    val average7DayValueMinor: Long? = null,
    val average30DayValueMinor: Long? = null,
    val holoAverageValueMinor: Long? = null,
    val holoLowValueMinor: Long? = null,
    val holoTrendValueMinor: Long? = null,
    val fetchedAtEpochMillis: Long,
    val effectiveAtEpochMillis: Long,
)

@Entity(
    tableName = "graded_market_records",
    foreignKeys = [ForeignKey(entity = CatalogCardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index(value = ["cardId", "gradingCompany", "grade", "saleDateEpochDay"])],
)
data class GradedMarketRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val gradingCompany: String,
    val grade: Double,
    val recordType: String,
    val valueMinor: Long,
    val currency: String,
    val saleDateEpochDay: Long?,
    val source: String,
    val sourceReference: String?,
    val confidence: Double?,
    val sampleSize: Int?,
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "grading_analyses",
    foreignKeys = [ForeignKey(entity = CollectionEntryEntity::class, parentColumns = ["id"], childColumns = ["collectionEntryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("collectionEntryId")],
)
data class GradingAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionEntryId: Long,
    val status: String,
    val estimatedMinimumGrade: Double?,
    val estimatedMaximumGrade: Double?,
    val confidence: Double?,
    val centeringFinding: String?,
    val cornersFinding: String?,
    val edgesFinding: String?,
    val surfaceFinding: String?,
    val frontPhotoUri: String?,
    val backPhotoUri: String?,
    val anglePhotoUris: String?,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val analyzerVersion: String?,
    val rawValueMinorAtAnalysis: Long?,
    val gradingCostMinor: Long?,
    val shippingCostMinor: Long?,
    val insuranceCostMinor: Long?,
    val economicRecommendation: String?,
    val economicReasoning: String?,
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "catalog_sync_state")
data class CatalogSyncStateEntity(
    @PrimaryKey val source: String,
    val status: String,
    val lastAttemptAtEpochMillis: Long?,
    val lastSuccessAtEpochMillis: Long?,
    val dataCreatedAt: String?,
    val catalogProductCount: Int,
    val priceRecordCount: Int,
    val errorMessage: String?,
)

data class CollectionProjection(
    val entryId: Long,
    val cardId: String,
    val name: String,
    val setName: String,
    val collectorNumber: String,
    val language: String,
    val finish: String,
    val variant: String,
    val quantity: Int,
    val condition: String,
    val acquisitionPriceMinor: Long?,
    val acquisitionCurrency: String?,
    val acquisitionDateEpochDay: Long?,
    val notes: String,
    val marketValueMinor: Long?,
    val priceCurrency: String?,
    val priceUpdatedAtEpochMillis: Long?,
    val externalProductSource: String?,
    val externalProductId: Long?,
    val priceCardId: String,
    val imageUrl: String?,
    val binderId: String,
    val rarity: String,
    val effectOverride: String?,
    val sourceSetId: String?,
    val sourceCardId: String?,
)

data class BinderProjection(
    val id: String,
    val name: String,
    val heroAsset: String?,
    val uniqueCards: Int,
    val ownedCards: Int,
    val marketValueMinor: Long?,
    val lastPriceUpdatedAtEpochMillis: Long?,
)

data class CatalogSearchProjection(
    val externalProductId: Long,
    val name: String,
    val setId: String,
    val setName: String,
    val collectorNumber: String,
    val language: String,
    val finish: String,
    val variant: String,
    val imageUrl: String?,
    val marketValueMinor: Long?,
    val holoMarketValueMinor: Long?,
    val currency: String?,
    val rarity: String?,
)

/** Small Room projection: the price value is chosen for the exact finish by the DAO. */
data class CardPriceHistoryProjection(
    val effectiveAtEpochMillis: Long,
    val valueMinor: Long,
    val currency: String,
    val source: String,
)
