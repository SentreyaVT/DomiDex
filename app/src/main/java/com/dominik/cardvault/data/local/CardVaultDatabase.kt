package com.dominik.cardvault.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CatalogCardEntity::class, BinderEntity::class, CollectionEntryEntity::class, CollectionSyncOutboxEntity::class, RawPriceSnapshotEntity::class, GradedMarketRecordEntity::class, GradingAnalysisEntity::class, AppMetadataEntity::class, CatalogSyncStateEntity::class],
    version = 10,
    exportSchema = true,
)
abstract class CardVaultDatabase : RoomDatabase() {
    abstract fun cardVaultDao(): CardVaultDao

    companion object {
        @Volatile private var instance: CardVaultDatabase? = null

        fun get(context: Context): CardVaultDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CardVaultDatabase::class.java, "cardvault.db")
                // Every future schema version must provide explicit migrations. Never silently erase collections.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_cards ADD COLUMN externalSource TEXT")
                db.execSQL("ALTER TABLE catalog_cards ADD COLUMN externalProductId INTEGER")
                db.execSQL("ALTER TABLE raw_price_snapshots ADD COLUMN average1DayValueMinor INTEGER")
                db.execSQL("ALTER TABLE raw_price_snapshots ADD COLUMN average7DayValueMinor INTEGER")
                db.execSQL("ALTER TABLE raw_price_snapshots ADD COLUMN average30DayValueMinor INTEGER")
                db.execSQL("ALTER TABLE raw_price_snapshots ADD COLUMN holoAverageValueMinor INTEGER")
                db.execSQL("ALTER TABLE raw_price_snapshots ADD COLUMN holoLowValueMinor INTEGER")
                db.execSQL("ALTER TABLE raw_price_snapshots ADD COLUMN holoTrendValueMinor INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_sync_state (source TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, lastAttemptAtEpochMillis INTEGER, lastSuccessAtEpochMillis INTEGER, dataCreatedAt TEXT, catalogProductCount INTEGER NOT NULL, priceRecordCount INTEGER NOT NULL, errorMessage TEXT)")
                db.execSQL("DROP INDEX IF EXISTS index_catalog_cards_tcg_setId_collectorNumber_language_finish_variant")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cards_tcg_setId_collectorNumber_language_finish_variant ON catalog_cards(tcg, setId, collectorNumber, language, finish, variant)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_catalog_cards_externalSource_externalProductId ON catalog_cards(externalSource, externalProductId)")
                // Remove only explicitly marked Phase-1 demonstration prices, never user or imported data.
                db.execSQL("DELETE FROM raw_price_snapshots WHERE source = 'DEMO_LOCAL'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN externalProductSource TEXT")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN externalProductId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_externalProductSource_externalProductId ON collection_entries(externalProductSource, externalProductId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_cards ADD COLUMN imageUrl TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS binders (id TEXT NOT NULL PRIMARY KEY, setKey TEXT NOT NULL, name TEXT NOT NULL, heroAsset TEXT, createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_binders_setKey ON binders(setKey)")
                val now = System.currentTimeMillis()
                db.execSQL("INSERT OR IGNORE INTO binders(id, setKey, name, heroAsset, createdAtEpochMillis, updatedAtEpochMillis) SELECT 'set-' || replace(lower(c.setId), ' ', '-'), lower(c.setId), c.setName, NULL, $now, $now FROM catalog_cards c JOIN collection_entries e ON e.cardId = c.id GROUP BY c.setId, c.setName")
                db.execSQL("INSERT OR IGNORE INTO binders(id, setKey, name, heroAsset, createdAtEpochMillis, updatedAtEpochMillis) VALUES ('inbox', 'inbox', 'Ohne Setzuordnung', NULL, $now, $now)")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN binderId TEXT NOT NULL DEFAULT 'inbox'")
                db.execSQL("UPDATE collection_entries SET binderId = COALESCE((SELECT 'set-' || replace(lower(c.setId), ' ', '-') FROM catalog_cards c WHERE c.id = collection_entries.cardId), 'inbox')")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_binderId ON collection_entries(binderId)")
            }
        }

