package com.dominik.cardvault.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
)

@Entity(
    tableName = "collection_entries",
    foreignKeys = [ForeignKey(entity = CatalogCardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("cardId")],
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
)
