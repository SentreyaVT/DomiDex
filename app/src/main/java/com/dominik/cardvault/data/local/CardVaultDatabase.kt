package com.dominik.cardvault.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CatalogCardEntity::class, CollectionEntryEntity::class, RawPriceSnapshotEntity::class, GradedMarketRecordEntity::class, GradingAnalysisEntity::class, AppMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CardVaultDatabase : RoomDatabase() {
    abstract fun cardVaultDao(): CardVaultDao

    companion object {
        @Volatile private var instance: CardVaultDatabase? = null

        fun get(context: Context): CardVaultDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CardVaultDatabase::class.java, "cardvault.db")
                // Every future schema version must provide explicit migrations. Never silently erase collections.
                .build().also { instance = it }
        }
    }
}