        /** Rarity is additive source metadata. Existing cards stay intact as UNKNOWN. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_cards ADD COLUMN rarity TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        /** Display-effect overrides are additive and never alter the card's real finish. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_cards ADD COLUMN effectOverride TEXT DEFAULT NULL")
            }
        }

        /**
         * Existing rows deliberately remain guest-owned. Merely signing in must never upload,
         * expose or reassign a legacy collection to a Firebase account.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN ownerUid TEXT NOT NULL DEFAULT '__domidex_guest__'")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN syncId TEXT NOT NULL DEFAULT 'pending'")
                db.execSQL("UPDATE collection_entries SET syncId = 'legacy-' || id")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN remoteRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN deletedAtEpochMillis INTEGER")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN finishOverride TEXT")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN variantOverride TEXT")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN effectOverride TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_collection_entries_ownerUid_syncId ON collection_entries(ownerUid, syncId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_ownerUid_deletedAtEpochMillis ON collection_entries(ownerUid, deletedAtEpochMillis)")
                db.execSQL("CREATE TABLE IF NOT EXISTS collection_sync_outbox (entryKey TEXT NOT NULL PRIMARY KEY, mutationId TEXT NOT NULL, ownerUid TEXT NOT NULL, syncId TEXT NOT NULL, operation TEXT NOT NULL, expectedRemoteRevision INTEGER NOT NULL, state TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, lastError TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_sync_outbox_ownerUid_state_createdAtEpochMillis ON collection_sync_outbox(ownerUid, state, createdAtEpochMillis)")
            }
        }

        /**
         * Adds non-destructive source identity for scanner/catalog cards. Only rows already
         * backed by the explicit TCGDEX_DE catalogue are backfilled; arbitrary manual rows are
         * intentionally left unclassified instead of being guessed from name or number.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN sourceSetId TEXT")
                db.execSQL("ALTER TABLE collection_entries ADD COLUMN sourceCardId TEXT")
                db.execSQL("""
                    UPDATE collection_entries
                    SET sourceSetId = (
                            SELECT c.setId FROM catalog_cards c
                            WHERE c.id = collection_entries.cardId
                              AND c.externalSource = 'TCGDEX_DE'
                              AND c.id LIKE 'tcgdex-' || c.setId || '-%'
                        ),
                        sourceCardId = (
                            SELECT CASE
                                WHEN lower(rawSourceCardId) LIKE '%-1steditionholofoil' THEN substr(rawSourceCardId, 1, length(rawSourceCardId) - length('-1steditionholofoil'))
                                WHEN lower(rawSourceCardId) LIKE '%-unlimitedholofoil' THEN substr(rawSourceCardId, 1, length(rawSourceCardId) - length('-unlimitedholofoil'))
                                WHEN lower(rawSourceCardId) LIKE '%-reverseholofoil' THEN substr(rawSourceCardId, 1, length(rawSourceCardId) - length('-reverseholofoil'))
                                WHEN lower(rawSourceCardId) LIKE '%-1stedition' THEN substr(rawSourceCardId, 1, length(rawSourceCardId) - length('-1stedition'))
                                WHEN lower(rawSourceCardId) LIKE '%-unlimited' THEN substr(rawSourceCardId, 1, length(rawSourceCardId) - length('-unlimited'))
                                WHEN lower(rawSourceCardId) LIKE '%-holofoil' THEN substr(rawSourceCardId, 1, length(rawSourceCardId) - length('-holofoil'))
                                ELSE rawSourceCardId
                            END
                            FROM (
                                SELECT substr(c.id, length('tcgdex-' || c.setId || '-') + 1) AS rawSourceCardId
                                FROM catalog_cards c
                                WHERE c.id = collection_entries.cardId
                                  AND c.externalSource = 'TCGDEX_DE'
                                  AND c.id LIKE 'tcgdex-' || c.setId || '-%'
                            )
                        )
                    WHERE EXISTS (
                        SELECT 1 FROM catalog_cards c
                        WHERE c.id = collection_entries.cardId
                          AND c.externalSource = 'TCGDEX_DE'
                          AND c.id LIKE 'tcgdex-' || c.setId || '-%'
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_entries_ownerUid_sourceSetId_sourceCardId ON collection_entries(ownerUid, sourceSetId, sourceCardId)")
            }
        }

        /** Covers name filtering without loading every full catalog row; existing data is untouched. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cards_externalSource_externalProductId_name ON catalog_cards(externalSource, externalProductId, name)")
            }
        }
    }
}
